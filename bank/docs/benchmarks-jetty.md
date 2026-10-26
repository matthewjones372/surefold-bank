# The bank on Jetty against the bank on Pekko HTTP

The same load scenarios against two builds of the bank, on one machine, one build at a time:

- **Before**: Pelican's Pekko backend, for the server and for the screening client.
- **After**: Pelican's Jetty backend for both, with no Pekko or Scala on the runtime classpath.

## Summary

| Scenario, 60 s each | Failed, before | Failed, after | p99, before | p99, after | Larger than the noise? |
|---|---:|---:|---:|---:|---|
| `spread` at 400/s | 0, 0 | 0, 0 | 51 ms, 95 ms | 27 ms, 25 ms | yes: p50, p95 and p99 lower |
| `hot` at 600/s, one account | 0, 0 | 0, 0 | 71 ms, 39 ms | 28 ms, 22 ms | yes: p50, p95 and p99 lower |
| `transfers` at 400/s | 0, 0 | 0, 0 | 1.75 s, 1.83 s | 1.50 s, 1.11 s | yes: p50, p99 and max lower |
| `payroll` of 100 at 2/s | 0, 0 | 0, 0 | 18 ms, 51 ms | 13 ms, 15 ms | p50 and p95 lower; 120 requests a run, so the tail is a few samples |
| `payroll` of 100 at 20/s | 1,173, 1,149 | 1,115, 1,159 | timeout | timeout | no: both saturate |
| `transfers` at 400/s, screened, as shipped | 861, 0, 0 | 192, 19, 0, 9 | 4.21 s, 2.00 s, 1.93 s | 4.36, 3.88, 2.89, 2.99 s | no: the runs overlap |
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
requests under `chaos`. Screened transfers are within the noise, as #54 ships them and with the check's logging at
INFO in both builds; #54 needs its `loadtest/src/main/resources/logback.xml` for that, and without it the screened
run collapses (below).

## What differs between the two builds

| | Before | After |
|---|---|---|
| surefold-bank | `main`, `cb94b88672603710140fb367c12e3002aff167a7` | `claude/exciting-albattani-0ak864` (#54): `8a5362acc3e9b83c9f14e139a1927b4b0cf9966d`, and `717fbfa` for screened transfers as shipped |
| HTTP server | `pelican-pekko`, Pekko HTTP 1.3.0 on Pekko 1.2.1 | `pelican-jetty`, Jetty 12.1.13 |
| Screening client | `pelican-client-pekko`, a pool of 64 connections and 1,024 queued requests | `pelican-client-jetty`, Jetty's `HttpClient` defaults (64 connections, 1,024 queued per destination) |
| Stand-in check (`loadtest`) | Pelican on Pekko HTTP | Pelican on Jetty |
| surefold | `3dbffec46baa36534502af5dc6840429c837bc53` | `e2710b5bf8740f4243fa78c031ca0ae1e282ef98` (116 commits later) |
| pelican | `a2024349bc5504ab4466a1a7bc0c1f0382c2a14a` | `53efbe3c579a1853f13c6327f9306fd6ccbbba65` (134 commits later) |
| OpenTelemetry | 1.66.0 | 1.67.0 |

`717fbfa` adds only `bank/loadtest/src/main/resources/logback.xml` (root at INFO, `org.eclipse.jetty` at WARN) to
`8a5362a`, so the bank's image is the same at both; only the load and the stand-in check differ.

Both builds also differ in everything surefold and pelican changed between those commits (among them surefold's parked
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

- Built as CI builds the bank: surefold and pelican checked out at the pinned revisions and passed as `-PsurefoldSource`,
  `-PpelicanSource`, `-PaccessSource=../bank-access`, in `nix develop .#ci`, with
  `-Pkotlin.compiler.execution.strategy=in-process`; then `:app:installDist :loadtest:installDist` and the two images
  from `deploy/docker/`.
- The compose stack as committed: three nodes of 1,536 MiB (heap 50%), two journal databases, Kafka and Apicurio,
  every event published, tracing off.
- Each scenario: `docker compose run load` with `SCENARIO`, `RATE` and `SECONDS=60`, 10,000 accounts, after the
  load's own 10 s warm-up. Screening on is `SCREENING_ENABLED=true` with the stand-in check. `chaos` is
  `scripts/chaos-docker.sh` at its defaults.
- Every scenario twice on each build, all of one build before the other. Screened transfers ran a third time on the
  Pekko build, four times on the Jetty build at `717fbfa`, and twice more on each build with the stand-in check's
  logging set to INFO through a compose override that mounts a `logback.xml` into the check's container.
- **Service time** is Proofload's: from the moment a request left to the moment its answer arrived, read from its HTML
  report. **Behind schedule** is how late the generator sent at p99; Proofload says it kept its schedule when that is
  under one interval between departures (2.5 ms at 400/s), so at 400/s and 600/s a millisecond's stall in the
  generator's JVM is enough to say no. Its service times are the target's either way.
- **Start-up**: from Docker's `StartedAt` for the node's container to its log line `Surefold Bank on http://…` (listening)
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

The Jetty build at `717fbfa`. The first of its four runs was the first after the host restarted.

| | Before, run 1 | Before, run 2 | Before, run 3 | After, run 1 | After, run 2 | After, run 3 | After, run 4 |
|---|---:|---:|---:|---:|---:|---:|---:|
| Requests, failed | 24,000, 861 | 24,000, 0 | 24,000, 0 | 24,000, 192 | 24,000, 19 | 24,000, 0 | 24,000, 9 |
| Answered OK a second | 386 | 400 | 400 | 397 | 400 | 400 | 400 |
| Service time p50 | 1.58 s | 495 ms | 524 ms | 1.33 s | 684 ms | 491 ms | 405 ms |
| p95 | 3.86 s | 1.59 s | 1.53 s | 3.52 s | 2.87 s | 2.11 s | 2.13 s |
| p99 | 4.21 s | 2.00 s | 1.93 s | 4.36 s | 3.88 s | 2.89 s | 2.99 s |
| max | 5.23 s | 3.69 s | 4.14 s | 5.74 s | 5.07 s | 4.04 s | 4.60 s |
| Generator behind schedule, p99 | 175 ms | 94.4 ms | 111 ms | 144 ms | 65.0 ms | 98.6 ms | 85.5 ms |
| Proofload: kept its schedule | yes | yes | no | yes | yes | yes | yes |
| Ledger conserved | yes | yes | yes | yes | yes | yes | yes |
| Peak RSS, bank-1 / 2 / 3 (MiB) | 1151 / 1184 / 1164 | 1189 / 1122 / 1129 | 1159 / 1171 / 1160 | 1131 / 1125 / 1130 | 1075 / 1148 / 1127 | 1092 / 1105 / 1164 | 1095 / 1064 / 1082 |

The check wrote about 20 lines a run at `717fbfa`, and the Pekko check 27. At `8a5362a`, without the `logback.xml`,
logback's default puts the root logger at DEBUG and the Jetty check logs every request: 1.5 million lines and 492 MB
in a run. Writing them takes the CPU the bank needs, and both runs there failed: 20,282 and 19,871 of 24,000, p99
30.1 s and 30.2 s (the load client's timeout). In the run whose logs were kept, the check answered 10,017 screenings,
up to 11,464 requests were in flight from the generator, bank-1's Postgres pool had up to 5,323 callers waiting, and
bank-1 stopped with `OutOfMemoryError: Java heap space` before Docker restarted it; its ledger had not settled within
the load's 180 s.

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

The p99 of about 4 s on both is the requests that waited out a fault. The Jetty check's DEBUG lines at `8a5362a` are most
of the tenfold lines searched.

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
- **Screened transfers at `8a5362a`**, which fail for the stand-in check's DEBUG logging, not the bank's screening;
  `717fbfa`'s `logback.xml` is what brings them back within the noise.

Within the noise: `payroll` at 2/s (120 requests a run, so its p99 is the slowest two), `payroll` at 20/s (both
saturate), screened transfers both as shipped and with the check at INFO, and peak RSS, which is 1.0–1.2 GiB a node
on both builds and lower on Jetty by 50–100 MiB in most runs, inside the up to 200 MiB that the same node varies by
between runs of one build. Screened transfers as shipped have a higher p99 on Jetty in three of its four runs, 2.89–4.36 s
against 1.93–4.21 s, which more runs would settle.

## The load spread over every node

The same harness on main before and after #55, both on the Jetty bank above:

- **Before**: `c9cc57c9f185c00851074a097a0b225ddf3a85c8` (main with #54). The load sends every request to bank-1 and calls as `ops`, so each request
  is also a recorded look, a row and a Kafka record (bank spec 0021).
- **After**: `c7fea003749d101992425a4734bcc27eb493f9c0` (main with #55). The load sends each request to one of the three nodes at random (`BASE_URLS`),
  and calls as the accounts' owner; `ops` only sends payrolls and reads the ledger. The summary counts how each transfer
  was answered: `Completed`, or `Debited` or `Pending` when the bank's 1 s `transferWait` ran out first.

The bank's code is the same on both: with whitespace, trailing commas, braces and import order set aside, every file
under `src/main` but `Load.kt` reads the same, and `application.conf` only adds `TRANSFER_WAIT`, left at 1 s. surefold
`e2710b5` and pelican `53efbe3` on both. So what differs is how the load calls the bank.

Same machine and method as above, every scenario on a fresh cluster, each twice and `transfers` at 400/s three times,
one side and then the other. The answered counts include the load's 10 s warm-up.

### Summary

| Scenario | Failed, before | Failed, after | p50, before | p50, after | p99, before | p99, after | Larger than the noise? |
|---|---:|---:|---:|---:|---:|---:|---|
| `spread` at 400/s | 0, 0 | 0, 0 | 3.5, 3.6 ms | 4.0, 3.8 ms | 63, 81 ms | 127, 54 ms | no |
| `hot` at 600/s | 0, 0 | 0, 0 | 4.7, 4.8 ms | 4.1, 3.9 ms | 39, 66 ms | 42, 34 ms | p50 lower on after, by under 1 ms |
| `transfers` at 200/s | 0, 0 | 0, 0 | 32, 32 ms | 55, 49 ms | 419, 287 ms | 789, 579 ms | yes: slower on after |
| `transfers` at 400/s | 0, 0, 0 | 0, 0, 0 | 250, 163, 199 ms | 363, 263, 508 ms | 2.48, 1.70, 1.59 s | 2.06, 2.23, 2.25 s | p50 slower on after; p95 and p99 overlap |
| `payroll` of 100 at 2/s | 0, 0 | 0, 0 | 4.9, 4.3 ms | 7.4, 7.9 ms | 20, 16 ms | 29, 31 ms | yes: slower on after |
| `payroll` of 100 at 20/s | 1,165, 1,160 | 315, 359 | timeout | 18.9, 22.5 s | timeout | timeout | yes: after answers about 860 of 1,200, before about 40 |

Every run conserved the ledger. On after, the highest rate at which `transfers` runs clean (under 1 in 1,000 failed and
a p99 under 1 s) is about 200 a second: both runs at 200/s were clean, one of two at 225/s, neither at 250/s. No run
failed a transfer at any rate up to 400/s; what passes 1 s first is the tail, as the share answered before completing
grows from 2–3% at 200/s to 26–38% at 400/s.

### Results

#### spread, 400/s

| | Before, run 1 | Before, run 2 | After, run 1 | After, run 2 |
|---|---:|---:|---:|---:|
| Failed, of | 0 of 24,000 | 0 of 24,000 | 0 of 24,000 | 0 of 24,000 |
| Service time p50 | 3.5 ms | 3.6 ms | 4.0 ms | 3.8 ms |
| p95 | 20 ms | 25 ms | 39 ms | 20 ms |
| p99 | 63 ms | 81 ms | 127 ms | 54 ms |
| max | 145 ms | 184 ms | 348 ms | 213 ms |
| Proofload: kept its schedule | no | no | no | no |
| Ledger conserved | yes | yes | yes | yes |
| Peak RSS, bank-1 / 2 / 3 (MiB) | 1047 / 820 / 939 | 1037 / 973 / 992 | 1022 / 1069 / 1102 | 1050 / 930 / 1068 |

#### hot, 600/s at one account

| | Before, run 1 | Before, run 2 | After, run 1 | After, run 2 |
|---|---:|---:|---:|---:|
| Failed, of | 0 of 36,000 | 0 of 36,000 | 0 of 36,000 | 0 of 36,000 |
| Service time p50 | 4.7 ms | 4.8 ms | 4.1 ms | 3.9 ms |
| p95 | 18 ms | 24 ms | 17 ms | 15 ms |
| p99 | 39 ms | 66 ms | 42 ms | 34 ms |
| max | 105 ms | 159 ms | 139 ms | 137 ms |
| Proofload: kept its schedule | no | no | no | no |
| Ledger conserved | yes | yes | yes | yes |
| Peak RSS, bank-1 / 2 / 3 (MiB) | 1019 / 608 / 1032 | 1058 / 534 / 1054 | 1025 / 929 / 1053 | 1037 / 1043 / 1091 |

#### transfers, 200/s

| | Before, run 1 | Before, run 2 | After, run 1 | After, run 2 |
|---|---:|---:|---:|---:|
| Failed, of | 0 of 12,000 | 0 of 12,000 | 0 of 12,000 | 0 of 12,000 |
| Service time p50 | 32 ms | 32 ms | 55 ms | 49 ms |
| p95 | 208 ms | 166 ms | 447 ms | 302 ms |
| p99 | 419 ms | 287 ms | 789 ms | 579 ms |
| max | 700 ms | 600 ms | 1.53 s | 1.17 s |
| Proofload: kept its schedule | yes | no | yes | yes |
| Ledger conserved | yes | yes | yes | yes |
| Answered completed / debited / pending | — | — | 13,706 / 240 / 52 (2% not completed) | 13,584 / 321 / 90 (3% not completed) |
| Peak RSS, bank-1 / 2 / 3 (MiB) | 1042 / 995 / 1029 | 1071 / 1037 / 988 | 1112 / 1109 / 1073 | 1089 / 1066 / 1074 |

#### transfers, 400/s

| | Before, run 1 | Before, run 2 | Before, run 3 | After, run 1 | After, run 2 | After, run 3 |
|---|---:|---:|---:|---:|---:|---:|
| Failed, of | 0 of 24,000 | 0 of 24,000 | 0 of 24,000 | 0 of 24,000 | 0 of 24,000 | 0 of 24,000 |
| Service time p50 | 250 ms | 163 ms | 199 ms | 363 ms | 263 ms | 508 ms |
| p95 | 1.80 s | 1.31 s | 1.26 s | 1.67 s | 1.69 s | 1.81 s |
| p99 | 2.48 s | 1.70 s | 1.59 s | 2.06 s | 2.23 s | 2.25 s |
| max | 4.03 s | 2.43 s | 2.42 s | 2.99 s | 3.59 s | 3.44 s |
| Proofload: kept its schedule | yes | yes | yes | yes | yes | yes |
| Ledger conserved | yes | yes | yes | yes | yes | yes |
| Answered completed / debited / pending | — | — | — | 19,346 / 4,417 / 4,227 (31% not completed) | 20,718 / 3,977 / 3,295 (26% not completed) | 17,315 / 5,795 / 4,884 (38% not completed) |
| Peak RSS, bank-1 / 2 / 3 (MiB) | 1049 / 1097 / 1080 | 1039 / 1089 / 1059 | 1100 / 1102 / 1123 | 1089 / 1055 / 1056 | 1139 / 1111 / 1021 | 1024 / 1048 / 1078 |

#### payroll of 100, 2/s

| | Before, run 1 | Before, run 2 | After, run 1 | After, run 2 |
|---|---:|---:|---:|---:|
| Failed, of | 0 of 120 | 0 of 120 | 0 of 120 | 0 of 120 |
| Service time p50 | 4.9 ms | 4.3 ms | 7.4 ms | 7.9 ms |
| p95 | 11 ms | 10 ms | 21 ms | 22 ms |
| p99 | 20 ms | 16 ms | 29 ms | 31 ms |
| max | 27 ms | 20 ms | 40 ms | 32 ms |
| Proofload: kept its schedule | no | no | no | no |
| Ledger conserved | yes | yes | yes | yes |
| Peak RSS, bank-1 / 2 / 3 (MiB) | 1096 / 609 / 740 | 1109 / 701 / 751 | 1106 / 1052 / 1034 | 1065 / 1054 / 1048 |

#### payroll of 100, 20/s

| | Before, run 1 | Before, run 2 | After, run 1 | After, run 2 |
|---|---:|---:|---:|---:|
| Failed, of | 1,165 of 1,200 | 1,160 of 1,200 | 315 of 1,200 | 359 of 1,200 |
| Service time p50 | 30.10 s | 30.10 s | 18.90 s | 22.50 s |
| p95 | 30.10 s | 30.10 s | 30.10 s | 30.10 s |
| p99 | 30.10 s | 30.10 s | 30.30 s | 30.20 s |
| max | 30.10 s | 30.10 s | 30.50 s | 30.30 s |
| Proofload: kept its schedule | yes | yes | yes | yes |
| Ledger conserved | yes | yes | yes | yes |
| Peak RSS, bank-1 / 2 / 3 (MiB) | 1123 / 1061 / 1012 | 1095 / 876 / 1055 | 1084 / 1195 / 1126 | 1101 / 1144 / 1156 |

#### transfers on the spread load, by rate

| Rate | Failed | p50 | p95 | p99 | max | Answered completed / debited / pending | Clean |
|---:|---:|---:|---:|---:|---:|---|---|
| 200/s | 0 | 55 ms | 447 ms | 789 ms | 1.53 s | 13,706 / 240 / 52 (2% not completed) | yes |
| 200/s | 0 | 49 ms | 302 ms | 579 ms | 1.17 s | 13,584 / 321 / 90 (3% not completed) | yes |
| 225/s | 0 | 72 ms | 440 ms | 709 ms | 1.38 s | 15,026 / 588 / 131 (5% not completed) | yes |
| 225/s | 0 | 76 ms | 638 ms | 1.09 s | 1.64 s | 14,734 / 700 / 312 (6% not completed) | no, p99 over 1 s |
| 250/s | 0 | 90 ms | 692 ms | 1.12 s | 1.79 s | 16,422 / 795 / 281 (6% not completed) | no, p99 over 1 s |
| 250/s | 0 | 102 ms | 893 ms | 1.32 s | 1.83 s | 16,138 / 1,085 / 274 (8% not completed) | no, p99 over 1 s |
| 300/s | 0 | 129 ms | 986 ms | 1.41 s | 2.40 s | 18,852 / 1,204 / 937 (10% not completed) | no, p99 over 1 s |
| 300/s | 0 | 141 ms | 1.27 s | 1.61 s | 2.43 s | 18,739 / 1,565 / 687 (11% not completed) | no, p99 over 1 s |
| 400/s | 0 | 363 ms | 1.67 s | 2.06 s | 2.99 s | 19,346 / 4,417 / 4,227 (31% not completed) | no, p99 over 1 s |
| 400/s | 0 | 263 ms | 1.69 s | 2.23 s | 3.59 s | 20,718 / 3,977 / 3,295 (26% not completed) | no, p99 over 1 s |
| 400/s | 0 | 508 ms | 1.81 s | 2.25 s | 3.44 s | 17,315 / 5,795 / 4,884 (38% not completed) | no, p99 over 1 s |

### What is larger than the noise

- **Transfers are slower on the spread load at 200/s**, p50 49–55 ms against 32 ms and p99 579–789 ms against
  287–419 ms, and at 400/s on p50, 263–508 ms against 163–250 ms. At 400/s the p95 and p99 overlap.
- **Payroll at 2/s is slower**, p50 7.4–7.9 ms against 4.3–4.9 ms (120 requests a run).
- **Payroll at 20/s is answered**: 841 and 885 of 1,200 against 35 and 40. Both still lose requests to the load
  client's 30 s timeout.
- **Memory follows the load**: bank-2, which the before load never calls, peaks at 534–701 MiB under `hot` and
  `payroll` at 2/s before and 929–1,054 MiB after.

`spread`, and `hot` apart from its p50, are within the noise. The bank under before is the bank measured in the
sections above, and its `transfers` at 400/s here (p99 1.59–2.48 s) is slower than the same build there (1.11–1.50 s):
runs hours apart on this host, with restarts between, differ by more than runs within one sitting, so compare within a section.

## Four throughput changes, on the spread load

The spread load of the section above (every node, as a customer, `TRANSFER_WAIT` at 1 s) on two commits of main,
measured in one sitting, one after the other:

- **Before**: `c7fea003749d101992425a4734bcc27eb493f9c0` (main with #55).
- **After**: `5c0d5224206c4eaad1320115eac1cdf9b51d317d` (main with #56 to #59):
  - #56: a new transfer sends its debit once, not twice.
  - #57: a transfer writes two journal events, not three (no `SourceDebited`), one append fewer on its path.
  - #58: read-model batches commit with `synchronous_commit = off`; group commit's size and committers are settings,
    left at 64 and 2.
  - #59: payroll goes through four senders on each node (`PAYROLL_SENDERS`), not one.

surefold `e2710b5` and pelican `53efbe3` on both. `spread` and `hot` once each, `transfers` at 200/s twice and at 400/s
three times, `payroll` twice at each rate. The load prints `credits a second` (each payroll is 100 credits) on after
only; for before it is computed the same way, OK payrolls × 100 over the 60 s.

### Summary

| Scenario | Failed, before | Failed, after | p50, before | p50, after | p99, before | p99, after | Larger than the noise? |
|---|---:|---:|---:|---:|---:|---:|---|
| `spread` at 400/s | 0 | 0 | 3.8 ms | 3.1 ms | 196 ms | 35 ms | one run each: no noise to judge by |
| `hot` at 600/s | 0 | 0 | 3.4 ms | 2.9 ms | 63 ms | 29 ms | one run each: no noise to judge by |
| `transfers` at 200/s | 0, 0 | 0, 0 | 44, 43 ms | 19, 24 ms | 1.15, 1.07 s | 491, 390 ms | yes: faster on after |
| `transfers` at 400/s | 3, 0, 0 | 0, 0, 0 | 361, 296, 257 ms | 134, 161, 193 ms | 2.80, 2.15, 2.12 s | 1.57, 1.64, 1.54 s | yes: faster on after |
| `payroll` at 2/s | 0, 0 | 0, 0 | 7.4, 6.6 ms | 7.2, 6.7 ms | 56, 36 ms | 21, 21 ms | no (120 requests a run) |
| `payroll` at 20/s | 813, 284 | 0, 0 | 30.1, 17.3 s | 8.4, 6.6 ms | timeout | 93, 64 ms | yes: after carries 2,000 credits a second |

Every run conserved the ledger. Transfers answered before they completed (`Debited` or `Pending`, the 1 s wait run
out) are 2–6% before and 1–4% after at 200/s, and 26–38% before and 13–15% after at 400/s.

On after, the highest rate at which `transfers` runs clean (under 1 in 1,000 failed and a p99 under 1 s) is 250 to
300 a second: both runs at 250/s were clean, one of two at 300/s (p99 784 ms and 1.18 s), neither at 350/s. On
before, that was about 200 a second (the section above), and in this sitting neither run at 200/s was under 1 s.
No transfer failed on after at any rate up to 400/s.

### Results

#### spread, 400/s

| | Before, run 1 | After, run 1 |
|---|---:|---:|
| Failed, of | 0 of 24,000 | 0 of 24,000 |
| Service time p50 | 3.8 ms | 3.1 ms |
| p95 | 70 ms | 12 ms |
| p99 | 196 ms | 35 ms |
| max | 579 ms | 198 ms |
| Proofload: kept its schedule | no | no |
| Ledger conserved | yes | yes |
| Peak RSS, bank-1 / 2 / 3 (MiB) | 1048 / 1000 / 1061 | 1036 / 1032 / 1013 |

#### hot, 600/s at one account

| | Before, run 1 | After, run 1 |
|---|---:|---:|
| Failed, of | 0 of 36,000 | 0 of 36,000 |
| Service time p50 | 3.4 ms | 2.9 ms |
| p95 | 17 ms | 12 ms |
| p99 | 63 ms | 29 ms |
| max | 201 ms | 98 ms |
| Proofload: kept its schedule | no | no |
| Ledger conserved | yes | yes |
| Peak RSS, bank-1 / 2 / 3 (MiB) | 1024 / 1029 / 1080 | 985 / 1014 / 1149 |

#### transfers, 200/s

| | Before, run 1 | Before, run 2 | After, run 1 | After, run 2 |
|---|---:|---:|---:|---:|
| Failed, of | 0 of 12,000 | 0 of 12,000 | 0 of 12,000 | 0 of 12,000 |
| Service time p50 | 44 ms | 42 ms | 19 ms | 24 ms |
| p95 | 713 ms | 684 ms | 228 ms | 177 ms |
| p99 | 1.15 s | 1.07 s | 491 ms | 390 ms |
| max | 1.85 s | 1.83 s | 902 ms | 927 ms |
| Proofload: kept its schedule | yes | yes | yes | yes |
| Ledger conserved | yes | yes | yes | yes |
| Answered completed / debited / pending | 13,089 / 707 / 202 (6% not completed) | 13,763 / 214 / 22 (2% not completed) | 13,871 / 86 / 40 (1% not completed) | 13,473 / 315 / 210 (4% not completed) |
| Peak RSS, bank-1 / 2 / 3 (MiB) | 1039 / 1065 / 1058 | 1112 / 1128 / 1064 | 1060 / 1012 / 1104 | 1046 / 1059 / 1018 |

#### transfers, 400/s

| | Before, run 1 | Before, run 2 | Before, run 3 | After, run 1 | After, run 2 | After, run 3 |
|---|---:|---:|---:|---:|---:|---:|
| Failed, of | 3 of 24,000 | 0 of 24,000 | 0 of 24,000 | 0 of 24,000 | 0 of 24,000 | 0 of 24,000 |
| Service time p50 | 361 ms | 296 ms | 257 ms | 134 ms | 161 ms | 193 ms |
| p95 | 2.10 s | 1.69 s | 1.70 s | 1.18 s | 1.33 s | 1.17 s |
| p99 | 2.80 s | 2.15 s | 2.12 s | 1.57 s | 1.64 s | 1.54 s |
| max | 4.63 s | 3.44 s | 3.42 s | 2.53 s | 2.57 s | 2.35 s |
| Proofload: kept its schedule | yes | yes | yes | yes | yes | yes |
| Ledger conserved | yes | yes | yes | yes | yes | yes |
| Answered completed / debited / pending | 17,383 / 3,685 / 6,895 (38% not completed) | 20,588 / 3,974 / 3,433 (26% not completed) | 19,674 / 5,293 / 3,027 (30% not completed) | 24,495 / 1,663 / 1,838 (13% not completed) | 23,872 / 2,207 / 1,918 (15% not completed) | 24,214 / 1,711 / 2,067 (13% not completed) |
| Peak RSS, bank-1 / 2 / 3 (MiB) | 1125 / 1095 / 1043 | 1089 / 1066 / 1132 | 1059 / 1095 / 1051 | 1100 / 1052 / 1145 | 1104 / 1084 / 1132 | 1037 / 1134 / 1110 |

#### payroll of 100, 2/s

| | Before, run 1 | Before, run 2 | After, run 1 | After, run 2 |
|---|---:|---:|---:|---:|
| Failed, of | 0 of 120 | 0 of 120 | 0 of 120 | 0 of 120 |
| Service time p50 | 7.4 ms | 6.6 ms | 7.2 ms | 6.7 ms |
| p95 | 23 ms | 18 ms | 14 ms | 16 ms |
| p99 | 56 ms | 36 ms | 21 ms | 21 ms |
| max | 74 ms | 44 ms | 24 ms | 33 ms |
| Proofload: kept its schedule | yes | no | no | no |
| Ledger conserved | yes | yes | yes | yes |
| Credits a second | 200 (computed) | 200 (computed) | 200 | 200 |
| Peak RSS, bank-1 / 2 / 3 (MiB) | 1048 / 1075 / 1047 | 1068 / 1031 / 1036 | 1063 / 1042 / 1098 | 815 / 894 / 970 |

#### payroll of 100, 20/s

| | Before, run 1 | Before, run 2 | After, run 1 | After, run 2 |
|---|---:|---:|---:|---:|
| Failed, of | 813 of 1,200 | 284 of 1,200 | 0 of 1,200 | 0 of 1,200 |
| Service time p50 | 30.10 s | 17.30 s | 8.4 ms | 6.6 ms |
| p95 | 30.20 s | 30.10 s | 45 ms | 30 ms |
| p99 | 30.30 s | 30.20 s | 93 ms | 64 ms |
| max | 30.50 s | 30.50 s | 327 ms | 203 ms |
| Proofload: kept its schedule | yes | yes | no | no |
| Ledger conserved | yes | yes | yes | yes |
| Credits a second | 645 (computed) | 1,526 (computed) | 2,000 | 2,000 |
| Peak RSS, bank-1 / 2 / 3 (MiB) | 1138 / 1167 / 1162 | 1097 / 1144 / 1172 | 1138 / 1045 / 1134 | 1096 / 1058 / 1124 |

#### transfers on 5c0d522, by rate

| Rate | Failed | p50 | p95 | p99 | max | Answered completed / debited / pending | Clean |
|---:|---:|---:|---:|---:|---:|---|---|
| 200/s | 0 | 19 ms | 228 ms | 491 ms | 902 ms | 13,871 / 86 / 40 (1% not completed) | yes |
| 200/s | 0 | 24 ms | 177 ms | 390 ms | 927 ms | 13,473 / 315 / 210 (4% not completed) | yes |
| 250/s | 0 | 32 ms | 281 ms | 789 ms | 1.42 s | 17,272 / 192 / 32 (1% not completed) | yes |
| 250/s | 0 | 39 ms | 533 ms | 826 ms | 1.33 s | 17,086 / 322 / 88 (2% not completed) | yes |
| 300/s | 0 | 53 ms | 459 ms | 784 ms | 1.30 s | 19,326 / 906 / 766 (8% not completed) | yes |
| 300/s | 0 | 66 ms | 793 ms | 1.18 s | 1.88 s | 19,659 / 773 / 562 (6% not completed) | no, p99 over 1 s |
| 350/s | 0 | 109 ms | 1.17 s | 1.53 s | 2.42 s | 21,612 / 1,657 / 1,228 (12% not completed) | no, p99 over 1 s |
| 350/s | 0 | 106 ms | 1.24 s | 1.74 s | 2.89 s | 22,147 / 1,523 / 825 (10% not completed) | no, p99 over 1 s |
| 400/s | 0 | 134 ms | 1.18 s | 1.57 s | 2.53 s | 24,495 / 1,663 / 1,838 (13% not completed) | no, p99 over 1 s |
| 400/s | 0 | 161 ms | 1.33 s | 1.64 s | 2.57 s | 23,872 / 2,207 / 1,918 (15% not completed) | no, p99 over 1 s |
| 400/s | 0 | 193 ms | 1.17 s | 1.54 s | 2.35 s | 24,214 / 1,711 / 2,067 (13% not completed) | no, p99 over 1 s |

### What is larger than the noise

- **Transfers**: every after run is faster than every before run at both rates, at p50, p95, p99 and max: at 200/s a
  p50 of 19–24 ms against 43–44 ms and a p99 of 390–491 ms against 1.07–1.15 s; at 400/s a p50 of 134–193 ms against
  257–361 ms and a p99 of 1.54–1.64 s against 2.12–2.80 s.
- **Payroll at 20/s**: after answered every one of 2,400 requests with a p99 under 100 ms; before failed 284 and 813
  of 1,200 to the load client's 30 s timeout. 2,000 credits a second is all the load offered, so it is a floor on what
  after carries, not its ceiling.

`spread` and `hot` ran once each, so their lower tail on after has no noise to be read against. Payroll at 2/s is
within the noise. `c7fea00` at 200/s was slower in this sitting (p99 1.07–1.15 s) than in the section above
(579–789 ms), which is why both sides here were run again rather than compared with those.

## petshop

petshop at `c50e3e02459c628558cba324221c90a043c6414c`, on the same machine after the bank's runs, with JDK 25.

**Before and after are the same surefold.** The surefold petshop's `main` resolves, `0.10.2-SNAPSHOT` from Central's snapshot
repository (`0.10.2-20261021.200612-5`, published at 20:06 UTC on 21 October), was published from surefold `e2710b5`,
merged at 19:59 UTC. Every surefold jar petshop builds, runs and tests with (`surefold`, `surefold-actor`, `surefold-app` and its
modules, `surefold-app-compiler`, `surefold-app-gradle`, `surefold-kafka`, `surefold-micrometer`, `surefold-otel`, `surefold-slf4j`,
`surefold-stream`, `surefold-stream-forks`, `surefold-test`) is identical, entry by entry, to `e2710b5` built here with
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
