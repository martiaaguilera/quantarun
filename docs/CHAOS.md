# Chaos experiments

QuantaRun can inject eight predefined faults into its own workers and show how the system detects them and recovers.
This is the chaos lab from the brief. It is safe to expose because of what it cannot do: there is no fault outside the
catalogue, no parameter outside fixed bounds, and no way to reach anything except a QuantaRun worker process that
agreed to it.

## Safety model

- **Closed catalogue.** A fault is a value of `WorkerProtocol.ChaosFault`. The request carries the fault's name and
  up to five bounded integers, never a command, a path, a URL or code. The same bounds are checked three times: by
  the API (`FaultCatalog`), by `CHECK` constraints on `chaos_experiments` (V9), and again by the worker
  (`ChaosInjector`), which does not trust the wire either.
- **Double opt-in.** The control plane creates experiments only with `quantarun.chaos.enabled=true`; otherwise the
  API answers 403 `CHAOS_DISABLED` and heartbeats carry no faults. Each worker applies faults only when started with
  `quantarun.worker.chaos-enabled=true`; otherwise it logs and ignores them. Both default to off. In compose, one
  switch turns on both: `QUANTARUN_CHAOS_ENABLED=true docker compose up`.
- **Operators only.** A fault hits a shared worker, whoever's jobs run there, so only the admin token may create,
  list or cancel experiments. A project key gets 403.
- **Nothing reaches into a worker.** The control plane never opens a connection to a worker (ADR-0005). A worker
  receives a fault in its own heartbeat response and acts on its own process only (ADR-0007). `KILL_WORKER` halts the
  worker's JVM with `Runtime.halt`; there is no host command anywhere.
- **Bounded in time.** An experiment is delivered within 30 s (`quantarun.chaos.delivery-window`) or it expires; a
  fault never fires long after the operator asked for it. A worker holds at most 5 undelivered experiments
  (`max-pending-per-worker`). Every fault ends by itself: time-boxed faults within 2 minutes, counted ones after at
  most 20 hits.

## The faults

| Fault | Parameters (default) | What the worker does | What should happen |
|---|---|---|---|
| `KILL_WORKER` | `delayMs` 0–60,000 (0) | Halts its JVM after the delay: no deregistration, no reports | Leases expire; the reaper recovers the attempts as `WORKER_LOST`; jobs retry at once elsewhere; the registration is retired |
| `PAUSE_HEARTBEAT` | `durationMs` 1,000–120,000 (30,000) | Keeps executing but sends no heartbeats | Leases expire and jobs move; the paused worker's late reports are fenced off (409); when it resumes it finds its registration retired and joins again under a new id |
| `STOP_CLAIMING` | `durationMs` 1,000–120,000 (30,000) | Claims nothing, while still heartbeating | Work placed on it is released once it has gone unclaimed for the claim timeout (30 s) and its lease runs out, then placed elsewhere |
| `NETWORK_LATENCY` | `latencyMs` 1–5,000 (1,000), `durationMs` (30,000) | Delays every request to the control plane | Slower claims and reports; nothing is lost while the delay stays well under the lease |
| `STALL_ATTEMPTS` | `count` 1–20 (1) | The next attempts hang until their own timeout | TIMEOUT failures, retried with backoff |
| `PROVIDER_RATE_LIMITED` | `count` (1), `retryAfterMs` 0–60,000 (5,000) | The next mock-inference calls get HTTP 429 with a Retry-After | RATE_LIMITED; the retry waits at least the Retry-After |
| `PROVIDER_ERROR` | `count` (1) | The next mock-inference calls get HTTP 500 | TRANSIENT, retried with backoff |
| `PROVIDER_MALFORMED` | `count` (1) | The next mock-inference calls get a body that cannot be parsed | TRANSIENT, retried with backoff (a cut-off or proxied response usually succeeds when sent again) |

The provider faults hit the `mock-inference` workload, which stands in for an LLM provider. They are classified the
way the `http` workload classifies a real provider (FAILURE_SEMANTICS.md).

## API

All under `/api/v1/chaos`, admin token only.

- `GET /faults`: whether chaos is enabled, and the catalogue with each parameter's range and default.
- `POST /experiments`: `{"fault": ..., "workerId": ...}` or `{"fault": ..., "jobId": ...}` to aim at whichever worker
  holds that job's active attempt (409 `JOB_NOT_ON_A_WORKER` if none does), plus optional parameters. A parameter
  the fault does not take is refused (400), not ignored, so a typo cannot quietly run a different experiment.
- `GET /experiments`, `GET /experiments/{id}`: the latter with the recovery timeline.
- `POST /experiments/{id}/cancel`: only while it is still pending (409 `EXPERIMENT_NOT_PENDING` otherwise).

An experiment is `PENDING`, then `DELIVERED`, `EXPIRED` or `CANCELLED`. Delivery is a single conditional
`UPDATE ... WHERE status = 'PENDING' AND deliver_by > now() RETURNING`, so overlapping heartbeats of one worker deliver
each fault exactly once, and an expired one is never delivered. Delivery is at most once *to the worker*: if the
heartbeat response is lost on the way, the fault is recorded as delivered but never applied, and the timeline shows
no disruption. The per-worker limit is checked and the row inserted under a transaction-scoped advisory lock, so
concurrent requests cannot overshoot it.

## The recovery timeline

`GET /experiments/{id}` rebuilds the story from records the system keeps anyway. Nothing is recorded for chaos
specifically besides the experiment row.

- The experiment's own moments: created, delivered, the moment a delayed kill fires, expired or cancelled.
- Every job event, from delivery on, of each job whose attempt was on the target worker during the fault's window
  (the fault's duration or delay plus 2 minutes to settle; 10 minutes for counted faults), at most 50 jobs. A job the
  experiment was aimed at is always included.
- Later registrations of the same worker name, which is how a retired or restarted worker shows up again.

For each job, *detection* runs from the fault firing to the first lost or failed attempt, and *recovery* to the
first success after that. Two caveats:
- The timeline belongs to the target worker's window, not to the fault alone. Two experiments on the same worker at
  the same time share their disruptions.
- Event times are database `now()`, the start of the writing transaction, so two racing events can be out of order
  by a transaction's length (ENGINEERING_LOG, 2026-10-02). That is milliseconds; recoveries take seconds.

## Measured runs

Measured on 2026-10-04, branch `wip/phase-9` on main `04e321b`. The machine had 4 vCPU (Intel Xeon @ 2.80 GHz) and
15 GiB RAM. The stack was the control plane and three workers running as host JVM processes (`run-stack.sh`, with
the compose configuration and chaos enabled), against PostgreSQL 18.6 in Docker. Lease 15 s, heartbeat 3 s, claim
timeout 30 s, policy BIN_PACKING. One run each, so these are examples, not distributions.

| Experiment | What happened | Detection | Recovery |
|---|---|---|---|
| `KILL_WORKER` aimed at a running 10 s `delay` job, `delayMs` 4,000 ("kill the worker at 40 % progress") | Worker halted at the fault time; ATTEMPT_LOST when the lease expired; rescheduled on another worker 128 ms later; succeeded there | 14.8 s | 25.1 s |
| `PAUSE_HEARTBEAT` 30 s aimed at a running 20 s job | Attempt lost after the lease; rescheduled at once and succeeded elsewhere. The paused worker finished its copy and its report was rejected; when its heartbeats resumed it re-registered as a new member | 15.7 s | 36.0 s |
| Provider faults: 429 (Retry-After 3 s), 500 and malformed, one each on two workers; 6 `mock-inference` jobs | 6 classified failures (2 RATE_LIMITED, 4 TRANSIENT) for 6 injected faults; all 6 jobs succeeded. The rate-limited retries started 3.04 s and 3.35 s after the failure | – | ≤ 9.2 s after delivery |
| `STALL_ATTEMPTS` | The stalled attempt failed with TIMEOUT at its 3 s timeout; the retry succeeded | – | – |
| `STOP_CLAIMING` 120 s on all three workers | Before the fix, the assignment waited about 27 s, until claiming resumed; it was never released. After it: released after 42.6 s (claim timeout 30 s plus the rest of the lease) | – | – |
| `NETWORK_LATENCY` 2 s | Claim delayed 1.6 s; a 0.5 s job finished 2.52 s after it started (its report waited 2 s). Before the fix, reports skipped the latency | – | – |

Commands: `scratchpad/chaos-demo.sh` drives the API with curl. The equivalent requests are
`POST /api/v1/chaos/experiments` with the bodies above, followed by `GET /api/v1/chaos/experiments/{id}`.

## What chaos found

Two real defects, both fixed in this phase (ENGINEERING_LOG, 2026-10-04):

1. **A worker that heartbeats but never claims held its assignments forever.** Heartbeats renewed every unclaimed
   assignment, on the theory that the worker had not seen it yet. A worker whose intake is stuck while its
   heartbeat thread lives would hold placed work indefinitely. Unclaimed assignments are now renewed only for the
   claim timeout.
2. **Latency skipped the reports.** `LockSupport.parkNanos` returns at once when the thread holds an unpark permit,
   and the attempt threads did. The injector now sleeps.

## Not covered

- Faults inside the control plane or PostgreSQL (connection drops, slow queries). Those belong to the concurrency
  hardening and Testcontainers-based tests (Phase 12), not to an operator-facing endpoint.
- Real network partitions between processes. `NETWORK_LATENCY` delays one side only. Toxiproxy would need new
  infrastructure, and the protocol's fencing is already proven by the lease and race tests.
- The console's Chaos Lab view comes with the frontend (Phase 11). This phase delivers the API it will use.
