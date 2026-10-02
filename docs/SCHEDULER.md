# Scheduler

How QuantaRun decides where each workload runs, why it is correct under concurrency, and what each policy trades
away. Semantics are in `SPEC.md` §6–7; this document explains the mechanism.

## The cycle

One scheduling cycle is one short PostgreSQL transaction (`SchedulingCycle.runCycle`):

1. **Lock a window of runnable jobs**: `status IN ('QUEUED','RETRY_WAIT') AND available_at <= now()`, ordered for
   the policy, `LIMIT window FOR UPDATE SKIP LOCKED`. Concurrent cycles take disjoint jobs instead of queueing behind
   each other.
2. **Lock every live worker**: `FOR UPDATE OF w`, in id order. Here the lock is plain, not SKIP LOCKED: a cycle that
   skipped a worker another cycle holds would wrongly conclude that a job fits nowhere. Only the `workers` rows are
   locked; the joined `worker_heartbeats` rows are not, so heartbeats never wait on scheduling.
3. **Plan**: `PlacementPlanner.planPlacements(snapshot, policy)`, a pure function (ADR-0004).
4. **Apply**: for each placement, reserve capacity on the worker, insert attempt N+1 (`ASSIGNED`, with a lease), move
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

**Dominant utilisation** is the highest fraction in use across CPU, memory, slots and (if the worker has any)
accelerators, after the job is placed. One number per worker is what makes heterogeneous workers comparable.

**Backfilling.** A job that does not fit does not block the jobs behind it. That keeps utilisation high, but a large job
can wait while smaller ones overtake it. Fair-share and deadline policies (Phase 7) address this trade-off, and
simulation (Phase 8) measures it.

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

Finished jobs never enter the partial index, so history size does not slow scheduling. The priority window sorts the
whole runnable backlog. That is fine at this size; an index on `(priority DESC, available_at, id)` for runnable rows
is the fix if a measured backlog makes it matter (Phase 13).

## Configuration

`quantarun.scheduler.*`: `policy` (compose sets `QUANTARUN_SCHEDULER_POLICY`, default `BIN_PACKING` for the demo),
`window-size` (200), `idle-delay` (500 ms between empty cycles; a productive cycle repeats at once), `loops`
(concurrent loops in one process, default 1).
