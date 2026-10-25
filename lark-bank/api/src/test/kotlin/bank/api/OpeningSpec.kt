package bank.api

import bank.domain.AccountError
import bank.domain.Currencies
import bank.domain.Currency
import bank.domain.Listed
import bank.domain.Money
import bank.domain.Unavailable
import io.github.matthewjones372.lark.fail.Either
import io.github.matthewjones372.pelican.Outcome
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Bank spec 0027: opening an account with a daily limit, and the refusals the limit adds, as the API writes them. */
class OpeningSpec {
    private val gbp = Currency("GBP", 2)
    private val currencies = Currencies(listOf(Listed(gbp, "£")))

    private fun pounds(minor: Long) = Money.ofMinor(minor, gbp)

    @Test
    fun `an opening reads its initial amount and its daily limit in the account's currency`() {
        currencies.opening(OpenAccount("GBP", "10.00", dailyLimit = "500.00")) shouldBe
            Either.Right(pounds(1_000) to pounds(50_000))
    }

    @Test
    fun `an opening without a daily limit leaves it to the account's default`() {
        currencies.opening(OpenAccount("GBP", "10.00")) shouldBe Either.Right(pounds(1_000) to null)
    }

    @Test
    fun `a daily limit that is not an amount of the account's currency is refused`() {
        currencies.opening(OpenAccount("GBP", "10.00", dailyLimit = "1.005")) shouldBe
            Either.Left(InvalidAmount("'1.005' is not an amount of GBP"))
        currencies.opening(OpenAccount("GBP", "ten")) shouldBe
            Either.Left(InvalidAmount("'ten' is not an amount of GBP"))
    }

    @Test
    fun `a refusal for the daily limit names the limit, what is left today and what was asked`() {
        val refused = AccountError.DailyLimitExceeded("ada", pounds(10_000), pounds(4_000), pounds(5_000))

        capped(refused) shouldBe DailyLimitExceeded(
            "ada",
            Amount("100.00", "GBP"),
            Amount("40.00", "GBP"),
            Amount("50.00", "GBP"),
            refused.message,
        )
    }

    @Test
    fun `an opening is refused as already open, as an invalid amount, or as unavailable`() {
        (openRefused(AccountError.AlreadyOpen("ada")) as Outcome.Err).error shouldBe AccountError.AlreadyOpen("ada")
        (openRefused(AccountError.InvalidAmount(pounds(-1))) as Outcome.Err).error shouldBe
            InvalidAmount("-0.01 GBP is not an amount that can move")
        (openRefused(Unavailable("slow")) as Outcome.Err).error shouldBe Unavailable("slow")
    }

    @Test
    fun `a refusal no opening can give is a bug, not an answer`() {
        val missing = AccountError.NoSuchAccount("ada")
        shouldThrow<IllegalStateException> { openRefused(missing) }.message shouldBe "opening refused as $missing"
        val limited = AccountError.DailyLimitExceeded("ada", pounds(1), pounds(0), pounds(1))
        shouldThrow<IllegalStateException> { openRefused(limited) }.message shouldBe "opening refused as $limited"
    }
}
