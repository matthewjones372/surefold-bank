package bank.domain

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** What an account's events and refusals carry, field by field: what the journal, wire and API read. */
class AccountDetailsSpec {
    private val id = AccountId("acc-1")
    private val t = TransferId("t-1")
    private val now = 1_700_000_000_000L

    private fun Account.after(command: AccountCommand): Account =
        decide(id, command, now).shouldBeRight().fold(this, Account::evolve)

    private val opened = Account.Unopened.after(AccountCommand.Open("ada", gbp(1_000), "open"))

    private fun single(command: AccountCommand, from: Account = opened): AccountEvent =
        from.decide(id, command, now).shouldBeRight().single()

    @Test
    fun `an account pays out its whole balance, and not a penny more`() {
        opened.decide(id, AccountCommand.Withdraw(gbp(1_000), "w"), now).shouldBeRight() shouldHaveSize 1
        opened.decide(id, AccountCommand.Withdraw(gbp(1_001), "w"), now).shouldBeLeft()
            .shouldBeInstanceOf<AccountError.InsufficientFunds>()
    }

    @Test
    fun `a deposit and a withdrawal carry their reference and when they happened`() {
        val deposited = single(AccountCommand.Deposit(gbp(5), "d-1")).shouldBeInstanceOf<AccountEvent.Deposited>()
        deposited.reference shouldBe "d-1"
        deposited.atMillis shouldBe now
        val withdrawn = single(AccountCommand.Withdraw(gbp(5), "w-1")).shouldBeInstanceOf<AccountEvent.Withdrawn>()
        withdrawn.reference shouldBe "w-1"
        withdrawn.atMillis shouldBe now
    }

    @Test
    fun `a transfer's legs carry the transfer, a reference made from it, and when they happened`() {
        val credit = AccountCommand.Credit(t, gbp(5))
        credit.transfer shouldBe t
        credit.reference shouldBe "credit:t-1"
        AccountCommand.Refund(t, gbp(5)).reference shouldBe "refund:t-1"
        AccountCommand.Close(t).reference shouldBe "close:t-1"

        val credited = single(credit).shouldBeInstanceOf<AccountEvent.Credited>()
        credited.transfer shouldBe t
        credited.reference shouldBe "credit:t-1"
        credited.atMillis shouldBe now

        val debited = opened.after(AccountCommand.Debit(t, gbp(5)))
        val refunded = single(AccountCommand.Refund(t, gbp(5)), debited).shouldBeInstanceOf<AccountEvent.Refunded>()
        refunded.reference shouldBe "refund:t-1"
        val closed = single(AccountCommand.Close(t), debited).shouldBeInstanceOf<AccountEvent.LegsClosed>()
        closed.reference shouldBe "close:t-1"
        closed.atMillis shouldBe now
    }

    @Test
    fun `each refusal names the account and says why in words`() {
        val missing = Account.Unopened.decide(id, AccountCommand.Deposit(gbp(1), "d"), now).shouldBeLeft()
            .shouldBeInstanceOf<AccountError.NoSuchAccount>()
        missing.id shouldBe "acc-1"
        missing.message shouldBe "No account acc-1"

        val clash = opened.decide(id, AccountCommand.Open("bob", gbp(1), "again"), now).shouldBeLeft()
            .shouldBeInstanceOf<AccountError.AlreadyOpen>()
        clash.id shouldBe "acc-1"
        clash.message shouldBe "Account acc-1 is already open"

        val short = opened.decide(id, AccountCommand.Withdraw(gbp(1_001), "w"), now).shouldBeLeft()
            .shouldBeInstanceOf<AccountError.InsufficientFunds>()
        short.id shouldBe "acc-1"
        short.message shouldBe "Account acc-1 has 10.00 GBP, not 10.01 GBP"

        val nothing = opened.decide(id, AccountCommand.Deposit(gbp(0), "d"), now).shouldBeLeft()
            .shouldBeInstanceOf<AccountError.InvalidAmount>()
        nothing.message shouldBe "0.00 GBP is not an amount that can move"

        val yen = opened.decide(id, AccountCommand.Deposit(Money.ofMinor(5, JPY), "d"), now).shouldBeLeft()
            .shouldBeInstanceOf<AccountError.CurrencyMismatch>()
        yen.id shouldBe "acc-1"
        yen.account shouldBe "GBP"
        yen.given shouldBe "JPY"
        yen.message shouldBe "Account acc-1 is in GBP, not JPY"

        Unavailable("the journal is slow").message shouldBe "the journal is slow"
    }

    @Test
    fun `a balance names its account and owner`() {
        val balance = opened.balanceOf(id).shouldBeRight()
        balance.id shouldBe "acc-1"
        balance.owner shouldBe "ada"
    }

    @Test
    fun `a transfer's view names it, both accounts and why it stopped`() {
        val moving = Transfer.Moving(
            AccountId("a"),
            AccountId("b"),
            gbp(5),
            TransferStatus.Rejected,
            "no funds",
            requestedAt = 1,
            settledAt = 2,
        )
        val view = moving.view(t)
        view.id shouldBe "t-1"
        view.from shouldBe "a"
        view.to shouldBe "b"
        view.reason shouldBe "no funds"
    }
}
