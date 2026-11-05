package bank.app

import bank.domain.AccountEvent
import bank.domain.Leg
import bank.domain.TransferId
import dev.surefold.junit.Suite
import dev.surefold.junit.holds
import io.kotest.matchers.shouldBe

/** Bank spec 0030: a refused leg moves no money, so the read models list it and count nothing for it. */
object LegRefusedReadSpec : Suite() {
    init {
        test("a refused leg is listed as refused, with no amount") {
            holds {
                val refused = AccountEvent.LegRefused(
                    TransferId("t-4"),
                    Leg.Debit,
                    "Account cy has 0.00 GBP, not 22.56 GBP",
                    1,
                )
                refused.kind() shouldBe "refused"
                refused.amount() shouldBe null
            }
        }
    }
}
