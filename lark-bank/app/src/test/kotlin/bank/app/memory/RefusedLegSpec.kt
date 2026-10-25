package bank.app.memory

import bank.app.gbp
import bank.domain.AccountCommand
import bank.domain.AccountId
import bank.domain.TransferId
import bank.domain.TransferStatus
import io.github.matthewjones372.lark.assert.shouldBeRight
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** A debit refused, and a retry of the same debit that arrives once the money is there (found by seed 22). */
class RefusedLegSpec {
    @Test
    fun `a debit refused and then retried after a deposit does not take money from a transfer that was rejected`() =
        inMemory {
            val cy = AccountId("cy")
            val bob = AccountId("bob")
            val t3 = TransferId("t-3")
            opened(cy, gbp(20_00))
            opened(bob, gbp(0))
            // The refusal is slow to come back, so the saga sends the debit again; that answer is slow too.
            network.next(Fault.HoldAnswer, times = 2) { it is AccountCommand.Debit }

            transfer(t3, cy, bob, gbp(38_63))
            deposit(cy, gbp(50_00), "d-1").shouldBeRight()
            advance()
            // The first answer, the refusal, arrives first and decides; the retry's answer arrives after.
            network.releaseOldest()
            network.releaseOldest()

            val status = transferStatus(t3).shouldBeRight().status
            val moved = gbp(70_00) - balanceOf(cy)
            // Either it moved and completed, or it was rejected and moved nothing.
            moved shouldBe if (status == TransferStatus.Completed) gbp(38_63) else gbp(0)
            balanceOf(cy) + balanceOf(bob) shouldBe gbp(70_00)
        }
}
