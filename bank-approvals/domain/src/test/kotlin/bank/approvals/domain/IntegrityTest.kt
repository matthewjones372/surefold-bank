package bank.approvals.domain

import dev.surefold.junit.Suite
import dev.surefold.junit.holds
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/** What is approved cannot change after, and tampering with the record shows (bank spec 0019's audit). */
object IntegrityTest : Suite() {
    init {
        test("the content hash is one an owning service can make itself, as bank-checks' PolicySpec does") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    val proposal = Proposal(
                        "checks.rule-version",
                        "checks/rule/large",
                        "large",
                        "amount is at least 1000",
                        "amount is at least 500",
                        facts = mapOf("severity" to "high", "change" to "tighten"),
                    )
                    proposal.contentHash shouldBe "47d22c0de0e7bbc23492e9967a25cf4268cd1e6c0ef15f87639dc13a76c05408"
                }
            }
        }
        test("the content hash covers before, after and facts, and nothing shown only beside them") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    proposal.copy(after = "amount is at least 50").contentHash shouldNotBe proposal.contentHash
                    proposal.copy(before = "").contentHash shouldNotBe proposal.contentHash
                    proposal.copy(facts = mapOf("change" to "loosen")).contentHash shouldNotBe proposal.contentHash

                    proposal.copy(
                        title = "a better title",
                        impact = "{}",
                        link = "https://x",
                    ).contentHash shouldBe proposal.contentHash
                }
            }
        }
        test("facts hash the same in any order") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    val one = proposal.copy(facts = linkedMapOf("a" to "1", "b" to "2"))
                    val other = proposal.copy(facts = linkedMapOf("b" to "2", "a" to "1"))

                    one.contentHash shouldBe other.contentHash
                }
            }
        }
        test("the text cannot be moved from before to after without the hash changing") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    proposal.copy(
                        before = "x",
                        after = "yz",
                    ).contentHash shouldNotBe proposal.copy(before = "xy", after = "z").contentHash
                }
            }
        }
        test("an untouched chain verifies") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    Chain.firstBroken(stored).shouldBeNull()
                }
            }
        }
        test("an edited event is named, and it is the first that fails") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    val edited = stored.toMutableList().apply { set(2, get(2).copy(event = "Commented bob: fine")) }

                    Chain.firstBroken(edited) shouldBe 2
                }
            }
        }
        test("a dropped or reordered event breaks the chain where it happened") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    Chain.firstBroken(stored.filterIndexed { index, _ -> index != 1 }) shouldBe 1
                    Chain.firstBroken(listOf(stored[0], stored[2], stored[1], stored[3], stored[4])) shouldBe 1
                }
            }
        }
        test("an event rewritten with a freshly computed link still breaks the next one") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    val forged = stored.toMutableList().apply {
                        set(1, Chain.Stored("Approved eve", Chain.link(stored[0].link, "Approved eve")))
                    }

                    Chain.firstBroken(forged) shouldBe 2
                }
            }
        }
    }

    private class Fixture {

        val proposal = Proposal(
            kind = "checks.rule-version",
            subject = "checks/rule/large-transfer",
            title = "v4",
            before = "amount is at least 1000",
            after = "amount is at least 500",
            facts = mapOf("change" to "tighten"),
        )

        val events = listOf("Requested req-1", "Approved ada", "Commented bob: why?", "Approved bob", "Applied")
        val stored = events.zip(Chain.links(events)) { event, link -> Chain.Stored(event, link) }
    }
}
