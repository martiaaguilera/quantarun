# Portfolio material

Short descriptions of QuantaRun for a CV, LinkedIn and GitHub. Every claim here is implemented and tested, and every
number is measured, with its source given.

## Project description

QuantaRun is a control plane for distributed AI workloads, built as a portfolio project. Projects submit jobs.
Heterogeneous workers advertise CPU, memory, simulated accelerators and labels. A scheduler places each job under
one of six policies and records why every candidate worker was chosen or rejected. Workers run jobs under leases, so
a worker that dies loses nothing: its lease expires, its reservation is released, and the job runs again elsewhere,
from its last checkpoint. A simulator replays workloads through the same placement code in simulated time, to
compare policies with reproducible results.

PostgreSQL is the only coordinator. Every state change is a conditional write, and CHECK constraints make invalid
states impossible to commit. The concurrency guarantees are tested against real PostgreSQL, including a test that
runs every actor at once and audits the invariants in every snapshot. The system is traced end to end with
OpenTelemetry and has an operations console. It runs locally with Docker Compose at no cost.

## CV description (one line)

PostgreSQL-coordinated workload scheduler in Java 25 and Spring Boot 4: resource-aware placement with explained
decisions, lease-based crash recovery, deterministic policy replay, and concurrency invariants verified against
real PostgreSQL.

## Three CV bullet points

- Built a PostgreSQL-coordinated distributed workload scheduler (Java 25, Spring Boot 4, explicit SQL) with
  lease-based crash recovery, resource-aware placement under six policies and deterministic policy replay. A killed
  worker's job was re-placed 25 ms after its lease expired and resumed from its last checkpoint.
- Verified 22 concurrency and correctness invariants with 410 Java tests against real PostgreSQL (Testcontainers),
  including property tests and a torture test that runs schedulers, crashing workers and lease recovery at once. It
  found and fixed a lease-fencing race; the race suites then passed 55 of 55 repeated runs.
- Profiled the system end to end (JFR, `pg_stat_statements`, `EXPLAIN ANALYZE`) and replaced polling with signalled
  long polls and batched statements. Median time from submission to start fell from 526 ms to 11 ms, and burst
  throughput rose from 15.3 to 71.7 jobs/s on the same hardware.

Sources: DEMO.md (recovery), INVARIANTS.md (tests and repeated runs), BENCHMARKS.md (performance).

## Technology list

Java 25 · Spring Boot 4.1 · Spring Modulith · PostgreSQL 18 · Flyway · JdbcClient (explicit SQL) · Testcontainers ·
JUnit 6 · OpenTelemetry · Micrometer · Jaeger · Prometheus · React 19 · TypeScript (strict) · TanStack Query ·
Vite · Docker Compose · nginx · GitHub Actions

## LinkedIn description

QuantaRun: a control plane for scheduling, recovering and replaying distributed AI workloads, built to explore what
makes job systems fail.

Workers die, reports arrive after their lease ran out, and two schedulers reach for the same last slot. In
QuantaRun, PostgreSQL row locks, leases with fencing and CHECK constraints keep each of those cases correct, and
tests try to break them against a real database. A simulator replays workloads through the scheduler's own code, so
policies like FIFO and fair share can be compared on the same input. In one scenario, fair share cut two light
tenants' median wait from about 530 s to about 1 s; the flooding tenant waited 758 s instead of 624 s.

Java 25, Spring Boot 4, PostgreSQL 18, OpenTelemetry, React. Every number in the repository is measured and dated.

## GitHub repository description

PostgreSQL-coordinated control plane for AI workloads: explained placement, lease-based crash recovery,
deterministic policy replay, fault injection and OpenTelemetry tracing. Java 25, Spring Boot 4, React.

Suggested topics: `distributed-systems`, `scheduler`, `postgresql`, `spring-boot`, `java`, `opentelemetry`,
`fault-tolerance`, `testcontainers`.
