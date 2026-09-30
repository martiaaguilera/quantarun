# ADR-0002: Attempt leases with fencing by attempt identity

- Status: accepted (2026-09-30)

## Context
A worker can die, pause (GC, laptop sleep) or lose its network while holding work. We need to (a) recover
the work and its resources and (b) stop a slow "zombie" worker from committing a result after the work was
reassigned.

## Decision
- Each attempt carries `lease_expires_at`. The worker's heartbeat renews the leases of its active attempts, but
  only those that have **not yet expired** (an expired lease is never revived).
- A reaper recovers expired leases with `FOR UPDATE SKIP LOCKED` and finishes the attempt as `LOST`
  (failure class `WORKER_LOST`). This releases the reservation and applies the retry policy.
- Every worker write (claim, checkpoint, report) names the attempt id and must match an **active** attempt
  owned by that worker. The attempt id is the fencing token: a new attempt has a new id, so stale writes
  change zero rows.
- The defaults are heartbeat 3 s, lease 15 s and an offline threshold of 15 s. They are configurable, and
  the tests shrink them.

## Consequences
- At-least-once execution, at-most-once committed success. A zombie may *execute* again, but it cannot
  *commit*. Side-effecting workloads must tolerate re-execution.
- Correctness depends on the database clock only (`now()` in SQL), not on worker clocks.
- Rejected alternative: the worker holds a DB advisory lock for the duration of the job. It needs a
  database connection per running job and gives workers database credentials.
