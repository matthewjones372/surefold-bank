package bank.app

import io.github.matthewjones372.surefold.app.liquibase.migrate
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import javax.sql.DataSource

/**
 * A database the bank used while the library was named Lark, its journal in `lark_*` tables, keeps its events once
 * the node starts on Surefold: the tables are renamed, and the changelog finds them rather than making empty ones.
 */
class LarkTablesSpec {

    private val data: DataSource = PGSimpleDataSource().apply { setURL(TestPostgres.fresh()) }

    @Test
    fun `a Lark-era journal is renamed to surefold's tables with its events in it`() {
        migrate(data, "lark/journal/jdbc/postgres.sql")
        sql("insert into lark_journal (kind, id, seq_nr, bytes, slice) values ('account', 'acc-1', 1, '\\x01', 7)")
        sql("insert into lark_offset (name, last_ordering) values ('statements', 1)")

        renameLarkTables(data)
        migrate(data, "db/changelog.xml")

        count("select count(*) from surefold_journal where id = 'acc-1'") shouldBe 1
        count("select last_ordering from surefold_offset where name = 'statements'") shouldBe 1
        count("select count(*) from pg_class where relname like 'lark\\_%'") shouldBe 0
        // An append after the rename takes the next ordering, from the renamed sequence.
        sql("insert into surefold_journal (kind, id, seq_nr, bytes, slice) values ('account', 'acc-1', 2, '\\x02', 7)")
        count("select max(ordering) from surefold_journal") shouldBe 2
    }

    @Test
    fun `a database already on surefold's tables is left as it is`() {
        migrate(data, "db/changelog.xml")
        sql("insert into surefold_journal (kind, id, seq_nr, bytes, slice) values ('account', 'acc-1', 1, '\\x01', 7)")

        renameLarkTables(data)
        migrate(data, "db/changelog.xml")

        count("select count(*) from surefold_journal") shouldBe 1
    }

    private fun sql(statement: String) = data.connection.use { connection ->
        connection.createStatement().use { it.execute(statement) }
    }

    private fun count(query: String): Long = data.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(query).use { rows ->
                rows.next()
                rows.getLong(1)
            }
        }
    }
}
