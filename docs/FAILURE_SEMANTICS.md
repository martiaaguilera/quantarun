# Failure semantics

What happens when an attempt does not simply succeed. The rules are in `SPEC.md` §3, §4 and §8, and the proofs are in
`INVARIANTS.md`. This document explains them with worked examples. It covers leases, lost workers, reported
failures, timeouts and cancellation (Phase 5), plus revive, checkpoints, Retry-After and the `http`, `memory` and
`staged` workloads (Phase 6).

## The guarantee

**At-least-once execution, at-most-once committed success.** A job's workload may run more than once, for example
when a worker is cut off from the control plane, keeps running, and the job is retried elsewhere. Only one attempt can
ever *commit* an outcome. The other attempt's report is rejected with `409 ATTEMPT_NOT_ACTIVE`, because the attempt
id is the fencing token (ADR-0002). Workloads with external side effects must therefore be idempotent or tolerate
duplicates. Of the built-in workloads only `http` reaches the outside world, and it allows only GET and HEAD, which
are safe to repeat. The others (`delay`, `cpu-hash`, `mock-inference`, `fail`, `memory`, `staged`) have no external
side effects; `staged` writes only its own checkpoints, which are fenced.

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
| Out of memory in the `memory` workload | worker | FAILED | RESOURCE_EXHAUSTED | retry with backoff |
| `http`: target refused by the SSRF guard, bad URL or method | worker | FAILED | INVALID_INPUT | job FAILED |
| `http`: 429 | worker | FAILED | RATE_LIMITED, with the provider's Retry-After | retry after max(Retry-After, backoff) |
| `http`: 502, 503, 504 / other 5xx / 408 / other 4xx | worker | FAILED | PROVIDER_UNAVAILABLE / TRANSIENT / TIMEOUT / NON_RETRYABLE | per class |
| `http`: connection failure / timeout | worker | FAILED | TRANSIENT / TIMEOUT | retry with backoff |
| A checkpoint is rejected (the attempt was recovered meanwhile) | worker | – (no report) | – | the control plane already decided |
| An assignment stays unclaimed past the claim timeout (30 s) while its worker heartbeats | control plane (renewal stops, then the lease reaper) | LOST | WORKER_LOST | retry at once on any worker |
| `mock-inference`, chaos: provider 429 / 500 / malformed response | worker | FAILED | RATE_LIMITED with Retry-After / TRANSIENT / TRANSIENT | per class (CHAOS.md) |

A worker can never report `WORKER_LOST` (400 `INVALID_REPORT`): a worker that is reporting is evidently not lost.
A FAILED report must carry a failure class, and other outcomes must not.

## Retry policy

Implemented in `RetryPolicy` (pure, seeded randomness in tests):
1. A pending cancel request wins: the job ends CANCELLED.
2. `INVALID_INPUT` and `NON_RETRYABLE`: the job ends FAILED. Retrying the same input cannot help.
3. The attempt budget is exhausted (`attemptNo >= max_attempts`): the job ends DEAD (I9). Only an explicit revive
   (Phase 6) runs it again.
4. `WORKER_LOST`: the job goes RETRY_WAIT with no delay. The work did not fail, only its host did.
5. RATE_LIMITED with a Retry-After: RETRY_WAIT for max(Retry-After, backoff). Retrying sooner only earns another 429.
   Retry-After is capped at 10 minutes, and a worker may send it only with RATE_LIMITED.
6. Anything else: RETRY_WAIT with full-jitter exponential backoff, a random delay in
   `[0, min(60 s, 1 s × 2^(attempt-1))]`.

**Revive.** `POST /api/v1/jobs/{id}/revive` moves a DEAD job back to QUEUED with a fresh budget of `max_attempts`. The
earlier attempts stay in history and attempt numbers keep counting (a revived job with `max_attempts = 2` has attempts
1–2, then 3–4). Its checkpoints stay too, so a revived staged job resumes. Only DEAD jobs can be revived (409
`JOB_NOT_DEAD` otherwise), at most 10 times (409 `REVIVE_LIMIT_REACHED`); concurrent revives revive once.

**Checkpoints.** Only the `staged` workload checkpoints. After each stage the worker commits the stage index and a
small result (≤ 8 KiB). The control plane accepts it only from the RUNNING attempt of the worker that owns it, only for
the next stage, and never rewrites a committed stage; a repeated identical commit is answered as already committed. A
retry gets the last committed stage with its assignment and starts after it; the timeline's STARTED event says
`resume: from zero` or `resume: after stage k`.

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

**A staged job resumes on another worker** (2026-10-02, commit `44c3330`). Five 4-second stages; the worker running
it was killed with `kill -9` after stages 0 and 1 were committed:

```
15:26:43.690 STARTED               resume: from zero          (attempt 1, worker-cpu)
15:26:47.760 CHECKPOINT_COMMITTED  stage 0
15:26:51.798 CHECKPOINT_COMMITTED  stage 1
             (worker-cpu killed at 15:26:53)
15:27:08.582 ATTEMPT_LOST          WORKER_LOST
15:27:08.582 RETRY_SCHEDULED       retry after 0 ms
15:27:09.206 STARTED               resume: after stage 1      (attempt 2, another worker)
15:27:13.256 CHECKPOINT_COMMITTED  stage 2
15:27:17.287 CHECKPOINT_COMMITTED  stage 3
15:27:21.307 CHECKPOINT_COMMITTED  stage 4
15:27:21.365 SUCCEEDED             executedStages 3, resumedAfterStage 1
```

The final digest chains every stage's output, so it equals the digest of an uninterrupted run only if no stage was
skipped or repeated (`StagedWorkloadTest`).

**Revive, live.** `fail {TRANSIENT, succeedOnAttempt: 3}` with `max_attempts = 2` went DEAD after 2 attempts; after a
revive it ran attempt 3 (the first of the new budget) and succeeded.

**Retry-After, live.** `fail {RATE_LIMITED, retryAfterMillis: 20000}` failed at 15:27:32 and became runnable again at
15:27:52.

## The http workload and SSRF

The worker resolves the target and filters its addresses **inside the HTTP client's DNS resolver** (Spring Boot's
`InetAddressFilter` on Apache HttpClient 5), so the addresses checked are the ones the connection uses. A separate
"resolve, check, then connect" would let DNS rebinding swap in an internal address between the check and the connect.
Refused: loopback, RFC 1918 private ranges, link-local (including cloud metadata at 169.254.169.254), CGNAT, IPv6
unique-local, multicast and the other special-purpose ranges. IP literals are also refused before any connection is
attempted. Redirects are not followed. An operator can allow specific internal IPs or CIDRs
(`QUANTARUN_WORKER_HTTP_ALLOWED_PRIVATE_ADDRESSES`), never hostnames. The response body is hashed and counted (up to
1 MiB), never stored. Live check: jobs targeting `169.254.169.254` and `localhost` both ended FAILED with INVALID_INPUT,
"the target address is not allowed".

## Races and who wins

| Race | Outcome | Proof |
|---|---|---|
| Worker reports success while its lease expires and the reaper recovers it | A report that takes the attempt's row lock while the lease is held wins, and the reaper skips the attempt. Once the lease has expired the report is refused (409 `LEASE_EXPIRED`, or `ATTEMPT_NOT_ACTIVE` after recovery) and the reaper recovers it | `completionRacingLeaseExpiry_hasExactlyOneWinnerPerAttempt` |
| A report arrives after the lease expired but before the reaper ran | 409 `LEASE_EXPIRED`, consistent with the heartbeat, which already called the attempt lost; the worker drops it | `report_afterLeaseExpiry_beforeRecovery_isFencedWith409` |
| Heartbeat renews while the reaper recovers | An expired lease is never renewed, even after waiting for the reaper's lock | `heartbeatRacingTheReaper_neverRevivesAnExpiredLease` |
| A late report after recovery | 409 `ATTEMPT_NOT_ACTIVE`; the worker drops it and does not retry | `report_afterLeaseRecovery_isFencedWith409` |
| The same report delivered twice | The second gets the recorded outcome (200) | `report_duplicateIsAnsweredIdempotently_aDifferentOutcomeIsRejected` |
| A retired (OFFLINE) worker comes back | 409 on claim, heartbeat and report; it must register again under a new id | `retiredWorker_cannotClaimRenewOrReport` |
| Cancel races the scheduler placing the job | The job ends CANCELLED, or SCHEDULED with the cancel flagged, which the worker then honours; never placed after a cancel | `cancelRacingTheRealScheduler_neverLosesTheCancellation`, `cancelRacingARetryPlacement_neverLosesTheCancellation` |
| Cancel races a failure report | Report first: RETRY_WAIT, then the cancel ends it. Cancel first: the failure's retry decision sees the flag. Either way CANCELLED, never retried | `cancelRacingAFailureReport_alwaysEndsCancelled` |
| A checkpoint races lease recovery | Both lock the attempt row: the checkpoint commits while the attempt still runs, or is refused because it no longer does | `checkpointRacingLeaseRecovery_isNeverCommittedByARecoveredAttempt` |
| Concurrent revives | One conditional update matches; the others get 409 | `concurrentRevives_reviveExactlyOnce` |
| Overlapping heartbeats of one worker pick up its chaos faults | Each fault is delivered by exactly one of them | `concurrentHeartbeats_deliverEachFaultExactlyOnce` |

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
