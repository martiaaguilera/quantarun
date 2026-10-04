# Engineering log

Notable discoveries, dead ends and trade-offs, newest first. Not a changelog.

## 2026-10-04: The first live chaos runs found two defects
**A heartbeating worker that never claims held its work forever.** Heartbeats renewed every unclaimed (ASSIGNED)
attempt, on the reasoning that the worker had not seen it yet. That is true for a moment, because a worker with a
free slot claims within 500 ms. It is not true for a worker whose intake is stuck while its heartbeat thread lives.
In the live STOP_CLAIMING run, two jobs placed on such a worker sat unclaimed for 27 s and moved only when claiming
resumed. With a stuck claim loop they would never have moved. Unclaimed assignments are now renewed only for
`quantarun.workers.claim-timeout` (30 s) after placement. Then the lease runs out and the reaper recovers them like
any lost attempt (I19). 30 s is longer than the worst legitimate gap, a worker whose free-slot count lags because
it is retrying a report (5 tries, at most about 25 s). The live rerun released the assignment after 42.6 s.

**Latency skipped the reports.** NETWORK_LATENCY delayed heartbeats and claims but not reports. The injector waited
with `LockSupport.parkNanos`, which returns at once when the thread holds an unpark permit. The attempt threads did:
the HTTP client and the virtual-thread scheduler unpark threads as part of their own work. It now uses
`Thread.sleep`. An interrupt still ends the wait early and stays set. `ChaosInjectorTest` holds a pending permit to
keep this fixed.

**Design note: faults are pulled, not pushed (ADR-0007).** The control plane never calls a worker, so a fault rides
the worker's own heartbeat response. A worker that did not opt in ignores it, and there is no inbound "break
yourself" endpoint to secure.

## 2026-10-02 — now() is when a transaction began, not when its writes happened
`CheckpointTest.checkpointRacingLeaseRecovery_isNeverCommittedByARecoveredAttempt` failed once in a full run. A
checkpoint had been committed, yet its `committed_at` was later than the recovered attempt's `finished_at`. The
invariant held, and the test's clock was wrong. Both columns come from `now()`, which PostgreSQL fixes when a
transaction starts. The reaper's transaction can start first, while its `SKIP LOCKED` scan is still running. The
checkpoint can then lock, commit and release the attempt. The scan reaches the row after that, locks it and marks it
LOST, stamped with its earlier start time. In real order the checkpoint came first, but the timestamps say the
opposite. The test now orders the two by `job_events` id. An id is drawn at insert, while the attempt's row lock is
held, so ids follow the lock order. The same caveat applies to every timeline built from these timestamps: across
transactions that race on one row, the event id is the order and `occurred_at` is approximate. The events API
already orders by id.

## 2026-10-02 — An unexplained failure of the weighted fair-share test (open)
`FairShareSchedulingTest.weightsSetTheShareOfService_acrossCycles` failed once in a full `./mvnw verify` on the Phase 8
branch, with 200 of the expected 320 jobs served. That is 25 full rounds of 8, after which nothing more finished. The
run was about 3× slower than usual. The test passed in 23 later executions: 3 alone, 5 in full suites, and 15 in a
row under CPU saturation. No code path in the test is time-dependent (the leases are 15 s and the whole test takes
under 9 s), and no background loop runs in the test profile. A permanent stall fits a report that was not applied,
which would leave its attempt RUNNING and its slot held. The test discarded report results, so it could not show this.
It is not resolved, and the test was not relaxed. It now asserts every round instead: each cycle places 8, each
report is applied, and a failure names the round and every queued job's scheduling verdict. If it recurs, the
failure shows which step stopped.

## 2026-10-02 — The first fairness metric called FIFO fairer than fair share
The simulator's first fairness metric was Jain's index over each project's service rate "while backlogged", where
backlogged meant "has jobs waiting". On `NOISY_NEIGHBOR` it scored FIFO 0.457 and FAIR_SHARE 0.422, and
`noisyNeighbor_fairShareIsFairerThanFifo` failed. The test was right and the metric was wrong. Under fair share a quiet
tenant's job waits only a moment, often while none of its jobs is running, so its "rate while waiting" was near zero
and the metric scored it as starved. Fairness has to be measured against what a project is entitled to, capped by
what it wants. The metric now uses weighted max-min fairness: at each instant, water-filling splits the fleet by weight
among projects with work (waiting or running), never giving one more than it demands, and Jain's index compares
received with entitled service. FAIR_SHARE now scores 0.991 and FIFO 0.462.

The same test also asserted that fair share lowers the worst wait (starvation). It raised it, from 628 s to 769 s,
because the noisy tenant's last jobs now wait longer. That is the policy's cost, not a defect, so the assertion was
replaced by the honest one: the quiet tenants' p95 wait falls (1,175 s to 3 s) and the noisy tenant's rises (1,197 s
to 1,461 s). Per-project waits are now a reported metric, because totals hide where a policy puts the waiting.

## 2026-10-02 — Simulator: O(n²) twice before the first run
The first draft walked every job at every event to integrate utilisation and fairness, and searched the outcome list
linearly for each job at the end. Both are O(n²): about 10⁹ steps at 20,000 jobs. The simulator now keeps per-project
waiting and serving shares up to date as jobs start and stop, so a step costs O(projects), and the outcomes go
through a map. `theLargestTraceSimulatesInSeconds` keeps it honest. The remaining cost is the planner itself: a
20,000-job burst takes 6 to 8 s per policy, mostly spent building decision records for jobs that cannot be placed.

## 2026-10-02 — Fair share: the window starved the tenant before the policy saw it
The first design only reordered jobs inside the planner. Walking through the brief's test case (tenant A queues
10,000 jobs, then tenant B queues 10) showed it could not work: the cycle locks a window of the 200 oldest runnable
jobs, which were all A's, so the planner never saw B's jobs at all. The fairness had to start in SQL. FAIR_SHARE now
locks a round-robin window: one `LATERAL` index probe per project on `(project_id, available_at, id)` for runnable
rows, ranked within each project, ordered by rank. `underFifo_theSameFloodStarvesTheLightTenant` keeps the contrast
visible: under FIFO, B gets nothing in the first cycle.

## 2026-10-02 — Quotas and virtual times need no extra locks: worker locks already serialise cycles
Two concurrent cycles could each read "4 of 5 running" and both place, overrunning a quota, or both read a project's
virtual time and lose one charge. Locking project rows in the cycle would add a lock to the global order and a hot row.
That turned out to be unnecessary: every cycle locks all live workers `FOR UPDATE` before planning and holds them until
it commits, so cycles that can place work are already serialised. Reading usage and virtual times *after* that lock
makes them consistent for the whole cycle. The constraint is now documented where it is relied on (`SchedulingCycle`,
`JobPlacement.activeUsage`, `FairShareRepository`), and `runningQuota_holdsUnderConcurrentCycles` checks it with
8 concurrent cycles. If the cycle ever stops locking every worker (for example, sharded scheduling), this argument
breaks and the quota needs its own lock.

## 2026-10-02 — The same query, 12.5 ms then 2 ms: visibility map
`EXPLAIN ANALYZE` of the round-robin window right after bulk-inserting 210,000 jobs chose bitmap scans and sorted each
project's whole backlog (12.5 ms). Minutes later the same statement used an index-only scan on
`jobs_runnable_by_project_idx` (1.9–2.6 ms over three runs). Autovacuum had run in between and set the visibility map,
which an index-only scan needs. Lesson for Phase 13: measure plans after `VACUUM ANALYZE`, and say so, or the numbers
describe a state production rarely sits in.

## 2026-10-02 — SSRF guard: filter inside the resolver, and the test that checks it
The http workload needed a guard that DNS rebinding cannot bypass. Resolving the host, checking the addresses and then
connecting (letting the client resolve again) leaves a window in which a hostile DNS server can answer with a public
address for the check and `127.0.0.1` for the connect. Spring Boot 4.1's `InetAddressFilter` can sit at different
points depending on the client. On the JDK client it is a `ProxySelector` that checks before the client resolves again,
which leaves the same window. On Apache HttpClient 5 it wraps the `DnsResolver`, so the filtered addresses are exactly
the ones connected to. Hence the new `httpclient5` dependency on the worker. One side effect: `detect()` now picks
HttpComponents for the control-plane client too. That is harmless (same timeouts, no filter there), and the end-to-end
runs used it. `HttpWorkloadTest.hostnameResolvingToLoopback_isBlockedAtConnectTime` checks the resolver path itself,
with no lookup of our own in front of it. An open question was whether HttpClient skips the resolver for IP literals.
Experiment: with the explicit literal check disabled, all 22 cases still passed, so 5.6 filters literals as well. The
explicit check stays as defence in depth, documented as such.

## 2026-10-02 — A test assumed the reaper waits; it skips
The first version of `checkpointRacingLeaseRecovery_isNeverCommittedByARecoveredAttempt` asserted that the attempt was
LOST after the race and failed: it was still RUNNING. The reaper locks expired leases with SKIP LOCKED, so when the
checkpoint held the attempt's row lock it skipped the attempt, as designed, and the next tick recovered it. The test
now runs that next tick before asserting. The invariant it checks (no checkpoint is ever committed by an attempt that
was already recovered) is unchanged and was never violated.

## 2026-10-02 — Revive and the budget CHECK
`CHECK (attempt_count <= max_attempts)` made revive impossible without forgetting history: `attempt_count` numbers the
attempts, so it must keep growing. V6 adds `budget_start` (set to `attempt_count` at a revive) and restates the guard
per budget, `attempt_count - budget_start <= max_attempts`. The retry policy counts attempts within the budget in the
same way. Revives are capped at 10 per job (a CHECK as well), so `attempt_count` stays bounded.

## 2026-10-02 — Two deadlocks the lease race tests found
Both appeared as `ERROR: deadlock detected` in the new race tests on real PostgreSQL 18. Neither had shown up in any
single-threaded test.
1. **Overlapping heartbeats.** Lease renewal was one multi-row `UPDATE ... WHERE worker_id = ? AND ...`. PostgreSQL
   locks rows in whatever order the plan visits them, so two renewals for the same worker could each hold a row the
   other needed. One worker heartbeats sequentially, but a client-side timeout followed by a retry, or two
   control-plane instances, overlap for real. `heartbeatRacingTheReaper_neverRevivesAnExpiredLease` failed on its first
   run. Fix: a CTE selects the renewable rows `ORDER BY id FOR UPDATE`, then the UPDATE joins it, so every renewal locks
   in the same order and they queue instead of deadlocking. The predicate is re-checked after any wait, so an attempt
   the reaper recovered meanwhile is still skipped (I10).
2. **Reapers across several workers.** A reaper batch was ordered by `lease_expires_at`. Ending each attempt locks
   its worker row (to release the reservation) and holds it until commit, so a batch that spans workers takes worker
   locks in expiry order, not id order. Two reapers could then wait on each other's worker rows, and so could a reaper
   and the scheduler, which locks workers in id order. Fix: lock the oldest expired leases with SKIP LOCKED as before,
   then return the batch `ORDER BY worker_id, id`. Sorting in SQL rather than Java matters: `UUID.compareTo` compares
   signed longs and disagrees with PostgreSQL's byte order. Verified by experiment: with expiry order restored,
   `completionRacingLeaseExpiry_hasExactlyOneWinnerPerAttempt` hit six deadlocks in one run; with worker order, none in
   repeated runs.

## 2026-10-02 — A draining worker must still claim what was placed on it
The Phase 5 plan said a DRAINING worker should be refused at `/claim`. That would strand work: the scheduler can place
an attempt just before an operator drains the worker. Heartbeats renew ASSIGNED leases implicitly (the worker has not
seen them yet), so the lease would never expire and the job would sit in SCHEDULED forever. Draining means "no new
placements", and the scheduler already enforces that. So a draining worker claims whatever is already assigned to it.
Only retired registrations (OFFLINE, DEREGISTERED) are refused, which is what I11 needs. Test:
`drainingWorker_stillClaimsWhatWasPlacedOnItBeforeTheDrain`.

## 2026-10-02 — Lease recovery, measured end to end
Run with host processes (control plane plus three workers as JVMs, PostgreSQL in Docker) because this environment
cannot build the images: Maven inside `docker build` has no route to Maven Central here. CI's compose job builds and
starts the real images. Results:
- `kill -9` of the worker running a 20 s `delay` job: lease expiry after 15.2 s, then ATTEMPT_LOST and
  RETRY_SCHEDULED (0 ms), a new placement on another worker 0.5 s later, then SUCCEEDED. Full timeline in
  FAILURE_SEMANTICS.md.
- Control plane killed for about 29 s (longer than the 15 s lease) while a job ran: on startup it extended the one
  active lease before reaping, and the job finished with a single attempt. This is the lease counterpart of the worker
  startup grace (2026-10-01 entry), and it worked the first time because that entry had predicted it.
- SIGTERM to a busy worker: it went DRAINING, finished the attempt, reported it, then deregistered.

Hot-path plans (`EXPLAIN ANALYZE`, 200,004 finished attempts plus 50 running, PostgreSQL 18.6, 4 vCPU Xeon @ 2.1 GHz
cloud container, commit `91f91d0`): the reaper's expired-lease batch is an index scan on the partial
`job_attempts_lease_idx` (0.19 ms); renewing 46 leases takes 1.65 ms; an empty claim takes 0.11 ms. The partial indexes
keep finished history out of every one of them. Renewal joins its CTE with a nested loop, quadratic in the number of a
worker's attempts, but that number is capped by its slots (≤ 256).

## 2026-10-02 — Self-invocation silently disabled @Transactional; MANDATORY caught it
Every scheduling test passed, but in the compose stack nothing was ever placed. `SchedulingCycle` had a convenience
overload `runCycle()` that called `this.runCycle(policy)`. A call on `this` bypasses the Spring proxy, so the
`@Transactional` on the real method never applied. The tests called the transactional overload directly; only the
background loop used the other one. Two design choices limited the damage. First, the lock-taking methods in
`JobPlacement` and `WorkerCapacity` use `Propagation.MANDATORY`, so without a transaction they threw at once instead
of scheduling without locks, which would have allowed real overcommit under concurrency. Second, a structured log made
the stack trace easy to find. A second bug surfaced at the same time: the loop caught only `DataAccessException`, so
the unexpected exception killed the scheduler thread for good. Fixes: remove the overload, make the loop survive any
`RuntimeException` (logged at ERROR with its stack trace), and add `SchedulerLoopTest`, which drives the real
background loop instead of the cycle. Lesson: test the call path production actually uses, not the convenient one.

## 2026-10-02 — Property tests without jqwik
The brief suggests jqwik, but jqwik 1.10 is built on JUnit Platform 1.14 while Spring Boot 4 ships JUnit Platform 6.
Mixing two platform generations in one test runtime is the kind of dependency risk that fails in confusing ways.
Instead, `PlacementPropertiesTest` generates cases from per-case seeds (`SplittableRandom(seed)`) and reports the seed
on failure, so every counterexample is reproducible. It loses shrinking, and keeps zero extra dependencies.

## 2026-10-02 — Scheduler window query plans
`EXPLAIN ANALYZE` with 200,000 finished and 2,000 runnable jobs: the oldest-first window is an index scan on the
partial `jobs_runnable_idx`, reading 10 buffers in 0.44 ms. The priority window sorts all runnable rows (2.46 ms). The
partial index keeps finished history out of the hot path entirely. Details in SCHEDULER.md.

## 2026-10-01 — A control-plane restart looked like the death of every worker
End-to-end test: restart the control plane while three healthy workers keep running. On startup the liveness monitor
retired **all** of them, and each had to register again under a new id. The cause: while the control plane is down
nobody records heartbeats, so on startup every `last_seen_at` is older than the offline threshold, and an outage of the
observer is indistinguishable from an outage of the observed. Once leases exist (Phase 5), the same mistake would
declare every running attempt lost and re-execute healthy work. The fix is a startup grace period
(`quantarun.workers.startup-grace`, default = offline threshold) during which nothing is retired, which gives every live
worker time to heartbeat again. Verified end to end: after a restart the three workers keep the same ids and none is
retired. Regression test: `WorkerRetirementGraceTest`. **The Phase 5 lease reaper needs the same grace.**

## 2026-10-01 — Path-based security decisions were fragile; separate namespaces instead
The first version had the project-key filter on `/api/*` skip requests whose path started with the worker-protocol
prefix. Two problems surfaced in review. First, `getRequestURI()` is not normalised, so
`/api/v1/worker-protocol/../jobs` would have skipped project authentication and still been routed to `/api/v1/jobs`.
Second, `getServletPath()` (normalised in Tomcat) is empty under MockMvc, so tests would not exercise the real behaviour.
Fix: the worker protocol moved to its own prefix, `/worker-api/v1`. The servlet container maps each filter by URL
pattern on the normalised path, so the two credential families cannot overlap. Inside the worker filter, the
*credential type* decides what a call may do (the bootstrap token can only register; a worker credential acts only as
its own worker), never the path.

## 2026-10-01 — Boot 4 notes: RestClient module, OTLP defaults, an SSRF hook
- `RestClient.Builder` auto-configuration lives in `spring-boot-starter-restclient` in Boot 4. Without it there is no
  builder bean.
- `spring-boot-starter-opentelemetry` exports OTLP metrics to `localhost:4318` by default, and every node logs
  connection errors when no collector runs. Export is now opt-in (`QUANTARUN_OTLP_ENABLED`) until Phase 10.
- `HttpClientSettings.withInetAddressFilter(...)` (Boot 4.1) filters resolved addresses at connect time. It is the
  natural place for the HTTP workload's SSRF guard: checking after DNS resolution defeats DNS-rebinding tricks that a
  hostname check misses.

## 2026-09-30 — Jackson 3 changed a default: missing primitives are now errors
Every job submission that omitted the optional `accelerators` field failed with 400 "Failed to read request".
An isolated reproduction showed the cause: Jackson 3 enables `FAIL_ON_NULL_FOR_PRIMITIVES` by default, so a
missing field bound to a primitive `int` is rejected instead of becoming 0. Rather than turning the feature off
globally, which would silently turn missing required numbers into zeros, request DTOs use `@NotNull Integer` for
required fields and an explicit default for optional ones. A second surprise: once any controller parameter carries
a constraint (the `Idempotency-Key` pattern), Spring 7 validates the whole method and reports `@Valid` body errors
as `HandlerMethodValidationException`, not `MethodArgumentNotValidException`. The error handler now unpacks
`ParameterErrors` so clients still get per-field violations. While the handler still used the old code path, one
request produced a `TypeNotPresentException: Type E not present` (a 500). The rewrite removed that path and the
error has not recurred in any report; the exact trigger was not isolated.

## 2026-09-30 — Idempotency: ON CONFLICT DO NOTHING instead of catching unique violations
The obvious implementation (INSERT, catch the unique violation, SELECT the winner) is wrong in PostgreSQL: the
failed INSERT aborts the surrounding transaction, so the SELECT cannot run in it. `INSERT ... ON CONFLICT DO
NOTHING RETURNING` makes a losing insert wait for the winner's transaction and then return no row, without an
error. The follow-up SELECT runs as a new statement under READ COMMITTED, so it sees the committed winner. The
fingerprint is computed over the *normalised* request with sorted JSON keys, so an omitted default and an explicit
default are the same request. Proven by 500 racing submissions.

## 2026-09-30 — Project API keys moved forward from the security phase
Jobs are project-scoped, so Phase 2 needed a caller identity. A temporary "X-Project" header would have been
exactly the kind of placeholder the brief forbids. Keys are 256-bit random secrets hashed with SHA-256 (a slow
password hash adds nothing for high-entropy secrets) and looked up by a public 8-hex prefix. They are verified in
constant time and shown once. Keys live in a `security` module rather than `projects`: `projects` needs the caller
identity for its admin checks, and putting keys there would have created a module cycle.

## 2026-09-30 — Heartbeats must not live on the row the scheduler locks
While designing the scheduling transaction: the scheduler locks worker rows `FOR UPDATE` to reserve capacity
safely. In PostgreSQL an ordinary `UPDATE` of the same row waits for that lock. If `last_seen_at` lived on
`workers`, every heartbeat would queue behind scheduling cycles, and slow heartbeats could even make healthy
workers look LATE. Decision: a separate `worker_heartbeats` table (ARCHITECTURE.md §4).

## 2026-09-30 — Toolchain: TypeScript 7 cannot be used with typescript-eslint yet
npm's `latest` tag for TypeScript is 7.0.2, but typescript-eslint 8.71 declares `typescript <6.1`. Taking the
newest compiler would silently break linting in CI. Pinned TypeScript 6.0.3 (ADR-0006).

## 2026-09-30 — Spring Boot 3.5 is out of OSS support
Boot 3.5's open-source support ended on 2026-06-30, and start.spring.io no longer offers 3.x. The project uses
Boot 4.1.1 (Framework 7, Jackson 3, Hibernate is not used).

## 2026-09-29/30 — Local Docker would not start: half-installed Windows feature
Symptoms: WSL2 reported "virtualization not enabled" even though firmware virtualization was on. Root causes,
in order:
1. The boot configuration had no `hypervisorlaunchtype`, so the hypervisor never started.
2. "Virtual Machine Platform" showed as *Enabled*, but its payload (`vmcompute.exe`, `vmwp.exe`) was
   missing. The CBS log showed the package *staged* but not installed, with a pending transaction.
3. A plain "Restart" deferred the pending servicing ("Deferring startup processing at users request").
   Only "Update and restart" applied it.

Lesson: trust the servicing log (`CBS.log`) and package states over `Get-WindowsOptionalFeature`, which reported
`RestartNeeded: False` while a reboot was actually required.
