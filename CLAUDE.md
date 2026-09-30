# QuantaRun

OpenTelemetry-native control plane for scheduling, executing, recovering, replaying and stress-testing
distributed AI workloads. It is a portfolio project by Martí Aguilera (github.com/martiaaguilera). It runs locally
at zero cost, with no API keys.

**Source of truth:**
- The owner's brief: `docs/brief/PROJECT_BRIEF.md` (+ `PROJECT_BRIEF_v1_ADDITIONS.md`).
- The semantics: `docs/SPEC.md`.
- How it is built: `docs/ARCHITECTURE.md`.
- Invariants and their proofs: `docs/INVARIANTS.md`.
- Decisions: `docs/adr/`.
- Status and discoveries: `docs/ENGINEERING_LOG.md`.

Read the relevant ones before changing behaviour.

## Layout

```
apps/control-plane     Spring Boot 4.1, Java 25 — modular monolith (Spring Modulith), explicit SQL via JdbcClient
apps/worker            Spring Boot worker process — HTTP only, no DB access (ADR-0005)
apps/worker-protocol   Java records shared by both sides of the worker HTTP protocol
apps/web               React 19 + TypeScript (strict) + Vite 8 operations console
infra/                 compose support files (otel collector, prometheus, ...)
benchmarks/  scripts/  docs/
```

## Commands

```
./mvnw verify                          # all Java modules: format check, compile, unit + Testcontainers tests (Docker required)
./mvnw -pl apps/control-plane -am test -Dtest=ClassName
./mvnw spotless:apply                  # format Java
cd apps/web && npm ci && npm run check # typecheck + lint + test + build
docker compose up --build              # full local stack
```

## Non-negotiable rules

- **Correctness over speed.** Every state change is a conditional write validated by `JobStatus`; the affected-row
  count is the result. Invariants I1–I16 in `docs/INVARIANTS.md` hold. Add the test when you add the mechanism.
- **Schema only through new Flyway migrations.** No generated DDL; never edit an applied migration.
- **No network I/O, workload execution or sleeps inside a DB transaction.** Keep transactions short, and lock jobs
  before workers (in id order).
- **Concurrency is tested against real PostgreSQL (Testcontainers).** Never mock the DB for locking semantics.
- **Never weaken a test to make it pass.** If an invariant test fails, fix the design and log it in ENGINEERING_LOG.
- **No arbitrary code execution.** Workloads are registered built-in executors; the HTTP workload keeps SSRF guards.
- **No new infrastructure** (Redis, Kafka, brokers, Kubernetes) without a measured need recorded in an ADR.
- **Benchmark numbers must be measured** and recorded with date, commit, hardware and command. Otherwise write
  "not measured".
- **TypeScript `strict` stays on**, with no `any` unless a comment explains why.
- **Code style:** no Service/ServiceImpl pairs, factories, managers or utils without a real reason. Records,
  constructor injection, package-private by default. Comments explain *why*, never *what*.
- **Architecture changes update the docs** (ARCHITECTURE, an ADR where it matters) in the same commit.
- **Before declaring done,** run `./mvnw verify`, plus `npm run check` for web changes, and exercise the change for
  real (API call, compose stack). Report anything that could not run.

## Git

Conventional Commits (`feat(scheduler): ...`), coherent commits, real history. The author is Martí Aguilera.
Never fake dates or authorship.

## Workflow

Load `.claude/skills/quantarun-engineering` for any code change. Use `/feature-dev` for phase-sized features,
the pr-review-toolkit agents after milestones, and `/claude-security` at the security checkpoints in the skill.
