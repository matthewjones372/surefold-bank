package bank.app.memory

import bank.app.gbp
import bank.domain.AccountId
import bank.domain.TransferId
import bank.domain.TransferStatus
import io.github.matthewjones372.surefold.assert.shouldBeRight
import io.github.matthewjones372.surefold.metrics
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** What the saga counts as it goes (bank spec 0028): each step it writes, by the event it wrote. */
class CountedStepsSpec {
    @Test
    fun `a transfer counts each step it writes, once, by its event`() {
        val counted = Counted()
        metrics.locally(counted) {
            inMemory {
                opened(AccountId("ada"), gbp(10_00))
                opened(AccountId("bob"), gbp(0))
                val settled = transfer(TransferId("t-1"), AccountId("ada"), AccountId("bob"), gbp(4_00)).shouldBeRight()
                settled.status shouldBe TransferStatus.Completed
            }
        }
        listOf("SourceDebited", "DestinationCredited", "SourceRefunded").map {
            counted.total("bank.transfer.steps", "event" to it)
        } shouldBe listOf(1.0, 1.0, 0.0)
    }
}
