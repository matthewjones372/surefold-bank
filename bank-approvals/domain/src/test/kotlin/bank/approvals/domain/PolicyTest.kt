package bank.approvals.domain

import dev.surefold.junit.Suite
import dev.surefold.junit.holds
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

object PolicyTest : Suite() {
    init {
        test("a condition matches facts that hold every one of its keys, extra facts or not") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    limits.autoApproval(mapOf("direction" to "lower", "by" to "100")) shouldBe lowerOnly
                    limits.autoApproval(mapOf("direction" to "raise")).shouldBeNull()
                    limits.autoApproval(emptyMap()).shouldBeNull()
                }
            }
        }
        test("a condition with no facts would approve everything, and is refused") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    shouldThrow<IllegalArgumentException> { Condition(emptyMap()) }
                }
            }
        }
        test("a policy needs at least one approver from at least one group") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    shouldThrow<IllegalArgumentException> { Policy("k", approvers = setOf("ops"), needed = 0) }
                    shouldThrow<IllegalArgumentException> { Policy("k", approvers = emptySet(), needed = 1) }
                }
            }
        }
    }

    private class Fixture {

        val lowerOnly = Condition(mapOf("direction" to "lower"))
        val limits = Policy(
            "bank.account-limit",
            approvers = setOf("ops"),
            needed = 1,
            autoApprove = listOf(lowerOnly),
        )
    }
}
