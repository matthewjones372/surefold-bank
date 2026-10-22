# The bank on Jetty against the bank on Pekko HTTP

The same load scenarios against two builds of the bank, on one machine, one build at a time:

- **Before**: Pelican's Pekko backend, for the server and for the screening client.
- **After**: Pelican's Jetty backend for both, with no Pekko or Scala on the runtime classpath.

The README's and the specs' figures are not changed by this page.

## Summary

| Scenario, 60 s each | Failed, before | Failed, after | p99, before | p99, after | Larger than the noise? |
|---|---:|---:|---:|---:|---|
| `spread` at 400/s | 0, 0 | 0, 0 | 51 ms, 95 ms | 27 ms, 25 ms | yes: p50, p95 and p99 lower |
| `hot` at 600/s, one account | 0, 0 | 0, 0 | 71 ms, 39 ms | 28 ms, 22 ms | yes: p50, p95 and p99 lower |
| `transfers` at 400/s | 0, 0 | 0, 0 | 1.75 s, 1.83 s | 1.50 s, 1.11 s | yes: p50, p99 and max lower |
| `payroll` of 100 at 2/s | 0, 0 | 0, 0 | 18 ms, 51 ms | 13 ms, 15 ms | p50 and p95 lower; 120 requests a run, so the tail is a few samples |
| `payroll` of 100 at 20/s | 1,173, 1,149 | 1,115, 1,159 | timeout | timeout | no: both saturate |
| `transfers` at 400/s, screened, as shipped | 861, 0, 0 | 20,282, 19,871 | 4.21 s, 2.00 s, 1.93 s | 30.1 s, 30.2 s | yes, and it is the stand-in check (below) |
| `transfers` at 400/s, screened, check at INFO | 0, 0 | 0, 2 | 2.12 s, 1.98 s | 2.33 s, 2.21 s | no |
| `chaos`, 200/s for 420 s | 10,795, 7,266 | 5,904, 6,110 | 4.04 s, 4.03 s | 4.01 s, 4.03 s | fewer failed; restarts cost far less |

| Start-up and memory | Before | After |
|---|---:|---:|
| Container start to listening on 8080, median of each node's starts | 11.8 s, 8.8 s, 9.9 s | 5.4 s, 4.8 s, 4.8 s |
| Container start to `Up` in its own view | 8.9 s, 6.6 s, 7.1 s | 8.6 s, 9.5 s, 9.7 s |
| `compose up --wait`, every service healthy, median | 89.5 s | 87.2 s |
| Peak RSS of a busy node under `transfers` | 1,109–1,168 MiB | 1,043–1,093 MiB |
| `app/build/install/app/lib` | 144 jars, 91 MB | 141 jars, 69 MB |

The Jetty build answers `spread`, `hot` and `transfers` with a lower tail than either Pekko run, and loses fewer
requests under `chaos`. Screened transfers as the branch ships them collapse, because the stand-in check, now on Jetty,
logs at DEBUG; with the check logging at INFO the two builds are the same within the noise.

## What differs between the two builds

| | Before | After |
|---|---|---|
| tweet-street | `main`, `cb94b88672603710140fb367c12e3002aff167a7` | `claude/exciting-albattani-0ak864` (#54), `8a5362acc3e9b83c9f14e139a1927b4b0cf9966d` |
| HTTP server | `pelican-pekko`, Pekko HTTP 1.3.0 on Pekko 1.2.1 | `pelican-jetty`, Jetty 12.1.13 |
| Screening client | `pelican-client-pekko`, a pool of 64 connections and 1,024 queued requests | `pelican-client-jetty`, Jetty's `HttpClient` defaults (64 connections, 1,024 queued per destination) |
| Stand-in check (`loadtest`) | Pelican on Pekko HTTP | Pelican on Jetty |
| lark | `3dbffec46baa36534502af5dc6840429c837bc53` | `e2710b5bf8740f4243fa78c031ca0ae1e282ef98` (116 commits later) |
| pelican | `a2024349bc5504ab4466a1a7bc0c1f0382c2a14a` | `53efbe3c579a1853f13c6327f9306fd6ccbbba65` (134 commits later) |
| OpenTelemetry | 1.66.0 | 1.67.0 |

Both builds also differ in everything lark and pelican changed between those commits (among them lark's parked
runners, spec 0143, and pelican's Jetty tuning, spec 0085), so a difference below is the whole change, not the server
alone.

## Machine

- 4 vCPUs, Intel Xeon @ 2.10 GHz (Sapphire Rapids, model 207), KVM guest, 1 thread per core; 15 GiB RAM, no swap.
- Ubuntu 24.04.5, kernel 6.18.44-fc-v140.
- Docker 29.9.1, Compose v5.6.0. The bank's image is `eclipse-temurin:25-jre`; the build ran in `nix develop .#ci`
  (OpenJDK 25.0.4.1).
- Nothing else running: each scenario on a fresh `deploy/docker/compose.yml` cluster (`down -v`, then `up --wait`),
  Gradle and Kotlin daemons stopped before the first run of each build. The load generator runs on the same machine,
  in the `load` container, so everything here shares four cores.

## Method

- Built as CI builds the bank: lark and pelican checked out at the pinned revisions and passed as `-PlarkSource`,
  `-PpelicanSource`, `-PaccessSource=../bank-access`, in `nix develop .#ci`, with
  `-Pkotlin.compiler.execution.strategy=in-process`; then `:app:installDist :loadtest:installDist` and the two images
  from `deploy/docker/`.
- The compose stack as committed: three nodes of 1,536 MiB (heap 50%), two journal databases, Kafka and Apicurio,
  every event published, tracing off.
- Each scenario: `docker compose run load` with `SCENARIO`, `RATE` and `SECONDS=60`, 10,000 accounts, after the
  load's own 10 s warm-up. Screening on is `SCREENING_ENABLED=true` with the stand-in check. `chaos` is
  `scripts/chaos-docker.sh` at its defaults.
- Every scenario twice on each build, all of one build before the other. Screened transfers ran a third time on the
  Pekko build, and twice more on each build with the stand-in check's logging set to INFO through a compose override
  that mounts a `logback.xml` into the check's container.
- **Service time** is Proofload's: from the moment a request left to the moment its answer arrived, read from its HTML
  report. **Behind schedule** is how late the generator sent at p99; Proofload says it kept its schedule when that is
  under one interval between departures (2.5 ms at 400/s), so at 400/s and 600/s a millisecond's stall in the
  generator's JVM is enough to say no. Its service times are the target's either way.
- **Start-up**: from Docker's `StartedAt` for the node's container to its log line `Lark Bank on http://…` (listening)
  and to `cluster: Up this node` (a member), over every start in every run (15 per node before, 14 after).
- **Peak RSS**: `VmHWM` of each node's JVM, read from `/proc` at the end of the load.

## Results

### spread, 400/s

| | Before, run 1 | Before, run 2 | After, run 1 | After, run 2 |
|---|---:|---:|---:|---:|
| Requests, failed | 24,000, 0 | 24,000, 0 | 24,000, 0 | 24,000, 0 |
| Answered OK a second | 400 | 400 | 400 | 400 |
| Service time p50 | 4.7 ms | 4.6 ms | 3.4 ms | 3.1 ms |
| p95 | 24 ms | 29 ms | 12 ms | 10 ms |
| p99 | 51 ms | 95 ms | 27 ms | 25 ms |
| max | 180 ms | 583 ms | 137 ms | 151 ms |
| Generator behind schedule, p99 | 4.65 ms | 6.39 ms | 2.61 ms | 2.44 ms |
| Proofload: kept its schedule | no | no | no | no |
| Ledger conserved | yes | yes | yes | yes |
| Peak RSS, bank-1 / 2 / 3 (MiB) | 1101 / 1072 / 1034 | 1091 / 878 / 1000 | 1026 / 956 / 989 | 1094 / 950 / 921 |

### hot, 600/s at one account

| | Before, run 1 | Before, run 2 | After, run 1 | After, run 2 |
|---|---:|---:|---:|---:|
| Requests, failed | 36,000, 0 | 36,000, 0 | 36,000, 0 | 36,000, 0 |
| Answered OK a second | 600 | 600 | 600 | 600 |
| Service time p50 | 6.3 ms | 5.0 ms | 4.5 ms | 3.4 ms |
| p95 | 26 ms | 20 ms | 15 ms | 12 ms |
| p99 | 71 ms | 39 ms | 28 ms | 22 ms |
| max | 167 ms | 109 ms | 106 ms | 61 ms |
| Generator behind schedule, p99 | 4.75 ms | 3.39 ms | 2.72 ms | 2.06 ms |
| Proofload: kept its schedule | no | no | no | no |
| Ledger conserved | yes | yes | yes | yes |
| Peak RSS, bank-1 / 2 / 3 (MiB) | 1102 / 591 / 1055 | 1137 / 568 / 1123 | 1032 / 561 / 1048 | 1022 / 527 / 1041 |

### transfers, 400/s

| | Before, run 1 | Before, run 2 | After, run 1 | After, run 2 |
|---|---:|---:|---:|---:|
| Requests, failed | 24,000, 0 | 24,000, 0 | 24,000, 0 | 24,000, 0 |
| Answered OK a second | 400 | 400 | 400 | 400 |
| Service time p50 | 245 ms | 212 ms | 176 ms | 135 ms |
| p95 | 1.36 s | 1.39 s | 1.14 s | 709 ms |
| p99 | 1.75 s | 1.83 s | 1.50 s | 1.11 s |
| max | 2.73 s | 2.75 s | 2.11 s | 2.00 s |
| Generator behind schedule, p99 | 42.7 ms | 45.6 ms | 32.6 ms | 43.3 ms |
| Proofload: kept its schedule | yes | yes | yes | yes |
| Ledger conserved | yes | yes | yes | yes |
| Peak RSS, bank-1 / 2 / 3 (MiB) | 1109 / 1157 / 1151 | 1123 / 1168 / 1145 | 1093 / 1043 / 1076 | 1089 / 1058 / 1085 |

### payroll of 100, 2/s

| | Before, run 1 | Before, run 2 | After, run 1 | After, run 2 |
|---|---:|---:|---:|---:|
| Requests, failed | 120, 0 | 120, 0 | 120, 0 | 120, 0 |
| Answered OK a second | 2.0 | 2.0 | 2.0 | 2.0 |
| Service time p50 | 5.7 ms | 6.5 ms | 5.0 ms | 4.8 ms |
| p95 | 14 ms | 19 ms | 10 ms | 9.3 ms |
| p99 | 18 ms | 51 ms | 13 ms | 15 ms |
| max | 22 ms | 86 ms | 28 ms | 18 ms |
| Generator behind schedule, p99 | 3.72 ms | 2.97 ms | 1.11 ms | 2.18 ms |
| Proofload: kept its schedule | no | yes | yes | no |
| Ledger conserved | yes | yes | yes | yes |
| Peak RSS, bank-1 / 2 / 3 (MiB) | 1170 / 719 / 785 | 1168 / 899 / 764 | 1145 / 630 / 766 | 1124 / 612 / 717 |

### payroll of 100, 20/s

Two thousand credits a second is more than either build carries here: almost every request runs into the load
client's timeout, 20 s on the Pekko build's test client and 30 s on the Jetty build's, so the service times are those
timeouts.

| | Before, run 1 | Before, run 2 | After, run 1 | After, run 2 |
|---|---:|---:|---:|---:|
| Requests, failed | 1,200, 1,173 | 1,200, 1,149 | 1,200, 1,115 | 1,200, 1,159 |
| Answered OK a second | 0.5 | 0.8 | 1.4 | 0.7 |
| Service time p50 | 20.10 s | 20.10 s | 30.10 s | 30.10 s |
| p99 | 20.10 s | 20.10 s | 30.10 s | 30.10 s |
| Generator behind schedule, p99 | 26.9 ms | 15.3 ms | 19.5 ms | 9.76 ms |
| Proofload: kept its schedule | yes | yes | yes | yes |
| Ledger conserved | yes | yes | yes | yes |
| Peak RSS, bank-1 / 2 / 3 (MiB) | 1164 / 1096 / 1085 | 1138 / 1112 / 1113 | 1100 / 1070 / 1077 | 1066 / 1059 / 1067 |

### transfers, 400/s, screened, with the stand-in check as each build ships it

| | Before, run 1 | Before, run 2 | Before, run 3 | After, run 1 | After, run 2 |
|---|---:|---:|---:|---:|---:|
| Requests, failed | 24,000, 861 | 24,000, 0 | 24,000, 0 | 24,000, 20,282 | 24,000, 19,871 |
| Answered OK a second | 386 | 400 | 400 | 62 | 69 |
| Service time p50 | 1.58 s | 495 ms | 524 ms | 16.20 s | 25.20 s |
| p95 | 3.86 s | 1.59 s | 1.53 s | 30.10 s | 30.10 s |
| p99 | 4.21 s | 2.00 s | 1.93 s | 30.10 s | 30.20 s |
| max | 5.23 s | 3.69 s | 4.14 s | 30.30 s | 30.30 s |
| Generator behind schedule, p99 | 175 ms | 94.4 ms | 111 ms | 210 ms | 145 ms |
| Proofload: kept its schedule | yes | yes | no | yes | yes |
| Ledger conserved | yes | yes | yes | yes | not within the load's 180 s |
| bank-1 restarted | no | no | no | yes, out of heap | yes, out of heap |

The Jetty stand-in check has no `logback.xml`, so logback's default puts its root logger at DEBUG and Jetty logs every
request: 1.5 million lines and 492 MB in the run whose logs were kept, against 27 lines from the Pekko check in the
same scenario. Writing them took the CPU the bank needed: in that run the check answered 10,017 screenings of the
24,000, up to 11,464 requests were in flight from the generator, bank-1's Postgres pool had up to 5,323 callers
waiting, and bank-1 stopped with `OutOfMemoryError: Java heap space` before Docker restarted it.

### transfers, 400/s, screened, with the stand-in check logging at INFO

| | Before, run 1 | Before, run 2 | After, run 1 | After, run 2 |
|---|---:|---:|---:|---:|
| Requests, failed | 24,000, 0 | 24,000, 0 | 24,000, 0 | 24,000, 2 |
| Answered OK a second | 400 | 400 | 400 | 400 |
| Service time p50 | 447 ms | 608 ms | 470 ms | 489 ms |
| p95 | 1.60 s | 1.57 s | 1.85 s | 1.75 s |
| p99 | 2.12 s | 1.98 s | 2.33 s | 2.21 s |
| max | 3.25 s | 3.25 s | 3.19 s | 5.00 s |
| Generator behind schedule, p99 | 80.7 ms | 103 ms | 93.8 ms | 72.9 ms |
| Proofload: kept its schedule | yes | no | yes | yes |
| Ledger conserved | yes | yes | yes | yes |
| Peak RSS, bank-1 / 2 / 3 (MiB) | 1148 / 1140 / 1072 | 1158 / 1169 / 1175 | 1068 / 1117 / 1139 | 1063 / 1169 / 1077 |

### chaos, 200/s for 420 s

`scripts/chaos-docker.sh` at its defaults, screening off. Requests failed during each fault, from the load's progress
at each fault's end:

| Fault | Before, run 1 | Before, run 2 | After, run 1 | After, run 2 |
|---|---:|---:|---:|---:|
| 1. bank-3 cut off for 25 s | 1,127 | 1,162 | 824 | 645 |
| 2. bank-2 frozen for 20 s | 2,753 | 1,713 | 1,824 | 2,578 |
| 3. journal database db-1 stopped for 20 s | 2,857 | 1,930 | 1,702 | 2,046 |
| 4. Kafka frozen for 30 s | 1,676 | 1,261 | 1,460 | 755 |
| 5. bank-3 restarted | 1,010 | 0 | 47 | 46 |
| 5. bank-2 restarted | 598 | 614 | 46 | 2 |
| From there to the end of the load | 774 | 586 | 1 | 38 |
| **Total, of 84,000** | **10,795** | **7,266** | **5,904** | **6,110** |
| Service time p50 / p99 / max | 69 ms / 4.04 s / 4.56 s | 50 ms / 4.03 s / 4.33 s | 49 ms / 4.01 s / 4.43 s | 50 ms / 4.03 s / 4.63 s |
| Ledger conserved, no node restarted by Docker, no secret in the logs | yes | yes | yes | yes |
| Lines searched for secrets | 2,789 | 2,482 | 25,125 | 24,535 |

The p99 of about 4 s on both is the requests that waited out a fault. The Jetty check's DEBUG lines are most of the
tenfold lines searched.

## What is larger than the noise

The noise is the spread between the two runs of one build. A difference counts where every run of one build is better
than every run of the other, by more than that spread.

- **Latency under `spread`, `hot` and `transfers`.** Both Jetty runs have a lower p50, p95 and p99 than both Pekko runs:
  `spread` p99 25–27 ms against 51–95 ms, `hot` 22–28 ms against 39–71 ms, `transfers` p50 135–176 ms against
  212–245 ms and p99 1.11–1.50 s against 1.75–1.83 s. All six runs carried the full rate with no failure.
- **Rolling restarts under `chaos`.** Restarting bank-3 and then bank-2 cost 48–93 requests on Jetty against
  614–1,608 on Pekko, and the rest of the run 1–38 against 586–774. The total, 5,904 and 6,110 against 7,266 and
  10,795, is lower on both Jetty runs; the faults that cut off or freeze a node vary by up to 1,000 between two runs of
  the same build and show no difference.
- **Start-up to listening.** About 5 s on Jetty against 9–12 s on Pekko, with every start inside its build's range. The
  Jetty node now opens its port before it joins the cluster, so it reaches `Up` no sooner: bank-1 at 8.6 s against
  8.9 s, and the two that join it about 3 s later than before. Readiness is `Up`, so `compose up --wait` is unchanged.
- **Screened transfers as shipped**, which fail on the Jetty build for the stand-in check's logging, not the bank's
  screening: with the check at INFO, the two builds are within each other's noise.

Within the noise: `payroll` at 2/s (120 requests a run, so its p99 is the slowest two), `payroll` at 20/s (both
saturate), screened transfers with the check at INFO, and peak RSS, which is 1.0–1.2 GiB a node on both builds and
lower on Jetty by 50–100 MiB in most runs, inside the up to 200 MiB that the same node varies by between runs of one
build.

## petshop

petshop at `c50e3e02459c628558cba324221c90a043c6414c`, on the same machine after the bank's runs, with JDK 25.

**Before and after are the same lark.** The lark petshop's `main` resolves, `0.10.2-SNAPSHOT` from Central's snapshot
repository (`0.10.2-20261021.200612-5`, published at 20:06 UTC on 21 October), was published from lark `e2710b5`,
merged at 19:59 UTC. Every lark jar petshop builds, runs and tests with (`lark`, `lark-actor`, `lark-app` and its
modules, `lark-app-compiler`, `lark-app-gradle`, `lark-kafka`, `lark-micrometer`, `lark-otel`, `lark-slf4j`,
`lark-stream`, `lark-stream-forks`, `lark-test`) is identical, entry by entry, to `e2710b5` built here with
`publishToMavenLocal` at the version `gradle.properties` names. So the two runs below are the same build twice, and
show petshop's noise on this machine rather than a change.

Each run is `./gradlew :loadtest:loadTest` (the browsing and adoption ladders of [performance.md], and the three
`PetshopLoadTest` tests, all passing) then `loadtest/heavy.sh`.

### Browsing ladder

p50 / p99 / p99.9 service time in milliseconds. *Not offered* is Proofload saying the generator lost ground, which on
four cores shared with the shop it does from 4,000/s.

| Rate /s | Run 1 | Run 2 |
|---:|---|---|
| 100 | 0.82 / 1.5 / 4.6 | 0.84 / 1.7 / 3.4 |
| 250 | 0.75 / 1.5 / 3.0 | 0.77 / 1.6 / 3.3 |
| 500 | 0.65 / 1.7 / 3.4 | 0.66 / 1.6 / 3.2 |
| 1,000 | 0.52 / 1.3 / 2.9 | 0.52 / 1.1 / 2.4 |
| 2,000 | 0.32 / 0.86 / 3.7 | 0.32 / 0.95 / 3.6 |
| 3,000 | 0.22 / 0.71 / 2.6 | 0.25 / 0.79 / 2.3 |
| 4,000 | 0.28 / 1.2 / 3.9, not offered | 0.29 / 1.2 / 4.8, not offered |
| 5,000 | 0.29 / 1.6 / 5.5, not offered | 0.29 / 2.0 / 5.5, not offered |
| 6,000 | 0.29 / 1.7 / 5.1, not offered | 0.30 / 2.1 / 8.4, not offered |
| 8,000 | 0.37 / 3.0 / 6.6, not offered | 0.38 / 3.2 / 7.8, not offered |
| 10,000 | 0.43 / 4.7 / 11, not offered | 0.52 / 8.0 / 17, not offered |
| 12,000 | 0.56 / 10 / 21, not offered | 2.1 / 29 / 59, not offered |

No rung failed a request or fell behind.

### Adoption ladder

| Rate /s | Run 1 | Run 2 |
|---:|---|---|
| 100 | 8.5 / 22 / 34 | 8.3 / 20 / 33 |
| 250 | 8.1 / 79 / 87, not offered | 7.9 / 72 / 85, not offered |
| 500 | 7.3 s / 23.9 s / 25.5 s, 4,646 of 5,000 failed, fell behind | 6.7 s / 22.7 s / 24.6 s, 4,693 failed, fell behind |

### heavy.sh, ramped to 50,000/s over a minute and held for a minute

| | Run 1 | Run 2 |
|---|---:|---:|
| Load average at the start | 3.73 | 3.09 |
| Ramp: answered a second, failed | 23,316, 0 | 23,373, 0 |
| Ramp: service p50 / p99 / max | 1.97 / 67 / 839 ms | 0.95 / 71 / 839 ms |
| Hold: answered a second, failed | 40,508, 0 | 40,286, 0 |
| Hold: service p50 / p99 / max | 12 / 117 / 520 ms | 12 / 143 / 638 ms |
| Arrivals refused at 1,024 in flight | 673,324 | 683,350 |
| Generator behind schedule, p99 | 53 ms | 64 ms |
| Adoptions taken, refused as declared | 99, 38,218 | 99, 37,990 |

Both runs are void by Proofload's rule, as every run of this script is when the generator refuses arrivals: the shop
and its generator share four cores here, and in both runs the shop used about 1.3 of them and the generator 2.2.
The shop never failed a request. `heavy.sh` reads its socket count with macOS's `netstat`, so that column is empty on
Linux.

### What is larger than the noise

Nothing differs between the builds, so this is the noise: browsing below 4,000/s within 0.2 ms at p99 between runs,
and from 10,000/s up the generator's own slipping widens the tail by up to three times; the adoption ladder within
a few milliseconds and ending on the same rung; and the heavy hold within 1% on answers a second and 25% on p99.

[performance.md]: https://github.com/matthewjones372/petshop/blob/main/docs/performance.md
