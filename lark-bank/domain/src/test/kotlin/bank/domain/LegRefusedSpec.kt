package bank.domain

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** Bank spec 0030: a transfer's leg is decided once, so a leg refused is refused again, whatever changed since. */
class LegRefusedSpec {
    private val id = AccountId("cy")
    private val t3 = TransferId("t-3")

    /** [command] decided and, applied or refused, written as the actor writes it. */
    private fun Account.after(command: AccountCommand): Account =
        decide(id, command, now = 0).fold(
            { refusal -> refusalToKeep(command, refusal, now = 0)?.let(::evolve) ?: this },
            { events -> events.fold(this, Account::evolve) },
        )

    private val opened = Account.Unopened.after(AccountCommand.Open("cy", gbp(2_000), "open"))

    @Test
    fun `a debit refused and sent again after a deposit is refused again, with the first reason`() {
        val debit = AccountCommand.Debit(t3, gbp(3_863))
        val refused = opened.decide(id, debit, 0).shouldBeLeft()
        val kept = opened.refusalToKeep(debit, refused, 0).shouldBeInstanceOf<AccountEvent.LegRefused>()
        kept shouldBe AccountEvent.LegRefused(t3, "debit", "Account cy has 20.00 GBP, not 38.63 GBP", 0)

        val richer = opened.evolve(kept).after(AccountCommand.Deposit(gbp(5_000), "d-1"))

        richer.decide(id, debit, 0).shouldBeLeft() shouldBe
            AccountError.AlreadyRefused("cy", "Account cy has 20.00 GBP, not 38.63 GBP")
        // Answered again, it is not written again.
        richer.refusalToKeep(debit, AccountError.AlreadyRefused("cy", kept.reason), 0).shouldBeNull()
        richer.balanceOf(id).shouldBeRight().balance shouldBe gbp(7_000)
    }

    @Test
    fun `a credit refused by an account nobody opened is refused again once it is opened`() {
        val credit = AccountCommand.Credit(t3, gbp(500))
        val refusing = Account.Unopened.after(credit)
        refusing shouldBe Account.Refusing(mapOf("credit:t-3" to "No account cy"))

        val opened = refusing.after(AccountCommand.Open("cy", gbp(0), "open"))

        opened.decide(id, credit, 0).shouldBeLeft() shouldBe AccountError.AlreadyRefused("cy", "No account cy")
        opened.balanceOf(id).shouldBeRight().balance shouldBe gbp(0)
    }

    @Test
    fun `a close forgets a transfer's refusals with its legs, and a refusing account with none left is unopened`() {
        val credit = AccountCommand.Credit(t3, gbp(500))
        val refusing = Account.Unopened.after(credit)
        refusing.after(AccountCommand.Close(t3)) shouldBe Account.Unopened

        val debit = AccountCommand.Debit(t3, gbp(3_863))
        val closed = opened.after(debit).after(AccountCommand.Close(t3)) as Account.Open
        closed.refused shouldBe emptyMap()
    }

    @Test
    fun `only a transfer's legs are remembered, and nothing the account could not decide`() {
        opened.refusalToKeep(AccountCommand.Withdraw(gbp(5_000), "w-1"), AccountError.InsufficientFunds("cy", gbp(2_000), gbp(5_000)), 0)
            .shouldBeNull()
        opened.refusalToKeep(AccountCommand.Debit(t3, gbp(1)), Unavailable("slow"), 0).shouldBeNull()
    }
}
