package bank.app.memory

import bank.app.Screener
import bank.app.Screening
import bank.app.gbp
import bank.domain.AccountId
import bank.domain.ScreeningDecision
import bank.domain.ScreeningOutcome
import bank.domain.TransferError
import bank.domain.TransferId
import bank.domain.TransferStatus
import bank.domain.WhenUnanswered
import dev.surefold.Promise
import dev.surefold.Shared
import dev.surefold.assert.shouldBeLeft
import dev.surefold.assert.shouldBeRight
import dev.surefold.awaitValue
import dev.surefold.fail.Either
import dev.surefold.fail.onLeft
import dev.surefold.fail.right
import dev.surefold.junit.Suite
import dev.surefold.junit.holds
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds

/** A start with an id the saga already has: a caller's retry, the id reused for another transfer, or one mid-way. */
object SecondStartSpec : Suite() {
    init {
        test(
            "the same start again after it settled is the same transfer, answered as it settled, and moves " +
                "nothing more",
        ) {
            holds {
                inMemory {
                    opened(ada, gbp(10_00))
                    opened(bob, gbp(0))
                    val first = transfer(t1, ada, bob, gbp(7_00)).shouldBeRight()

                    transfer(t1, ada, bob, gbp(7_00)).shouldBeRight() shouldBe first

                    first.status shouldBe TransferStatus.Completed
                    transferEvents(t1) shouldHaveSize 2
                    balanceOf(ada) shouldBe gbp(3_00)
                    balanceOf(bob) shouldBe gbp(7_00)
                }
            }
        }
        test("an id used again for a different transfer is refused, and moves nothing") {
            holds {
                inMemory {
                    opened(ada, gbp(10_00))
                    opened(bob, gbp(0))
                    transfer(t1, ada, bob, gbp(7_00)).shouldBeRight()

                    transfer(t1, ada, bob, gbp(1)).shouldBeLeft() shouldBe TransferError.TransferIdReused("t-1")
                    transfer(t1, bob, ada, gbp(7_00)).shouldBeLeft() shouldBe TransferError.TransferIdReused("t-1")

                    transferEvents(t1) shouldHaveSize 2
                    balanceOf(ada) shouldBe gbp(3_00)
                }
            }
        }
        test("a different transfer under an id still moving is refused, and the same one waits for it") {
            holds {
                val asked = Shared(0, "bank.second-start.asked")
                val entered = Promise<Throwable, Unit>("bank.second-start.entered")
                val release = Promise<Nothing, Unit>("bank.second-start.release")
                val screening = Screening.On(
                    Screener { _, _ ->
                        asked.update { it + 1 }
                        entered.succeed(Unit)
                        release.awaitValue()
                        ScreeningDecision(ScreeningOutcome.Approved, null, null, "ok").right()
                    },
                    5.seconds,
                    WhenUnanswered.Approve,
                )
                inMemory(screening = screening) {
                    opened(ada, gbp(10_00))
                    opened(bob, gbp(0))
                    val first = Promise<Throwable, Unit>("bank.second-start.first")
                    Thread.ofVirtual().start {
                        Either.catch {
                            transfer(t1, ada, bob, gbp(7_00)).shouldBeRight()
                            first.succeed(Unit)
                        }.onLeft { failed ->
                            first.fail(failed)
                            entered.fail(failed)
                        }
                    }
                    entered.await().shouldBeRight()

                    transfer(t1, ada, bob, gbp(1)).shouldBeLeft() shouldBe TransferError.TransferIdReused("t-1")
                    val same = Promise<Throwable, Unit>("bank.second-start.same")
                    Thread.ofVirtual().start {
                        Either.catch {
                            transfer(t1, ada, bob, gbp(7_00)).shouldBeRight()
                            same.succeed(Unit)
                        }.onLeft { failed -> same.fail(failed) }
                    }
                    release.succeed(Unit)
                    first.await().shouldBeRight()
                    same.await().shouldBeRight()

                    asked.peek() shouldBe 1
                    transfer(t1, ada, bob, gbp(7_00)).shouldBeRight().status shouldBe TransferStatus.Completed
                    transferEvents(t1).kinds() shouldBe listOf("Requested", "Screened", "DestinationCredited")
                    balanceOf(ada) shouldBe gbp(3_00)
                }
            }
        }
    }
    private val ada = AccountId("ada")
    private val bob = AccountId("bob")
    private val t1 = TransferId("t-1")
}
