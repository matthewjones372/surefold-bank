package bank.app

import bank.api.accountLooks
import bank.api.getAccount
import bank.api.statement
import bank.domain.AccountId
import bank.events.v1.AccessEvent
import com.microsoft.playwright.assertions.LocatorAssertions
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import dev.surefold.assert.shouldBeRight
import dev.surefold.clock
import dev.surefold.junit.Suite
import dev.surefold.junit.holds
import io.github.matthewjones372.pelican.In3
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.test.apiClient
import io.github.matthewjones372.pelican.test.shouldBeOk
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val patiently = LocatorAssertions.ContainsTextOptions().setTimeout(15_000.0)

/** Bank spec 0021's `access-log`: every look by staff recorded and published, and the customer told who has looked. */
object AccessLogSpec : Suite(isolated = true, concurrent = false) {
    init {
        test("Ada's page lists Bob's look, and the topic carries it with the grant's approval id") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    val account = "ada-$run"
                    TestCluster(1, extra = TestKafka.config).running { (node) ->
                        node.bank.open(AccountId(account), "ada", gbp(5_000), "open:$account").shouldBeRight()
                        node.grants.give("look-$run", "bob", Access.View(account), clock.get().now().plusSeconds(600))
                        apiClient(node.server.baseUrl, JacksonCodecs).use { client ->
                            val bob = client.calling("bob", "support")
                            bob.response(getAccount, account).status shouldBe 200
                            bob.response(statement, In3(account, 50, null)).status shouldBe 200

                            // The auditor sees who it was, and the grant it was under.
                            val auditor = client.calling("aud", "auditor")
                            eventually("both of Bob's looks recorded") {
                                auditor.outcome(accountLooks, account).shouldBeOk().count { it.who == "bob" } >= 2
                            }
                            auditor.outcome(accountLooks, account)
                                .shouldBeOk().filter { it.who == "bob" }.forEach { look ->
                                    look.role shouldBe "Support"
                                    look.grant shouldBe "look-$run"
                                }
                        }

                        inBrowser { page ->
                            page.signIn(node.server.baseUrl, "ada", then = "/account.html?id=$account")
                            assertThat(page.locator("#looks")).containsText("Support", patiently)
                            assertThat(page.locator("#looks")).containsText("viewed the statement", patiently)
                            assertThat(page.locator("#looks")).containsText("viewed the account", patiently)
                            // Names are for the auditor: Ada sees the role.
                            withClue(
                                "Ada's page names nobody",
                            ) { page.locator("#looks").innerText().contains("bob") shouldBe false }
                        }

                        val published = TestKafka.read<AccessEvent>("bank.access-events", setOf(account)) { seen ->
                            seen.any { it.who.subject == "bob" && it.endpoint.endsWith("/statement") }
                        }
                        val look = published.first { it.who.subject == "bob" && it.endpoint.endsWith("/statement") }
                        look.accountId shouldBe account
                        look.grantApprovalId shouldBe "look-$run"
                        look.status shouldBe 200
                        look.who.groupsList shouldContain "support"
                        look.endpoint shouldBe "GET /accounts/{accountId}/statement"
                    }
                }
            }
        }
        test("refusals are recorded for everyone, a customer's own looks are not, and only the auditor sees either") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    val account = "ada-$run"
                    TestCluster(1).running { (node) ->
                        node.bank.open(AccountId(account), "ada", gbp(5_000), "open:$account").shouldBeRight()
                        apiClient(node.server.baseUrl, JacksonCodecs).use { client ->
                            client.calling("ada").response(getAccount, account).status shouldBe 200
                            client.calling("eve").response(getAccount, account).status shouldBe 404
                            client.calling("sam", "support").response(getAccount, account).status shouldBe 404

                            val auditor = client.calling("aud", "auditor")
                            eventually("the refusals recorded") {
                                auditor.outcome(
                                    accountLooks,
                                    account,
                                ).shouldBeOk().mapNotNull { it.who }.containsAll(listOf("eve", "sam"))
                            }
                            val seen = auditor.outcome(accountLooks, account).shouldBeOk()
                            seen.filter { it.who == "eve" || it.who == "sam" }.forEach { it.status shouldBe 404 }
                            seen.mapNotNull { it.who } shouldNotContain "ada"

                            // Ada is told of looks that saw something, not of attempts that saw nothing.
                            client.calling(
                                "ada",
                            ).outcome(accountLooks, account).shouldBeOk().filter { it.role != "Auditor" }
                                .shouldBeEmpty()
                            client.calling("eve").response(accountLooks, account).status shouldBe 404
                        }
                    }
                }
            }
        }
    }

    private class Fixture {
        val run = UUID.randomUUID().toString().take(8)

        fun eventually(what: String, done: () -> Boolean) {
            dev.surefold.test.eventually(30.seconds, every = 100.milliseconds) {
                check(done()) { "never: $what" }
            }
        }
    }
}
