package bank.app.memory

import bank.app.account
import bank.app.gbp
import bank.domain.AccountCommand
import bank.domain.AccountEvent
import bank.domain.AccountId
import bank.domain.Money
import bank.domain.TransferId
import bank.protocol.AccountAnswer
import bank.protocol.AccountAsk
import bank.protocol.AccountEvents
import bank.protocol.AccountMessage
import bank.protocol.Kinds
import dev.surefold.Shared
import dev.surefold.actor.ActorRef
import dev.surefold.actor.Address
import dev.surefold.actor.InMemoryJournal
import dev.surefold.actor.Journal
import dev.surefold.actor.PersistenceId
import dev.surefold.actor.Reply
import dev.surefold.actor.TestActors
import dev.surefold.actor.events
import dev.surefold.actor.testActors
import dev.surefold.assert.shouldBeRight
import dev.surefold.clock
import dev.surefold.fixedClock
import dev.surefold.junit.Suite
import dev.surefold.junit.holds
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/**
 * Bank spec 0031: a settled transfer's close is held by the account and written with its next write, so it costs no
 * append of its own, or alone once [CLOSE_AFTER] passes with nothing to carry it. A close lost before it is written
 * leaves the leg open, which only answers a late copy of it as applied (spec 0013).
 */
object HeldClosesSpec : Suite() {
    init {
        test("a close is held, and written in the same append as the account's next command") {
            holds {
                ada {
                    debit(t1, gbp(3_00))
                    val before = appends.peek()

                    close(t1)
                    appends.peek() shouldBe before
                    events().filterIsInstance<AccountEvent.LegsClosed>() shouldHaveSize 0

                    deposit(gbp(1_00), "d-1")
                    appends.peek() shouldBe before + 1
                    events().takeLast(2).map { it::class.simpleName } shouldBe listOf("LegsClosed", "Deposited")
                    balance() shouldBe gbp(8_00)
                }
            }
        }
        test("a close with nothing after it to carry it is written once its time is up") {
            holds {
                ada {
                    debit(t1, gbp(3_00))
                    close(t1)
                    val before = appends.peek()

                    sim.advance(CLOSE_AFTER)

                    appends.peek() shouldBe before + 1
                    events().last() shouldBe AccountEvent.LegsClosed(t1, NOON.toEpochMilli())
                    sim.pendingTimers shouldBe 0
                }
            }
        }
        test("the same transfer closed twice while held is written once") {
            holds {
                ada {
                    debit(t1, gbp(3_00))
                    close(t1)
                    close(t1)

                    deposit(gbp(1_00), "d-1")

                    events().filterIsInstance<AccountEvent.LegsClosed>() shouldHaveSize 1
                }
            }
        }
        test("a held close lost in a crash leaves the leg open, so a late copy of the debit moves nothing") {
            holds {
                ada {
                    debit(t1, gbp(3_00))
                    close(t1)

                    sim.crash(account)
                    debit(t1, gbp(3_00))
                    sim.advance(CLOSE_AFTER)

                    balance() shouldBe gbp(7_00)
                    events().filterIsInstance<AccountEvent.Debited>() shouldHaveSize 1
                    events().filterIsInstance<AccountEvent.LegsClosed>() shouldHaveSize 0
                }
            }
        }
    }
    private val t1 = TransferId("t-1")

    /** Ada's account, opened with 10.00, holding closes for [CLOSE_AFTER], on a journal that counts its appends. */
    private fun ada(block: Ada.() -> Unit) = clock.locally(fixedClock(NOON)) {
        val appends = Shared(0, "bank.account.appends")
        val counting = object : Journal {
            private val inner = InMemoryJournal()

            override fun append(id: PersistenceId, expected: Long, events: List<ByteArray>) =
                inner.append(id, expected, events).also { appends.update { it + 1 } }

            override fun read(id: PersistenceId, from: Long) = inner.read(id, from)
        }
        testActors(clock = fixedClock(NOON), journal = counting) { Ada(this, appends).apply { open() }.block() }
    }

    private class Ada(val sim: TestActors, val appends: Shared<Int>) {
        private val id = AccountId("ada")
        val account: ActorRef<AccountMessage> =
            sim.spawn("account-ada", account(id, snapshots = null, closeAfter = CLOSE_AFTER))
        private val answers = mutableListOf<AccountAnswer>()
        private val reply = object : Reply<AccountAnswer> {
            override val address = Address("test", "/temp/ada", 1)

            override fun invoke(answer: AccountAnswer) {
                answers += answer
            }
        }

        fun open() = ask(AccountCommand.Open(id.value, gbp(10_00), "open:ada"))

        fun debit(transfer: TransferId, amount: Money) = ask(AccountCommand.Debit(transfer, amount))

        fun close(transfer: TransferId) = ask(AccountCommand.Close(transfer))

        fun deposit(amount: Money, reference: String) = ask(AccountCommand.Deposit(amount, reference))

        fun balance(): Money = answers.last().shouldBeRight().balance

        fun events(): List<AccountEvent> = sim.journal.events(PersistenceId(Kinds.ACCOUNT, id.value), AccountEvents)

        private fun ask(command: AccountCommand) = account.tell(AccountAsk(command, reply))
    }

    private val CLOSE_AFTER = 30.seconds
    private val NOON: Instant = Instant.parse("2026-10-10T12:00:00Z")
}
