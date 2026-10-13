package bank.app

import bank.domain.AccountEvent
import bank.domain.Leg
import bank.domain.TransferId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Bank spec 0029: a refused leg moves no money, so the read models list it and count nothing for it. */
class LegRefusedReadSpec {
    @Test
    fun `a refused leg is listed as refused, with no amount`() {
        val refused = AccountEvent.LegRefused(TransferId("t-4"), Leg.Debit, "Account cy has 0.00 GBP, not 22.56 GBP", 1)
        refused.kind() shouldBe "refused"
        refused.amount() shouldBe null
    }
}
