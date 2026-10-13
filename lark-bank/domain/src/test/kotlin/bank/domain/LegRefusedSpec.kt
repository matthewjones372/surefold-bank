package bank.domain

import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** Bank spec 0030: a leg an account refused is refused again, whatever has changed since, and written once. */
class LegRefusedSpec {
    private val cy = AccountId("cy")
    private val t4 = TransferId("t-4")
    private val now = 1_700_000_000_000L

    private fun Account.after(events: List<AccountEvent>): Account = events.fold(this, Account::evolve)

    private val empty = Account.Unopened.after(listOf(AccountEvent.Opened("cy", gbp(0), "open:cy", now)))

    @Test
    fun `a debit refused for want of money is refused again after money arrives, and written once`() {
        val debit = AccountCommand.Debit(t4, gbp(22_56))
        val refusal = empty.decide(cy, debit, now).shouldBeLeft().shouldBeInstanceOf<AccountError.InsufficientFunds>()
        val noted = empty.refusing(debit, refusal, now)
        noted shouldBe listOf(AccountEvent.LegRefused(t4, Leg.Debit, refusal.message, now))

        val funded = empty.after(noted + AccountEvent.Deposited(gbp(50_00), "d-1", now))
        val again = funded.decide(cy, debit, now).shouldBeLeft()
        again shouldBe AccountError.LegRefused("cy", "debit:t-4", refusal.message)
        again.message shouldBe refusal.message
        funded.refusing(debit, again, now).shouldBeEmpty()
    }

    @Test
    fun `a refused credit is refused again, so a refunded transfer is never paid out as well`() {
        val credit = AccountCommand.Credit(t4, Money.ofMinor(5, JPY))
        val refusal = empty.decide(cy, credit, now).shouldBeLeft()
        val refused = empty.after(empty.refusing(credit, refusal, now))
        refused.decide(cy, credit, now).shouldBeLeft() shouldBe
            AccountError.LegRefused("cy", "credit:t-4", refusal.message)
    }

    @Test
    fun `closing a transfer forgets its refused legs, as it does its applied ones`() {
        val debit = AccountCommand.Debit(t4, gbp(1_00))
        val refused = empty.after(empty.refusing(debit, empty.decide(cy, debit, now).shouldBeLeft(), now))
        val closed = refused.decide(cy, AccountCommand.Close(t4), now).shouldBeRight()
        closed shouldBe listOf(AccountEvent.LegsClosed(t4, now))
        refused.after(closed).shouldBeInstanceOf<Account.Open>().refused shouldBe emptyMap()
        refused.decide(cy, AccountCommand.Close(TransferId("t-5")), now).shouldBeRight() shouldBe emptyList()
    }

    @Test
    fun `a refusal is kept under the leg's own reference, and answered with its own message`() {
        AccountEvent.LegRefused(t4, Leg.Debit, "no", now).reference shouldBe "debit:t-4"
        val refused = AccountEvent.LegRefused(t4, Leg.Credit, "no", now)
        listOf(refused.reference, refused.transfer.value, refused.atMillis) shouldBe listOf("credit:t-4", "t-4", now)
        val again = AccountError.LegRefused("cy", "debit:t-4", "Account cy has 0.00 GBP, not 1.00 GBP")
        listOf(again.id, again.reference, again.message) shouldBe
            listOf("cy", "debit:t-4", "Account cy has 0.00 GBP, not 1.00 GBP")
    }

    @Test
    fun `closing one transfer keeps every other transfer's legs, applied and refused`() {
        val t5 = TransferId("t-5")
        val held = empty.after(
            listOf(
                AccountEvent.Credited(t4, gbp(1_00), now),
                AccountEvent.Credited(t5, gbp(1_00), now),
                AccountEvent.LegRefused(t4, Leg.Debit, "no", now),
                AccountEvent.LegRefused(t5, Leg.Debit, "no", now),
            ),
        )
        val closed = held.after(listOf(AccountEvent.LegsClosed(t4, now))).shouldBeInstanceOf<Account.Open>()
        closed.legs shouldBe setOf("credit:t-5")
        closed.refused shouldBe mapOf("debit:t-5" to "no")
    }

    @Test
    fun `only a debit or a credit to an open account is kept as refused`() {
        val refusal = AccountError.InvalidAmount(gbp(0))
        listOf(
            AccountCommand.Open("cy", gbp(0), "open:cy"),
            AccountCommand.Deposit(gbp(0), "d-1"),
            AccountCommand.Refund(t4, gbp(0)),
            AccountCommand.Close(t4),
        ).map { empty.refusing(it, refusal, now) } shouldBe List(4) { emptyList() }
        val withdraw = AccountCommand.Withdraw(gbp(1), "w-1")
        Account.Unopened.refusing(withdraw, AccountError.NoSuchAccount("cy"), now) shouldBe emptyList()
    }

    @Test
    fun `an event other than its opening, for an account never opened, is a corrupt journal`() {
        val refusing = Account.Refusing(mapOf("credit:t-4" to "No account cy"))
        val deposited = AccountEvent.Deposited(gbp(1), "d-1", now)
        listOf(Account.Unopened, refusing).forEach { never ->
            val corrupt = shouldThrow<IllegalStateException> { never.evolve(deposited) }
            corrupt.message shouldBe "an event for an account that was never opened"
        }
    }

    @Test
    fun `a credit refused because nobody had opened the account is refused again once it is opened`() {
        val credit = AccountCommand.Credit(t4, gbp(5_00))
        val refusal = Account.Unopened.decide(cy, credit, now).shouldBeLeft()
        val noted = Account.Unopened.refusing(credit, refusal, now)
        noted shouldBe listOf(AccountEvent.LegRefused(t4, Leg.Credit, refusal.message, now))

        val refusing = Account.Unopened.after(noted).shouldBeInstanceOf<Account.Refusing>()
        val opened = refusing.after(listOf(AccountEvent.Opened("cy", gbp(0), "open:cy", now)))
        opened.decide(cy, credit, now).shouldBeLeft() shouldBe
            AccountError.LegRefused("cy", "credit:t-4", refusal.message)
        opened.decide(cy, AccountCommand.Close(t4), now).shouldBeRight() shouldBe
            listOf(AccountEvent.LegsClosed(t4, now))
    }

    @Test
    fun `an account holding refusals but never opened is still not open`() {
        val credit = AccountCommand.Credit(t4, gbp(5_00))
        val refusing = Account.Unopened.after(Account.Unopened.refusing(credit, AccountError.NoSuchAccount("cy"), now))
        refusing.balanceOf(cy).shouldBeLeft() shouldBe AccountError.NoSuchAccount("cy")
        refusing.decide(cy, AccountCommand.Deposit(gbp(1), "d-1"), now).shouldBeLeft() shouldBe
            AccountError.NoSuchAccount("cy")
        refusing.decide(cy, AccountCommand.Close(TransferId("t-5")), now).shouldBeLeft() shouldBe
            AccountError.NoSuchAccount("cy")
        val closed = refusing.decide(cy, AccountCommand.Close(t4), now).shouldBeRight()
        refusing.after(closed) shouldBe Account.Unopened
        val two = refusing.after(listOf(AccountEvent.LegRefused(TransferId("t-5"), Leg.Debit, "no", now)))
        two.after(closed) shouldBe Account.Refusing(mapOf("debit:t-5" to "no"))
    }

    @Test
    fun `a refused withdrawal is not kept, so it may succeed once money arrives`() {
        val withdraw = AccountCommand.Withdraw(gbp(1_00), "w-1")
        val refusal = empty.decide(cy, withdraw, now).shouldBeLeft()
        empty.refusing(withdraw, refusal, now) shouldBe emptyList()
    }
}
