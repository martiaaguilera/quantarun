# Invariants

Every invariant lists **what** must hold, **how** it is enforced (application logic *and* database guard), and
**which tests prove it**. A test entry shows `planned (Phase N)` until the test exists and passes; after that it
names the test class. Never mark an invariant proven without a passing test.

Transaction semantics per operation are in `ARCHITECTURE.md` §4.

| # | Invariant | Mechanism | Database guard | Proof |
|---|---|---|---|---|
| I1 | A worker's reserved CPU, memory, accelerators and slots never exceed its capacity, and are never negative | Reservations are made in the scheduling transaction while holding the worker row lock (`FOR UPDATE`); releases happen in the same transaction that finishes the attempt | `CHECK (reserved_x BETWEEN 0 AND capacity_x)` on `workers` | **DB guard proven**: `WorkerFleetTest.database_rejectsReservationsBeyondCapacityOrBelowZero` (every resource, above capacity and below zero). Planned (Phase 4): property test over random topologies and N concurrent schedulers on real PG |
| I2 | `workers.reserved_*` equals the sum of the reservations of that worker's active attempts | Reserve and release only happen together with attempt insert/finish, in one transaction | – (checked by a consistency query) | planned (Phase 5): consistency query asserted after every concurrency test |
| I3 | A job has at most one active attempt (ASSIGNED or RUNNING) | Placement only takes QUEUED/RETRY_WAIT jobs under row lock | partial unique index `ON job_attempts(job_id) WHERE status IN ('ASSIGNED','RUNNING')` | planned (Phase 4–5) |
| I4 | A job is never placed on a worker it is incompatible with (labels, capacity) | The pure policy's compatibility filter | I1 guards capacity; labels are checked in the policy only | planned (Phase 4): property test |
| I5 | Only legal job state transitions happen | `JobStatus.canTransitionTo` is the single transition table; every write is `UPDATE ... WHERE status = expected` | `CHECK (status IN (...))` | **proven**: `JobStatusTest` (all 64 ordered pairs checked against the SPEC table; illegal transitions throw); `JobRepository.transition` checks the table before issuing SQL |
| I6 | A logical job has at most one committed successful result | Reports are fenced: only an active attempt owned by the reporting worker can finish; job goes RUNNING → SUCCEEDED conditionally | I3 + conditional update | planned (Phase 5): duplicate and concurrent completion tests; completion racing lease expiry |
| I7 | Duplicate submissions with the same idempotency key create exactly one job | `INSERT ... ON CONFLICT DO NOTHING RETURNING` | `UNIQUE (project_id, idempotency_key)` | **proven**: `JobConcurrencyTest.concurrentDuplicateSubmissions_createExactlyOneJob` (500 submissions, 64 threads, one latch, real PG 18: 1 job, 1 SUBMITTED event); `JobApiTest.Idempotency` |
| I8 | Same idempotency key with a different body is rejected, not silently merged | canonical SHA-256 fingerprint of the normalised request (sorted keys, defaults applied) | – | **proven**: `JobConcurrencyTest.concurrentSubmissionsWithDifferentBodies_neverMergeIntoTheWinner` (200 racing requests over 2 bodies: 100 replays of the winner, 100 conflicts, 0 merged); `JobApiTest.Idempotency` (key order and explicit defaults do not change the fingerprint) |
| I9 | Attempts never exceed `max_attempts` | The retry decision is taken in the transaction that finishes the attempt, using the locked job row's `attempt_count` | `CHECK (attempt_count <= max_attempts)` | planned (Phase 6): property test over failure sequences; concurrency test |
| I10 | An expired lease is never renewed; an expired attempt is recovered exactly once | Renewal only updates leases with `lease_expires_at > now()`; the reaper uses `FOR UPDATE SKIP LOCKED` and the conditional finish | conditional update | planned (Phase 5): heartbeat racing reaper |
| I11 | A stale (fenced-out) worker cannot claim, checkpoint or report | Every worker write matches `(attempt_id, worker_id, active status)` | conditional update | planned (Phase 5) |
| I12 | Cancelled jobs are never newly placed | Placement selects only QUEUED/RETRY_WAIT rows under row lock; cancel changes status with a conditional update | status CHECK + conditional update | **partially proven**: `JobConcurrencyTest.cancelRacingPlacement_neverLosesTheCancellation` (20 repetitions against the conditional placement transition; the job ends CANCELLED or SCHEDULED with the cancel flagged). The full proof against the real scheduler follows in Phase 4/6 |
| I13 | A DEAD job executes again only after an explicit revive | Only `revive` performs DEAD → QUEUED | I5 | planned (Phase 6) |
| I14 | Checkpoints are committed only by the active attempt, and stage indexes never go backwards | Fenced checkpoint write | `UNIQUE (job_id, stage_index)` | planned (Phase 5/6) |
| I15 | The same scenario, seed and policy produce an identical simulation result | Pure policies (ADR-0004), seeded `SplittableRandom`, simulated clock, stable ordering | – | planned (Phase 8): result hash equality across runs |
| I16 | A project flooding the queue does not starve another backlogged project under FAIR_SHARE | Weighted virtual time, with idle projects clamped to the active minimum | – | planned (Phase 7): 10,000 vs 10 jobs test and simulation |

## What these tests do and do not prove

These tests will prove behaviour under the concurrency the tests generate (threads and connections against a
real PostgreSQL 18) and across the states that property tests generate. They do **not** prove the absence of
every possible interleaving. Where a race is prevented by a database constraint rather than by locking, the
constraint is the proof, and the test shows the application reacts correctly to the violation.
