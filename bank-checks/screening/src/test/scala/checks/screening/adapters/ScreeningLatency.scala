package checks.screening.adapters

import checks.platform.TestPostgres
import checks.policy.domain.{LiveRule, LiveRules, Severity, Subject}
import checks.policy.service.LiveRulesSource
import checks.screening.service.*
import verdict.{FieldPath, Rule}
import zio.*
import zio.http.*

import java.nio.file.{Files, Path}

// Spec 0018's p99 under 20 ms, as a Gauntlet budget: a timing is only fair run alone, so not among the unit tests.
object ScreeningLatency extends ZIOAppDefault:
  private val Rounds   = 3
  private val Requests = 10000
  private val Parallel = 8

  private val large =
    LiveRules(
      Subject.Transfers,
      List(LiveRule("large-transfer", 3, Severity.High, 0, Rule.Gte(FieldPath.unsafe("amount"), 1000)))
    )

  // As lark-bank's generated ScreeningClient writes it.
  private def body(transfer: String, amount: String) =
    s"""{"transfer":"$transfer","from":"acc-1","to":"acc-2","amount":{"value":"$amount","currency":"GBP"},"requestedAtMillis":1700000000000}"""

  private val serve =
    for
      ref  <- Ref.make(large)
      rules = new LiveRulesSource:
                def live(subject: Subject) = ref.get
      port <- Server
                .install(ScreeningHttp.routes)
                .provideSomeLayer[Server & Decisions](ZLayer.succeed(rules) >>> Screening.layer.fresh)
                .provideSomeLayer[Server & javax.sql.DataSource](PostgresDecisions.layer.orDie)
    yield s"http://127.0.0.1:$port/screen"

  private def post(url: String, json: String) =
    ZIO.scoped(Client.batched(Request.post(url, Body.fromString(json))).flatMap(_.body.asString))

  private def round(url: String, n: Int) =
    ZIO
      .foreachPar(1 to Requests) { i =>
        post(url, body(s"r$n-$i", if i % 4 == 0 then "2500.00" else "10.00")).timed.map(_._1.toNanos)
      }
      .withParallelism(Parallel)
      .map { timings =>
        val sorted = timings.sorted
        (sorted(sorted.size / 2) / 1_000_000.0, sorted((sorted.size * 0.99).toInt) / 1_000_000.0)
      }

  def run =
    for
      out       <- getArgs.map(_.headOption).someOrFail(new IllegalArgumentException("the JSON file to write"))
      url       <- serve
      _         <- ZIO.foreachParDiscard(1 to 1000)(n => post(url, body(s"warm-$n", "10.00"))).withParallelism(Parallel)
      rounds    <- ZIO.foreach(1 to Rounds)(round(url, _))
      (p50, p99) = rounds.sortBy(_._2).apply(Rounds / 2)
      _         <-
        ZIO.logInfo(
          f"screening rounds ${rounds.map(r => f"p99 ${r._2}%.2f").mkString(", ")}; median p50 $p50%.2f ms, p99 $p99%.2f ms"
        )
      _ <- ZIO.attemptBlocking(Files.writeString(Path.of(out), f"""{"p50": $p50%.3f, "p99": $p99%.3f}"""))
    yield ()
  .provideSome[ZIOAppArgs](
    TestPostgres.migrated("screening").orDie,
    Server.defaultWith(_.onAnyOpenPort),
    Client.default
  )
