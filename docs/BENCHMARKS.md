# Benchmarks

Measured numbers only. Each table states the date, commit, hardware, OS, command and configuration. Where a number
was not measured, it says so. Expensive benchmarks run by hand, outside PR CI.

## Environment

All runs below come from one session on 2026-10-06:

- **Hardware:** cloud container, 4 vCPUs (Intel Xeon, 2.10 GHz), 16 GB RAM.
- **OS:** Linux 6.18.44.
- **Software:** Java 25, PostgreSQL 18.6 (Docker, `postgres:18.6-alpine`).
- **Stack:** the control plane and three workers run as host JVM processes, the same images' jars as compose:
  - `worker-cpu`: 4 slots;
  - `worker-mixed`: 4 slots;
  - `worker-accel`: 2 slots.
- **Configuration:** scheduler policy `BIN_PACKING`, one scheduling loop, default timings (500 ms idle delay and claim
  interval, 2 s claim wait).
- **Profiling:** `pg_stat_statements` was loaded for both builds alike.
- **Isolation:** the machine is shared with nothing else in the session, but it is a cloud VM. Run-to-run spread is
  shown as a range.

## End to end: time to start and throughput

**Driver:** `benchmarks/e2e/bench.py`.
- It submits through the public API, waits until every job is final, then reads the timestamps the database clock
  wrote (created, assigned, started, finished), so the driver's own latency is not counted.
- Only first attempts are counted. Every run had zero retries.
- Each value is the **median of three runs**, with the range in parentheses.

**Builds compared:** baseline `2ac6925` (main before Phase 13) against final `55bbfc2`.

### Steady: 5 jobs/s for 60 s, 200 ms each (300 jobs, below capacity)

`python3 benchmarks/e2e/bench.py steady --rate 5 --seconds 60 --duration-ms 200`

| Metric | Baseline `2ac6925` | Final `55bbfc2` |
|---|---|---|
| Time to start p50 | 526 ms (461–549) | **11 ms** (11–15) |
| Time to start p95 | 827 ms (698–890) | **18 ms** (16–23) |
| Time to start p99 | 912 ms (721–956) | **23 ms** (19–28) |
| of which: placement wait p50 | 248 ms (248–259) | 4 ms (4–5) |
| of which: claim wait p50 | 265 ms (208–268) | 7 ms (7–10) |
| Mean placing cycle | 12.6 ms (12.6–16.7) | 7.8 ms (7.5–10.4) |
| Throughput | 5.0 jobs/s | 5.0 jobs/s (the offered load) |

### Burst: 1,000 jobs at once, 100 ms each, on 10 slots (ceiling 100 jobs/s)

`python3 benchmarks/e2e/bench.py burst --jobs 1000 --duration-ms 100`

| Metric | Baseline `2ac6925` | Final `55bbfc2` |
|---|---|---|
| Throughput | 15.3 jobs/s (15.0–15.6) | **71.7 jobs/s** (61.0–71.8) |
| Makespan | 65.6 s (64.1–66.8) | **13.9 s** (13.9–16.4) |
| Time to start p50 | 32.4 s (31.5–33.3) | 6.6 s (6.4–8.0) |
| Time to start p95 | 60.0 s (59.7–61.2) | 11.6 s (11.5–13.2) |
| Claim wait p50 | 287 ms (285–287) | 14 ms (14–20) |
| Placing cycles | 135 (131–138) | 645 (564–646) |
| Mean placing cycle | 58 ms (58–68) | 15.2 ms (15.0–20.5) |

In a burst, time to start is mostly queueing behind the backlog. It falls because throughput rises.

### What changed, and what each step bought

The steps were measured one at a time, as single runs on intermediate builds. They are good for comparing steps, not
for absolute numbers. ENGINEERING_LOG has the reasoning behind each.

| Step | Commit | Steady time to start p50 | Burst throughput |
|---|---|---|---|
| Baseline | `2ac6925` | 455 ms | 15.2 jobs/s |
| Wake an idle scheduler when work becomes placeable; wake the worker's intake when a slot frees | `25ae2e0` | 274 ms | 15.4 jobs/s |
| Claims wait for the placement instead of polling | `6d88c03` | 14 ms | 42.9 jobs/s |
| A cycle's waiting verdicts in one statement | `8d4a95f` | 12 ms | 50.6 jobs/s |
| Release capacity last in the report transaction (reverted: no effect) | – | – | 50.8 jobs/s |

The last single run (50.8 jobs/s) and the final repeated matrix (median 71.7 jobs/s) differ more than the code
between them can explain. The two commits after `8d4a95f` change the `PRIORITY` window and the simulator, and neither
is on this path under `BIN_PACKING`. The difference is **not explained**. The repeated matrix is the number to quote.

## Scheduler hot path (PostgreSQL)

`pg_stat_statements` during a burst on `8d4a95f`, before and after batching the waiting verdicts:

| Statement | Before: calls / mean | After: calls / mean |
|---|---|---|
| Report's capacity release (`UPDATE workers ... - $1`), mostly lock wait | 1,000 / 29.1 ms | 1,000 / 7.3 ms |
| Waiting verdict (`UPDATE jobs SET scheduling_outcome ...`) | 66,905 / 0.02 ms | 1,719 / 0.41 ms (one per cycle) |

One commit costs 0.33 ms on this storage (`pgbench`, single-row insert, `synchronous_commit = on`), so the remaining
time was lock waiting, not I/O.

**Window queries.** These were run with 10,000 runnable jobs, inside a rolled-back transaction after `ANALYZE`, using
`EXPLAIN (ANALYZE, TIMING OFF)`:

| Window | Before | After |
|---|---|---|
| FIFO (`available_at, id`) | 0.25 ms (index scan) | unchanged |
| PRIORITY (`priority DESC, available_at, id`) | 10.2 ms (sort of the whole backlog) | 0.15–0.24 ms (V11 index) |

## Console overview at a million jobs

The overview's four queries, run with `psql \timing` in the order the repository issues them, as the operator (no
project filter). Three runs each. Same hardware as above, 2026-10-06.

- **Data:** 1,000,000 finished jobs spread over 30 days, each with one attempt, plus 2,000 queued jobs.
- **Database:** a scratch PostgreSQL 18.6 container with migrations V1–V11, then V12 applied, then `ANALYZE`.

| Query | Before (`64520bb`) | After (`cf16a02`, V12) |
|---|---|---|
| Counts by status | 81–110 ms (all statuses, parallel seq scan) | 4.6–6.1 ms (unfinished statuses only) |
| Finished in the last hour | 72–87 ms | 1.6 ms |
| Retries in the last hour | 50–61 ms | 3.0–3.8 ms |
| Time-to-start p95, last 15 minutes | 56–65 ms | 2.2–3.1 ms |
| **Whole overview** | **259–318 ms** | **11.5–14.6 ms** |

The V12 indexes take 21 MB, 8 KB and 21 MB at this size. The console requests an overview at most once a second per
open tab, while events flow.

## Simulation

`POST /api/v1/simulations` with `{"scenario":"BURST","seed":7,"jobCount":20000,"policies":[...]}`, timed with
`curl -w %{time_total}`. Three runs each, same stack.

| Policies | Baseline `8d4a95f` | Final `03ef015` |
|---|---|---|
| FIFO, FAIR_SHARE | 11.0–13.0 s (2 runs) | 4.3–5.6 s |
| All six | 30.2–32.9 s | 11.4–13.0 s |

All six result hashes and metrics were identical between the two builds. `SimulationGoldenTest` pins the hashes of
all 8 scenarios × 6 policies (seed 42, 1,000 jobs).

## Not measured

- Multiple control-plane instances. The scheduler and claim wake-ups are in-process; another instance falls back to
  its 500 ms recheck. Two instances were run live for correctness (ENGINEERING_LOG, 2026-10-06), not timed.
- Throughput beyond 10 slots, and jobs shorter than 100 ms.
- The compose stack inside Docker networking. These runs used host processes against PostgreSQL in Docker.
- A JMH micro-benchmark of the planner. The JFR profile and the end-to-end timing were enough to choose and verify
  the change.
