package bank.api

import dev.surefold.junit.Suite
import dev.surefold.junit.holds
import io.github.matthewjones372.pelican.codegen.kotlinClient
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

/** Spec 0018: the client the bank calls Screening through is the one its description generates. */
object ScreeningClientSpec : Suite() {
    init {
        test("the committed client is the one the description generates") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    val generated = screeningSpec().kotlinClient(
                        packageName = "bank.api.screening",
                        clientName = "ScreeningClient",
                    )
                    // -Pregenerate (see the build file) writes it instead, for a change to the description.
                    if (System.getProperty("bank.regenerate") == "true") {
                        Files.createDirectories(committed.parent)
                        Files.writeString(committed, generated)
                    }
                    Files.readString(committed) shouldBe generated
                }
            }
        }
    }

    private class Fixture {
        val committed = Path.of("src/main/kotlin/bank/api/screening/ScreeningClient.kt")
    }
}
