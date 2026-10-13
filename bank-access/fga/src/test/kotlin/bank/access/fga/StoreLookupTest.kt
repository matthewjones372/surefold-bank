package bank.access.fga

import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue

/** A fake OpenFGA whose store lookup takes [lookupTakes], answering each page of `GET /stores` from [pages]. */
private class FakeStores(private val pages: Map<String, String>, private val lookupTakes: Duration = Duration.ZERO) {
    val asked = ConcurrentLinkedQueue<String>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            asked += "${exchange.requestMethod} ${exchange.requestURI}"
            val body = if (exchange.requestURI.path == "/stores") {
                Thread.sleep(lookupTakes.toMillis())
                val continuation = exchange.requestURI.query.substringAfter("continuation_token=", "")
                pages.getValue(continuation)
            } else {
                """{"allowed":true}"""
            }
            exchange.sendResponseHeaders(200, body.length.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        start()
    }
    val url = "http://127.0.0.1:${server.address.port}"

    fun stop() = server.stop(0)
}

/** The store is found once by its name, and finding it is not held to a check's budget. */
class StoreLookupTest {
    @Test
    fun `the store is asked for by name, across pages, and a check slower to find it than its budget still answers`() {
        val fake = FakeStores(
            mapOf(
                "" to """{"stores":[{"name":"bank-old","id":"s0"}],"continuation_token":"next"}""",
                "next" to """{"stores":[{"name":"bank","id":"s1"}],"continuation_token":""}""",
            ),
            lookupTakes = Duration.ofMillis(200),
        )
        try {
            val fga = Fga(fake.url, "key", "bank", timeout = Duration.ofMillis(50))

            fga.check("person:ada", "owner", "account:a1") shouldBe true

            fake.asked.toList() shouldContainExactly listOf(
                "GET /stores?name=bank&page_size=100&continuation_token=",
                "GET /stores?name=bank&page_size=100&continuation_token=next",
                "POST /stores/s1/check",
            )
        } finally {
            fake.stop()
        }
    }

    @Test
    fun `a store that is not there is no store`() {
        val fake = FakeStores(mapOf("" to """{"stores":[]}"""))
        try {
            val missing = shouldThrow<FgaUnavailable> { Fga(fake.url, "key", "bank").storeId }

            missing.message shouldContain "no store named bank"
        } finally {
            fake.stop()
        }
    }
}
