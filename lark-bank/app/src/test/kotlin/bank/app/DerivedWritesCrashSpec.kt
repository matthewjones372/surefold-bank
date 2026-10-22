package bank.app

import bank.domain.AccountEvent
import com.github.dockerjava.api.model.ExposedPort
import io.github.matthewjones372.lark.actor.journal.jdbc.JdbcOffsets
import io.github.matthewjones372.lark.app.liquibase.migrate
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.SQLException
import javax.sql.DataSource

/**
 * Bank spec 0032: a read model's batch commits without waiting for the disk, and Postgres killed mid-projection still
 * leaves the books whole. Its log writer here waits 10 s, the most it can, so a batch's commit sits unflushed in
 * Postgres's memory until something flushes it. A kill loses what it holds, as a crash of the machine would.
 */
class DerivedWritesCrashSpec {

    private val server = PostgreSQLContainer(
        DockerImageName.parse("public.ecr.aws/docker/library/postgres:17").asCompatibleSubstituteFor("postgres"),
    ).apply {
        withEnv("POSTGRES_HOST_AUTH_METHOD", "trust")
        withCommand("postgres", "-c", "wal_writer_delay=10000ms", "-c", "wal_writer_flush_after=0")
        start()
    }

    private var data: DataSource = source(server.getMappedPort(PORT)).also { migrate(it, "db/changelog.xml") }

    private val accounts = (1..20).map { "acc-$it" }
    private val batch = accounts.flatMap { id ->
        listOf(Sequenced(id, 1, AccountEvent.Opened("owner", gbp(1_000), "open:$id", 0) as AccountEvent)) +
            (2L..5L).map { sequence -> Sequenced(id, sequence, AccountEvent.Deposited(gbp(10), "d:$id:$sequence", 0)) }
    }

    @AfterEach
    fun stop() = server.stop()

    @Test
    fun `a batch whose offset was saved after it is still there after Postgres is killed`() {
        JdbcReadModels(data).project(batch) shouldBe batch.size
        // As a projection does: the batch, then its offset, durably, on the same server.
        JdbcOffsets(data).save(OFFSET, batch.size.toLong())

        killAndStartAgain()

        JdbcOffsets(data).load(OFFSET) shouldBe batch.size.toLong()
        val ledger = JdbcReadModels(data).ledger()
        ledger.eventsProjected shouldBe batch.size.toLong()
        ledger.conserved shouldBe true
    }

    @Test
    fun `a batch lost with Postgres, its offset never saved, is projected again from the start and counted once`() {
        JdbcReadModels(data).project(batch) shouldBe batch.size

        killAndStartAgain()

        // The offset is where the projection starts again: none saved, so from the start, whatever of the batch
        // survived. A batch written twice changes nothing the second time (spec 0014).
        JdbcOffsets(data).load(OFFSET) shouldBe null
        JdbcReadModels(data).project(batch)
        val ledger = JdbcReadModels(data).ledger()
        ledger.eventsProjected shouldBe batch.size.toLong()
        ledger.conserved shouldBe true
    }

    /** The server killed outright, then started again on the same data, as Kubernetes restarts a crashed pod. */
    private fun killAndStartAgain() {
        val docker = server.dockerClient
        docker.killContainerCmd(server.containerId).withSignal("KILL").exec()
        docker.startContainerCmd(server.containerId).exec()
        val port = docker.inspectContainerCmd(server.containerId).exec()
            .networkSettings.ports.bindings.getValue(ExposedPort.tcp(PORT)).first().hostPortSpec.toInt()
        data = source(port)
        awaitUp(data)
    }

    private fun source(port: Int) = PGSimpleDataSource().apply {
        setURL("jdbc:postgresql://${server.host}:$port/${server.databaseName}")
        user = server.username
        password = server.password
    }

    private fun awaitUp(data: DataSource) = check(eventually(seconds = 60) { answers(data) }) {
        "Postgres did not start again"
    }

    private fun answers(data: DataSource): Boolean = try {
        data.connection.use { it.createStatement().use { query -> query.execute("select 1") } }
    } catch (_: SQLException) {
        false
    }

    private companion object {
        const val PORT = 5432
        const val OFFSET = "statements-db-0-0"
    }
}
