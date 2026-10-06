# Interview guide

How to explain QuantaRun, and how to answer the questions it invites. Every story here happened and is recorded in
ENGINEERING_LOG.md, with the test or measurement that settled it. When you don't know an answer in an interview, say
what you would measure; this project is mostly a record of doing that.

## The 30-second explanation

QuantaRun schedules AI-style jobs onto a fleet of different workers and keeps them correct when things fail. A
worker can die mid-job, a report can arrive after its lease ran out, and two schedulers can reach for the same last
slot. In each case PostgreSQL row locks, leases with fencing, and constraints decide the outcome, and the tests try
to break that against a real database. It also replays the same workload under different scheduling policies, with
reproducible results, so you can see the trade-off before picking one.

## The 2-minute technical explanation

There are three parts: a Spring Boot control plane, worker processes, and PostgreSQL.

1. **Submission.** A tenant submits a job with resource requests and labels. Submission is idempotent: an
   `Idempotency-Key` plus a fingerprint of the request means a retried POST returns the same job.
2. **Placement.** A scheduling cycle locks a window of runnable jobs with `SKIP LOCKED` and the workers with
   `FOR UPDATE`, in id order. It runs a pure policy function over that snapshot and, in the same transaction,
   reserves capacity and inserts an attempt with a lease. CHECK constraints make overcommitting a worker impossible
   to commit. Every decision stores each candidate's verdict.
3. **Execution.** Workers never touch the database. They long-poll for assignments, heartbeat every 3 s to renew
   their leases, and report outcomes. Every write a worker makes is fenced by attempt id, worker id and an unexpired
   lease.
4. **Recovery.** If a worker dies, its lease expires. A reaper marks the attempt lost, releases the reservation and
   the job retries elsewhere, resuming after its last checkpoint. A late report from the "dead" worker gets `409`.
5. **Replay.** The policies are pure, so a discrete-event simulator runs the same code in simulated time. The same
   seed gives the same SHA-256 result hash.
6. **Observability.** A job is one OpenTelemetry trace across both processes, and metrics are recorded only after
   commit.

What I'd stress: the guarantees are in the database, the tests run real concurrency against real PostgreSQL, and
the numbers in the repository are measured.

## Topics

### Architecture

A modular monolith, not microservices. The modules (`jobs`, `workers`, `scheduler`, `execution`, `simulation`, …)
are package boundaries that Spring Modulith verifies. They share one transaction when they must: placement touches
jobs and workers atomically. Splitting them into services would turn that transaction into a distributed protocol
for no benefit at this size. Workers are separate processes because they are the failure domain: killing one is the
point of the demo.

### PostgreSQL as the coordinator

The one-sentence reason: every guarantee QuantaRun makes is about rows changing together atomically. PostgreSQL
already offers that, plus row locks, `SKIP LOCKED` and constraints. A broker would add a second source of truth to
keep consistent (ADR-0001). Four tools do most of the work:
- **Conditional writes.** `UPDATE ... WHERE id = ? AND status = ?`. The affected-row count says whether you won the
  race. A lost race is a typed result, not an exception.
- **CHECK constraints** as the last line of defence: reserved capacity between 0 and capacity, attempts within the
  budget, revives at most 10.
- **Partial unique indexes**: at most one active attempt per job.
- **Lock order**: jobs first, then workers in ascending id, everywhere. Two deadlocks were found by breaking this
  rule (see the hardest race below).

### SKIP LOCKED

`SELECT ... FOR UPDATE SKIP LOCKED` lets several schedulers or reapers take disjoint batches without waiting on each
other. Use it for **work queues**: the scheduler's job window and the reaper's expired leases. Do not use it where
waiting is the correct behaviour. Worker rows are locked with plain `FOR UPDATE`: a cycle that skipped a locked
worker would see a fleet with a hole in it and make a worse decision. Live with two control planes, the reaper
batches split the four lost attempts two and two, without coordination.

### Leases and heartbeats

A heartbeat says "I'm alive"; a lease says "this attempt is mine until T". Liveness is always a guess, because a
slow worker looks exactly like a dead one. So the design does not try to guess right. It makes a wrong guess
harmless: after `lease_expires_at`, only the reaper may act on the attempt, and the worker's report is refused. The
result is at-least-once execution with exactly-once completion.

Heartbeats live in their own table (`worker_heartbeats`). The scheduler holds `FOR UPDATE` on worker rows during a
cycle, and a heartbeat that updated the worker row would wait behind every cycle (ENGINEERING_LOG, 2026-09-30).

### Idempotency

The naive version (INSERT, catch the unique violation, SELECT the winner) is wrong in PostgreSQL: the failed INSERT
aborts the transaction, so the SELECT cannot run in it. `INSERT ... ON CONFLICT DO NOTHING RETURNING` makes the loser
wait for the winner's commit and return no row. A fresh SELECT under READ COMMITTED then sees the winner. The request
fingerprint is computed over the normalised request, so an omitted default equals an explicit one. Proven with 500
racing duplicates, and live across two control-plane instances: 300 keys, each sent to both at once, gave 300 jobs.

### Transactions and isolation

Everything runs at READ COMMITTED, PostgreSQL's default. The correctness comes from row locks and conditional
writes, not from SERIALIZABLE. Be ready to explain why that is enough. Each transaction locks the rows it is about
to change, and re-checks its predicate after any wait: PostgreSQL re-evaluates the WHERE clause on the new row
version. SERIALIZABLE would also work, but it would turn contention into retries everywhere.

Rules:
- no network I/O, workload execution or sleeps inside a transaction;
- transactions are short (a placing cycle averages 15 ms under a burst);
- `MANDATORY` propagation on methods that must join the caller's transaction.

`MANDATORY` exists because a self-invocation once silently disabled `@Transactional` (ENGINEERING_LOG, 2026-10-02).

A subtle one: `now()` is the transaction's start time, not the time of the write. A checkpoint and a reaper racing
on one row can commit in one order and be timestamped in the other. Event ids, drawn under the row lock, are the
true order (ENGINEERING_LOG, 2026-10-02).

### Resource scheduling

Each job asks for CPU, memory, accelerators and a slot, plus labels. A worker fits if every resource fits and it has
the labels. The policies differ in which job goes first and which fitting worker wins. `BIN_PACKING` scores by
dominant utilisation after placement and penalises putting accelerator-free jobs on accelerator workers. Every
rejected candidate gets a verdict (`MISSING_LABELS`, `INSUFFICIENT_FREE_CAPACITY`, …), so "why is my job waiting?"
always has an answer.

### Fair scheduling

Start-time fair queuing over projects. Each project's virtual time is the service it has received divided by its
weight, and the backlogged project with the lowest virtual time goes next. Two details matter more than the formula:
- **The window.** An oldest-first window of 200 jobs, taken when one tenant had queued 10,000, contained only that
  tenant's jobs. The policy never saw the other tenant. The window is now round-robin across projects (ENGINEERING_LOG,
  2026-10-02).
- **No banked credit.** An idle project returns at the current minimum virtual time. Otherwise its stale, low value
  would take every slot.

Result: 10,000 queued jobs from one tenant do not keep another tenant's 10 out of the first cycle. FIFO places none
of them.

### Failure recovery

Walk through the kill. Heartbeats stop, and the lease (15 s) runs out. The reaper locks expired leases with
`SKIP LOCKED`, marks the attempts LOST, releases the reservations and decides the retry, all in one transaction.
The job is placed again within milliseconds. A staged job resumes after its last committed checkpoint. Live: killed
at 14:11:53.3, lost at 14:12:06.9, re-placed 25 ms later, resumed after stage 1.

The less obvious part is not blaming the workers for the control plane's own deafness. After a control-plane
restart or a database outage, nobody could renew a lease, so every lease looks expired. Reaping then would re-run
the whole fleet's work. The fix is I22: when the control plane has not heard anything for more than two heartbeat
intervals, it extends every lease before judging any of them.

### Retry semantics

Failures are classified: `TRANSIENT`, `RATE_LIMITED`, `TIMEOUT`, `WORKER_LOST`, `INVALID_INPUT`, and so on.
Permanent classes end the job; the others retry with exponential full-jitter backoff, which honours a provider's
Retry-After. Full jitter spreads out retries of jobs that failed together, for example during a provider outage. The
budget is `max_attempts`, enforced by a CHECK constraint. A DEAD job can be revived with a fresh budget at most 10
times, and every earlier attempt stays in history.

### Simulation

The simulator is a discrete-event loop over a generated workload: arrivals, completions, failures, worker deaths.
It calls the same `PlacementPlanner` the scheduler uses. Determinism comes from three things: pure policies (no
clock, randomness or SQL), a seeded `SplittableRandom`, and stable ordering everywhere. Planning time is measured, so
it is excluded from the hash. When the simulator was made 2.5× faster, 48 golden hashes and a lean-equals-full
property test made sure no result changed. A mutation that dropped the quota check survived the first version of
that property test, which is why it now has random quotas.

### OpenTelemetry

A job outlives the request that submitted it: it is placed later, on another thread, and run in another process.
So the submission's W3C `traceparent` is stored with the job, and placement and the worker's run continue that
trace. Spans and metrics for a placement are emitted after commit; a rolled-back placement once left a phantom span
(I20). Polling (claims, heartbeats, scheduled tasks, scrapes) was creating hundreds of empty traces, and it is
filtered out before sampling.

### Benchmarking

`benchmarks/e2e/bench.py` drives the public API, then reads the database's own timestamps, so the driver's latency
is not counted. Each result is the median of three runs, with the range, the commit and the hardware. The lesson
was that the baseline matched the arithmetic of the polling intervals: placement waited half the scheduler's idle
delay, and the claim waited half the worker's poll. Waking the scheduler alone did not raise throughput. Long
polling did, and batching the cycle's per-job statements (found with `pg_stat_statements`) did again. One
difference between a single run and the repeated matrix is reported as unexplained rather than explained away.

### Trade-offs to own

- **At-least-once execution.** Exactly-once completion is guaranteed; exactly-once side effects are not, and cannot
  be without the external system's cooperation (idempotency keys at the provider).
- **One database.** The simplest correct design, and the scale ceiling.
- **Strict PRIORITY starves.** That is what the policy means; FAIR_SHARE and DEADLINE are the alternatives.
- **Fair share charges an estimate at placement**, not measured runtime, so long jobs are under-charged.
- **The cycle holds worker locks for its whole duration.** That is what serialises quota decisions (I17), and the
  remaining throughput gap.
- **No history retention** yet.

## Deep-dive questions

**What was the hardest race condition?** Two candidates, both found by tests on real PostgreSQL.
- **Deadlocks from lock order.** Lease renewal was one multi-row `UPDATE`, and PostgreSQL locks rows in plan
  order, so two overlapping renewals for one worker deadlocked. The reaper ordered its batch by expiry, so a batch
  spanning workers took worker locks out of id order and deadlocked against the scheduler. The fixes: lock in id
  order in a CTE before updating, and sort the reaper's batch by worker id *in SQL*. Java's `UUID.compareTo`
  compares signed longs and disagrees with PostgreSQL's byte order.
- **The report that beat the reaper.** The torture test caught a worker reporting success for an attempt its own
  heartbeat had just been told was lost. The lease had expired, but the reaper had not run yet, and the report
  checked only status and ownership. The fix made expiry the single decision point: report and checkpoint now check
  `lease_expires_at > now()` under the row lock.

**What were the biggest bugs?**
- A **database outage under load lost every running attempt and retired every worker**, although all of them were
  alive. It took four live runs to find every cause:
  - a 30 s connection-pool wait;
  - deafness charged to the workers;
  - workers dropping reports after five tries;
  - a report beating the lease catch-up.

  After the fixes, the same outage lost nothing.
- A **heartbeating worker that never claimed held its work forever**, found by the first live chaos run. That is now
  I19.
- **Tailing `job_events` by `id > last`** lost events whose transaction committed after a higher id. Under concurrent
  cycles that is normal, not rare. The tailer now keeps a low watermark and waits on gaps.

**How do you know the scheduler never overcommits?** Three layers:
- property tests over 2,000 random fleets per policy;
- 16 concurrent cycles per policy against real PostgreSQL;
- a CHECK constraint that rejects the write if all else fails, tested directly.

**Why not use `LISTEN/NOTIFY` for the event stream?** One reader polling every 250 ms is cheaper, and it needs no
connection held outside the pool. A notification would still need the same watermark logic to be correct.

**Why HTTP for workers rather than direct database access?** A worker is untrusted with anything but its own work.
With HTTP, its credential grants exactly that, and every write passes through the fence. Direct database access would
put the fencing logic in every worker and give each one the keys to everyone's jobs (ADR-0005).

**How would you add a new scheduling policy?** Implement the pure interface, then add it to the property tests and
the simulator's golden hashes. It is immediately comparable in the policy lab.

## "What would you change at 100× scale?"

Be concrete about which limit comes first, and say what you would measure before changing anything.
1. **Worker lock hold in the cycle.** It is already the measured bottleneck at 72 jobs/s on 10 slots. Plan outside
   the lock against a snapshot, then lock and re-validate only the chosen workers. Alternatively, shard the fleet
   into pools with their own cycles. Either needs new race tests for quota decisions.
2. **History growth.** Partition `jobs`, `job_attempts`, `job_events` and `scheduler_decisions` by time, and drop old
   partitions. Archive to object storage if history matters.
3. **The event stream.** Fan out from one tailer per instance to many subscribers. That is already the design, but
   the per-instance caps would need to become global.
4. **Heartbeat write load.** 10,000 workers at 3 s is about 3,300 writes/s. That is feasible on one primary but
   worth batching, or moving liveness to a dedicated store, with leases staying in PostgreSQL.
5. **Read load** from the console onto a replica.

Only after those: replace PostgreSQL as the queue, not as the system of record.

## "When would Kafka become useful?"

When there are many consumers of the job lifecycle that are not the scheduler: billing, analytics, audit, other
teams' services. Each would want its own copy of the event stream, at its own pace, with replay. Today there is one
consumer (the console), and polling `job_events` serves it. If Kafka arrived, it would carry events out through an
outbox written in the same transaction as the state change, never act as the source of truth for job state. As a
work queue it would be the wrong tool: placement needs to see the whole fleet's capacity and the queue at once, and
a partitioned log cannot give that view.

**RabbitMQ** fits a different shape: many independent workers pulling homogeneous tasks with acknowledgements. That
works for "run this task somewhere", not for "place this job on the worker whose free CPU, memory and accelerators
fit it, fairly across tenants". Resource-aware placement needs the global view, which is why the scheduler and
PostgreSQL do it.

## "When would Kubernetes become useful?"

For running QuantaRun itself: restarting crashed processes, rolling upgrades, and scaling the worker fleet. Compose
does the first well enough locally. Kubernetes would not replace the scheduler. It places pods on nodes; QuantaRun
places jobs on long-lived workers with tenant fairness, explained decisions, per-job leases and replay. A real
deployment might run workers as a Kubernetes Deployment and let QuantaRun schedule onto them, or compare against
Kueue or Volcano for batch jobs on Kubernetes (RESEARCH.md).

## Questions to ask yourself before the interview

- Can I draw the lock order and explain both deadlocks from memory?
- Can I explain why READ COMMITTED is enough here?
- Can I walk through the kill timeline, including what "detected" means when nothing detects anything?
- Can I say what is *not* guaranteed (exactly-once side effects, scale beyond one primary) without being asked?
