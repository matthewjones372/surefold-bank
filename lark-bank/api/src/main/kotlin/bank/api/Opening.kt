package bank.api

import arrow.core.Either
import arrow.core.raise.either
import bank.domain.AccountError
import bank.domain.Currencies
import bank.domain.Money
import bank.domain.Unavailable

// Opening an account with its daily limit, and the refusals that limit adds (bank spec 0027).

internal fun capped(refused: AccountError.DailyLimitExceeded) = DailyLimitExceeded(
    refused.id, refused.limit.toAmount(), refused.remaining.toAmount(), refused.requested.toAmount(), refused.message,
)

/** An opening's initial amount and daily limit, both read in the account's currency (bank spec 0027). */
internal fun Currencies.opening(body: OpenAccount): Either<InvalidAmount, Pair<Money, Money?>> = either {
    amount(Amount(body.initial, body.currency)).bind() to
        body.dailyLimit?.let { amount(Amount(it, body.currency)).bind() }
}

internal fun openRefused(refusal: AccountError) = when (refusal) {
    is AccountError.AlreadyOpen -> accountExists(refusal)
    is AccountError.InvalidAmount -> invalidAmount(InvalidAmount(refusal.message))
    is Unavailable -> unavailable(refusal)
    is AccountError.NoSuchAccount, is AccountError.InsufficientFunds, is AccountError.CurrencyMismatch,
    is AccountError.DailyLimitExceeded, is AccountError.AlreadyRefused,
    -> error("opening refused as $refusal")
}
