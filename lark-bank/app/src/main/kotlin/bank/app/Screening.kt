package bank.app

import bank.api.screening.Amount
import bank.api.screening.ProposedTransfer
import bank.api.screening.ScreeningClient
import bank.domain.ScreeningDecision
import bank.domain.ScreeningOutcome
import bank.domain.Step
import bank.domain.TransferId
import bank.domain.WhenUnanswered
import io.github.matthewjones372.lark.fail.Either
import io.github.matthewjones372.lark.timed
import io.github.matthewjones372.pelican.ClientRequest
import io.github.matthewjones372.pelican.ClientTransport
import io.github.matthewjones372.pelican.Method
import io.github.matthewjones372.pelican.client.jetty.JettyHttpTransport
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.metrics.otel.traced
import io.opentelemetry.api.OpenTelemetry
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/** Why screening gave no decision: it failed, or answered something the bank cannot read. The policy decides then. */
data class NoDecision(val why: String)

/** Screening, as the Ledger asks it about a transfer before it debits (spec 0018). Blocks: never on an entity's thread. */
fun interface Screener {
    fun screen(id: TransferId, proposed: Step.Screen): Either<NoDecision, ScreeningDecision>
}

/** Whether transfers are screened, and if they are, through what, how long the saga waits, and what it does then. */
sealed interface Screening {
    data object Off : Screening

    data class On(val screener: Screener, val timeout: Duration, val whenUnanswered: WhenUnanswered) : Screening
}

fun screening(settings: ScreeningSettings, telemetry: OpenTelemetry, transport: ClientTransport): Screening =
    if (!settings.enabled) {
        Screening.Off
    } else {
        Screening.On(
            PelicanScreener(settings.url, settings.timeout, telemetry, transport),
            settings.timeout,
            settings.whenUnanswered,
        )
    }

/**
 * The check's `POST /screen`, through the client generated from its description, translated at this edge: nothing
 * of Screening's own types gets past it. Jetty's client defaults, 64 connections and 1,024 requests queued per
 * destination, take a few hundred transfers a second at once without refusing one (spec 0018).
 */
class PelicanScreener(
    url: String,
    timeout: Duration,
    telemetry: OpenTelemetry = OpenTelemetry.noop(),
    transport: ClientTransport = JettyHttpTransport(),
) : Screener {
    // Each call a client span, its traceparent sent, so the check continues the transfer's trace (bank spec 0024).
    private val transport = transport.traced(telemetry)
    private val client = ScreeningClient(url, JacksonCodecs, this.transport, timeout.toJavaDuration())

    init {
        // The client's first call starts it and opens a connection, which can take longer than the timeout, so the
        // first transfer after every start would go unscreened. The check's /health pays it instead.
        transport.send(ClientRequest(Method.GET, url.trimEnd('/') + "/health"))
            .whenComplete { response, _ -> response?.body?.close() }
    }

    override fun screen(id: TransferId, proposed: Step.Screen): Either<NoDecision, ScreeningDecision> {
        val asked = ProposedTransfer(
            transfer = id.value,
            from = proposed.from.value,
            to = proposed.to.value,
            amount = Amount(proposed.amount.amount.toPlainString(), proposed.amount.currency.code),
            requestedAtMillis = proposed.requestedAt,
        )
        // Whatever goes wrong on the way, the answer is the same: no decision, and the bank's policy applies.
        val decision = try {
            timed("bank.screening.duration") { client.screen(asked) }
        } catch (failed: Exception) {
            return Either.Left(NoDecision(failed.message ?: failed::class.java.name))
        }
        val outcome = when (decision.outcome) {
            "approved" -> ScreeningOutcome.Approved
            "declined" -> ScreeningOutcome.Declined
            else -> return Either.Left(NoDecision("an outcome the bank does not know: ${decision.outcome}"))
        }
        return Either.Right(ScreeningDecision(outcome, decision.rule, decision.version, decision.evidence))
    }
}
