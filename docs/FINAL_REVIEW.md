# Final review

The brief asks for one last review before release, as a skeptical senior engineer interviewing the author would do
it. This is that review, done on 2026-10-06 against `main` at `64520bb` plus the fixes on the Phase 15 branch. Every
answer points at the evidence: a test, a measurement or a file. Where the answer is "no" or "partly", it says so.

## Findings

Severity is the impact on a user of the system as it is meant to run (a local, single-operator stack), not on a
hypothetical large deployment.

| # | Finding | Severity | Status | Verification |
|---|---|---|---|---|
| 1 | The overview scanned the whole job history on every call: all-time counts by status, plus last-hour and last-15-minute windows with no index. The console asks for it up to once a second while events flow. | Medium | Fixed in `cf16a02`: counts unfinished jobs through their partial indexes and finished ones over the last hour; V12 indexes the windows | At 1,002,000 jobs: 259–318 ms of database time per overview before, 12–15 ms after (three runs each). `ConsoleApiTest` pins the new contract |
| 2 | Short ids were the first 8 characters of a UUIDv7, which is its timestamp. Every job in a burst, and every worker started together, showed the same short id, so the activity feed and the chaos timeline could not tell rows apart. | Medium (operability) | Fixed in `93f400d`: the random tail is shown; the chaos page names workers | `format.test.ts`; screenshots retaken |
| 3 | ARCHITECTURE.md said several control-plane instances may share one database. It was true by design but had never been run. | Medium (an unverified claim) | Verified live, no code change | Two control planes on one database, a worker attached to each, 300 idempotency keys each sent to both at once, a worker killed mid-run: 300 jobs, each succeeded once, 4 lost attempts recovered (2 by each instance through `SKIP LOCKED`), every reservation back to zero. ENGINEERING_LOG, 2026-10-06 |
| 4 | With several instances, some limits are per instance: at most 2 simulations and 50 event streams *per instance*, and a placement only wakes waiting claims on its own instance (claims on another instance find it on their 500 ms recheck). | Low | Documented in ARCHITECTURE.md | Code reading; the recheck path is `ExecutionController.CLAIM_RECHECK` |
| 5 | Nothing is ever deleted: jobs, attempts, events and decisions grow without bound. | Medium for a long-running deployment, Low for the local stack | Accepted and listed in the README's known limitations. Finding 1 removed the one hot query that read the whole history | Every other query is keyed by id, by a partial index on live rows, or paged by keyset (ENGINEERING_LOG, BENCHMARKS) |
| 6 | `PRIORITY` starves low priorities under a steady stream of urgent work. There is no ageing. | Low (by design) | Documented as the policy's trade-off in SCHEDULER.md; `FAIR_SHARE` and `DEADLINE` are the alternatives, and the policy lab shows the cost | SCHEDULER.md, SIMULATION.md |
| 7 | The lease reaper logs "attempt recovered" inside its transaction, so a rolled-back batch would leave a log line for a recovery that did not happen. Spans and metrics are already recorded after commit (I20); this log line is not. | Low | Accepted: a rollback here means the database failed, which the reaper also logs as a warning on the same tick | Code reading, `JobAttempts.recoverExpiredLeases` |
| 8 | The policy lab's charts labelled only the first of several tied policies as best. | Low | Fixed in `e4e0b05` | `charts.test.tsx` fails without the fix |
| 9 | `FairShareSchedulingTest.weightsSetTheShareOfService_acrossCycles` failed once in Phase 8 and was never explained. | Low (test) | Explained and fixed during the release verification: on a slow machine the test workers' only heartbeat aged past the 7 s LATE threshold, so they correctly stopped receiving work. The tests now heartbeat before each cycle; thresholds unchanged | ENGINEERING_LOG, 2026-10-06; full `./mvnw verify` green |
| 10 | The automated Claude Security scan that the project rules ask for before release has not run. | Process gap | Approved by the owner, but the development environment lacks the workflow runtime it needs. A second manual review ran instead and found three defects in what a tenant's job can make a worker do, all fixed (THREAT_MODEL.md, "Findings of the release review") | – |

No finding is high or critical. The two medium defects in code (1, 2) are fixed.

## The brief's questions

**Is this architecture unnecessarily complex?** For what it proves, no. It is one modular monolith, a worker
process and PostgreSQL. There is no broker, cache or orchestrator. ADR-0001 records why PostgreSQL coordinates
everything: row locks, `SKIP LOCKED` and CHECK constraints give the guarantees with one moving part. The parts that
look heavy are the parts under test: leases, fencing, the event tailer's watermark. Each exists because a test or a
live run broke without it (ENGINEERING_LOG).

**Is this actually distributed?** Partly, and the docs say how much. Workers are separate processes, with their own
failure domain, speaking HTTP only (ADR-0005). They are killed, paused, slowed and partitioned from the database in
tests and live. The control plane can run as several instances (finding 3), but its scale-out is bounded by one
PostgreSQL primary. That is a deliberate choice, stated in ADR-0001, not an accident.

**Are concurrency guarantees real?** They are enforced by the database, not by JVM memory: conditional writes
checked by affected-row count, partial unique indexes, CHECK constraints on capacity and attempt budgets. They are
tested by running every actor at once against real PostgreSQL (`ConcurrencyTortureTest`, auditing I1–I3 in every
snapshot). The race suites passed 10 of 10 repeated runs on `64520bb`, after 20 of 20 in Phase 12 and 5 of 5 in Phase
13 (INVARIANTS.md). INVARIANTS.md also says what the tests do not prove.

**Can resources be overallocated?** No. Reservations happen under `FOR UPDATE` on worker rows in id order, and CHECK
constraints (`reserved <= capacity`, non-negative) reject any write that would overcommit. 16 concurrent schedulers
per policy never overcommitted a worker (`SchedulingCycleTest.concurrentCycles_neverOvercommitOrDoublePlace`), CHECK violations are tested directly
(`WorkerFleetTest`), and the torture test audits reserved capacity
against active attempts in every snapshot.

**Can two workers execute the same assignment?** An assignment is an attempt row owned by one worker. Claim, renew,
report and checkpoint are all fenced by attempt id, worker id and an unexpired lease (I6, I10, I11). A partial
unique index allows one active attempt per job. If a lost worker keeps running its copy, the work can execute
twice. Only one result can ever be recorded: the stale worker's report is refused with `409 LEASE_EXPIRED`. That is
at-least-once execution with exactly-once completion, and the README says so.

**What happens when a worker dies?** Its leases stop being renewed. After the lease (15 s) the reaper marks the
attempts LOST, releases the reservations and the jobs retry at once on another worker. A staged job resumes after
its last checkpoint. Live, on this branch: killed at 14:11:53.3, detected at 14:12:06.9, re-placed 25 ms later, and
finished after resuming from stage 1 (DEMO.md).

**What happens when completion races lease expiration?** Expiry is a single decision point: after `lease_expires_at`
only the reaper may act on the attempt. A report that loses the race gets `409 LEASE_EXPIRED`. The torture test found
the version where this was not true (ENGINEERING_LOG, 2026-10-04). `completionRacingLeaseExpiry_hasExactlyOneWinnerPerAttempt`
requires all three possible endings to occur.

**What happens if PostgreSQL restarts?** The API answers `503` with Retry-After within 3 s, not after a 30 s pool
wait. Workers keep their reports until the database is back. The time the control plane could not hear heartbeats
is not charged to the workers: leases are extended before anything is judged (I22). Live, a 25 s outage under load
lost every running attempt before these fixes and none after (FAILURE_SEMANTICS.md).

**Are retries bounded?** Yes, three ways. `max_attempts` is 1–10 per job, held by a CHECK constraint. Backoff is
exponential with full jitter, capped, and honours Retry-After. A revive grants a fresh budget at most 10 times per job (CHECK
`jobs_revive_count_range`).

**Can a tenant starve another?** Not under `FAIR_SHARE`: 10,000 queued jobs from one tenant do not keep another
tenant's 10 jobs out of the first cycle (`FairShareSchedulingTest`). The policy lab shows the same thing on 2,000
jobs: under FIFO the two light tenants wait 527–531 s at the median, under FAIR_SHARE about 1 s. Under FIFO, PRIORITY
and BIN_PACKING a flood does delay others; that is what those policies mean. Per-project quotas cap queued and
running work under any policy.

**Can priority jobs starve normal jobs?** Under `PRIORITY`, yes, by design (finding 6). Under the other policies,
no.

**Are database indexes appropriate?** Every hot path was read with `EXPLAIN ANALYZE` (ENGINEERING_LOG, BENCHMARKS).
The scheduler windows use partial indexes over runnable rows only, so they stay small as history grows. The review
found one gap, the overview (finding 1), now fixed.

**Are transaction boundaries short?** Yes. A placing cycle holds its worker locks for 15 ms on average in a 1,000-job
burst (BENCHMARKS.md). Every other write is one or a few statements. The remaining cost is known and stated: the
cycle's lock hold is what serialises quota decisions (I17). Shortening it would mean planning outside the lock and
re-validating, which is a design change, not a tuning step.

**Are network calls inside transactions?** No. The control plane makes no outbound network calls at all; the worker
never touches the database (ADR-0005). There are no sleeps inside transactions either: the only two sleeps are the
event tailer's poll and the scheduler's idle pause, both outside any transaction.

**Are there hidden N+1 queries?** None found. The cycle used to send one `UPDATE` per waiting job, about 190 per
pass; Phase 13 found it with `pg_stat_statements` and replaced it with one statement. Job pages are one keyset query.
Events, attempts, checkpoints and decisions are each one query per job page.

**Are logs useful?** They are structured JSON (ECS), with `jobId`, `attemptId`, `workerId` and the trace id as
fields, and messages are fixed strings. A lost attempt logs at WARN with all three ids. Polling noise is kept out of
traces (ENGINEERING_LOG, 2026-10-04).

**Are metrics meaningful?** They cover queue wait, cycle time, placements, attempt outcomes and duration, fleet
capacity and reservations, and job counts. Tags are low-cardinality only, and every metric is recorded after commit,
so a rolled-back placement counts nothing (I20, `ObservabilityTest`).

**Are secrets protected?** Keys and worker credentials are 256-bit, stored as SHA-256, shown once, compared in
constant time and never logged (`SecretsNotLoggedTest`). THREAT_MODEL.md lists the residual risks. The automated scan
is still pending (finding 10).

**Are benchmark results reproducible?** Every number in BENCHMARKS.md comes with its date, commit, hardware, OS and
command. Each end-to-end figure is the median of three runs, with the range. One unexplained difference between a
single run and the repeated matrix is reported as unexplained rather than smoothed over.

**Does Docker start from a clean machine?** CI does exactly the README's quick start on a fresh runner with no cache
(`cp .env.example .env`, then `docker compose up --build --wait`, then probes through nginx) on every pull request.
In this development container the image build cannot fetch Maven dependencies through the sandbox's TLS-intercepting
proxy. The stack used for the demo and screenshots therefore ran the same compose services from jars built on the
host.

**Can another engineer understand the project?** The docs are layered: the README for the minute-one picture;
SPEC.md for semantics; ARCHITECTURE.md for structure; INVARIANTS.md for guarantees with their proofs; the ADRs for
decisions; ENGINEERING_LOG.md for what went wrong and why. DEMO.md walks through the system in five minutes.

**Would I approve this pull request?** Yes, with the open items above recorded rather than hidden: the pending
automated scan and unbounded history. The one test failure that was open is explained and fixed (finding 9).

## Extra questions from the v1 additions

**Is this just CRUD disguised as infrastructure?** No. The CRUD is the smallest part. The substance is placement
under contention with explanations, lease-based recovery with fencing, idempotent submission under races,
deterministic replay of the same planner, and fault injection. Each is backed by tests that try to break it.

**Are the charts showing real data?** Yes. Every console view reads the API, and the screenshots in the README come
from a live run on this branch: a seeded mixed workload, a killed worker and a chaos experiment. The only fixed data
in the web app is in its unit tests.

**Could a recruiter understand the value in 60 seconds?** The README opens with what it is, why it exists and
four screenshots. PORTFOLIO.md has the short versions.

**Could Martí explain every decision?** INTERVIEW_GUIDE.md is written for that, from the real discoveries in
ENGINEERING_LOG.md.
