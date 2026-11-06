package bank.access.sync

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import dev.surefold.junit.Suite
import dev.surefold.junit.alone
import dev.surefold.junit.holds
import io.kotest.matchers.shouldBe
import org.slf4j.LoggerFactory
import java.nio.file.Files

/** bank spec 0026's debug switch: access-sync's level, from the file its ConfigMap is mounted as. */
object LevelFileSpec : Suite(isolated = true, concurrent = false) {
    init {
        test("DEBUG in the file turns its own lines to DEBUG, and INFO turns them back", alone) {
            holds {
                val fixture = Fixture()
                try {
                    with(fixture) {
                        kafka.level = Level.WARN
                        Files.writeString(file, "DEBUG\n")
                        LevelFile(file).read() shouldBe Level.DEBUG
                        LoggerFactory.getLogger("bank.access.sync.AccessSync").isDebugEnabled shouldBe true
                        kafka.level shouldBe Level.WARN

                        Files.writeString(file, "info")
                        LevelFile(file).read() shouldBe Level.INFO
                        LoggerFactory.getLogger("bank.access.sync.AccessSync").isDebugEnabled shouldBe false
                    }
                } finally {
                    fixture.putBack()
                }
            }
        }
        test("a file that is missing, or names no level, changes nothing", alone) {
            holds {
                val fixture = Fixture()
                try {
                    with(fixture) {
                        root.level = Level.INFO
                        LevelFile(file.resolveSibling("nowhere-${System.nanoTime()}")).read() shouldBe null
                        Files.writeString(file, "LOUDER")
                        LevelFile(file).read() shouldBe null
                        root.level shouldBe Level.INFO
                    }
                } finally {
                    fixture.putBack()
                }
            }
        }
    }

    private class Fixture {
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val kafka = LoggerFactory.getLogger("org.apache.kafka") as Logger
        val before = listOf(root, kafka).associateWith { it.level }
        val file = Files.createTempFile("level", "")

        fun putBack() = before.forEach { (logger, level) -> logger.level = level }
    }
}
