package bank.app

import bank.domain.TransferError
import bank.domain.Unavailable
import dev.surefold.assert.shouldBeLeft
import dev.surefold.fail.left
import dev.surefold.junit.Suite
import dev.surefold.junit.holds
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * A start that timed out before the saga's `Started` was journalled. The same id again is safe, so the answer is
 * [Unavailable] and not a transfer that does not exist.
 */
object ShardedBankSpec : Suite() {
    init {
        test("a start that timed out and never landed is unavailable, to send again, and not a missing transfer") {
            holds {
                val lost = unstarted(
                    "t-lost",
                    TransferError.NoSuchTransfer("t-lost").left(),
                    sagaStarted = false,
                )
                lost.shouldBeLeft().shouldBeInstanceOf<Unavailable>().message shouldBe
                    "transfer t-lost did not start in time; send it again with the same id"
            }
        }
    }
}
