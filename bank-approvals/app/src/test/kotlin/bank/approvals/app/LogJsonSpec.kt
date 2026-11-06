package bank.approvals.app

import bank.approvals.api.NewRequest
import bank.approvals.api.ask
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.surefold.app.use
import dev.surefold.junit.Suite
import dev.surefold.junit.alone
import dev.surefold.junit.holds
import dev.surefold.logAnnotated
import dev.surefold.logWarn
import io.github.matthewjones372.pelican.test.shouldBeOk
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.UUID

/** bank spec 0023's `log-json`: every line Approvals writes is one JSON object, with the estate's field names. */
object LogJsonSpec : Suite(isolated = true) {
    init {
        test(
            "every line a node writes, migrating, starting and answering, is one JSON object with the shared fields",
            alone,
        ) {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    val lines = written {
                        TestCluster(1).node(0).use { node: TestNode ->
                            node.calling("rae", "engineers")
                                .outcome(
                                    ask,
                                    NewRequest(
                                        "test.change",
                                        "test/logged-$run",
                                        "a rule",
                                        before = "1000",
                                        after = "500",
                                    ),
                                )
                                .shouldBeOk()
                            logAnnotated("trace_id" to "4bf92f3577b34da6a3ce929d0e0e4736", "request_id" to "r-$run") {
                                logWarn("a line about one request")
                            }
                        }
                    }
                    lines.shouldNotBeEmpty()
                    val parsed = lines.map { line -> withClue(line) { json.readTree(line) } }
                    parsed.forEach { line: JsonNode ->
                        withClue(line.toString()) {
                            listOf("ts", "level", "service", "msg").forEach {
                                line.path(it).asText()
                                    .shouldNotBeBlank()
                            }
                            line.path("service").asText() shouldBe "bank-approvals"
                        }
                    }
                    val annotated = parsed.single { it.path("msg").asText() == "a line about one request" }
                    annotated.path("trace_id").asText() shouldBe "4bf92f3577b34da6a3ce929d0e0e4736"
                    annotated.path("request_id").asText() shouldBe "r-$run"
                }
            }
        }
    }

    private class Fixture {
        val run = UUID.randomUUID().toString().take(8)
        val json = ObjectMapper()

        /** What [block] wrote to stdout under the service's own `logback.xml`, the tests' configuration put back after.
         */
        fun written(block: () -> Unit): List<String> {
            val context = LoggerFactory.getILoggerFactory() as LoggerContext
            fun configure(name: String) {
                context.reset()
                JoranConfigurator().apply {
                    setContext(
                        context,
                    )
                }.doConfigure(requireNotNull(javaClass.classLoader.getResource(name)))
            }
            val out = ByteArrayOutputStream()
            val before = System.out
            System.setOut(PrintStream(out, true))
            try {
                configure("logback.xml")
                block()
            } finally {
                configure("logback-test.xml")
                System.setOut(before)
            }
            return out.toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }
        }
    }
}
