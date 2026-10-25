package bank.app

import bank.api.Caller
import bank.api.Look
import io.github.matthewjones372.lark.app.liquibase.migrate
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import java.sql.Connection
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/**
 * Bank spec 0032: the access log is written by one writer per node, in batches, so a staff page's many looks share a
 * few inserts and commits, and every look is still recorded.
 */
class AccessLogBatchesSpec {

    private val source = PGSimpleDataSource().apply { setURL(TestPostgres.fresh()) }
        .also { migrate(it, "db/read-models.sql") }

    // Each batch takes a connection of its own, so the connections taken count the inserts.
    private val taken = AtomicInteger()
    private val counting = object : DataSource by source {
        override fun getConnection(): Connection = source.connection.also { taken.incrementAndGet() }
    }

    private fun look(n: Int) = Look(
        at = Instant.now(),
        who = Caller("bob", "Bob", setOf("support"), null),
        endpoint = "GET /accounts/{accountId}",
        account = "acc-1",
        status = 200,
        address = null,
        userAgent = "look-$n",
        grant = null,
    )

    @Test
    fun `a staff page's 1,000 looks make at most 20 inserts, and every one is recorded`() {
        val log = JdbcAccessLog(counting)
        repeat(1_000) { log.record(look(it)) }
        log.close()

        taken.get() shouldBeLessThanOrEqual 20
        val recorded = JdbcAccessLog(source).use { it.looksAt("acc-1", limit = 2_000) }
        recorded.size shouldBe 1_000
        // In the order they came: the newest first, as the log answers.
        recorded.map { it.look.userAgent } shouldBe (999 downTo 0).map { "look-$it" }
    }

    @Test
    fun `a lone look is written once its window passes, without waiting for others`() {
        JdbcAccessLog(source).use { log ->
            log.record(look(0))

            eventually(seconds = 5) { log.looksAt("acc-1").size == 1 } shouldBe true
        }
    }
}
