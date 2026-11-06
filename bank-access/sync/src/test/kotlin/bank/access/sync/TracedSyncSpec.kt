package bank.access.sync

import bank.access.fga.TestOpenFga
import bank.events.v1.AccountEvent
import bank.events.v1.Money
import bank.events.v1.Opened
import dev.surefold.assert.shouldBeRight
import dev.surefold.clock
import dev.surefold.fail.getOrElse
import dev.surefold.junit.Suite
import dev.surefold.junit.holds
import dev.surefold.test.eventually
import io.kotest.matchers.shouldBe
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import io.opentelemetry.sdk.trace.samplers.Sampler
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Every span ended, kept for the test to read. */
private class Kept : SpanExporter {
    val spans = CopyOnWriteArrayList<SpanData>()

    override fun export(spans: Collection<SpanData>): CompletableResultCode = CompletableResultCode.ofSuccess().also {
        this.spans +=
            spans
    }

    override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()

    override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
}

/** Surefold-bank spec 0024's `trace-events`: an account opened inside a trace has its owner written in that trace. */
object TracedSyncSpec : Suite(isolated = true) {
    init {
        test("an account published inside a trace has its owner written to OpenFGA in a span of that trace") {
            holds {
                val fixture = Fixture()
                with(fixture) {
                    TestKafka.ready()
                    val fga = TestOpenFga.fresh()
                    val kept = Kept()
                    // Never sampled where it starts a trace: what is kept is kept because the publisher's trace was.
                    val telemetry = OpenTelemetrySdk.builder()
                        .setTracerProvider(
                            SdkTracerProvider.builder().setSampler(
                                Sampler.parentBased(Sampler.alwaysOff()),
                            ).addSpanProcessor(SimpleSpanProcessor.create(kept)).build(),
                        )
                        .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                        .build()
                    val acc = "traced-$run"
                    val trace = "4bf92f3577b34da6a3ce929d0e0e4736"
                    val publish = "00f067aa0ba902b7"
                    val settings = SyncSettings(
                        kafka = KafkaSettings(TestKafka.bootstrap, group = "access-sync-$run"),
                        fgaUrl = TestOpenFga.url,
                        fgaToken = TestOpenFga.KEY,
                        store = fga.store,
                        pocketIdUrl = null,
                        pocketIdKey = null,
                    )
                    telemetry.use {
                        AccessSync(settings, telemetry).use { sync ->
                            sync.start().shouldBeRight()
                            val opened = AccountEvent.newBuilder().setAccountId(
                                acc,
                            ).setSequence(1).setAtMillis(clock.get().now().toEpochMilli())
                                .setOpened(
                                    Opened.newBuilder().setOwner(
                                        "ada-$run",
                                    ).setInitial(
                                        Money.newBuilder().setCurrency("GBP").setAmount("0.00"),
                                    ).setReference("open:$acc"),
                                )
                                .build()
                            TestKafka.send(ACCOUNTS, acc, opened, mapOf("traceparent" to "00-$trace-$publish-01"))
                            TestKafka.send(
                                ACCOUNTS,
                                "untraced-$run",
                                opened.toBuilder().setAccountId("untraced-$run").build(),
                            )

                            eventually(60.seconds, every = 200.milliseconds) {
                                check(
                                    fga.check(
                                        "person:ada-$run",
                                        "owner",
                                        "account:untraced-$run",
                                    ).getOrElse { false },
                                ) {
                                    "the untraced account was never written"
                                }
                            }
                        }
                    }

                    fga.check("person:ada-$run", "owner", "account:$acc").shouldBeRight() shouldBe true
                    val span = kept.spans.single()
                    span.name shouldBe "access-sync account"
                    span.kind shouldBe SpanKind.CONSUMER
                    span.traceId shouldBe trace
                    span.parentSpanId shouldBe publish
                }
            }
        }
    }

    private class Fixture {
        val run = UUID.randomUUID().toString().take(8)
    }
}
