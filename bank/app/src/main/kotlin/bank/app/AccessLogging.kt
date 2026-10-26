package bank.app

import bank.api.AccessLog
import bank.api.Caller
import bank.api.Logged
import bank.api.Look
import bank.events.v1.AccessEvent
import io.github.matthewjones372.surefold.Schedule
import io.github.matthewjones372.surefold.counter
import io.github.matthewjones372.surefold.increment
import io.github.matthewjones372.surefold.logWarn
import io.github.matthewjones372.surefold.stream.Running
import io.github.matthewjones372.surefold.stream.Stream
import io.github.matthewjones372.surefold.stream.StreamBackend
import io.github.matthewjones372.surefold.stream.map
import io.github.matthewjones372.surefold.stream.restartOnDefect
import io.github.matthewjones372.surefold.stream.runFold
import io.github.matthewjones372.surefold.stream.start
import io.github.matthewjones372.surefold.stream.tick
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The access log in the read models' database (bank spec 0021). A look is handed to one writer per node, which writes
 * what has come within [window] of the first, or [most] of them, in one insert (bank spec 0032), so a staff page's
 * requests share their commits. A look still never waits for its write; one held when the node is killed is lost, as
 * one being written beside its request was before.
 */
class JdbcAccessLog(
    private val data: DataSource,
    private val window: Duration = 100.milliseconds,
    private val most: Int = 500,
) : AccessLog,
    AutoCloseable {
    private val due = LinkedBlockingQueue<Look>()

    @Volatile private var open = true
    private val writer = Thread.ofVirtual().name("access-log").start(::writing)

    override fun record(look: Look) {
        due.put(look)
    }

    /** Writes what is held, then stops. */
    override fun close() {
        open = false
        writer.join()
    }

    private fun writing() {
        while (open || due.isNotEmpty()) {
            val first = due.poll(window.inWholeNanoseconds, TimeUnit.NANOSECONDS) ?: continue
            val batch = mutableListOf(first)
            val until = System.nanoTime() + window.inWholeNanoseconds
            while (batch.size < most) {
                batch += due.poll(until - System.nanoTime(), TimeUnit.NANOSECONDS) ?: break
            }
            write(batch)
        }
    }

    private fun write(looks: List<Look>) {
        try {
            data.connection.use { connection -> connection.insert(looks) }
            counter("bank.access.recorded").increment(looks.size.toDouble())
        } catch (failed: java.sql.SQLException) {
            // The answers have gone already; looks not recorded are counted and alerted on, never silent.
            counter("bank.access.unrecorded").increment(looks.size.toDouble())
            val first = looks.first()
            logWarn(
                "${looks.size} looks, the first by ${first.who.actor ?: first.who.subject} at ${first.endpoint}, " +
                    "were not recorded: ${failed.message}",
            )
        }
    }

    private fun Connection.insert(looks: List<Look>) {
        val rows = looks.joinToString(", ") { "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)" }
        prepareStatement(
            "insert into access_log (at, subject, name, groups, actor, endpoint, account_id, status, address, " +
                "user_agent, grant_id) values $rows",
        ).use { insert ->
            looks.forEachIndexed { row, look ->
                var column = row * COLUMNS
                insert.setTimestamp(++column, Timestamp.from(look.at))
                insert.setString(++column, look.who.subject)
                insert.setString(++column, look.who.name)
                insert.setArray(++column, createArrayOf("text", look.who.groups.sorted().toTypedArray()))
                insert.setString(++column, look.who.actor)
                insert.setString(++column, look.endpoint)
                insert.setString(++column, look.account)
                insert.setInt(++column, look.status)
                insert.setString(++column, look.address)
                insert.setString(++column, look.userAgent)
                insert.setString(++column, look.grant)
            }
            insert.executeUpdate()
        }
    }

    override fun looksAt(account: String, limit: Int): List<Logged> = data.connection.use { connection ->
        connection.prepareStatement(
            "select * from access_log where account_id = ? order by id desc limit ?",
        ).use { query ->
            query.setString(1, account)
            query.setInt(2, limit)
            query.executeQuery().use { rows -> generateSequence { if (rows.next()) rows.logged() else null }.toList() }
        }
    }

    /**
     * Up to [most] looks not yet on `bank.access-events`, handed to [publish], and marked once all are there. Rows are
     * locked while they go, and skipped by any other node publishing at once, so each goes once from here; a crash
     * between sending and marking sends them again, which a consumer dedupes on the id.
     */
    fun publishSome(most: Int, publish: (List<Logged>) -> Unit): Int = data.connection.use { connection ->
        connection.autoCommit = false
        try {
            val due = connection.prepareStatement(
                "select * from access_log where published_at is null order by id limit ? for update skip locked",
            ).use { query ->
                query.setInt(1, most)
                query.executeQuery().use { rows ->
                    generateSequence { if (rows.next()) rows.logged() else null }.toList()
                }
            }
            if (due.isNotEmpty()) {
                publish(due)
                connection.prepareStatement(
                    "update access_log set published_at = now() where id = any(?)",
                ).use { mark ->
                    mark.setArray(1, connection.createArrayOf("bigint", due.map { it.id }.toTypedArray()))
                    mark.executeUpdate()
                }
            }
            connection.commit()
            due.size
        } catch (failed: Exception) {
            connection.rollback()
            throw failed
        } finally {
            connection.autoCommit = true
        }
    }

    private fun ResultSet.logged(): Logged {
        @Suppress("UNCHECKED_CAST")
        val groups = (getArray("groups").array as Array<String>).toSet()
        val who = Caller(getString("subject"), getString("name"), groups, getString("actor"))
        return Logged(
            getLong("id"),
            Look(
                getTimestamp("at").toInstant(),
                who,
                getString("endpoint"),
                getString("account_id"),
                getInt("status"),
                getString("address"),
                getString("user_agent"),
                getString("grant_id"),
            ),
        )
    }
}

/** A logged look as the topic carries it. */
fun Logged.toEvent(): AccessEvent = AccessEvent.newBuilder().also { event ->
    event.id = id
    event.atMillis = look.at.toEpochMilli()
    // Who was really there: the person acting, when someone was.
    event.who = AccessEvent.Person.newBuilder()
        .setSubject(look.who.actor ?: look.who.subject).setName(look.who.actor ?: look.who.name)
        .addAllGroups(look.who.groups.sorted()).build()
    look.who.actor?.let { event.actingAs = look.who.subject }
    event.endpoint = look.endpoint
    look.account?.let { event.accountId = it }
    event.status = look.status
    look.address?.let { event.address = it }
    look.userAgent?.let { event.userAgent = it }
    look.grant?.let { event.grantApprovalId = it }
}.build()

/** The outbox: every [every], this node publishes what no node has yet, a batch at a time, while it runs. */
fun accessPublisher(
    log: JdbcAccessLog,
    publisher: Publisher,
    backend: StreamBackend,
    every: Duration = 1.seconds,
): Running<Nothing, Long> = Stream.tick(every, Unit)
    .map { _ ->
        log.publishSome(BATCH) { due ->
            val sent = due.map { logged ->
                val key = logged.look.account ?: logged.look.who.subject
                publisher.publish(Outbound(EventStream.ACCESS.topic, key, logged.toEvent()))
            }
            CompletableFuture.allOf(*sent.toTypedArray()).join()
            counter("bank.access.published").increment(due.size.toDouble())
        }.toLong()
    }
    .restartOnDefect(Schedule.spaced(every))
    .runFold(0L) { published, now -> published + now }
    .start(backend)

private const val BATCH = 500

/** An access_log row's columns, as the insert sets them. */
private const val COLUMNS = 11
