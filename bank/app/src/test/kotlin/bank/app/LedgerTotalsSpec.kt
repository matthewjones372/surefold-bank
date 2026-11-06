package bank.app

import bank.domain.AccountEvent
import dev.surefold.app.liquibase.migrate
import dev.surefold.junit.Suite
import dev.surefold.junit.holds
import io.kotest.matchers.shouldBe
import org.postgresql.ds.PGSimpleDataSource
import javax.sql.DataSource

/**
 * Bank spec 0032: the ledger's totals keep a row per currency and share, so the partitions' batches never wait on one
 * another's row, and the ledger is the rows' sum.
 */
object LedgerTotalsSpec : Suite(isolated = true) {
    init {
        test("two shares' batches each add to a row of their own, and the ledger is their sum") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    reads.project(opened("acc-1", "acc-2"), share = "db-0/0") shouldBe 4
                    reads.project(opened("acc-3"), share = "db-0/1") shouldBe 2

                    rows() shouldBe 2
                    val ledger = reads.ledger()
                    ledger.eventsProjected shouldBe 6
                    ledger.conserved shouldBe true
                    ledger.currencies.single().balances shouldBe "30.30"
                }
            }
        }
        test("a batch two shares both hold, as after its slices moved, is counted once") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    val batch = opened("acc-1", "acc-2")

                    reads.project(batch, share = "db-0/0") shouldBe 4
                    reads.project(batch, share = "db-1/0") shouldBe 0

                    reads.ledger().eventsProjected shouldBe 4
                    reads.ledger().conserved shouldBe true
                }
            }
        }
    }

    private class Fixture {

        val data: DataSource = PGSimpleDataSource().apply { setURL(TestPostgres.fresh()) }
            .also { migrate(it, "db/read-models.sql") }
        val reads = JdbcReadModels(data)

        fun opened(vararg ids: String) = ids.flatMap { id ->
            listOf(
                Sequenced(id, 1, AccountEvent.Opened("owner", gbp(1_000), "open:$id", 0) as AccountEvent),
                Sequenced(id, 2, AccountEvent.Deposited(gbp(10), "d:$id", 0)),
            )
        }

        fun rows(): Long = data.connection.use { connection ->
            connection.createStatement().use { query ->
                query.executeQuery("select count(*) from ledger_totals").use { rows ->
                    rows.next()
                    rows.getLong(1)
                }
            }
        }
    }
}
