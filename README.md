# QuantaRun

A local-first control plane for scheduling, executing, recovering, replaying and stress-testing distributed AI
workloads. It uses PostgreSQL for coordination and OpenTelemetry for visibility, and runs at zero cost with no
API keys.

> **Status: early development (Phase 8 of 15).** Implemented so far: the job API with a centrally enforced state
> machine, idempotent submission (proven with 500 concurrent duplicates on real PostgreSQL), cooperative
> cancellation, project-scoped API keys and OpenAPI. On the fleet side: three heterogeneous workers that register with
> per-worker credentials, heartbeat, drain and deregister; silent ones are retired, with a grace period after a
> control-plane restart. The scheduler places jobs with six policies (FIFO, priority, least-loaded, bin-packing with
> accelerator conservation, fair share, deadline) and records why each worker was chosen or rejected; 16 concurrent schedulers are tested not to
> overcommit any worker. Workers now execute built-in workloads (`delay`, `cpu-hash`, `mock-inference`, `fail`, `memory`) under
> leases: a worker killed mid-job is detected by lease expiry and its job finishes on another worker, a stale worker's
> late report is rejected, and failures are retried with backoff (honouring Retry-After) up to a budget; DEAD jobs can be
> revived. A `staged` workload checkpoints after every stage and resumes on another worker after a crash, and an `http`
> workload calls external endpoints behind an SSRF guard ([docs/FAILURE_SEMANTICS.md](docs/FAILURE_SEMANTICS.md)).
> Projects share the fleet fairly (weighted virtual time: a tenant flooding the queue cannot starve another), deadlines
> can be scheduled earliest-first, and per-project quotas hold work back with a stated reason
> ([docs/SCHEDULER.md](docs/SCHEDULER.md)). A deterministic simulator replays eight scenarios through the same planner
> and compares the policies; the same seed gives the same result hash ([docs/SIMULATION.md](docs/SIMULATION.md)). Chaos,
> observability and the console come next. This README only describes what exists.

## What it will be

Projects submit workloads. Heterogeneous workers advertise CPU, memory and simulated accelerator capacity.
A pluggable scheduler (FIFO, priority, least-loaded, fair-share, deadline, bin-packing) places each workload and
records *why*. Leases and heartbeats recover work from crashed workers. The same workload trace can be replayed
deterministically against different policies to compare their trade-offs.

- [docs/SPEC.md](docs/SPEC.md): semantics, the state machines, and acceptance criteria per phase.
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): components, and the transaction semantics of each operation.
- [docs/INVARIANTS.md](docs/INVARIANTS.md): the guarantees, how each is enforced, and the test that proves it.
- [docs/RESEARCH.md](docs/RESEARCH.md): what already exists, and the gap QuantaRun targets.
- [docs/adr/](docs/adr): the decisions that matter, with their trade-offs.

## Quick start

Requirements: Docker with Compose. Java 25 and Node 24 are needed only for development outside containers.

```bash
cp .env.example .env
docker compose up --build --wait
# Web console:            http://localhost:3000
# Control-plane health:   http://localhost:8080/actuator/health
```

## Development

```bash
./mvnw verify                       # format check, compile, unit + Testcontainers integration tests
cd apps/web && npm ci && npm run check
```

## Technology

Java 25, Spring Boot 4.1 (modular monolith verified by Spring Modulith), explicit SQL with `JdbcClient`, and
Flyway on PostgreSQL 18; React 19 and TypeScript 6 (strict) on Vite 8; Testcontainers, Docker Compose and
GitHub Actions. The reasoning behind each choice is in [ADR-0006](docs/adr/0006-development-environment-and-toolchain.md)
and the other ADRs.

## Author

Martí Aguilera. [GitHub](https://github.com/martiaaguilera) · [LinkedIn](https://www.linkedin.com/in/martiaaguilera/)

Licensed under the [MIT License](LICENSE).
