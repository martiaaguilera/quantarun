# Simulation and replay

QuantaRun can replay a synthetic workload against every scheduling policy and compare the outcomes. Semantics are in
`SPEC.md` §11; this document explains the engine, the metrics and what the results show. The code is the
`simulation` module.

## How it works

A **scenario** turns a seed and a job count into a **trace**: the fleet, the projects, and every job with its arrival
time, resource demand, labels, priority, optional deadline, duration and injected failures, plus worker outages. One
`SplittableRandom` drives every draw in a fixed order, so the same seed always gives the same trace.

The **simulator** is a discrete-event engine. A priority queue holds timestamped events (arrival, attempt finished,
retry ready, worker down, worker up, lease expired), and ties break on a sequence number. Simulated time only: no clock,
no threads, no sleeping. After each instant's events it runs scheduling cycles, as the live loop does, while they place
work.

What runs is **the production decision code**:
- `PlacementPlanner` places jobs. The window it sees is selected the way the live cycle selects it (oldest first,
  priority, earliest deadline, or the round-robin window for FAIR_SHARE), 200 jobs per cycle.
- `RetryPolicy` decides retries, with the same backoff, jitter (seeded), Retry-After and budget.
- A crashed worker's attempts keep their reservations until their 15 s leases expire, then retry as WORKER_LOST.

What it does **not** model: the database (lock waits, query latency), heartbeats and network delay. Those are measured
separately in benchmarks (Phase 13). A simulated result is therefore a statement about the policies, not about
PostgreSQL.

## Scenarios

All run on the demo fleet: `worker-cpu` (4 CPU, 8 GiB, 4 slots), `worker-mixed` (8 CPU, 16 GiB, 1 accelerator,
4 slots), `worker-accel` (8 CPU, 32 GiB, 2 accelerators, 2 slots), ten slots in all.

| Scenario | Workload | What it shows |
|---|---|---|
| `STEADY` | Poisson arrivals at ~70% of slot capacity, 3 equal projects | The baseline: policies barely differ when there is room |
| `BURST` | Every job arrives in the first 5 s | How a backlog drains |
| `MIXED_RESOURCES` | CPU-heavy, memory-heavy (12–16 GiB) and accelerator jobs | Packing versus spreading; strict priority |
| `ACCELERATOR_SCARCE` | Half the jobs need 1 or 2 of the fleet's 3 accelerators | Who gets the scarce resource |
| `NOISY_NEIGHBOR` | One project submits 80% of the jobs in 10 s, two others a steady trickle | Fairness between tenants |
| `DEADLINE_HEAVY` | 80% of jobs with a tight deadline, ~90% load | Deadline misses |
| `WORKER_FAILURE` | Steady load; the mixed worker crashes a quarter of the way in, back 60 s later | Recovery through lease expiry |
| `RATE_LIMIT` | 40% of first attempts rate-limited with a Retry-After, 5% failing twice | Retries and Retry-After |

## Metrics

| Metric | Definition |
|---|---|
| Queue wait | Arrival to first start, per job that started; mean and nearest-rank p50/p95/p99 (always an observed value) |
| Completion latency | Arrival to success, per succeeded job |
| Throughput | Succeeded jobs per simulated minute of makespan |
| Deadline miss rate | Jobs with a deadline that did not succeed by it, over jobs with a deadline |
| Utilisation | Time-weighted reserved fraction of the fleet's CPU, memory, accelerators and slots |
| Starvation | The longest wait for a first start, counting jobs that never started up to the end |
| Fairness | Jain's index over each project's received / entitled service, while at least two projects contend. Entitlement is weighted max-min fairness: the fleet is split by weight, and a project wanting less than its split gets what it wants while the rest is split again. 1.0 means every project got its fair share whenever it wanted it |
| Per-project wait | Each project's queue-wait distribution: where a policy puts the waiting |
| Planning time | Wall-clock time of the planner calls. Measured, so **not** reproducible and **not** in the hash |

**Determinism (I15).** Each policy's result carries a SHA-256 over its metrics and every job's outcome (status,
attempts, first start, finish), in trace order. The same scenario, seed, job count and policy always produce the same
hash: `SimulatorTest.sameSeed_givesAnIdenticalResult_forEveryPolicy` checks all 8 scenarios × 6 policies, and
`SimulationApiTest.theSameRequest_givesTheSameHashes` checks the same through the API and the database.

## API

`POST /api/v1/simulations` with `{"scenario": "NOISY_NEIGHBOR", "seed": 42, "jobCount": 2000, "policies": [...]}`
(policies default to all six; at most 20,000 jobs). The run is synchronous and stored; `GET /api/v1/simulations/{id}`
and `GET /api/v1/simulations` read it back. A project sees the runs its keys requested, an operator sees all.
`GET /api/v1/simulations/scenarios` lists the scenarios.

## Results

Measured on 2026-10-02: commit `dc3d9f1` plus this branch, PostgreSQL 18.6 in Docker, 4 vCPU Xeon @ 2.1 GHz cloud
container, Java 25. Command: `POST /api/v1/simulations {"scenario": <each>, "seed": 42, "jobCount": 2000}`. Every
metric except planning time is reproducible from those inputs; planning time depends on the machine.

#### STEADY

| Policy | Wait p50 / p95 / p99 (s) | Latency p95 (s) | Starvation (s) | Deadline miss | Fairness | CPU / Acc / Slot util | Done / attempts | Planning mean / p99 (µs) |
|---|---|---|---|---|---|---|---|---|
| FIFO | 0.0 / 4.5 / 6.7 | 16.1 | 11.2 | – | 1.000 | 0.22 / 0.00 / 0.72 | 2000 / 2000 | 34 / 228 |
| PRIORITY | 0.0 / 3.8 / 10.4 | 15.5 | 37.9 | – | 1.000 | 0.22 / 0.00 / 0.72 | 2000 / 2000 | 10 / 55 |
| LEAST_LOADED | 0.0 / 4.5 / 6.7 | 16.1 | 11.2 | – | 1.000 | 0.22 / 0.00 / 0.72 | 2000 / 2000 | 15 / 84 |
| BIN_PACKING | 0.0 / 4.5 / 6.7 | 16.1 | 11.2 | – | 1.000 | 0.22 / 0.00 / 0.72 | 2000 / 2000 | 10 / 55 |
| FAIR_SHARE | 0.0 / 4.2 / 8.5 | 16.0 | 13.9 | – | 1.000 | 0.22 / 0.00 / 0.72 | 2000 / 2000 | 22 / 142 |
| DEADLINE | 0.0 / 3.8 / 10.4 | 15.5 | 37.9 | – | 1.000 | 0.22 / 0.00 / 0.72 | 2000 / 2000 | 9 / 51 |

#### BURST

| Policy | Wait p50 / p95 / p99 (s) | Latency p95 (s) | Starvation (s) | Deadline miss | Fairness | CPU / Acc / Slot util | Done / attempts | Planning mean / p99 (µs) |
|---|---|---|---|---|---|---|---|---|
| FIFO | 1074.9 / 2025.8 / 2114.7 | 2034.9 | 2139.3 | – | 1.000 | 0.31 / 0.00 / 1.00 | 2000 / 2000 | 154 / 551 |
| PRIORITY | 1083.6 / 2030.8 / 2115.9 | 2038.6 | 2136.8 | – | 1.000 | 0.31 / 0.00 / 0.99 | 2000 / 2000 | 122 / 334 |
| LEAST_LOADED | 1074.9 / 2025.8 / 2114.7 | 2034.9 | 2139.3 | – | 1.000 | 0.31 / 0.00 / 1.00 | 2000 / 2000 | 127 / 342 |
| BIN_PACKING | 1074.9 / 2025.8 / 2114.7 | 2034.9 | 2139.3 | – | 1.000 | 0.31 / 0.00 / 1.00 | 2000 / 2000 | 111 / 459 |
| FAIR_SHARE | 1077.6 / 2023.2 / 2113.1 | 2035.5 | 2138.8 | – | 1.000 | 0.31 / 0.00 / 1.00 | 2000 / 2000 | 158 / 423 |
| DEADLINE | 1083.6 / 2030.8 / 2115.9 | 2038.6 | 2136.8 | – | 1.000 | 0.31 / 0.00 / 0.99 | 2000 / 2000 | 101 / 425 |

#### MIXED_RESOURCES

| Policy | Wait p50 / p95 / p99 (s) | Latency p95 (s) | Starvation (s) | Deadline miss | Fairness | CPU / Acc / Slot util | Done / attempts | Planning mean / p99 (µs) |
|---|---|---|---|---|---|---|---|---|
| FIFO | 6.0 / 27.9 / 38.2 | 39.7 | 52.9 | – | 1.000 | 0.55 / 0.27 / 0.77 | 2000 / 2000 | 14 / 88 |
| PRIORITY | 2.0 / 123.0 / 1402.1 | 133.7 | 2951.1 | – | 1.000 | 0.53 / 0.26 / 0.74 | 2000 / 2000 | 26 / 94 |
| LEAST_LOADED | 6.1 / 27.7 / 39.0 | 40.6 | 48.4 | – | 1.000 | 0.55 / 0.27 / 0.77 | 2000 / 2000 | 10 / 41 |
| BIN_PACKING | 6.2 / 29.1 / 41.8 | 41.2 | 59.8 | – | 1.000 | 0.55 / 0.27 / 0.77 | 2000 / 2000 | 7 / 30 |
| FAIR_SHARE | 5.3 / 37.9 / 65.0 | 50.5 | 92.8 | – | 1.000 | 0.55 / 0.27 / 0.77 | 2000 / 2000 | 7 / 40 |
| DEADLINE | 2.5 / 96.7 / 1322.7 | 109.8 | 2519.5 | – | 1.000 | 0.53 / 0.26 / 0.73 | 2000 / 2000 | 17 / 86 |

#### ACCELERATOR_SCARCE

| Policy | Wait p50 / p95 / p99 (s) | Latency p95 (s) | Starvation (s) | Deadline miss | Fairness | CPU / Acc / Slot util | Done / attempts | Planning mean / p99 (µs) |
|---|---|---|---|---|---|---|---|---|
| FIFO | 135.6 / 2560.9 / 2657.6 | 2569.4 | 2686.9 | – | 0.876 | 0.19 / 0.86 / 0.42 | 2000 / 2000 | 146 / 482 |
| PRIORITY | 1.9 / 2435.8 / 3664.6 | 2443.9 | 4401.9 | – | 0.860 | 0.20 / 0.87 / 0.43 | 2000 / 2000 | 138 / 479 |
| LEAST_LOADED | 135.6 / 2560.9 / 2657.6 | 2569.4 | 2686.9 | – | 0.876 | 0.19 / 0.86 / 0.42 | 2000 / 2000 | 147 / 471 |
| BIN_PACKING | 135.6 / 2560.9 / 2657.6 | 2569.4 | 2686.9 | – | 0.876 | 0.19 / 0.86 / 0.42 | 2000 / 2000 | 118 / 423 |
| FAIR_SHARE | 8.8 / 1257.0 / 2192.9 | 1266.5 | 2207.1 | – | 0.969 | 0.21 / 0.92 / 0.45 | 2000 / 2000 | 124 / 496 |
| DEADLINE | 1.9 / 2435.8 / 3664.6 | 2443.9 | 4401.9 | – | 0.860 | 0.20 / 0.87 / 0.43 | 2000 / 2000 | 162 / 489 |

#### NOISY_NEIGHBOR

| Policy | Wait p50 / p95 / p99 (s) | Latency p95 (s) | Starvation (s) | Deadline miss | Fairness | CPU / Acc / Slot util | Done / attempts | Planning mean / p99 (µs) |
|---|---|---|---|---|---|---|---|---|
| FIFO | 607.3 / 1195.1 / 1244.7 | 1201.3 | 1258.0 | – | 0.462 | 0.28 / 0.00 / 0.89 | 2000 / 2000 | 128 / 339 |
| PRIORITY | 607.3 / 1195.1 / 1244.7 | 1201.3 | 1258.0 | – | 0.462 | 0.28 / 0.00 / 0.89 | 2000 / 2000 | 95 / 391 |
| LEAST_LOADED | 607.3 / 1195.1 / 1244.7 | 1201.3 | 1258.0 | – | 0.462 | 0.28 / 0.00 / 0.89 | 2000 / 2000 | 95 / 290 |
| BIN_PACKING | 607.3 / 1195.1 / 1244.7 | 1201.3 | 1258.0 | – | 0.462 | 0.28 / 0.00 / 0.89 | 2000 / 2000 | 109 / 303 |
| FAIR_SHARE | 564.2 / 1440.2 / 1514.4 | 1448.1 | 1533.7 | – | 0.991 | 0.28 / 0.00 / 0.89 | 2000 / 2000 | 116 / 322 |
| DEADLINE | 607.3 / 1195.1 / 1244.7 | 1201.3 | 1258.0 | – | 0.462 | 0.28 / 0.00 / 0.89 | 2000 / 2000 | 82 / 308 |

Per-tenant queue wait p95 (s):

| Policy | tenant-a (noisy) | tenant-b | tenant-c |
|---|---|---|---|
| FIFO | 1197.1 | 1174.7 | 1178.9 |
| PRIORITY | 1197.1 | 1174.7 | 1178.9 |
| LEAST_LOADED | 1197.1 | 1174.7 | 1178.9 |
| BIN_PACKING | 1197.1 | 1174.7 | 1178.9 |
| FAIR_SHARE | 1461.0 | 3.2 | 2.8 |
| DEADLINE | 1197.1 | 1174.7 | 1178.9 |

#### DEADLINE_HEAVY

| Policy | Wait p50 / p95 / p99 (s) | Latency p95 (s) | Starvation (s) | Deadline miss | Fairness | CPU / Acc / Slot util | Done / attempts | Planning mean / p99 (µs) |
|---|---|---|---|---|---|---|---|---|
| FIFO | 2.7 / 13.3 / 15.8 | 23.9 | 17.1 | 6.7% | 1.000 | 0.29 / 0.00 / 0.91 | 2000 / 2000 | 7 / 28 |
| PRIORITY | 0.8 / 18.9 / 68.5 | 29.3 | 100.4 | 6.7% | 1.000 | 0.29 / 0.00 / 0.91 | 2000 / 2000 | 6 / 30 |
| LEAST_LOADED | 2.7 / 13.3 / 15.8 | 23.9 | 17.1 | 6.7% | 1.000 | 0.29 / 0.00 / 0.91 | 2000 / 2000 | 5 / 24 |
| BIN_PACKING | 2.7 / 13.3 / 15.8 | 23.9 | 17.1 | 6.7% | 1.000 | 0.29 / 0.00 / 0.91 | 2000 / 2000 | 5 / 22 |
| FAIR_SHARE | 2.8 / 13.5 / 16.1 | 23.9 | 17.9 | 6.4% | 1.000 | 0.29 / 0.00 / 0.91 | 2000 / 2000 | 7 / 30 |
| DEADLINE | 0.7 / 14.7 / 59.0 | 26.1 | 249.0 | 0.0% | 1.000 | 0.29 / 0.00 / 0.91 | 2000 / 2000 | 8 / 51 |

#### WORKER_FAILURE

| Policy | Wait p50 / p95 / p99 (s) | Latency p95 (s) | Starvation (s) | Deadline miss | Fairness | CPU / Acc / Slot util | Done / attempts | Planning mean / p99 (µs) |
|---|---|---|---|---|---|---|---|---|
| FIFO | 0.0 / 2.6 / 21.2 | 14.9 | 26.5 | – | 1.000 | 0.18 / 0.00 / 0.57 | 2000 / 2003 | 5 / 29 |
| PRIORITY | 0.0 / 1.1 / 31.3 | 14.8 | 66.9 | – | 1.000 | 0.18 / 0.00 / 0.57 | 2000 / 2003 | 5 / 23 |
| LEAST_LOADED | 0.0 / 2.6 / 22.4 | 14.9 | 27.7 | – | 1.000 | 0.18 / 0.00 / 0.57 | 2000 / 2004 | 6 / 20 |
| BIN_PACKING | 0.0 / 2.6 / 21.6 | 14.9 | 26.9 | – | 1.000 | 0.18 / 0.00 / 0.57 | 2000 / 2004 | 3 / 16 |
| FAIR_SHARE | 0.0 / 2.7 / 22.4 | 14.9 | 28.4 | – | 1.000 | 0.18 / 0.00 / 0.57 | 2000 / 2004 | 5 / 22 |
| DEADLINE | 0.0 / 1.2 / 34.5 | 14.8 | 67.6 | – | 1.000 | 0.18 / 0.00 / 0.57 | 2000 / 2004 | 4 / 23 |

#### RATE_LIMIT

| Policy | Wait p50 / p95 / p99 (s) | Latency p95 (s) | Starvation (s) | Deadline miss | Fairness | CPU / Acc / Slot util | Done / attempts | Planning mean / p99 (µs) |
|---|---|---|---|---|---|---|---|---|
| FIFO | 0.0 / 2.6 / 4.6 | 28.3 | 6.9 | – | 1.000 | 0.22 / 0.00 / 0.72 | 2000 / 2986 | 2 / 9 |
| PRIORITY | 0.0 / 2.4 / 7.4 | 28.2 | 14.3 | – | 1.000 | 0.22 / 0.00 / 0.72 | 2000 / 2986 | 2 / 8 |
| LEAST_LOADED | 0.0 / 2.6 / 4.6 | 28.3 | 6.9 | – | 1.000 | 0.22 / 0.00 / 0.72 | 2000 / 2986 | 2 / 6 |
| BIN_PACKING | 0.0 / 2.6 / 4.6 | 28.3 | 6.9 | – | 1.000 | 0.22 / 0.00 / 0.72 | 2000 / 2986 | 2 / 7 |
| FAIR_SHARE | 0.0 / 2.4 / 4.7 | 28.3 | 9.3 | – | 1.000 | 0.22 / 0.00 / 0.72 | 2000 / 2986 | 4 / 17 |
| DEADLINE | 0.0 / 2.4 / 7.4 | 28.2 | 14.3 | – | 1.000 | 0.22 / 0.00 / 0.72 | 2000 / 2986 | 2 / 8 |

### What the numbers say

- **Fairness is a transfer, not a gift.** On `NOISY_NEIGHBOR`, FAIR_SHARE lifts Jain's index from 0.46 to 0.99:
  the quiet tenants' p95 wait drops from ~1,175 s to ~3 s. The noisy tenant's rises from 1,197 s to 1,461 s, and so
  does the overall p95. The waiting is moved onto the tenant that caused it, which is the point.
- **EDF meets every deadline it can, and makes others pay.** On `DEADLINE_HEAVY`, DEADLINE misses 0% against 6.7%
  for the others, and undated jobs pay: starvation 249 s against 17 s for FIFO.
- **Strict priority starves.** On `MIXED_RESOURCES`, PRIORITY halves the median wait (2.0 s against 6.0 s), but its
  p99 is 1,402 s and one job waited 2,951 s.
- **Where resources are scarce, interleaving helps.** On `ACCELERATOR_SCARCE`, FAIR_SHARE alternates the
  accelerator project and the CPU project instead of letting accelerator jobs block the window. It halves the p95 wait
  (1,257 s against 2,561 s) and has the highest accelerator utilisation (0.92).
- **With room to spare, policy hardly matters.** On `STEADY` and `BURST`, FIFO, LEAST_LOADED and BIN_PACKING give
  identical waits: small jobs fit anywhere, so which worker is chosen does not change when they run. On
  `WORKER_FAILURE` they differ only in the tail (p99 21.2, 22.4 and 21.6 s), depending on how much work was on the
  worker that crashed. No policy is best everywhere: that is the reason to simulate before choosing.

## Limits

- Attempts run for their trace duration, but the planner charges fair-share service with the same fixed estimate as
  the live scheduler (`SCHEDULER.md`). The simulation therefore reproduces the live policy, including its blind spot
  for long jobs.
- Runs are synchronous. The largest allowed request (`BURST`, 20,000 jobs) took 6.0 s for FIFO and 7.7 s for
  FAIR_SHARE, so about 45 s for all six policies. That is bounded but long for one HTTP request. Phase 13 will profile
  the planner (most of the time goes to building decision records for jobs that cannot be placed).
