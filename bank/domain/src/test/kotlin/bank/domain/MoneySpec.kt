package bank.domain

import dev.surefold.assert.shouldBeLeft
import dev.surefold.assert.shouldBeRight
import dev.surefold.junit.Suite
import dev.surefold.junit.holds
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.math.RoundingMode

object MoneySpec : Suite() {
    init {
        test("an amount reads with its currency's places, and its sign whatever its size") {
            holds {
                mapOf(
                    gbp(0) to "0.00 GBP",
                    gbp(1) to "0.01 GBP",
                    gbp(-50) to "-0.50 GBP",
                    gbp(123_456_789) to "1234567.89 GBP",
                    Money.ofMinor(1_250, JPY) to "1250 JPY",
                    Money.ofMinor(125_000, BTC) to "0.00125000 BTC",
                    Money.ofMinor(1_500_000_000, ETH) to "1.500000000 ETH",
                ).forEach { (money, written) -> money.toString() shouldBe written }
            }
        }
        test("a decimal string parses to its exact amount, padded to its currency's places") {
            holds {
                Money.parse("12.50", GBP).shouldBeRight() shouldBe gbp(1_250)
                Money.parse("12.5", GBP).shouldBeRight().amount shouldBe BigDecimal("12.50")
                Money.parse("12", GBP).shouldBeRight() shouldBe gbp(1_200)
                Money.parse("1250", JPY).shouldBeRight() shouldBe Money.ofMinor(1_250, JPY)
                Money.parse("0.00125", BTC).shouldBeRight() shouldBe Money.ofMinor(125_000, BTC)
                Money.parse("0.000000001", ETH).shouldBeRight() shouldBe Money.ofMinor(1, ETH)
            }
        }
        test("an amount is only ever held at its currency's places") {
            holds {
                shouldThrow<IllegalArgumentException> { Money(BigDecimal("12.5"), GBP) }
                shouldThrow<IllegalArgumentException> { Money(BigDecimal("12.500"), GBP) }
                Money(BigDecimal("12.50"), GBP) shouldBe gbp(1_250)
            }
        }
        test("more places than the currency has is refused, never rounded") {
            holds {
                Money.parse("12.505", GBP).shouldBeLeft()
                Money.parse("12.5", JPY).shouldBeLeft()
                Money.parse("0.000000001", BTC).shouldBeLeft()
                Money.parse("0.0000000001", ETH).shouldBeLeft()
            }
        }
        test("what is not a plain decimal is refused") {
            holds {
                listOf("", "-1", "+1", "1e3", "1,000", " 1", "1.", ".5", "0x10", "12.50 GBP").forEach { written ->
                    Money.parse(written, GBP).shouldBeLeft()
                }
            }
        }
        test("an amount past ten to the thirtieth is refused, the one limit an amount has") {
            holds {
                Money.parse("9".repeat(30), GBP).shouldBeRight()
                Money.parse("1" + "0".repeat(30), GBP).shouldBeLeft()
                shouldThrow<IllegalArgumentException> { Money(BigDecimal.TEN.pow(30).setScale(2), GBP) }
            }
        }
        test("amounts past what a Long of minor units held add exactly, even at eighteen places") {
            holds {
                val wei = Currency("ETHW", 18)
                val big = Money.parse("123456789.123456789123456789", wei).shouldBeRight()
                (big + big).amount shouldBe BigDecimal("246913578.246913578246913578")
                (big - big) shouldBe Money.zero(wei)
                Money.parse(
                    "99999999999999999999",
                    GBP,
                ).shouldBeRight().amount shouldBe BigDecimal("99999999999999999999.00")
            }
        }
        test("a rate's result is rounded once, as the caller says") {
            holds {
                Money.of(BigDecimal("0.125"), GBP, RoundingMode.HALF_EVEN) shouldBe gbp(12)
                Money.of(BigDecimal("0.135"), GBP, RoundingMode.HALF_EVEN) shouldBe gbp(14)
                Money.of(BigDecimal("0.125"), GBP, RoundingMode.HALF_UP) shouldBe gbp(13)
                gbp(1_250).amount shouldBe BigDecimal("12.50")
            }
        }
        test("adding two currencies is a bug, not a sum") {
            holds {
                shouldThrow<IllegalArgumentException> { gbp(1) + Money.ofMinor(1, JPY) }
                gbp(1) + gbp(2) shouldBe gbp(3)
            }
        }
        test("a currency is only what the registry lists") {
            holds {
                val registry = Currencies(listOf(Listed(GBP, "£"), Listed(BTC, "₿", Currency.Kind.Crypto)))
                registry.of("BTC").shouldBeRight() shouldBe BTC
                registry.of("XAU").shouldBeLeft() shouldBe UnknownCurrency("XAU")
                registry.format(gbp(1_250)) shouldBe "£12.50"
                registry.format(gbp(-1_250)) shouldBe "-£12.50"
            }
        }
    }
}
