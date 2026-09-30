# ADR-0001: PostgreSQL is the system of record and the coordinator

- Status: accepted (2026-09-30)

## Context
QuantaRun needs durable job state, mutual exclusion for placement and leases, uniqueness for idempotency,
and an event history. The usual portfolio answer is to add a broker (Kafka or RabbitMQ) and Redis. Each extra
system adds a failure mode, a consistency boundary between systems (the "dual write" problem), and
operational weight. None of that is justified before a measurement shows it is needed.

## Decision
PostgreSQL 18 is the only stateful dependency. Coordination uses row locks (`FOR UPDATE`,
`SKIP LOCKED`), conditional updates, and unique/check constraints. The control plane is a modular monolith;
workers are separate processes that talk to it over HTTP.

## Consequences
- Every state change and its event are committed atomically, in one transaction.
- Throughput is bounded by one primary. Phase 13 measures where the limit is.
- A broker becomes justified only if measured assignment throughput or notification latency misses a stated
  target that tuning cannot fix. That trigger is recorded in BENCHMARKS.md, not assumed.
