package bank.approvals.app

import bank.approvals.api.Approvals
import bank.approvals.api.Signing
import bank.approvals.api.approvalsApi
import bank.approvals.domain.RequestId
import bank.approvals.protocol.Kinds
import bank.approvals.protocol.RequestMessage
import bank.approvals.protocol.RequestMessages
import bank.approvals.protocol.SubjectMessage
import bank.approvals.protocol.SubjectMessages
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.surefold.actor.ActorRef
import dev.surefold.actor.journal
import dev.surefold.actor.journal.jdbc.JdbcJournal
import dev.surefold.actor.journal.jdbc.JdbcOffsets
import dev.surefold.actor.journal.jdbc.JdbcSnapshots
import dev.surefold.actor.projection.Projection
import dev.surefold.actor.snapshots
import dev.surefold.app.Module
import dev.surefold.app.SurefoldApp
import dev.surefold.app.actor.Actors
import dev.surefold.app.actor.actors
import dev.surefold.app.boundTo
import dev.surefold.app.cluster.cluster
import dev.surefold.app.liquibase.Migrated
import dev.surefold.app.liquibase.migrations
import dev.surefold.app.single
import dev.surefold.app.singleOf
import dev.surefold.cluster.Cluster
import dev.surefold.cluster.Sharded
import dev.surefold.cluster.sharding
import dev.surefold.cluster.spread
import dev.surefold.logInfo
import dev.surefold.stream.start
import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.jetty.PelicanServer
import io.github.matthewjones372.pelican.jetty.docs.startWithDocs
import io.github.matthewjones372.pelican.openapi.docs
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.ReadWriteSpan
import io.opentelemetry.sdk.trace.ReadableSpan
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.SpanProcessor
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.opentelemetry.sdk.trace.samplers.Sampler
import liquibase.Scope
import liquibase.ui.LoggerUIService
import org.slf4j.bridge.SLF4JBridgeHandler
import java.nio.file.Path
import javax.sql.DataSource
import dev.surefold.stream.Actors as StreamActors

// ---- storage ----

/**
 * Liquibase's lines into the log, as JSON like every other (bank spec 0023): its own through java.util.logging,
 * bridged to SLF4J, and what it would print to stdout ("Running Changeset", the summary) through that log instead.
 * Liquibase keeps a console UI on each thread, so this enters the logger UI on the thread that loads the app. A test
 * thread enters it again before it starts a node. This runs as this file's modules are defined, before any node is
 * built.
 */
@Suppress("unused")
private val liquibaseLogged: Unit = run {
    // The update summary has a switch of its own. The UI does not: it is the scope of the thread that migrates.
    System.setProperty("liquibase.command.showSummaryOutput", "LOG")
    Scope.enter(mapOf(Scope.Attr.ui.name to LoggerUIService()))
    SLF4JBridgeHandler.removeHandlersForRootLogger()
    SLF4JBridgeHandler.install()
}

private fun pool(settings: DatabaseSettings): HikariDataSource = HikariDataSource(
    HikariConfig().apply {
        jdbcUrl = settings.url
        username = settings.user
        password = settings.password
        maximumPoolSize = settings.poolSize
        poolName = "approvals"
    },
)

private val storage: Module =
    singleOf({ settings: DatabaseSettings -> pool(settings) }, { pool -> pool.close() }).boundTo<DataSource>() +
        migrations("db/changelog.xml")

// ---- the cluster and its entities ----

class Entities(val requests: Sharded<RequestMessage>, val subjects: Sharded<SubjectMessage>) {
    fun request(id: String): ActorRef<RequestMessage> = requests.entity(id)

    fun subject(id: String): ActorRef<SubjectMessage> = subjects.entity(id)
}

private val clustered: Module =
    actors() +
        single { actors: Actors, data: DataSource, _: Migrated ->
            // Set on the scope before any entity starts: a persistent entity reads its journal from the scope.
            val journal = JdbcJournal(data)
            actors.within {
                journal(journal)
                snapshots(JdbcSnapshots(data))
            }
            JournalReady(journal)
        } +
        cluster("approvals.cluster") +
        single { actors: Actors, cluster: Cluster, settings: EntitySettings, owners: Owners, _: JournalReady ->
            actors.within {
                val subjects = cluster.sharding(
                    Kinds.SUBJECT,
                    SubjectMessages,
                    settings.passivateAfter,
                    settings.shards,
                ) { id ->
                    subject(id)
                }
                val requests = cluster.sharding(
                    Kinds.REQUEST,
                    RequestMessages,
                    settings.passivateAfter,
                    settings.shards,
                ) { id ->
                    request(RequestId(id), subjects::entity, owners, settings.applyEvery)
                }
                Entities(requests, subjects)
            }
        }

/** The journal set on the scope: what the entities wait for, and what the publishers follow. */
class JournalReady(val journal: JdbcJournal)

// ---- every event published (bank spec 0019) ----

/** The publishers started, spread over the nodes: what the web waits for, so a request's events reach Kafka. */
object Published

// The owning service asked again on Kafka.

/**
 * The SDK Approvals' spans are made through (bank spec 0024): W3C's `traceparent` in and out, sampled where a
 * request enters at [TelemetrySettings.sampled] and as its parent was otherwise, batched to Tempo when enabled.
 */
private fun telemetry(settings: TelemetrySettings, web: WebSettings): OpenTelemetrySdk {
    val resource = Resource.getDefault().merge(
        Resource.create(Attributes.of(SERVICE_NAME, "bank-approvals", SERVICE_INSTANCE_ID, "${web.host}:${web.port}")),
    )
    val tracing = SdkTracerProvider.builder()
        .setResource(resource)
        .setSampler(Sampler.parentBased(Sampler.traceIdRatioBased(settings.sampled)))
        .apply {
            if (settings.enabled) {
                val exporter = OtlpHttpSpanExporter.builder().setEndpoint(
                    settings.otlp.trimEnd('/') + "/v1/traces",
                ).build()
                addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
            } else {
                addSpanProcessor(Unexported)
            }
        }
        .build()
    return OpenTelemetrySdk.builder()
        .setTracerProvider(tracing)
        .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
        .build()
}

/**
 * Spans kept and sent nowhere. OpenTelemetry 1.67 hands out a no-op tracer when no processor is registered, and
 * a no-op span carries no `traceparent` in or out: with export off Approvals still continues every caller's trace.
 */
private object Unexported : SpanProcessor {
    override fun onStart(parentContext: Context, span: ReadWriteSpan) = Unit
    override fun isStartRequired(): Boolean = false
    override fun onEnd(span: ReadableSpan) = Unit
    override fun isEndRequired(): Boolean = false
}

private val SERVICE_NAME = AttributeKey.stringKey("service.name")
private val SERVICE_INSTANCE_ID = AttributeKey.stringKey("service.instance.id")

val kafkaOwners: Module = single { events: ApprovalEvents -> KafkaOwners(events) }.boundTo<Owners>()

private val publishing: Module =
    singleOf({ settings: KafkaSettings -> approvalEvents(settings) }, { events -> events.close() }) +
        single {
                actors: Actors,
                cluster: Cluster,
                ready: JournalReady,
                data: DataSource,
                events: ApprovalEvents,
                settings: KafkaSettings,
            ->
            actors.within {
                val backend = StreamActors(this)
                val heads = RequestHeads(ready.journal)
                val offsets = JdbcOffsets(data)
                // Each a share of the requests, by slice, placed on a node and moved with its offset when that node
                // goes.
                cluster.spread(ApprovalTopic.PUBLISHED, settings.publishers) { share ->
                    Projection.worker {
                        publishedRequests(
                            ready.journal,
                            offsets,
                            events,
                            heads,
                            share,
                            settings.publishers,
                            settings.every,
                        ).start(backend)
                    }
                }
            }
            Published
        }

// ---- approvals and the web ----

private val web: Module =
    single { settings: PolicySettings -> PolicyFile(Path.of(settings.path)) } +
        single { entities: Entities, settings: EntitySettings, policies: PolicyFile ->
            ShardedApprovals(entities::request, entities::subject, policies::current, settings.askTimeout)
        }.boundTo<Approvals>() +
        single { identity: IdentitySettings -> signing(identity) } +
        single { settings: TelemetrySettings, web: WebSettings ->
            install(
                { telemetry(settings, web) },
            ) { sdk, _ -> sdk.close() }
        } +
        single {
                approvals: Approvals,
                audit: JournalAudit,
                signing: Signing,
                policies: PolicyFile,
                sdk: OpenTelemetrySdk,
            ->
            approvalsApi(approvals, audit, audit, signing, sdk) { policies.current().approverGroups }
        } +
        single { api: Api, settings: WebSettings, _: Published, _: Digesting ->
            install(
                {
                    api.startWithDocs(
                        port = settings.port,
                        host = settings.host,
                        docs = docs { docsPath = "/api-docs" },
                    )
                },
            ) { server, _ ->
                server.stop()
            }.also { server ->
                onStarted { logInfo("Approvals on ${server.baseUrl}, docs at ${server.baseUrl}/api-docs") }
            }
        }

// ---- the audit (bank spec 0019) ----

/** The nightly digest started, once in the cluster: what the web waits for. */
object Digesting

private val auditing: Module =
    single { ready: JournalReady, data: DataSource -> JournalAudit(ready.journal, data) } +
        single { actors: Actors, cluster: Cluster, audit: JournalAudit, settings: AuditSettings ->
            actors.within {
                val backend = StreamActors(this)
                // One worker, placed on a node and moved when it goes: a day is digested once, wherever.
                cluster.spread(
                    "digest",
                    1,
                ) { _ -> Projection.worker { digesting(audit, settings.digestEvery).start(backend) } }
            }
            Digesting
        }

/** Everything but how the owning service is asked again, which [owners] says. */
fun approvalsModule(owners: Module = kafkaOwners): Module =
    settings + storage + publishing + owners + clustered + auditing + web

/** The application as a value: the build reads the graph from here, and `main` runs it. */
object BankApprovals : SurefoldApp<PelicanServer>() {
    override val module: Module = approvalsModule()
}
