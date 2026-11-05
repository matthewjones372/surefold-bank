package bank.access.sync

import bank.access.fga.Fga
import bank.access.fga.FgaUnavailable
import bank.access.fga.Tuple
import bank.events.v1.AccountEvent
import bank.events.v1.ApprovalEvent
import com.fasterxml.jackson.databind.ObjectMapper
import dev.surefold.fail.Either
import dev.surefold.fail.flatMap
import dev.surefold.fail.fold
import dev.surefold.fail.left
import dev.surefold.fail.map
import dev.surefold.fail.onLeft
import dev.surefold.fail.right
import io.opentelemetry.api.OpenTelemetry
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.WakeupException
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/** The brokers, and how access-sync signs in to them: as its own SCRAM user, or as nobody where there are no users. */
data class KafkaSettings(
    val bootstrap: String,
    val group: String = "access-sync",
    val username: String? = null,
    val password: String? = null,
) {
    val client: Map<String, Any>
        get() = mapOf<String, Any>("bootstrap.servers" to bootstrap) + (
            username?.let { user ->
                mapOf(
                    "security.protocol" to "SASL_PLAINTEXT",
                    "sasl.mechanism" to "SCRAM-SHA-512",
                    "sasl.jaas.config" to "org.apache.kafka.common.security.scram.ScramLoginModule required " +
                        "username=\"$user\" password=\"${password.orEmpty()}\";",
                )
            } ?: emptyMap()
            )
}

/**
 * Where access-sync reads from and writes to. With [fromStart], the account events are read again from offset zero:
 * what an emptied store is rebuilt from.
 */
data class SyncSettings(
    val kafka: KafkaSettings,
    val fgaUrl: String,
    val fgaToken: String,
    val store: String = "bank",
    /** Null where no Pocket ID runs (kind, compose): groups are then left as they are. */
    val pocketIdUrl: String?,
    val pocketIdKey: String?,
    val groupsEvery: Duration = Duration.ofMinutes(1),
    val fromStart: Boolean = false,
)

/**
 * access-sync (bank spec 0022): every relationship in the store, derived from something already recorded. Three
 * readers, each on a thread of its own: the account events, the approval events, and Pocket ID's groups.
 */
class AccessSync(private val settings: SyncSettings, private val telemetry: OpenTelemetry = OpenTelemetry.noop()) :
    AutoCloseable {
    private val log = LoggerFactory.getLogger("access-sync")
    private val fga = Fga(settings.fgaUrl, settings.fgaToken, settings.store, carried = telemetry::headers)
    private val running = AtomicBoolean(true)
    private val consumers = mutableListOf<KafkaConsumer<String, ByteArray>>()
    private val threads = mutableListOf<Thread>()
    private val stopped = CountDownLatch(1)

    fun start(): Either<FgaUnavailable, Unit> {
        fga.write(wiring).onLeft { return it.left() }
        threads += Thread.ofVirtual().name("accounts").start { again("accounts", ::accounts) }
        threads += Thread.ofVirtual().name("approvals").start { again("approvals", ::approvals) }
        if (settings.pocketIdUrl != null && settings.pocketIdKey != null) {
            threads += Thread.ofVirtual().name("groups").start { again("groups", ::groups) }
        } else {
            log.warn("no Pocket ID to read groups from: group memberships are left as they are")
        }
        return Unit.right()
    }

    /**
     * [work] until stopped, begun again after it fails: OpenFGA, Kafka or Pocket ID away for a moment. An interrupt
     * still leaves, because [Either.catch] does not keep one.
     */
    private fun again(what: String, work: () -> Unit) {
        while (running.get()) {
            when (val ran = Either.catch(work)) {
                is Either.Right -> Unit

                is Either.Left -> {
                    if (ran.value is WakeupException || !running.get()) return
                    log.warn("{} failed, starting again in 2 s: {}", what, ran.value.toString())
                    stopped.await(2, java.util.concurrent.TimeUnit.SECONDS)
                }
            }
        }
    }

    private fun consumer(group: String?): KafkaConsumer<String, ByteArray> {
        val properties = settings.kafka.client + mapOf<String, Any>(
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
            // The topics are their writers' to make, with their partitions.
            ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG to false,
        ) + (group?.let { mapOf(ConsumerConfig.GROUP_ID_CONFIG to it) } ?: emptyMap())
        return KafkaConsumer(
            properties,
            StringDeserializer(),
            ByteArrayDeserializer(),
        ).also { synchronized(consumers) { consumers += it } }
    }

    /** Each account opened, its owner's: committed once written, so a restart goes on from there. */
    private fun accounts() = consumer(settings.kafka.group).use { consumer ->
        val rebuilding = settings.fromStart
        consumer.subscribe(
            listOf(ACCOUNTS),
            object : org.apache.kafka.clients.consumer.ConsumerRebalanceListener {
                override fun onPartitionsRevoked(partitions: Collection<TopicPartition>) = Unit
                override fun onPartitionsAssigned(partitions: Collection<TopicPartition>) {
                    if (rebuilding) consumer.seekToBeginning(partitions)
                }
            },
        )
        while (running.get()) {
            val records = consumer.poll(Duration.ofMillis(500))
            if (records.isEmpty) continue
            // A record published inside a trace is written in it, alone; the rest together, as before (bank spec 0024).
            val (inTrace, untraced) = records.partition(::traced)
            fga.write(untraced.flatMap(::relationships)).onLeft { failed ->
                log.warn("accounts were not written: {}", failed.toString())
                stopped.await(2, java.util.concurrent.TimeUnit.SECONDS)
                return@use
            }
            inTrace.forEach { record ->
                when (
                    val traced = telemetry.handling("access-sync account", record) {
                        fga.write(relationships(record))
                    }
                ) {
                    is Either.Left -> {
                        log.warn("an account was not written: {}", traced.value.toString())
                        stopped.await(2, java.util.concurrent.TimeUnit.SECONDS)
                        return@use
                    }

                    is Either.Right -> traced.value.onLeft { failed ->
                        log.warn("an account was not written: {}", failed.toString())
                        stopped.await(2, java.util.concurrent.TimeUnit.SECONDS)
                        return@use
                    }
                }
            }
            consumer.commitSync()
        }
    }

    private fun relationships(record: ConsumerRecord<String, ByteArray>) =
        AccountEvent.parseFrom(unframed(record.value())).relationships()

    /**
     * Every grant, read again from the start of the topic each time access-sync starts: a grant's facts are in its
     * `Requested`, and the topic is small. Nothing is committed, so a restart forgets nothing it needs.
     */
    private fun approvals() = consumer(null).use { consumer ->
        val partitions = consumer.partitionsFor(APPROVALS).map { TopicPartition(APPROVALS, it.partition()) }
        // Approvals makes its topic; until it runs, there is nothing to read, and nothing wrong.
        if (partitions.isEmpty()) {
            log.info("no {} yet: looking again in 30 s", APPROVALS)
            stopped.await(30, java.util.concurrent.TimeUnit.SECONDS)
            return@use
        }
        consumer.assign(partitions)
        consumer.seekToBeginning(partitions)
        val grants = Grants()
        while (running.get()) {
            consumer.poll(Duration.ofMillis(500)).forEach { record ->
                val tuple = grants.heard(ApprovalEvent.parseFrom(unframed(record.value()))) ?: return@forEach
                grant(tuple).onLeft { failed ->
                    log.warn("a grant was not written: {}", failed.toString())
                    stopped.await(2, java.util.concurrent.TimeUnit.SECONDS)
                    return@use
                }
            }
        }
    }

    /**
     * A grant written, or lengthened: OpenFGA keeps one relationship per key, so a later expiry replaces an earlier
     * one, and an earlier never shortens a later.
     */
    private fun grant(tuple: Tuple): Either<FgaUnavailable, Unit> =
        fga.read(tuple.user, tuple.relation, tuple.obj).flatMap { reads ->
            val held = reads.firstOrNull()
            val expires = tuple.expires
            val later = held?.expires?.let { heldUntil -> expires != null && expires.isAfter(heldUntil) } ?: true
            if (held != null && !later) {
                Unit.right()
            } else {
                (held?.let { fga.delete(listOf(it)) } ?: Unit.right()).flatMap {
                    fga.write(listOf(tuple)).map {
                        log.info("{} {} {} until {}", tuple.user, tuple.relation, tuple.obj, tuple.expires)
                    }
                }
            }
        }

    /** Pocket ID's groups, every [SyncSettings.groupsEvery]: members added, and those taken out removed. */
    private fun groups() {
        val pocketId = PocketIdGroups(checkNotNull(settings.pocketIdUrl), checkNotNull(settings.pocketIdKey))
        while (running.get()) {
            val members = when (val read = pocketId.members()) {
                is Either.Left -> {
                    log.warn("Pocket ID did not answer: {}", read.value.toString())
                    stopped.await(settings.groupsEvery.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                    return@groups
                }

                is Either.Right -> read.value
            }
            val wanted = members.flatMap { (group, people) ->
                people.map {
                    Tuple(
                        "person:$it",
                        "member",
                        "group:$group",
                    )
                }
            }
            // A group gone from Pocket ID is emptied too, if the model knows it.
            val held = mutableListOf<Tuple>()
            for (group in members.keys + KNOWN_GROUPS) {
                when (val read = fga.read(relation = "member", obj = "group:$group")) {
                    is Either.Left -> {
                        log.warn("groups were not read: {}", read.value.toString())
                        stopped.await(settings.groupsEvery.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                        return@groups
                    }

                    is Either.Right -> held += read.value
                }
            }
            val add = wanted.filter { want -> held.none { it.key == want.key } }
            val remove = held.filter { have -> wanted.none { it.key == have.key } }
            fga.write(add).onLeft { failed ->
                log.warn("groups were not written: {}", failed.toString())
                stopped.await(settings.groupsEvery.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                return@groups
            }
            fga.delete(remove).onLeft { failed ->
                log.warn("groups were not written: {}", failed.toString())
                stopped.await(settings.groupsEvery.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                return@groups
            }
            if (add.isNotEmpty() || remove.isNotEmpty()) log.info("groups: {} added, {} removed", add.size, remove.size)
            stopped.await(settings.groupsEvery.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
        }
    }

    override fun close() {
        running.set(false)
        stopped.countDown()
        synchronized(consumers) { consumers.forEach { it.wakeup() } }
        threads.forEach { it.join(Duration.ofSeconds(10)) }
    }
}

/** A record's Protobuf message, after Apicurio's framing: a zero, the schema's content id, then the message index. */
fun unframed(bytes: ByteArray): ByteArray {
    require(
        bytes.size >= 6 && bytes[0] == 0.toByte() && bytes[5] == 0.toByte(),
    ) { "not a record framed for the topic's first message" }
    return bytes.copyOfRange(6, bytes.size)
}

/** Pocket ID's admin API: each group's name and its members' ids, which are the subjects in their tokens. */
class PocketIdGroups(private val url: String, private val key: String) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val json = ObjectMapper()

    fun members(): Either<IOException, Map<String, Set<String>>> = groupNames(1, emptyMap()).flatMap { groups ->
        val none: Either<IOException, Map<String, Set<String>>> = emptyMap<String, Set<String>>().right()
        groups.entries.fold(none) { soFar, (id, name) ->
            soFar.flatMap { named ->
                get("/api/user-groups/$id").map { users ->
                    named + (name to users.path("users").map { it.path("id").asText() }.toSet())
                }
            }
        }
    }

    private tailrec fun groupNames(page: Int, soFar: Map<String, String>): Either<IOException, Map<String, String>> =
        when (
            val got = get("/api/user-groups?" + query("pagination[page]" to "$page", "pagination[limit]" to "100"))
        ) {
            is Either.Left -> got

            is Either.Right -> {
                val answer = got.value
                val named = soFar + answer.path("data").associate {
                    it.path("id").asText() to it.path("name").asText()
                }
                val pages = answer.path("pagination").path("totalPages").asInt(1)
                if (page >= pages) named.right() else groupNames(page + 1, named)
            }
        }

    private fun query(vararg pairs: Pair<String, String>) = pairs.joinToString(
        "&",
    ) { (k, v) -> URLEncoder.encode(k, Charsets.UTF_8) + "=" + URLEncoder.encode(v, Charsets.UTF_8) }

    private fun get(path: String): Either<IOException, com.fasterxml.jackson.databind.JsonNode> =
        Either.catchOrThrow<IOException, HttpResponse<String>> {
            http.send(
                HttpRequest.newBuilder(
                    URI.create(url.trimEnd('/') + path),
                ).timeout(Duration.ofSeconds(10)).header("X-API-KEY", key).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        }.fold(
            { it.left() },
            { answer ->
                if (answer.statusCode() != 200) {
                    IOException("Pocket ID answered $path with ${answer.statusCode()}").left()
                } else {
                    json.readTree(answer.body()).right()
                }
            },
        )
}
