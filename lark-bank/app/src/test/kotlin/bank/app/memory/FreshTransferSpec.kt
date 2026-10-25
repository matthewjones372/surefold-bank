package bank.app.memory

import bank.app.gbp
import bank.domain.AccountId
import bank.domain.TransferEvent
import bank.domain.TransferId
import bank.domain.TransferStatus
import bank.protocol.Kinds
import io.github.matthewjones372.lark.actor.InMemoryJournal
import io.github.matthewjones372.lark.actor.Journal
import io.github.matthewjones372.lark.actor.PersistenceId
import io.github.matthewjones372.lark.actor.StoredEvent
import io.github.matthewjones372.lark.assert.shouldBeRight
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bank spec 0031's `fresh-entity`: a new transfer's saga starts without reading its journal, which is always empty for
 * an id just numbered (lark spec 0198). A saga started for a transfer that exists still recovers what it wrote.
 */
class FreshTransferSpec {
    private val ada = AccountId("ada")
    private val bob = AccountId("bob")
    private val t1 = TransferId("t-1")

    /** A journal that counts the reads of transfers' events, leaving the accounts' uncounted. */
    private class Counting(private val inner: Journal = InMemoryJournal()) : Journal by inner {
        val transferReads = AtomicInteger()

        override fun read(id: PersistenceId, from: Long): List<StoredEvent> = inner.read(id, from).also {
            if (id.kind == Kinds.TRANSFER) transferReads.incrementAndGet()
        }
    }

    private val journal = Counting()

    private fun fresh(block: InMemoryBank.() -> Unit) = inMemoryOf(journal = journal) {
        opened(ada, gbp(10_00))
        opened(bob, gbp(0))
        block()
    }

    // Counted across the saga's own steps: settleAll reads the transfer's events too, to see whether it has settled.
    @Test
    fun `a new transfer settles without its saga reading the journal`() = fresh {
        val answers = start(t1, ada, bob, gbp(3_00))
        journal.transferReads.get() shouldBe 0
        settleAll()

        answers.answers.single().shouldBeRight().status shouldBe TransferStatus.Completed
        balanceOf(ada) shouldBe gbp(7_00)
        balanceOf(bob) shouldBe gbp(3_00)
    }

    @Test
    fun `a start for a transfer that exists, sent to a saga started afresh, reads it and is the same transfer`() =
        fresh {
            start(t1, ada, bob, gbp(3_00))
            settleAll()
            crashSaga(t1)
            val before = journal.transferReads.get()

            val again = start(t1, ada, bob, gbp(3_00))
            journal.transferReads.get() - before shouldBe 1
            settleAll()

            again.answers.single().shouldBeRight().status shouldBe TransferStatus.Completed
            transferEvents(t1).filterIsInstance<TransferEvent.Requested>() shouldHaveSize 1
            balanceOf(ada) shouldBe gbp(7_00)
        }
}
