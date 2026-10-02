# Scheduler

How QuantaRun decides where each workload runs, why it is correct under concurrency, and what each policy trades
away. Semantics are in `SPEC.md` §6–7; this document explains the mechanism.

## The cycle

One scheduling cycle is one short PostgreSQL transaction (`SchedulingCycle.runCycle`):

1. **Lock a window of runnable jobs**: `status IN ('QUEUED','RETRY_WAIT') AND available_at <= now()`, ordered for
   the policy, `LIMIT window FOR UPDATE SKIP LOCKED`. Concurrent cycles take disjoint jobs instead of queueing behind
   each other. FAIR_SHARE uses a round-robin window instead (see below), DEADLINE an earliest-deadline window.
2. **Lock every live worker**: `FOR UPDATE OF w`, in id order. Here the lock is plain, not SKIP LOCKED: a cycle that
   skipped a worker another cycle holds would wrongly conclude that a job fits nowhere. Only the `workers` rows are
   locked; the joined `worker_heartbeats` rows are not, so heartbeats never wait on scheduling.
3. **Read project state**: quota usage (active attempts and accelerators per project in the window) and fair-share
   virtual times. This happens *after* step 2: every cycle that can place work holds the worker locks until it commits,
   so cycles are serialised here, and the usage and virtual times a cycle reads cannot change under it.
4. **Plan**: `PlacementPlanner.plan(snapshot, policy)`, a pure function (ADR-0004).
5. **Apply**: for each placement, reserve capacity on the worker, insert attempt N+1 (`ASSIGNED`, with a lease), move
   the job to `SCHEDULED`, and append an event and a decision record. For a waiting job, write its reason only if it
   changed.

Lock order is always jobs, then workers in ascending id. Combined with SKIP LOCKED on jobs, two cycles can never
deadlock. No network I/O happens inside the transaction.

## Why the cycle cannot overcommit (I1–I4)

| Guarantee | Mechanism | Backstop | Proof |
|---|---|---|---|
| A plan never promises the same capacity twice | The planner subtracts each placement from the worker's free capacity before considering the next job | – | `PlacementPropertiesTest`: 2,000 random fleets × 4 policies |
| Concurrent cycles never overcommit | Worker rows are locked for the whole cycle, so the free capacity a cycle reads cannot change until it commits | `CHECK (reserved BETWEEN 0 AND capacity)` on every resource | `SchedulingCycleTest.concurrentCycles_neverOvercommitOrDoublePlace`: 16 cycles × 400 jobs × 4 policies |
| A job is never placed twice | Jobs are locked SKIP LOCKED; the transition requires a runnable status | partial unique index `job_attempts(job_id) WHERE active` | same concurrency test + `placedJob_isNotPlacedAgainByLaterCycles` |
| Reservations equal the active attempts | Reserve and insert-attempt happen in the same transaction | consistency query | `assertReservationsMatchActiveAttempts` after every concurrency test |
| Only compatible, accepting workers receive work | Planner verdicts (labels, capacity, lifecycle, heartbeat health) | – | property test + `drainingAndLateWorkers_receiveNoNewWork` |

## Policies

A policy combines a **job ordering** (who is considered first) with a **worker selection** (where a job goes among the
workers it fits on). Ties always break on worker id, so plans are reproducible.

| Policy | Ordering | Selection | Good at | Costs |
|---|---|---|---|---|
| `FIFO` | oldest first | first worker that fits | Predictable, cheap | Ignores priority; no packing |
| `PRIORITY` | highest priority, then oldest | first fit | Urgent work goes first | **Strict**: a steady stream of urgent jobs starves low priorities |
| `LEAST_LOADED` | oldest first | lowest dominant utilisation after placement | Spreads load, keeps headroom, lower interference | Fragments capacity; big jobs may find no single worker with room |
| `BIN_PACKING` | oldest first | highest dominant utilisation after placement, with a penalty for putting accelerator-free jobs on accelerator workers | Leaves whole workers free for big or GPU jobs | Concentrates load, which raises interference and failure blast radius |
| `FAIR_SHARE` | the backlogged project with the least weighted service first, FIFO within a project | least loaded | A tenant flooding the queue cannot starve the others; service follows the weights | A heavy project's urgent job waits behind a light project's ordinary one; priority is ignored across projects |
| `DEADLINE` | earliest deadline first, then priority, then oldest; undated jobs last | least loaded | Fewest missed deadlines while the fleet keeps up | Under overload EDF degrades badly (late jobs push everything later); undated work can starve |

**Dominant utilisation** is the highest fraction in use across CPU, memory, slots and (if the worker has any)
accelerators, after the job is placed. One number per worker is what makes heterogeneous workers comparable.

**Backfilling.** A job that does not fit does not block the jobs behind it. That keeps utilisation high, but a large job
can wait while smaller ones overtake it. No policy reserves capacity for a waiting large job; simulation (Phase 8)
measures what that costs.

## Fair share: weighted virtual time

QuantaRun implements start-time fair queuing over projects. Each project has a **virtual time** `v`: the service it
has received divided by its weight.

- **Who goes next.** Within a cycle, the planner repeatedly takes the backlogged project with the smallest `v` (ties:
  lower project id) and considers its oldest job.
- **Charging.** A placement charges `cost / weight`, where `cost` is the job's dominant share of the whole fleet's
  capacity (CPU, memory, slots, accelerators) times a duration estimate. Jobs carry no duration, so every job is
  estimated alike (60 s); the estimate only scales `v`. Charging happens under every policy, so switching to
  FAIR_SHARE later starts from the service actually given.
- **No banked credit.** Before planning, each project's `v` is raised to the **system virtual time**, the smallest `v`
  among the projects in the previous cycle's window. A project returning from a long idle period starts level with
  the busy ones instead of taking every free slot with its stale, low `v` (`fairShare_idleTimeCannotBeBankedAsCredit`).
- **Why a flood cannot starve anyone.** A project that queues 10,000 jobs raises only its own `v` as it is served.
  The others' `v` stay low, so they are served next.

**The window matters as much as the ordering.** An oldest-first window of 200 jobs, taken when one tenant queued 10,000
jobs before another queued 10, contains only the flooder's jobs: the policy would never even see the other tenant.
FAIR_SHARE therefore locks a **round-robin window**: each project's runnable jobs are ranked oldest first, and the
window takes rank 1 of every project, then rank 2, and so on (`jobs_runnable_by_project_idx`, one probe per project).

Measured (`FairShareSchedulingTest`, real PostgreSQL): with 10,000 queued jobs from one tenant and 10 from another on a
20-slot fleet, FAIR_SHARE places all 10 of the light tenant's jobs in the first cycle; FIFO places none of them. With
weights 3:1 and both tenants backlogged, 40 cycles with completions in between gave the heavy tenant 70–80% of the
service. These are tests, not benchmarks: the simulator (Phase 8) measures fairness (Jain's index), latency and
utilisation across policies.

**Trade-offs.** Fairness is between projects, not jobs: a job's priority does not let it jump ahead of another
project. Virtual time is charged at placement with an estimate, not measured at completion, so long-running jobs are
under-charged; a measured-duration correction is possible once durations are recorded per workload type.

## Quotas and admission

Per project (`PUT /api/v1/projects/{id}/limits`, operators only; null is unlimited):

| Quota | Enforced | When exceeded |
|---|---|---|
| `maxQueuedJobs` | at submission | `429 QUOTA_EXCEEDED` with the counts. The project row is locked for the count and insert, so concurrent submissions cannot overshoot; a retried submission that already got in is replayed |
| `maxRunningJobs` | by the scheduler | the job stays queued as `WAITING_FOR_QUOTA` with the reason, e.g. "Project alpha is at its quota of 3 running jobs" |
| `maxAccelerators` | by the scheduler | `WAITING_FOR_QUOTA`: "would exceed its quota of 3 accelerators (3 in use, 1 requested)" |

A job that no worker could ever run is reported `UNSCHEDULABLE` even if its project is also at quota: the more
fundamental reason wins. Quotas apply under every policy. Why concurrent cycles cannot overrun a quota is step 3 of
the cycle above (invariant I17).

## Explainability

Every placement, and every change in why a job waits, is recorded in `scheduler_decisions`:

```
PLACED [BIN_PACKING]: Placed on worker-cpu by BIN_PACKING (fits; 75% utilised after placement)
    worker-cpu    CHOSEN                      fits; 75% utilised after placement
    worker-mixed  FITS                        fits; 25% utilised after placement
    worker-accel  FITS                        fits; 100% utilised after placement   <- penalised: wastes accelerators

UNSCHEDULABLE [BIN_PACKING]: No live worker is large enough: worker-mixed needs 4 accelerators, 1 in total
```

(Real output from the compose stack on 2026-10-02.)

The verdicts are `CHOSEN`, `FITS`, `INSUFFICIENT_FREE_CAPACITY`, `NOT_ACCEPTING_WORK` (draining or late heartbeat),
`EXCEEDS_CAPACITY` and `MISSING_LABELS`. A job is **unschedulable** when no live worker could run it even if idle, and
**waiting for capacity** when some could but none can right now. Records are bounded to 16 candidates, and a job
waiting for an hour writes one record, not one per cycle.

API: `GET /api/v1/jobs/{id}/decisions` (scoped to the job's project), `GET /api/v1/scheduler/decisions` (admin),
`GET /api/v1/scheduler`.

## Query plans

Measured on 2026-10-02, PostgreSQL 18.6 in Docker, with 200,000 finished jobs and 2,000 runnable:

| Window | Plan | Execution |
|---|---|---|
| Oldest first | Index Scan on partial `jobs_runnable_idx`, LockRows, Limit; 10 index buffers | 0.44 ms |
| Highest priority first | Index Scan on `jobs_runnable_idx`, then Sort over all 2,001 runnable rows | 2.46 ms |

Phase 7, measured on 2026-10-02 (PostgreSQL 18.6 in Docker, 4 vCPU Xeon @ 2.1 GHz cloud container, branch
`wip/phase-7` on `6136e61`): 200,000 finished jobs, 20 projects, one with 10,000 runnable jobs and 19 with 20 each.

| Query | Plan | Execution |
|---|---|---|
| Round-robin window (FAIR_SHARE), 200 jobs | Per project: Index Only Scan on `jobs_runnable_by_project_idx` with LIMIT; then top-N sort and primary-key lookups | 1.9–2.6 ms |
| Same, right after the bulk insert | Bitmap scans and a sort of each project's whole backlog (10,000 rows for the flooder) | 12.5 ms |
| Earliest-deadline window, 200 jobs | Index Scan on `jobs_runnable_by_deadline_idx` | 0.46 ms |
| Admission count for the flooding project | Bitmap Index Scan on `jobs_unfinished_by_project_idx` (10,000 rows) | 2.25 ms |

The slow plan existed only until autovacuum set the visibility map (it ran between the two measurements); the
index-only scan needs the pages marked all-visible.

Finished jobs never enter the partial index, so history size does not slow scheduling. The priority window sorts the
whole runnable backlog. That is fine at this size; an index on `(priority DESC, available_at, id)` for runnable rows
is the fix if a measured backlog makes it matter (Phase 13).

## Configuration

`quantarun.scheduler.*`: `policy` (`FIFO`, `PRIORITY`, `LEAST_LOADED`, `BIN_PACKING`, `FAIR_SHARE` or `DEADLINE`;
compose sets `QUANTARUN_SCHEDULER_POLICY`, default `BIN_PACKING` for the demo),
`window-size` (200), `idle-delay` (500 ms between empty cycles; a productive cycle repeats at once), `loops`
(concurrent loops in one process, default 1).
