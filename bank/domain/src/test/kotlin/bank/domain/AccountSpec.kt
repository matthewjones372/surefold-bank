package bank.domain

import dev.surefold.assert.shouldBeLeft
import dev.surefold.assert.shouldBeRight
import dev.surefold.junit.Suite
import dev.surefold.junit.holds
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

object AccountSpec : Suite() {
    init {
        test("an account opens with its initial balance") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    opened.balanceOf(id).shouldBeRight().balance shouldBe gbp(1_000)
                }
            }
        }
        test("nothing but opening reaches an account that is not open") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    Account.Unopened.decide(id, AccountCommand.Deposit(gbp(1), "d"), 0)
                        .shouldBeLeft() shouldBe AccountError.NoSuchAccount("acc-1")
                }
            }
        }
        test("an account cannot be opened again for someone else") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    opened.decide(id, AccountCommand.Open("bob", gbp(1), "again"), 0)
                        .shouldBeLeft() shouldBe AccountError.AlreadyOpen("acc-1")
                }
            }
        }
        test("opening again for the same owner is a retry and writes nothing") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    opened.decide(id, AccountCommand.Open("ada", gbp(1), "again"), 0).shouldBeRight().shouldBeEmpty()
                }
            }
        }
        test("a withdrawal larger than the balance is refused and nothing is written") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    opened.decide(id, AccountCommand.Withdraw(gbp(1_001), "w"), 0)
                        .shouldBeLeft() shouldBe AccountError.InsufficientFunds("acc-1", gbp(1_000), gbp(1_001))
                }
            }
        }
        test("a zero or negative amount never moves") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    opened.decide(
                        id,
                        AccountCommand.Deposit(gbp(0), "d"),
                        0,
                    ).shouldBeLeft().shouldBeInstanceOf<AccountError.InvalidAmount>()
                    opened.decide(id, AccountCommand.Debit(TransferId("t"), gbp(-5)), 0).shouldBeLeft()
                        .shouldBeInstanceOf<AccountError.InvalidAmount>()
                }
            }
        }
        test("the same reference twice moves the money once") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    val once = opened.after(AccountCommand.Deposit(gbp(250), "pay-7"))
                    once.decide(id, AccountCommand.Deposit(gbp(250), "pay-7"), 0).shouldBeRight().shouldBeEmpty()
                    once.balanceOf(id).shouldBeRight().balance shouldBe gbp(1_250)
                }
            }
        }
        test("a transfer's legs are idempotent by transfer id") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    val debited = opened.after(AccountCommand.Debit(TransferId("t1"), gbp(400)))
                    debited.decide(id, AccountCommand.Debit(TransferId("t1"), gbp(400)), 0).shouldBeRight()
                        .shouldBeEmpty()
                    debited.after(
                        AccountCommand.Refund(TransferId("t1"), gbp(400)),
                    ).balanceOf(id).shouldBeRight().balance shouldBe
                        gbp(1_000)
                }
            }
        }
        test("an account keeps the currency it opened in, and refuses any other before anything moves") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    val yen = Money.ofMinor(500, JPY)
                    opened.decide(id, AccountCommand.Deposit(yen, "d"), 0).shouldBeLeft() shouldBe
                        AccountError.CurrencyMismatch("acc-1", "GBP", "JPY")
                    opened.decide(id, AccountCommand.Withdraw(yen, "w"), 0).shouldBeLeft()
                        .shouldBeInstanceOf<AccountError.CurrencyMismatch>()
                    opened.decide(id, AccountCommand.Credit(TransferId("t"), yen), 0).shouldBeLeft()
                        .shouldBeInstanceOf<AccountError.CurrencyMismatch>()
                    opened.decide(id, AccountCommand.Debit(TransferId("t"), yen), 0).shouldBeLeft()
                        .shouldBeInstanceOf<AccountError.CurrencyMismatch>()
                }
            }
        }
        test("an account in bitcoin moves to the satoshi") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    val wallet = Account.Unopened.after(AccountCommand.Open("ada", Money.ofMinor(0, BTC), "open"))
                        .after(AccountCommand.Deposit(Money.ofMinor(125_000, BTC), "d"))
                    wallet.balanceOf(id).shouldBeRight().balance.toString() shouldBe "0.00125000 BTC"
                }
            }
        }
        test("a transfer's leg retried after hundreds of other commands is still applied once") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    val debited = opened.after(AccountCommand.Debit(TransferId("t1"), gbp(400)))
                    val busy = (1..Account.RECENT + 44).fold(
                        debited,
                    ) { state, n -> state.after(AccountCommand.Deposit(gbp(1), "d$n")) }
                    busy.decide(id, AccountCommand.Debit(TransferId("t1"), gbp(400)), 0).shouldBeRight().shouldBeEmpty()
                    busy.decide(id, AccountCommand.Credit(TransferId("t1"), gbp(400)), 0).shouldBeRight().size
                        .shouldBe(1)
                }
            }
        }
        test("a settled transfer's legs are closed once, and an account with none writes nothing") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    val debited = opened.after(AccountCommand.Debit(TransferId("t1"), gbp(400)))
                    val closed = debited.after(AccountCommand.Close(TransferId("t1")))
                    (closed as Account.Open).legs.shouldBeEmpty()
                    closed.decide(id, AccountCommand.Close(TransferId("t1")), 0).shouldBeRight().shouldBeEmpty()
                    opened.decide(id, AccountCommand.Close(TransferId("t9")), 0).shouldBeRight().shouldBeEmpty()
                }
            }
        }
        test("only the most recent references are remembered") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    val busy = (1..Account.RECENT + 10).fold(opened) { state, n ->
                        state.after(AccountCommand.Deposit(gbp(1), "d$n"))
                    } as Account.Open
                    busy.recent.size shouldBe Account.RECENT
                    busy.recent.last() shouldBe "d${Account.RECENT + 10}"
                }
            }
        }
    }

    private class Fixture {
        val id = AccountId("acc-1")

        fun Account.after(vararg commands: AccountCommand): Account = commands.fold(this) { state, command ->
            state.decide(id, command, now = 0).shouldBeRight().fold(state, Account::evolve)
        }

        val opened = Account.Unopened.after(AccountCommand.Open("ada", gbp(1_000), "open"))
    }
}
