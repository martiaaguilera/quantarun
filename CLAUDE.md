# ForgeFlow

Distributed job-processing platform: clients submit jobs over a REST API, workers claim and execute them
with leases, heartbeats, retries with backoff and a dead-letter queue; a React dashboard shows jobs,
workers and metrics. Portfolio project by Martí Aguilera (GitHub/LinkedIn: `martiaaguilera`).

## Stack

- Backend: Java 21, Spring Boot 4.1.x (Framework 7, Security 7, Hibernate 7, Jackson 3), Maven wrapper
- Data: PostgreSQL (source of truth, Flyway migrations), Redis (ephemeral, justified uses only)
- Frontend: React + TypeScript (strict) + Vite
- Ops: Docker Compose, Testcontainers, Micrometer/Actuator, GitHub Actions

## Repository layout

The layout is planned until scaffolding lands. Update this section when it changes.

```
backend/            Spring Boot app, packages by feature (jobs, workers, scheduling, ...)
frontend/           React + Vite app
docs/               architecture, ADRs (docs/adr/), runbooks
docker-compose.yml  local stack
.claude/skills/     forgeflow-engineering (binding rules) + selected Spring Boot 4 skills
```

## Commands

These are the intended commands. Verify they exist before relying on them.

```
cd backend && ./mvnw verify                  # compile + unit + integration tests (needs Docker)
cd backend && ./mvnw -Dtest=ClassName test   # single test class
cd frontend && npm ci && npm run build       # production build
cd frontend && npm run typecheck && npm test
docker compose up -d                         # full local stack
```

## Rules

- Binding engineering rules: `.claude/skills/forgeflow-engineering/SKILL.md`. Load it before any code change.
- Invariants: PostgreSQL is the source of truth; delivery is at-least-once; every job state change is a
  conditional write validated against the transition table; no job execution or network I/O inside a DB
  transaction; schema changes only through new Flyway migrations.
- Naming: Java packages `io.github.martiaaguilera.forgeflow.<module>`; SQL snake_case; REST `/api/v1/<plural-kebab>`;
  migrations `V<n>__<description>.sql`; tests `method_condition_expectedOutcome`.
- Commits: Conventional Commits (`feat(jobs): ...`), one coherent change each. Author: Martí Aguilera.
  Never falsify authorship or dates.

## Definition of done

1. The backend build `./mvnw verify` passes. For frontend changes, the build, typecheck and tests also pass.
2. New behaviour has tests. SQL, locking and transaction code is tested against real PostgreSQL. Concurrency code has a concurrent test.
3. LSP diagnostics show no new errors or warnings.
4. Review agents have run for milestone-sized changes, and their findings are fixed or consciously deferred.
5. Docs or an ADR are updated when behaviour, configuration or architecture changed.
6. Everything claimed as working was actually run: migrations, `docker compose up`, API calls.

If a step could not run (for example, Docker is unavailable), say so explicitly and do not claim success.
