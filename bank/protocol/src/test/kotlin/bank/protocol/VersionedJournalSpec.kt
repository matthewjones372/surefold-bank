package bank.protocol

import bank.domain.AccountEvent
import dev.surefold.actor.UnreadableEvent
import dev.surefold.actor.Upgrade
import dev.surefold.actor.versioned
import dev.surefold.junit.Suite
import dev.surefold.junit.holds
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/** The journal's codecs are versioned (surefold spec 0091), and what that promises the bank's journal. */
object VersionedJournalSpec : Suite() {
    init {
        test("an event written before the codecs were versioned is read as version 1") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    AccountEvents.decode(AccountEventBody.encode(deposit)) shouldBe deposit
                }
            }
        }
        test("an event written today is marked with its version, and reads back") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    val written = AccountEvents.encode(deposit)

                    written shouldNotBe AccountEventBody.encode(deposit)
                    AccountEvents.decode(written) shouldBe deposit
                }
            }
        }
        test("a version 2 reads every event written today through its upgrade from 1") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    // A version 2 whose shape is today's, so its upgrade from 1 is the bytes as they are.
                    val next = versioned(JOURNAL_VERSION + 1, AccountEventBody, mapOf(1 to Upgrade { listOf(it) }))

                    next.decodeAll(AccountEvents.encode(deposit)) shouldBe listOf(deposit)
                    next.decodeAll(AccountEventBody.encode(deposit)) shouldBe listOf(deposit)
                }
            }
        }
        test("an event of a version newer than the codec is refused, not misread") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    val next = versioned(JOURNAL_VERSION + 1, AccountEventBody, mapOf(1 to Upgrade { listOf(it) }))

                    shouldThrow<UnreadableEvent> { AccountEvents.decode(next.encode(deposit)) }
                }
            }
        }
    }

    private class Fixture {

        val deposit = AccountEvent.Deposited(gbp(500), "ref-1", 2)
    }
}
