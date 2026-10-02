# Failure semantics

What happens when an attempt does not simply succeed. The rules are in `SPEC.md` §3, §4 and §8, and the proofs are in
`INVARIANTS.md`. This document explains them with worked examples. First version (Phase 5): it covers leases, lost
workers, reported failures, timeouts and cancellation. Checkpoints and the `http`, `memory` and `staged` workloads
come in Phase 6.

## The guarantee

**At-least-once execution, at-most-once committed success.** A job's workload may run more than once, for example
when a worker is cut off from the control plane, keeps running, and the job is retried elsewhere. Only one attempt can
ever *commit* an outcome. The other attempt's report is rejected with `409 ATTEMPT_NOT_ACTIVE`, because the attempt
id is the fencing token (ADR-0002). Workloads with external side effects must therefore be idempotent or tolerate
duplicates. None of the Phase 5 workloads (`delay`, `cpu-hash`, `mock-inference`, `fail`) has side effects.

## Who decides what

| Event | Detected by | Attempt ends as | Failure class | Then |
|---|---|---|---|---|
| Workload returns | worker | SUCCEEDED | – | job SUCCEEDED |
| Workload throws a classified failure | worker | FAILED | the workload's class | retry policy (below) |
| Bad payload | worker | FAILED | INVALID_INPUT | job FAILED, never retried |
| Unexpected exception in the workload | worker | FAILED | INTERNAL | retry with backoff |
| `timeoutSeconds` exceeded | worker (it interrupts the workload) | FAILED | TIMEOUT | retry with backoff |
| Worker shut down gracefully before the attempt finished | worker (after its grace period) | FAILED | TRANSIENT | retry with backoff |
| Job cancelled while running | worker sees it in the heartbeat response | CANCELLED | – | job CANCELLED |
| Lease expired (worker crashed, hung or partitioned) | control plane (lease reaper) | LOST | WORKER_LOST | retry **at once**, or CANCELLED if a cancel was pending |

A worker can never report `WORKER_LOST` (400 `INVALID_REPORT`): a worker that is reporting is evidently not lost.
A FAILED report must carry a failure class, and other outcomes must not.

## Retry policy

Implemented in `RetryPolicy` (pure, seeded randomness in tests):
1. A pending cancel request wins: the job ends CANCELLED.
2. `INVALID_INPUT` and `NON_RETRYABLE`: the job ends FAILED. Retrying the same input cannot help.
3. The attempt budget is exhausted (`attemptNo >= max_attempts`): the job ends DEAD (I9). Only an explicit revive
   (Phase 6) runs it again.
4. `WORKER_LOST`: the job goes RETRY_WAIT with no delay. The work did not fail, only its host did.
5. Anything else: RETRY_WAIT with full-jitter exponential backoff, a random delay in
   `[0, min(60 s, 1 s × 2^(attempt-1))]`.

Every decision is stored on the attempt that ended (`retry_decision`), and the job timeline records
`ATTEMPT_FAILED` or `ATTEMPT_LOST` followed by `RETRY_SCHEDULED`, `FAILED`, `DEAD` or `CANCELLED`.

## Worked examples

All of these were run against a live control plane with three workers on 2026-10-02 (commit `91f91d0`). The
output below is from `GET /api/v1/jobs/{id}/attempts`.

**A transient fault that heals.** `fail {failureClass: TRANSIENT, succeedOnAttempt: 3}`, `max_attempts = 3`:

| # | status | class | decision |
|---|---|---|---|
| 1 | FAILED | TRANSIENT | retry after 474 ms |
| 2 | FAILED | TRANSIENT | retry after 875 ms |
| 3 | SUCCEEDED | – | succeeded |

**Budget exhausted.** `fail {failureClass: PROVIDER_UNAVAILABLE}`, `max_attempts = 2`: attempt 1 FAILED, "retry after
570 ms"; attempt 2 FAILED, "DEAD: retry budget exhausted (2 of 2 attempts)".

**Not retryable.** `fail {failureClass: INVALID_INPUT}`: one attempt, "FAILED: INVALID_INPUT is not retryable".

**A worker killed mid-run** (`kill -9`, lease 15 s). A 20-second `delay` job:

```
14:49:52.731 SUBMITTED
14:49:53.126 SCHEDULED        on worker-cpu, attempt 1
14:49:53.472 STARTED
             (worker-cpu killed at 14:49:53)
14:50:08.712 ATTEMPT_LOST     WORKER_LOST, "lease expired"
14:50:08.712 RETRY_SCHEDULED  "retry after 0 ms"
14:50:09.221 SCHEDULED        on worker-accel, attempt 2
14:50:09.455 STARTED
14:50:29.505 SUCCEEDED
```

Recovery took one lease duration plus at most one reaper tick (1 s), and placement took one scheduler idle delay.

**The control plane restarts while work runs.** A 35-second `mock-inference` job was started, then the control plane
was killed and stayed down for about 29 s, longer than the 15 s lease. On startup it extended the one active lease
("Lease reaper armed", `extendedLeases: 1`) before reaping anything. The worker's next heartbeat renewed the lease,
and the job finished with a single attempt and no `ATTEMPT_LOST`. Without the extension, the reaper's first tick would
have declared that healthy work lost and run it again.

**Cooperative cancel.** A cancel request on a running 60-second `delay` job answers `202 CANCEL_REQUESTED`. The
worker sees the attempt in `cancelAttemptIds` on its next heartbeat (≤ 3 s), interrupts it, and reports CANCELLED.
If the worker never answers, lease expiry ends the job as CANCELLED instead of retrying it.

## Races and who wins

| Race | Outcome | Proof |
|---|---|---|
| Worker reports success while the reaper recovers the expired lease | Both lock the attempt row; whoever gets it first decides; the other sees a non-active attempt (report → 409, reaper → skips it) | `completionRacingLeaseExpiry_hasExactlyOneWinnerPerAttempt` |
| Heartbeat renews while the reaper recovers | An expired lease is never renewed, even after waiting for the reaper's lock | `heartbeatRacingTheReaper_neverRevivesAnExpiredLease` |
| A late report after recovery | 409 `ATTEMPT_NOT_ACTIVE`; the worker drops it and does not retry | `report_afterLeaseRecovery_isFencedWith409` |
| The same report delivered twice | The second gets the recorded outcome (200) | `report_duplicateIsAnsweredIdempotently_aDifferentOutcomeIsRejected` |
| A retired (OFFLINE) worker comes back | 409 on claim, heartbeat and report; it must register again under a new id | `retiredWorker_cannotClaimRenewOrReport` |

## Worker-side behaviour

- One execution per slot; the worker claims only as many assignments as it has free slots.
- An attempt ends exactly one way: the first of *completed*, *timed out*, *cancelled*, *lost* or *shut down* wins,
  and only the winner may interrupt the thread. A timeout that fires after the workload finished cannot abort the
  success report.
- Reports are retried with full-jitter backoff on network and 5xx errors (5 tries by default). 404 and 409 are final.
  If every try fails, the lease expires and the control plane recovers the attempt, so a lost report costs a retry,
  never correctness.
- Graceful shutdown: stop claiming; deregister, which drains the worker if it is busy; keep heartbeating while
  attempts finish (25 s grace); stop what is left as TRANSIENT; deregister.
