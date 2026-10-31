# The bank before and after held closes

The load scenarios on two commits of `main`, measured in one sitting on one machine:

- **Before**: `5c0d5224206c4eaad1320115eac1cdf9b51d317d`, with #56 to #59.
- **After**: `45597d10207887f5a7c9630f82f82583bce0165f`, which adds to it:
  - #60: an account holds a settled transfer's `LegsClosed` and writes it with its next append (spec 0031,
    `legs-closed-batched`).
  - #61: `ledger_totals` keeps a row per currency and partition (spec 0032).
  - #62: only a recovered saga that is still moving is nudged (spec 0031, `transfer-drives-once`).
  - #66: lark without Arrow, lark pinned at `200206e`.
  - #67: Gauntlet 0.1.0-rc.38, which builds nothing in the bank.

  The access log's batching (#68) is not in after.

| | Before | After |
|---|---|---|
| lark | `e2710b5bf8740f4243fa78c031ca0ae1e282ef98` | `200206eae2229fa7408f245831050c89d4b7a7ed` |
| pelican | `53efbe3c579a1853f13c6327f9306fd6ccbbba65` | `53efbe3c579a1853f13c6327f9306fd6ccbbba65` |

Each side's lark and pelican are the revisions its own `flake.lock` pins. So a difference below is the whole change,
lark's included, not #60 alone. The compose file, the load and the chaos script are the same on both sides.

## Summary

| Scenario, 60 s each | Failed, before | Failed, after | p50, before | p50, after | p99, before | p99, after | Larger than the noise? |
|---|---:|---:|---:|---:|---:|---:|---|
| `spread` at 400/s | 0, 0 | 0, 0 | 3.62, 3.56 ms | 3.96, 3.33 ms | 220, 81 ms | 166, 47 ms | no |
| `hot` at 600/s, one account | 0, 0 | 0, 0 | 3.52, 3.10 ms | 3.31, 3.72 ms | 60, 52 ms | 48, 77 ms | no |
| `transfers` at 200/s | 0, 0 | 0, 0 | 31, 26 ms | 30, 20 ms | 696, 396 ms | 650, 455 ms | no |
| `transfers` at 400/s | 0, 0, 0 | 0, 0, 0 | 243, 300, 159 ms | 134, 207, 175 ms | 2.02, 1.96, 1.88 s | 1.46, 1.91, 1.54 s | no: after is lower in most runs, inside the spread |
| `payroll` of 100 at 20/s | 0, 0 | 0, 0 | 6.95, 6.00 ms | 6.72, 6.29 ms | 87, 67 ms | 97, 56 ms | no: 2,000 credits a second on every run |
| `payroll` of 100 at 2/s | 0 | 0 | 6.85 ms | 7.37 ms | 17 ms | 20 ms | one run each |
| `chaos`, 200/s for 420 s | 12,572 | 16,296 | 25 ms | 31 ms | 5.03 s | 12.75 s | one run each |
| Clean ceiling of `transfers` | | | | | | | 250/s on both |

| Per transfer, `transfers` at 400/s | Before | After | Larger than the noise? |
|---|---:|---:|---|
| Journal appends (`insert into lark_journal`) | 5.97 | 4.10 | yes |
| Journal events written per append | 1.00 | 1.38 | yes |
| Journal events written, from start to settled | 5.97–5.99 | 5.68–5.69 | yes |
| Journal `select`s by id, a transfer's recovery read | 1.00 | 1.00 | no |
| Postgres time in journal appends | 3.18 ms | 1.53 ms | one diagnostic run each |
| Postgres time in its 40 most-called statements | 6.39 ms | 3.34 ms | one diagnostic run each |
| Postgres commits, both databases | 3.2–3.6 | 3.3–3.6 | no |
| WAL written | 6.83–6.84 KiB | 6.50–6.53 KiB | yes |

Every run conserved the ledger, and no node was restarted by Docker. The latencies are within the noise in every
scenario. The appends are not: after writes the same transfers in 4.1 appends instead of 6, as spec 0031 asks
for, and its Postgres spends half the time on each transfer's statements. That does not raise the ceiling: at
400/s the machine's four cores are close to full, and three quarters of what is busy is the bank's JVMs, not
Postgres ([where the time goes](#where-the-time-goes-on-after)).

## Machine

- 4 vCPUs, Intel Xeon @ 2.10 GHz (Sapphire Rapids, model 207), KVM guest, 1 thread per core; 15 GiB RAM, no swap.
- Ubuntu 24.04.5, kernel 6.18.44-fc-v153.
- Docker 29.9.2, Compose v5.6.1. The bank's image is `eclipse-temurin:25-jre` (Temurin 25.0.4.1). Postgres 17.11
  (`postgres:17-alpine`).
- Built in `nix develop .#ci` (OpenJDK 25.0.4.1).
- The load generator runs on the same machine, in the `load` container, so everything here shares four cores.

## Method

- **Build.** Both sides are built as CI builds the bank. Lark and pelican are checked out at the side's pinned
  revisions and passed as `-PlarkSource`, `-PpelicanSource` and `-PaccessSource=../bank-access`, with
  `-Pkotlin.compiler.execution.strategy=in-process`. The build makes `:app:installDist :loadtest:installDist`, then
  the two images from `deploy/docker/`, tagged per side. Both sides ran in the same `.#ci` shell: the two flakes
  differ only in their `lark` input, which the shell does not use. Gradle and Kotlin daemons were stopped before the
  first run.
- **The stack.** `deploy/docker/compose.yml` as committed: three nodes of 1,536 MiB, two journal databases, Kafka and
  Apicurio, every event published, screening and tracing off. Each run starts on a fresh stack (`down -v`, then
  `up --wait`) and tears it down after.
- **A run.** `docker compose run load` with `SCENARIO`, `RATE` and `SECONDS=60`, over 10,000 accounts, after the
  load's own 10 s warm-up. Runs go one at a time with nothing else running.
- **Order.** The two sides alternate run by run (before, after, before, after), so drift in the host over the
  sitting falls on both alike.
- **Repetitions.** `transfers` runs at 200/s twice, and at 300/s and 400/s three times. `spread`, `hot`, `transfers`
  at 250/s and 350/s and `payroll` at 20/s run twice. `payroll` at 2/s and `chaos` run once.
- **Proofload's figures.** Service time, *behind schedule* and *kept its schedule* come from Proofload's HTML report.
  The generator keeps its schedule when its p99 lateness is under one interval between departures (2.5 ms at
  400/s).
- **Transfers answered.** Completed, or `Debited` or `Pending` when the bank's 1 s `TRANSFER_WAIT` ran out first.
  These counts include the warm-up.
- **Per-transfer counters.** Postgres's own counters (`pg_stat_database`, `pg_stat_user_tables`, `pg_stat_wal`) on
  both databases, and `bank.transfer.steps` from each node's `/metrics`. Each is read when the accounts are open and
  the clock starts, and again once the books settle. The difference is divided by the transfers the ledger counts
  completed, warm-up included.
- **Diagnostic runs.** One more `transfers` run at 400/s on each side, with these added:
  - `pg_stat_statements` loaded in both Postgres (`shared_preload_libraries`, through a compose override outside the
    repository) and reset when the clock starts;
  - `docker stats` sampled every 6 s or so;
  - non-idle `pg_stat_activity` backends sampled about once a second.

  The samplers cost CPU, so these runs are kept out of the latency tables.
- **Chaos.** `scripts/chaos-docker.sh` as committed, less its three build lines; the images were already built.
- **The clean ceiling.** The highest rate at which every run had under 1 failure in 1,000 and a p99 under 1 s.

## Results

### spread, 400/s

| | Before, run 1 | Before, run 2 | After, run 1 | After, run 2 |
|---|---:|---:|---:|---:|
| Failed, of | 0 of 24,000 | 0 of 24,000 | 0 of 24,000 | 0 of 24,000 |
| Answered OK a second | 400 | 400 | 400 | 400 |
| Service time p50 | 3.62 ms | 3.56 ms | 3.96 ms | 3.33 ms |
| p95 | 61.6 ms | 24.2 ms | 65.0 ms | 14.5 ms |
| p99 | 220 ms | 81.3 ms | 166 ms | 46.9 ms |
| max | 713 ms | 258 ms | 480 ms | 350 ms |
| Generator behind schedule, p99 | 9.37 ms | 4.49 ms | 8.03 ms | 2.67 ms |
| Proofload: kept its schedule | yes | no | yes | no |
| Ledger conserved | yes | yes | yes | yes |

### hot, 600/s at one account

| | Before, run 1 | Before, run 2 | After, run 1 | After, run 2 |
|---|---:|---:|---:|---:|
| Failed, of | 0 of 36,000 | 0 of 36,000 | 0 of 36,000 | 0 of 36,000 |
| Answered OK a second | 600 | 600 | 600 | 600 |
| Service time p50 | 3.52 ms | 3.10 ms | 3.31 ms | 3.72 ms |
| p95 | 18.6 ms | 14.2 ms | 17.6 ms | 21.8 ms |
| p99 | 60.0 ms | 51.9 ms | 48.0 ms | 77.1 ms |
| max | 174 ms | 178 ms | 186 ms | 225 ms |
| Generator behind schedule, p99 | 3.70 ms | 2.67 ms | 4.36 ms | 4.36 ms |
| Proofload: kept its schedule | no | no | no | no |
| Ledger conserved | yes | yes | yes | yes |

### transfers, 200/s

| | Before, run 1 | Before, run 2 | After, run 1 | After, run 2 |
|---|---:|---:|---:|---:|
| Failed, of | 0 of 12,000 | 0 of 12,000 | 0 of 12,000 | 0 of 12,000 |
| Answered OK a second | 200 | 200 | 200 | 200 |
| Service time p50 | 30.9 ms | 26.2 ms | 30.3 ms | 19.8 ms |
| p95 | 333 ms | 209 ms | 304 ms | 229 ms |
| p99 | 696 ms | 396 ms | 650 ms | 455 ms |
| max | 1.28 s | 659 ms | 1.40 s | 910 ms |
| Generator behind schedule, p99 | 18.9 ms | 12.3 ms | 29.0 ms | 19.3 ms |
| Proofload: kept its schedule | yes | yes | yes | yes |
| Ledger conserved | yes | yes | yes | yes |
| Answered completed / debited / pending | 13,594 / 297 / 106 (3% not completed) | 13,740 / 192 / 62 (2% not completed) | 13,647 / 198 / 152 (3% not completed) | 13,783 / 176 / 41 (2% not completed) |

### transfers, 400/s

| | Before, run 1 | Before, run 2 | Before, run 3 | After, run 1 | After, run 2 | After, run 3 |
|---|---:|---:|---:|---:|---:|---:|
| Failed, of | 0 of 24,000 | 0 of 24,000 | 0 of 24,000 | 0 of 24,000 | 0 of 24,000 | 0 of 24,000 |
| Answered OK a second | 400 | 400 | 400 | 400 | 400 | 400 |
| Service time p50 | 243 ms | 300 ms | 159 ms | 134 ms | 207 ms | 175 ms |
| p95 | 1.56 s | 1.53 s | 1.43 s | 1.09 s | 1.45 s | 1.17 s |
| p99 | 2.02 s | 1.96 s | 1.88 s | 1.46 s | 1.91 s | 1.54 s |
| max | 3.59 s | 2.95 s | 2.82 s | 2.20 s | 2.85 s | 2.33 s |
| Generator behind schedule, p99 | 48.2 ms | 57.1 ms | 41.2 ms | 48.5 ms | 69.7 ms | 38.5 ms |
| Proofload: kept its schedule | yes | yes | yes | yes | yes | yes |
| Ledger conserved | yes | yes | yes | yes | yes | yes |
| Answered completed / debited / pending | 21,768 / 2,736 / 3,493 (22% not completed) | 21,566 / 2,835 / 3,587 (23% not completed) | 23,516 / 2,090 / 2,380 (16% not completed) | 24,760 / 1,079 / 2,009 (11% not completed) | 23,644 / 2,027 / 2,327 (16% not completed) | 24,485 / 1,388 / 2,118 (13% not completed) |

The diagnostic runs, at the same rate with the samplers on, answered with a p50 of 447 ms before and 233 ms after,
a p99 of 2.25 s and 2.02 s, and 33% and 18% not completed.

### payroll of 100, 20/s

| | Before, run 1 | Before, run 2 | After, run 1 | After, run 2 |
|---|---:|---:|---:|---:|
| Failed, of | 0 of 1,200 | 0 of 1,200 | 0 of 1,200 | 0 of 1,200 |
| Answered OK a second | 20 | 20 | 20 | 20 |
| Service time p50 | 6.95 ms | 6.00 ms | 6.72 ms | 6.29 ms |
| p95 | 35.7 ms | 28.6 ms | 35.9 ms | 29.6 ms |
| p99 | 86.5 ms | 67.1 ms | 96.5 ms | 56.1 ms |
| max | 294 ms | 163 ms | 323 ms | 194 ms |
| Generator behind schedule, p99 | 25.0 ms | 14.9 ms | 12.3 ms | 16.6 ms |
| Proofload: kept its schedule | no | no | no | no |
| Ledger conserved | yes | yes | yes | yes |
| Credits a second | 2,000 | 2,000 | 2,000 | 2,000 |

2,000 credits a second is all the load offers at 20/s, so it is a floor on what either side carries.

### payroll of 100, 2/s

| | Before, run 1 | After, run 1 |
|---|---:|---:|
| Failed, of | 0 of 120 | 0 of 120 |
| Answered OK a second | 2.0 | 2.0 |
| Service time p50 | 6.85 ms | 7.37 ms |
| p95 | 13.0 ms | 15.1 ms |
| p99 | 16.8 ms | 20.3 ms |
| max | 16.9 ms | 28.7 ms |
| Generator behind schedule, p99 | 1.88 ms | 1.07 ms |
| Proofload: kept its schedule | no | yes |
| Ledger conserved | yes | yes |
| Credits a second | 200 | 200 |

### chaos, 200/s for 420 s

Screening off. Requests failed during each fault, from the load's progress at the end of each fault:

| Fault | Before | After |
|---|---:|---:|
| 1. bank-3 cut off for 25 s | 3,392 | 5,793 |
| 2. bank-2 frozen for 20 s | 4,014 | 4,062 |
| 3. journal database db-1 stopped for 20 s | 1,502 | 2,089 |
| 4. Kafka frozen for 30 s | 1,074 | 1,536 |
| 5. bank-3 restarted | 1,245 | 1,326 |
| 5. bank-2 restarted | 1,329 | 1,435 |
| From there to the end of the load | 16 | 55 |
| **Total, of 84,000** | **12,572** | **16,296** |
| Service time p50 / p99 / max | 25 ms / 5.03 s / 30.1 s | 31 ms / 12.75 s / 30.1 s |
| Answered completed / debited / pending | 69,576 / 2,052 / 1,785 | 63,873 / 2,290 / 3,529 |
| Ledger conserved, no node restarted by Docker, the fence held, no secret in the logs | yes | yes |

Most of the difference is the first fault, bank-3 cut off: 3,392 failed before and 5,793 after. With one run each,
there is no noise to judge it by.

### The clean ceiling of transfers

| Rate | Side | Failed | p50 | p95 | p99 | max | Answered completed / debited / pending | Clean |
|---:|---|---:|---:|---:|---:|---:|---|---|
| 200/s | before | 0 | 30.9 ms | 333 ms | 696 ms | 1.28 s | 13,594 / 297 / 106 (3% not completed) | yes |
| 200/s | before | 0 | 26.2 ms | 209 ms | 396 ms | 659 ms | 13,740 / 192 / 62 (2% not completed) | yes |
| 200/s | after | 0 | 30.3 ms | 304 ms | 650 ms | 1.40 s | 13,647 / 198 / 152 (3% not completed) | yes |
| 200/s | after | 0 | 19.8 ms | 229 ms | 455 ms | 910 ms | 13,783 / 176 / 41 (2% not completed) | yes |
| 250/s | before | 0 | 49.8 ms | 478 ms | 810 ms | 1.68 s | 16,410 / 634 / 451 (6% not completed) | yes |
| 250/s | before | 0 | 46.4 ms | 489 ms | 814 ms | 1.44 s | 16,728 / 497 / 274 (4% not completed) | yes |
| 250/s | after | 0 | 27.5 ms | 289 ms | 570 ms | 1.34 s | 16,951 / 370 / 176 (3% not completed) | yes |
| 250/s | after | 0 | 39.1 ms | 468 ms | 952 ms | 1.48 s | 16,832 / 425 / 240 (4% not completed) | yes |
| 300/s | before | 0 | 75.0 ms | 591 ms | 1.14 s | 1.94 s | 19,852 / 888 / 258 (5% not completed) | no, p99 over 1 s |
| 300/s | before | 0 | 90.7 ms | 789 ms | 1.16 s | 1.73 s | 19,161 / 927 / 908 (9% not completed) | no, p99 over 1 s |
| 300/s | before | 0 | 74.4 ms | 789 ms | 1.13 s | 1.97 s | 18,846 / 813 / 1,339 (10% not completed) | no, p99 over 1 s |
| 300/s | after | 0 | 94.9 ms | 898 ms | 1.39 s | 2.40 s | 19,855 / 708 / 432 (5% not completed) | no, p99 over 1 s |
| 300/s | after | 0 | 91.2 ms | 910 ms | 1.38 s | 2.48 s | 18,807 / 1,103 / 1,088 (10% not completed) | no, p99 over 1 s |
| 300/s | after | 0 | 73.4 ms | 1.28 s | 1.87 s | 2.92 s | 18,441 / 1,360 / 1,193 (12% not completed) | no, p99 over 1 s |
| 350/s | before | 0 | 170 ms | 1.38 s | 2.00 s | 3.31 s | 20,376 / 1,442 / 2,674 (17% not completed) | no, p99 over 1 s |
| 350/s | before | 0 | 128 ms | 860 ms | 1.20 s | 1.74 s | 21,451 / 1,072 / 1,974 (12% not completed) | no, p99 over 1 s |
| 350/s | after | 0 | 107 ms | 977 ms | 1.37 s | 2.58 s | 22,145 / 1,122 / 1,225 (10% not completed) | no, p99 over 1 s |
| 350/s | after | 0 | 102 ms | 738 ms | 1.17 s | 2.02 s | 22,102 / 1,062 / 1,332 (10% not completed) | no, p99 over 1 s |
| 400/s | before | 0 | 243 ms | 1.56 s | 2.02 s | 3.59 s | 21,768 / 2,736 / 3,493 (22% not completed) | no, p99 over 1 s |
| 400/s | before | 0 | 300 ms | 1.53 s | 1.96 s | 2.95 s | 21,566 / 2,835 / 3,587 (23% not completed) | no, p99 over 1 s |
| 400/s | before | 0 | 159 ms | 1.43 s | 1.88 s | 2.82 s | 23,516 / 2,090 / 2,380 (16% not completed) | no, p99 over 1 s |
| 400/s | after | 0 | 134 ms | 1.09 s | 1.46 s | 2.20 s | 24,760 / 1,079 / 2,009 (11% not completed) | no, p99 over 1 s |
| 400/s | after | 0 | 207 ms | 1.45 s | 1.91 s | 2.85 s | 23,644 / 2,027 / 2,327 (16% not completed) | no, p99 over 1 s |
| 400/s | after | 0 | 175 ms | 1.17 s | 1.54 s | 2.33 s | 24,485 / 1,388 / 2,118 (13% not completed) | no, p99 over 1 s |

The clean ceiling is 250/s on both sides. Every run at 200/s and 250/s was clean, and no run at 300/s or above
was. No transfer failed at any rate. What passes 1 s first is the tail.

### Per transfer

The ranges are over every timed run at that rate. The statement figures come from the diagnostic runs'
`pg_stat_statements`.

| Per transfer | Before, 200/s | After, 200/s | Before, 400/s | After, 400/s |
|---|---:|---:|---:|---:|
| Journal appends (one fence check each, on `lark_journal_fenced`) | 5.98–5.99 | 4.36–4.39 | 5.96–5.99 | 4.10–4.12 |
| Journal events written, from start to settled | 5.98–5.99 | 5.53–5.54 | 5.97–5.99 | 5.68–5.69 |
| Index scans on `lark_journal` | 6.61–6.64 | 4.95–5.00 | 6.28–6.32 | 4.43–4.46 |
| Postgres commits, db-0 (journal and read models) | 3.50–3.64 | 3.07–3.13 | 1.57–1.87 | 1.69–1.88 |
| Postgres commits, db-1 (journal only) | 3.19–3.31 | 2.78–2.84 | 1.60–1.78 | 1.60–1.76 |
| WAL written, both databases | 6.88–6.89 KiB | 6.35 KiB | 6.83–6.84 KiB | 6.50–6.53 KiB |
| Read-model rows (`statement_line`, `account_balance`, `transfer_status`) | 5.22–5.26 | 5.22 | 5.30–5.36 | 5.23–5.30 |
| `bank.transfer.steps` (`SourceDebited` heard, `DestinationCredited`) | 1.96–1.98 | 1.95–1.97 | 1.96–1.98 | 1.95–1.96 |

| Per transfer, diagnostic run at 400/s | Before | After |
|---|---:|---:|
| `insert into lark_journal`: calls, events per call | 5.97, 1.00 | 4.10, 1.38 |
| … its statement time | 3.18 ms | 1.53 ms |
| `select … from lark_journal where kind = ? and id = ?`, a new entity's recovery read: calls, time | 1.00, 0.18 ms | 1.00, 0.14 ms |
| The projections' reads of the journal by slice and ordering: calls, time | 0.28, 1.18 ms | 0.29, 0.68 ms |
| Read-model upserts and inserts, one row a statement: calls, time | 5.45, 1.50 ms | 5.29, 0.82 ms |
| The 40 most-called statements, both databases: time | 6.39 ms | 3.34 ms |
| Journal appends per transaction on db-1 | 5.2 | 2.9 |

Each append is one statement. Along with its insert, it checks the slice's fence on `lark_journal_fenced` and, past
an entity's first event, that the previous `seq_nr` is there; those checks are the index scans above. Before, every
append writes one event. After, the `LegsClosed` an account holds rides in its next append, so the appends a transfer
costs fall to 4.1. The transfer's own two (`Requested`, `DestinationCredited`) and the account's `Debited` and
`Credited` are spec 0031's four on the path. The events written by the time the books settle fall from 6.0 to 5.5–5.7,
because the closes not yet ridden along are still held in memory. Commits do not fall at 400/s. Group commit puts
fewer appends in each transaction (2.9 against 5.2 on db-1) and commits as often.

`bank.transfer.steps` counts the saga's steps, not the journal's appends: about two a transfer on both sides.

## What is larger than the noise

The noise is the spread between runs of one side. A difference counts where every run of one side is better than
every run of the other, by more than that spread.

- **Appends and the work in Postgres.** Each run's counters vary by under 1%, so these are far outside the noise:
  - journal appends a transfer, 4.10–4.12 against 5.96–5.99 at 400/s, and 4.36–4.39 against 5.98–5.99 at 200/s;
  - index scans on the journal a transfer, 4.4–5.0 against 6.3–6.6;
  - WAL a transfer, 6.35–6.53 KiB against 6.83–6.89 KiB.

  In the diagnostic runs, Postgres's statement time a transfer halves: 3.34 ms against 6.39 ms.
- **Nothing in latency.**
  - At 400/s after has the lower p50, p95, p99 and max in most runs, and fewer transfers answered before they
    complete (11–16% against 16–23%), but the runs overlap or sit inside the spread.
  - At 300/s every after run has a higher p99 (1.38–1.87 s against 1.13–1.16 s), but inside after's own spread
    of 0.49 s.
  - `spread`, `hot`, `transfers` at 200/s and `payroll` at 20/s overlap on every percentile.
- **The ceiling.** 250/s on both sides.
- **Chaos and payroll at 2/s** ran once each, so there is no noise to judge them by.

## Where the time goes on after

From the diagnostic run of `transfers` at 400/s, with before beside it.

**CPU, by container.** The mean of `docker stats` over the run, where 400% is the whole machine:

| Container | Before | After |
|---|---:|---:|
| bank-1, bank-2, bank-3 | 63%, 64%, 75% (201%) | 77%, 59%, 75% (210%) |
| postgres (db-0: journal and read models) | 54% | 49% |
| postgres-2 (db-1: journal) | 38% | 34% |
| load (Proofload) | 31% | 41% |
| kafka | 12% | 9% |
| apicurio, its database, the issuer, the check | 8% | 6% |
| **Total** | **344%** | **349%** |

At 400 transfers a second the three nodes use about 5.3 ms of CPU a transfer, and the two Postgres about 2.1 ms. The
machine runs at seven eighths of its four cores on both sides. After moves CPU from Postgres (92% to 83%) to the
nodes and the load.

**Postgres's busy backends.** The `pg_stat_activity` backends that were not idle, sampled about once a second, as a
share of all such samples on both databases:

| What the backend was doing | Before | After |
|---|---:|---:|
| In a transaction, waiting on the bank, after an `insert into lark_journal` | 39% | 41% |
| In a transaction, waiting on the bank, after a read-model statement | 14% | 14% |
| Running `insert into lark_journal` on CPU | 16% | 9% |
| Committing, waiting on the WAL (`WALWrite`, `WalSync`) | 10% | 15% |
| Running a projection's read or a recovery read on CPU | 7% | 6% |
| Running a read-model statement on CPU | 6% | 5% |
| Everything else | 8% | 11% |

So on after:

- **Over half of Postgres's busy time is a transaction kept open waiting for the bank.** Most of it follows a
  journal append: the next append of the group, or its commit, has not arrived yet. A group's appends go one
  statement at a time, each a round trip, from JVMs that share four nearly full cores.
- **The disk is a sixth.** Commits waiting on the WAL are 15% of the busy samples, and the inserts themselves 9%.
- **The bank's own CPU is the largest share of the machine.** Its JVMs use 210% of 400%, against 83% for both
  Postgres together.

Per transfer, the work still on the path is:

- 4.1 appends, each its own round trip inside a group's transaction, with a fence check and a previous-event check;
- one recovery read of the transfer's id, which comes back empty because the entity is new (spec 0031's
  `fresh-entity`);
- 5.3 read-model statements of one row each;
- about 5.3 ms of the nodes' CPU.
