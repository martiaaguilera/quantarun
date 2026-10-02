# Phase 5 handoff: leases and reliability (work in progress)

Branch `wip/phase-5`. State on 2026-10-02: **compiles, untested, not wired to HTTP yet.** Delete this file when
Phase 5 is complete and merged. Read `CLAUDE.md`, `docs/SPEC.md` §3–5 and §8, `docs/ARCHITECTURE.md` §4–5 and
`docs/INVARIANTS.md` (I2, I6, I10, I11) first.

## Done (in this branch)

- `worker-protocol`: `ClaimRequest`/`ClaimResponse`/`Assignment`, `AttemptOutcome`, `FailureClass`,
  `ReportRequest`/`ReportResponse`.
- `V5__add_attempt_results.sql`: `job_attempts.result jsonb`.
- `WorkloadType` trimmed to the types workers will actually execute in Phase 5: delay, cpu-hash, mock-inference, fail.
  memory, http (SSRF guard) and staged (checkpoints) come back in Phase 6 together with their executors.
- `jobs.AttemptStatus` (transition rules), `jobs.RetryPolicy` (pure: cancel wins; INVALID_INPUT/NON_RETRYABLE give
  FAILED; budget exhausted gives DEAD; WORKER_LOST retries immediately; others use full-jitter exponential backoff),
  `jobs.JobsConfiguration` (`quantarun.retries.base-delay` 1s, `max-delay` 60s).
- `jobs.JobAttempts` + `jobs.internal.AttemptRepository`: `claim`, `renewLeases` (never revives an expired lease;
  returns cancel and lost lists), `report` (fenced by attempt id + worker id; an identical repeat is
  `AlreadyRecorded`, a different one is `Rejected`), `recoverExpiredLeases` (SKIP LOCKED), and
  `extendActiveLeasesAfterRestart`. All attempt endings go through `endAttempt`, which locks attempt, then job, then
  worker, releases the reservation, applies the retry decision and appends events.
- `JobRepository.lockById`, `JobRepository.applyAttemptOutcome`; `WorkerCapacity.release` (MANDATORY);
  `WorkerPrincipal` and `WorkerAuthenticationFilter.PRINCIPAL_ATTRIBUTE` made public for the new module.

## Remaining, in order

1. **`execution` module** (new package `controlplane.execution`; it exists to avoid a jobs ⇄ workers module cycle):
   `ExecutionController` under `WorkerProtocol.BASE_PATH`:
   - `POST /claim`: `principal.requireRegisteredWorker()`; refuse if the worker is DRAINING or not live;
     `JobAttempts.claim`.
   - `POST /heartbeat`: **move it here** from `workers.WorkerProtocolController`. Record liveness via
     `WorkerRegistry.heartbeat`, then `JobAttempts.renewLeases`, and fill `cancelAttemptIds` and `lostAttemptIds` in
     the response (the "nothing to cancel yet" comment in `WorkerRegistry.heartbeat` must go).
   - `POST /attempts/{attemptId}/report`: map `ReportResult` to 200 (`Applied`/`AlreadyRecorded`), 409 code
     `ATTEMPT_NOT_ACTIVE` (`Rejected`), 404 (`NotFound`). Validate that FAILED carries a failureClass and that
     WORKER_LOST is never reported by a worker.
2. **`reliability` module**: a `LeaseReaper` scheduled every 1s (configurable; disabled in the test profile like the
   liveness monitor) calling `recoverExpiredLeases(50)` in a loop while it returns a full batch. On
   `ApplicationReadyEvent`, call `extendActiveLeasesAfterRestart()` **before** the first reap. This is the lease
   counterpart of the worker startup grace (ENGINEERING_LOG, 2026-10-01).
3. **Worker executors** (`apps/worker`): bounded execution with one slot per capacity slot; claim only while ACTIVE
   with free slots; enforce `timeoutSeconds` (report FAILED/TIMEOUT); report with a bounded retry on network errors,
   dropping the report on 409; heartbeat with the running attempt ids; interrupt attempts listed as lost (no report)
   or cancelled (report CANCELLED). Graceful shutdown: stop claiming, wait for running attempts up to the shutdown
   phase, then deregister. Executors: `delay {durationMs ≤ 600000}`, `cpu-hash {iterations}`,
   `mock-inference {inputTokens, outputTokens, latencyMs, seed}` (deterministic; report token counts in `result`),
   `fail {failureClass, message, succeedOnAttempt?}` (to demo retries).
4. **Tests** (real PostgreSQL; no mocks for locking):
   - claim → RUNNING; duplicate report → `AlreadyRecorded`; report after lease recovery → 409;
   - **completion racing lease expiry**: many repetitions, exactly one winner, reservation released exactly once (I6,
     I10);
   - heartbeat racing the reaper (I10); stale worker cannot claim, report or renew (I11);
   - recovery releases capacity, and the I2 consistency query holds after every test (reuse the query in
     `SchedulingCycleTest`);
   - retries up to `max_attempts` then DEAD (I9); non-retryable class → FAILED; cancel flagged → heartbeat lists
     it → CANCELLED; lease expiry with a pending cancel → CANCELLED;
   - restart extension of leases; RetryPolicy unit tests (seeded random);
   - worker: executor unit tests, and agent claim/execute/report with `MockRestServiceServer`.
5. **End-to-end demo** (compose): submit a long `delay` job and `docker kill` its worker mid-run. Expect: lease
   expiry, then ATTEMPT_LOST, then RETRY_SCHEDULED, then a new placement on another worker, then SUCCEEDED, all in
   `GET /api/v1/jobs/{id}/events`. Also add `GET /api/v1/jobs/{id}/attempts` (`JobAttempts.history`).
6. **Docs**: INVARIANTS (I2 release path, I6, I9, I10, I11 proofs), a first version of FAILURE_SEMANTICS.md,
   ENGINEERING_LOG, the SPEC progress line, the README status. Then delete this file and squash-merge to `main`.
