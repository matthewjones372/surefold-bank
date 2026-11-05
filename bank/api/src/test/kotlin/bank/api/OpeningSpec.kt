package bank.api

import bank.domain.AccountError
import bank.domain.Currencies
import bank.domain.Currency
import bank.domain.Listed
import bank.domain.Money
import bank.domain.Unavailable
import dev.surefold.fail.Either
import dev.surefold.junit.Suite
import dev.surefold.junit.holds
import io.github.matthewjones372.pelican.Outcome
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe

/** Bank spec 0027: opening an account with a daily limit, and the refusals the limit adds, as the API writes them. */
object OpeningSpec : Suite() {
    init {
        test("an opening reads its initial amount and its daily limit in the account's currency") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    currencies.opening(OpenAccount("GBP", "10.00", dailyLimit = "500.00")) shouldBe
                        Either.Right(pounds(1_000) to pounds(50_000))
                }
            }
        }
        test("an opening without a daily limit leaves it to the account's default") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    currencies.opening(OpenAccount("GBP", "10.00")) shouldBe Either.Right(pounds(1_000) to null)
                }
            }
        }
        test("a daily limit that is not an amount of the account's currency is refused") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    currencies.opening(OpenAccount("GBP", "10.00", dailyLimit = "1.005")) shouldBe
                        Either.Left(InvalidAmount("'1.005' is not an amount of GBP"))
                    currencies.opening(OpenAccount("GBP", "ten")) shouldBe
                        Either.Left(InvalidAmount("'ten' is not an amount of GBP"))
                }
            }
        }
        test("a refusal for the daily limit names the limit, what is left today and what was asked") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    val refused = AccountError.DailyLimitExceeded("ada", pounds(10_000), pounds(4_000), pounds(5_000))

                    capped(refused) shouldBe DailyLimitExceeded(
                        "ada",
                        Amount("100.00", "GBP"),
                        Amount("40.00", "GBP"),
                        Amount("50.00", "GBP"),
                        refused.message,
                    )
                }
            }
        }
        test("an opening is refused as already open, as an invalid amount, or as unavailable") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    (
                        openRefused(
                            AccountError.AlreadyOpen("ada"),
                        ) as Outcome.Err
                        ).error shouldBe AccountError.AlreadyOpen("ada")
                    (openRefused(AccountError.InvalidAmount(pounds(-1))) as Outcome.Err).error shouldBe
                        InvalidAmount("-0.01 GBP is not an amount that can move")
                    (openRefused(Unavailable("slow")) as Outcome.Err).error shouldBe Unavailable("slow")
                }
            }
        }
        test("a refusal no opening can give is a bug, not an answer") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    val missing = AccountError.NoSuchAccount("ada")
                    shouldThrow<IllegalStateException> {
                        openRefused(
                            missing,
                        )
                    }.message shouldBe "opening refused as $missing"
                    val limited = AccountError.DailyLimitExceeded("ada", pounds(1), pounds(0), pounds(1))
                    shouldThrow<IllegalStateException> {
                        openRefused(
                            limited,
                        )
                    }.message shouldBe "opening refused as $limited"
                }
            }
        }
    }

    private class Fixture {
        val gbp = Currency("GBP", 2)
        val currencies = Currencies(listOf(Listed(gbp, "£")))

        fun pounds(minor: Long) = Money.ofMinor(minor, gbp)
    }
}
