package bank.app

import bank.api.Caller
import bank.api.Look
import dev.surefold.app.liquibase.migrate
import dev.surefold.clock
import dev.surefold.junit.Suite
import dev.surefold.junit.holds
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.postgresql.ds.PGSimpleDataSource
import java.sql.Connection
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/**
 * Bank spec 0032: the access log is written by one writer per node, in batches, so a staff page's many looks share a
 * few inserts and commits, and every look is still recorded.
 */
object AccessLogBatchesSpec : Suite(isolated = true) {
    init {
        test("a staff page's 1,000 looks make at most 20 inserts, and every one is recorded") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    val log = JdbcAccessLog(counting)
                    repeat(1_000) { log.record(look(it)) }
                    log.close()

                    taken.get() shouldBeLessThanOrEqual 20
                    val recorded = JdbcAccessLog(source).use { it.looksAt("acc-1", limit = 2_000) }
                    recorded.size shouldBe 1_000
                    // In the order they came: the newest first, as the log answers.
                    recorded.map { it.look.userAgent } shouldBe (999 downTo 0).map { "look-$it" }
                }
            }
        }
        test("a lone look is written once its window passes, without waiting for others") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    JdbcAccessLog(source).use { log ->
                        log.record(look(0))

                        eventually(seconds = 5) { log.looksAt("acc-1").size == 1 } shouldBe true
                    }
                }
            }
        }
    }

    private class Fixture {

        val source = PGSimpleDataSource().apply { setURL(TestPostgres.fresh()) }
            .also { migrate(it, "db/read-models.sql") }

        // Each batch takes a connection of its own, so the connections taken count the inserts.
        val taken = AtomicInteger()
        val counting = object : DataSource by source {
            override fun getConnection(): Connection = source.connection.also { taken.incrementAndGet() }
        }

        fun look(n: Int) = Look(
            at = clock.get().now(),
            who = Caller("bob", "Bob", setOf("support"), null),
            endpoint = "GET /accounts/{accountId}",
            account = "acc-1",
            status = 200,
            address = null,
            userAgent = "look-$n",
            grant = null,
        )
    }
}
