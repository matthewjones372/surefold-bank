package bank.app

import bank.domain.AccountEvent
import bank.domain.AccountId
import bank.domain.TransferEvent
import io.github.matthewjones372.surefold.app.liquibase.migrate
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.Statement
import javax.sql.DataSource

/**
 * Bank spec 0032: a read model's batch commits without waiting for the disk, since it is derived from the journal and
 * its offset's durable commit after it flushes it too. The setting is the transaction's alone: a pooled connection
 * goes back as durable as it came, for whatever borrows it next, a journal append included.
 */
class DerivedWritesSpec {

    private val source = PGSimpleDataSource().apply { setURL(TestPostgres.fresh()) }
        .also { migrate(it, "db/read-models.sql") }

    // One connection, handed out again and again as a pool's would be, with each plain statement it runs written down.
    private val raw: Connection = source.connection
    private val ran = mutableListOf<String>()
    private val reads = JdbcReadModels(
        object : DataSource by source {
            override fun getConnection(): Connection = recording(raw)
        },
    )

    @AfterEach
    fun close() = raw.close()

    @Test
    fun `an account batch commits without waiting for the disk, and the connection goes back durable`() {
        val opened = AccountEvent.Opened("owner", gbp(1_000), "open:acc-1", 0)

        reads.project(listOf(Sequenced("acc-1", 1, opened as AccountEvent))) shouldBe 1

        ran shouldContain ASYNC
        synchronousCommit() shouldBe "on"
        reads.ledger().eventsProjected shouldBe 1
    }

    @Test
    fun `a transfer batch commits without waiting for the disk, and the connection goes back durable`() {
        val requested = TransferEvent.Requested(AccountId("a"), AccountId("b"), gbp(300), 0)

        reads.projectTransfers(listOf(Sequenced("t-1", 1, requested as TransferEvent))) shouldBe 1

        ran shouldContain ASYNC
        synchronousCommit() shouldBe "on"
        reads.transfers().pending shouldBe 1
    }

    private fun synchronousCommit(): String = raw.createStatement().use { query ->
        query.executeQuery("show synchronous_commit").use { rows ->
            rows.next()
            rows.getString(1)
        }
    }

    // Given back rather than closed, and each statement it makes recorded.
    private fun recording(connection: Connection): Connection = proxy(connection) { method, args ->
        when {
            method == "close" -> null
            method == "createStatement" && args.isNullOrEmpty() -> recording(connection.createStatement())
            else -> PASS
        }
    }

    private fun recording(statement: Statement): Statement = proxy(statement) { method, args ->
        if (method == "execute" && args?.size == 1) ran += args[0] as String
        PASS
    }

    private companion object {
        const val ASYNC = "set local synchronous_commit = off"

        /** What a proxy's handler answers to hand a call on to its target. */
        val PASS = Any()

        inline fun <reified T : Any> proxy(target: T, crossinline handle: (String, Array<Any?>?) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
                val answer = handle(method.name, args)
                if (answer !== PASS) {
                    answer
                } else {
                    try {
                        method.invoke(target, *(args ?: emptyArray()))
                    } catch (thrown: InvocationTargetException) {
                        throw thrown.targetException
                    }
                }
            } as T
    }
}
