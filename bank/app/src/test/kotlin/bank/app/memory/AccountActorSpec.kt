package bank.app.memory

import bank.app.account
import bank.app.gbp
import bank.domain.AccountCommand
import bank.domain.AccountError
import bank.domain.AccountEvent
import bank.domain.AccountId
import bank.domain.Balance
import bank.domain.Currency
import bank.domain.Money
import bank.protocol.AccountAnswer
import bank.protocol.AccountAsk
import bank.protocol.AccountEvents
import bank.protocol.AccountMessage
import bank.protocol.BulkCredit
import bank.protocol.BulkCreditSent
import bank.protocol.Kinds
import dev.surefold.actor.ActorRef
import dev.surefold.actor.Address
import dev.surefold.actor.Confirmed
import dev.surefold.actor.Delivery
import dev.surefold.actor.PersistenceId
import dev.surefold.actor.Reply
import dev.surefold.actor.TestActors
import dev.surefold.actor.events
import dev.surefold.actor.testActors
import dev.surefold.clock
import dev.surefold.fail.Either
import dev.surefold.fixedClock
import dev.surefold.junit.Suite
import dev.surefold.junit.holds
import dev.surefold.metrics
import io.kotest.matchers.shouldBe
import java.time.Instant

/**
 * The account actor around the account's pure decisions (bank spec 0028): what it answers, what it writes and when it
 * stamps it, what it counts, and the payroll's bulk credits it confirms rather than answers.
 */
object AccountActorSpec : Suite() {
    init {
        test("a balance enquiry is answered with the balance, and an account nobody opened is missing") {
            holds {
                withAccount {
                    ask(null) shouldBe Either.Right(Balance("ada", "ada", gbp(10_00)))
                    ask(null, to = "nobody") shouldBe Either.Left(AccountError.NoSuchAccount("nobody"))
                }
            }
        }
        test("each command is counted by what became of it, and an applied one is stamped with the bank's clock") {
            holds {
                withAccount {
                    ask(
                        AccountCommand.Deposit(gbp(1_00), "d-1"),
                    ) shouldBe Either.Right(Balance("ada", "ada", gbp(11_00)))
                    ask(
                        AccountCommand.Deposit(gbp(1_00), "d-1"),
                    ) shouldBe Either.Right(Balance("ada", "ada", gbp(11_00)))
                    ask(AccountCommand.Withdraw(gbp(50_00), "w-1")) shouldBe
                        Either.Left(AccountError.InsufficientFunds("ada", gbp(11_00), gbp(50_00)))

                    counted.total("bank.account.commands", "outcome" to "applied") shouldBe 2.0
                    // the opening and the deposit
                    counted.total("bank.account.commands", "outcome" to "repeated") shouldBe 1.0
                    counted.total("bank.account.commands", "outcome" to "refused") shouldBe 1.0
                    events().last() shouldBe AccountEvent.Deposited(gbp(1_00), "d-1", NOON.toEpochMilli())
                }
            }
        }
        test("a bulk credit is paid in once and confirmed, and sent again it is confirmed and not paid twice") {
            holds {
                withAccount {
                    bulk(gbp(2_50), sequence = 1)
                    bulk(gbp(2_50), sequence = 1)

                    ask(null) shouldBe Either.Right(Balance("ada", "ada", gbp(12_50)))
                    events().filterIsInstance<AccountEvent.Deposited>() shouldBe
                        listOf(AccountEvent.Deposited(gbp(2_50), "payroll:p-1:ada", NOON.toEpochMilli()))
                    confirmed shouldBe listOf(Confirmed("ada", 1), Confirmed("ada", 1))
                    counted.total("bank.account.commands", "outcome" to "bulk") shouldBe 1.0
                }
            }
        }
        test("a bulk credit the account refuses writes nothing, and is still confirmed so it is not sent for ever") {
            holds {
                withAccount {
                    bulk(Money.ofMinor(2_50, Currency("EUR", 2)), sequence = 1)

                    ask(null) shouldBe Either.Right(Balance("ada", "ada", gbp(10_00)))
                    events().filterIsInstance<AccountEvent.Deposited>() shouldBe emptyList()
                    confirmed shouldBe listOf(Confirmed("ada", 1))
                    counted.total("bank.account.commands", "outcome" to "refused") shouldBe 1.0
                }
            }
        }
    }

    /** Ada's account opened with 10.00, on test actors, with what it counts and what it confirms kept. */
    private class OneAccount(private val sim: TestActors, val counted: Counted) {
        private val accounts = HashMap<String, ActorRef<AccountMessage>>()
        val confirmed = mutableListOf<Confirmed>()
        private val confirms = object : ActorRef<Confirmed> {
            override val address = Address("test", "/temp/payroll", 1)

            override fun tell(message: Confirmed) {
                confirmed += message
            }
        }

        init {
            ask(AccountCommand.Open("ada", gbp(10_00), "open:ada"))
        }

        private fun of(id: String) = accounts.getOrPut(id) { sim.spawn("account-$id", account(AccountId(id), null)) }

        /** [command] asked of account [to]; null asks its balance. */
        fun ask(command: AccountCommand?, to: String = "ada"): AccountAnswer {
            val answers = mutableListOf<AccountAnswer>()
            of(to).tell(AccountAsk(command, Kept(answers)))
            return answers.single()
        }

        fun bulk(amount: Money, sequence: Long) = of("ada").tell(
            BulkCreditSent(BulkCredit(amount, "payroll:p-1:ada"), Delivery("payroll-p-1", "ada", sequence, confirms)),
        )

        fun events(): List<AccountEvent> = sim.journal.events(PersistenceId(Kinds.ACCOUNT, "ada"), AccountEvents)
    }

    private class Kept(private val answers: MutableList<AccountAnswer>) : Reply<AccountAnswer> {
        override val address = Address("test", "/temp/asked", 1)

        override fun invoke(answer: AccountAnswer) {
            answers += answer
        }
    }

    private fun withAccount(block: OneAccount.() -> Unit) {
        val counted = Counted()
        metrics.locally(counted) {
            clock.locally(fixedClock(NOON)) {
                testActors(clock = fixedClock(NOON)) { OneAccount(this, counted).block() }
            }
        }
    }

    private val NOON: Instant = Instant.parse("2026-10-10T12:00:00Z")
}
