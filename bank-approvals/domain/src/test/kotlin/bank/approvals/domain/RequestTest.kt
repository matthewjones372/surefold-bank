package bank.approvals.domain

import dev.surefold.assert.shouldBeLeft
import dev.surefold.assert.shouldBeRight
import dev.surefold.fail.Either
import dev.surefold.fail.onRight
import dev.surefold.junit.Suite
import dev.surefold.junit.holds
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Duration
import java.time.Instant

/** Every path of the saga in bank spec 0019, and every vote the rules turn away. */
object RequestTest : Suite() {
    init {
        test("asked, then two approvers, is given approval, and applied ends it") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    val saga = asked()
                    saga.history.map { it::class.simpleName } shouldBe listOf("Requested", "AwaitingApproval")

                    saga.approve(ada, 1)
                    saga.request.shouldBeInstanceOf<Request.Waiting>().approvedBy shouldBe listOf(ada)
                    saga.approve(bob, 2).map { it::class.simpleName } shouldBe listOf("Approved", "ApprovalGiven")
                    saga.request.shouldBeInstanceOf<Request.Agreed>()

                    saga.run(RequestCommand.MarkApplied(tighten.contentHash, at(3))).shouldBeRight()
                    saga.request.shouldBeInstanceOf<Request.Ended>().how shouldBe Ending.Applied
                }
            }
        }
        test("a failed apply is recorded, the request stays agreed, and the next attempt applies it") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    val saga = asked().apply {
                        approve(ada, 1)
                        approve(bob, 2)
                    }

                    saga.run(RequestCommand.MarkApplyFailed("the check's database was down", at(3))).shouldBeRight()
                    saga.request.shouldBeInstanceOf<Request.Agreed>()
                    saga.run(RequestCommand.MarkApplied(tighten.contentHash, at(4))).shouldBeRight()
                    saga.request.shouldBeInstanceOf<Request.Ended>().how shouldBe Ending.Applied
                }
            }
        }
        test("applied said twice is applied once") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    val saga = asked().apply {
                        approve(ada, 1)
                        approve(bob, 2)
                    }
                    saga.run(RequestCommand.MarkApplied(tighten.contentHash, at(3))).shouldBeRight()

                    saga.run(RequestCommand.MarkApplied(tighten.contentHash, at(4))).shouldBeRight().shouldBeEmpty()
                    saga.history.count { it is RequestEvent.Applied } shouldBe 1
                }
            }
        }
        test("applied of content other than what was approved is refused, before and after the real one") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    val saga = asked().apply {
                        approve(ada, 1)
                        approve(bob, 2)
                    }
                    val swapped = tighten.copy(after = "amount is at least 5").contentHash

                    saga.run(RequestCommand.MarkApplied(swapped, at(3))).shouldBeLeft(Refusal.NotWhatWasApproved)
                    saga.request.shouldBeInstanceOf<Request.Agreed>()
                    saga.run(RequestCommand.MarkApplied(tighten.contentHash, at(4), by = checks)).shouldBeRight()
                    saga.run(RequestCommand.MarkApplied(swapped, at(5))).shouldBeLeft(Refusal.NotWhatWasApproved)
                    saga.history.last().shouldBeInstanceOf<RequestEvent.Applied>().let {
                        it.by shouldBe checks
                        it.hash shouldBe tighten.contentHash
                    }
                }
            }
        }
        test("every act by a person keeps where it came from, a refused vote's included") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    val desk = Origin("session-1", "192.0.2.20", "Firefox")
                    val saga = Fixture.Saga().apply {
                        run(
                            RequestCommand.Ask(RequestId("req-1"), tighten, rae, policies, t0, desk),
                        ).shouldBeRight()
                    }

                    saga.run(RequestCommand.Approve(rae, tighten.contentHash, at = at(1), origin = desk))
                        .shouldBeRight()
                    saga.run(RequestCommand.Comment(ada, "why?", at(2), desk)).shouldBeRight()
                    saga.run(RequestCommand.Approve(ada, tighten.contentHash, at = at(3), origin = desk))
                        .shouldBeRight()

                    saga.history[0].shouldBeInstanceOf<RequestEvent.Requested>().origin shouldBe desk
                    saga.history[2].shouldBeInstanceOf<RequestEvent.VoteRefused>().origin shouldBe desk
                    saga.history[3].shouldBeInstanceOf<RequestEvent.Commented>().origin shouldBe desk
                    saga.history[4].shouldBeInstanceOf<RequestEvent.Approved>().origin shouldBe desk
                }
            }
        }
        test("facts meeting a policy's condition are approved automatically, and still recorded as a request") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    val switchOff = tighten.copy(
                        facts = mapOf("change" to "switch-off", "severity" to "low", "rule" to "small-atm"),
                    )
                    val saga = asked(switchOff)

                    val auto = saga.history[1].shouldBeInstanceOf<RequestEvent.AutoApproved>()
                    auto.condition shouldBe Condition(mapOf("change" to "switch-off", "severity" to "low"))
                    saga.history[0].shouldBeInstanceOf<RequestEvent.Requested>().asked.policyVersion shouldBe
                        "policies@abc123"
                    saga.request.shouldBeInstanceOf<Request.Agreed>().automatically shouldBe auto.condition
                }
            }
        }
        test("facts meeting only part of a condition wait for people") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    val switchOffHigh = tighten.copy(facts = mapOf("change" to "switch-off", "severity" to "high"))

                    asked(switchOffHigh).request.shouldBeInstanceOf<Request.Waiting>()
                }
            }
        }
        test("a kind with no policy needs two admins, and nothing is approved automatically") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    val unknown = tighten.copy(kind = "bank.something-new", facts = mapOf("change" to "describe-only"))
                    val saga = asked(unknown)

                    saga.history[1] shouldBe RequestEvent.AwaitingApproval(2, setOf("admins"), t0)
                }
            }
        }
        test("a rejection with its reason ends the request") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    val saga = asked().apply { approve(ada, 1) }

                    saga.run(RequestCommand.Reject(bob, "the bound is too low", at(2))).shouldBeRight()
                    saga.request.shouldBeInstanceOf<Request.Ended>().how shouldBe Ending.Rejected(
                        bob,
                        "the bound is too low",
                    )
                }
            }
        }
        test("a rejection needs a reason") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    asked().run(RequestCommand.Reject(bob, "  ", at(1))).shouldBeLeft(Refusal.CommentRequired)
                }
            }
        }
        test("one who approved may still reject, and the approval stays in the history") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    val saga = asked().apply { approve(ada, 1) }

                    saga.run(RequestCommand.Reject(ada, "I misread the impact", at(2))).shouldBeRight()
                    saga.history.filterIsInstance<RequestEvent.Approved>().single().by shouldBe ada
                    saga.request.shouldBeInstanceOf<Request.Ended>().approvedBy shouldBe listOf(ada)
                }
            }
        }
        test("the requester may withdraw it while it waits, and nobody else may") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    asked().run(RequestCommand.Withdraw(ada, at(1))).shouldBeLeft(Refusal.OnlyTheRequesterWithdraws)

                    val saga = asked()
                    saga.run(RequestCommand.Withdraw(rae, at(1))).shouldBeRight()
                    saga.request.shouldBeInstanceOf<Request.Ended>().how shouldBe Ending.Withdrawn(rae)
                }
            }
        }
        test("nobody may withdraw it once it is approved") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    val saga = asked().apply {
                        approve(ada, 1)
                        approve(bob, 2)
                    }

                    saga.run(RequestCommand.Withdraw(rae, at(3))).shouldBeLeft(Refusal.NotWaiting)
                }
            }
        }
        test("a newer request for the same subject supersedes one still waiting") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    val saga = asked().apply { approve(ada, 1) }

                    saga.run(RequestCommand.Supersede(RequestId("req-2"), at(2))).shouldBeRight()
                    saga.request.shouldBeInstanceOf<Request.Ended>().how shouldBe Ending.Superseded(RequestId("req-2"))
                }
            }
        }
        test("a request with no decision in its time expires, and not a moment before") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    val saga = asked()

                    saga.run(
                        RequestCommand.Expire(t0.plus(Duration.ofDays(7)).minusSeconds(1)),
                    ).shouldBeLeft(Refusal.NotYetDue)
                    saga.run(RequestCommand.Expire(t0.plus(Duration.ofDays(7)))).shouldBeRight()
                    saga.request.shouldBeInstanceOf<Request.Ended>().how shouldBe Ending.Expired
                }
            }
        }
        test("an expiry timer that fires after the end finds nothing to do") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    val saga = asked().apply {
                        approve(ada, 1)
                        approve(bob, 2)
                    }

                    saga.run(RequestCommand.Expire(t0.plus(Duration.ofDays(8)))).shouldBeRight().shouldBeEmpty()
                }
            }
        }
        test("a requester's own approval is refused, and the refusal is on the record") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    val requesterInRisk = Person("rae", "Rae", setOf("risk"))
                    val saga = asked(by = requesterInRisk)

                    saga.approve(requesterInRisk, 1)
                    saga.refusedAs(RefusedBecause.OwnRequest)
                    saga.request.shouldBeInstanceOf<Request.Waiting>().approvedBy.shouldBeEmpty()
                }
            }
        }
        test("a second approval from one person is refused, and counts once") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    val saga = asked().apply {
                        approve(ada, 1)
                        approve(ada, 2)
                    }

                    saga.refusedAs(RefusedBecause.AlreadyApproved)
                    saga.request.shouldBeInstanceOf<Request.Waiting>().approvedBy shouldBe listOf(ada)
                }
            }
        }
        test("an approval from outside the policy's groups is refused") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    val saga = asked().apply { approve(olga, 1) }

                    saga.refusedAs(RefusedBecause.NotAnApprover)
                }
            }
        }
        test("a vote after the end is refused") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    val saga = asked().apply {
                        approve(ada, 1)
                        approve(bob, 2)
                    }

                    saga.approve(cy, 3)
                    saga.refusedAs(RefusedBecause.Ended)
                    saga.run(RequestCommand.Reject(cy, "too late to say", at(4))).shouldBeRight()
                    saga.refusedAs(RefusedBecause.Ended)
                }
            }
        }
        test("a vote cast on content other than what was asked is refused") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    val swapped = tighten.copy(after = "amount is at least 50")
                    val saga = asked().apply { approve(ada, 1, hash = swapped.contentHash) }

                    saga.refusedAs(RefusedBecause.NotWhatWasAsked)
                }
            }
        }
        test("the requester and approvers comment, and a stranger may not") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    val saga = asked()

                    saga.run(RequestCommand.Comment(ada, "why 500 and not 750?", at(1))).shouldBeRight()
                    saga.run(RequestCommand.Comment(rae, "750 let three through last week", at(2))).shouldBeRight()
                    saga.run(RequestCommand.Comment(olga, "looks fine to me", at(3)))
                        .shouldBeLeft(Refusal.NotOnTheRequest)

                    saga.history.filterIsInstance<RequestEvent.Commented>().map { it.by } shouldBe listOf(ada, rae)
                    saga.request.shouldBeInstanceOf<Request.Waiting>()
                }
            }
        }
        test("asking again with the same request is a retry, and with a different one is refused") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    val saga = asked()

                    saga.run(
                        RequestCommand.Ask(RequestId("req-1"), tighten, rae, policies, at(1)),
                    ).shouldBeRight().shouldBeEmpty()
                    saga.run(
                        RequestCommand.Ask(
                            RequestId("req-1"),
                            tighten.copy(after = "never"),
                            rae,
                            policies,
                            at(1),
                        ),
                    )
                        .shouldBeLeft(Refusal.AskedDifferently)
                }
            }
        }
        test("the history replays to the same request") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    val saga = asked().apply {
                        run(RequestCommand.Comment(ada, "a question", at(1)))
                        approve(ada, 2)
                        approve(ada, 3)
                        approve(bob, 4)
                        run(
                            RequestCommand.MarkApplyFailed("down", at(5)),
                        )
                        run(RequestCommand.MarkApplied(tighten.contentHash, at(6)))
                    }

                    replay(saga.history) shouldBe saga.request
                }
            }
        }
    }

    private class Fixture {

        val t0 = Instant.parse("2026-10-01T09:00:00Z")
        fun at(minutes: Long) = t0.plusSeconds(minutes * 60)

        val ada = Person("ada", "Ada", setOf("risk"))
        val bob = Person("bob", "Bob", setOf("risk"))
        val cy = Person("cy", "Cy", setOf("risk"))
        val olga = Person("olga", "Olga", setOf("ops"))
        val rae = Person("rae", "Rae", setOf("engineers"))
        val checks = Person("checks", "the check", setOf("services"))

        val policies = Policies(
            version = "policies@abc123",
            byKind = mapOf(
                "checks.rule-version" to Policy(
                    "checks.rule-version",
                    approvers = setOf("risk"),
                    needed = 2,
                    expiresAfter = Duration.ofDays(7),
                    autoApprove = listOf(
                        Condition(mapOf("change" to "switch-off", "severity" to "low")),
                        Condition(mapOf("change" to "describe-only")),
                    ),
                ),
            ),
        )

        val tighten = Proposal(
            kind = "checks.rule-version",
            subject = "checks/rule/large-transfer",
            title = "large-transfer, version 4",
            before = "amount is at least 1000",
            after = "amount is at least 500",
            facts = mapOf("change" to "tighten", "severity" to "high"),
        )

        /** The history so far, and the request it makes; each step decided and applied, as the entity will. */
        class Saga(var request: Request = Request.Unasked, val history: MutableList<RequestEvent> = mutableListOf()) {
            fun run(command: RequestCommand): Either<Refusal, List<RequestEvent>> =
                decide(request, command).onRight { events ->
                    history += events
                    request = events.fold(request, ::evolve)
                }
        }

        fun asked(proposal: Proposal = tighten, by: Person = rae): Saga =
            Saga().apply { run(RequestCommand.Ask(RequestId("req-1"), proposal, by, policies, t0)).shouldBeRight() }

        fun Saga.approve(by: Person, minutes: Long, hash: String = tighten.contentHash) =
            run(RequestCommand.Approve(by, hash, at = at(minutes))).shouldBeRight()

        // ---- the ways a request goes ----

        // ---- votes the rules turn away, each recorded ----

        fun Saga.refusedAs(expected: RefusedBecause) {
            val refused = history.last().shouldBeInstanceOf<RequestEvent.VoteRefused>()
            refused.because shouldBe expected
        }

        // ---- the thread ----

        // ---- asking ----
    }
}
