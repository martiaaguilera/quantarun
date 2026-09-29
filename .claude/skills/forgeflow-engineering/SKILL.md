---
name: forgeflow-engineering
description: >
  Canonical engineering rules for the ForgeFlow repository. Use before planning, writing, reviewing or
  testing any ForgeFlow backend (Java/Spring Boot/PostgreSQL/Redis), frontend (React/TypeScript) or
  infrastructure (Docker Compose, CI) change. Overrides conflicting guidance from generic skills.
---

# ForgeFlow Engineering Rules

ForgeFlow is a distributed job-processing platform. Correctness under concurrency and failure matters
more than feature count. This file holds the binding rules; rationale lives in `docs/`.

Precedence: product spec > this skill > the narrow Spring skills in `.claude/skills/` > general habit.
When a rule here is broken deliberately, record the reason in an ADR (`docs/adr/`).

## Baseline (verified 2026-09-29)

- Java 21 (Temurin). Spring Boot **4.1.x** → Spring Framework 7, Spring Security 7, Hibernate 7,
  Jakarta Persistence 3.2, **Jackson 3 (`tools.jackson`)**. Boot 3.5 is out of OSS support; do not use it.
- Maven via the checked-in wrapper (`./mvnw`). Versions come from the Boot BOM; no ad-hoc version pins.
- PostgreSQL is the source of truth. Redis only for justified ephemeral concerns.
- Frontend: React + TypeScript (strict) + Vite.
- Local stack: Docker Compose. Testcontainers uses the same image tags as Compose.
- Before using an API you are not certain exists in these versions, check official docs or source.

## Architecture

- MUST: modular monolith, packages by feature/domain (`jobs`, `workers`, `scheduling`, ...), not by layer
  across the whole app. Keep a module's internals package-private where Java allows.
- MUST: controllers only validate input, translate HTTP, call one application service, and return DTOs.
- MUST: JPA entities never leave the application layer; API uses `record` DTOs.
- MUST: job state changes go through one place that enforces the allowed transition table.
- MUST NOT: add an interface with a single implementation, factories, "manager" wrappers or generic
  frameworks without a second real use. No premature microservices, no fake event-driven design.
- MUST NOT: use Lombok. Use records, explicit constructors, and IDE-generated accessors where needed.
  (Upstream skill snippets use Lombok; translate them.)
- SHOULD: constructor injection only, `final` fields, no field `@Autowired`.

## Concurrency and job semantics

- MUST: claim work with a single SQL statement using `FOR UPDATE SKIP LOCKED` (or an equivalent
  conditional `UPDATE ... RETURNING`), in a short transaction. Never "select then update" without a lock
  or version predicate.
- MUST: every state transition is a conditional write (`WHERE id = ? AND status = ?` or `@Version`) and
  the caller checks the affected-row count. A lost race is a normal outcome, not an exception to swallow.
- MUST: delivery is at-least-once. Leases have an expiry; a heartbeat extends them; an expired lease makes
  the job reclaimable. Handlers must be idempotent. Never claim exactly-once.
- MUST: retries of jobs are durable state (`attempt`, `next_attempt_at`, backoff with jitter, max attempts →
  dead letter). Core `@Retryable` is only for transient infrastructure calls (e.g. optimistic-lock
  conflicts) and must wrap — not sit inside — the `@Transactional` method.
- MUST: no network I/O, sleeps or job execution inside an open DB transaction.
- MUST: time comes from an injected `java.time.Clock`, so tests control it.
- MUST: concurrency-sensitive code ships with a test that runs real concurrent workers against real
  PostgreSQL (Testcontainers) and asserts no duplicate or lost execution.
- SHOULD: use virtual threads for blocking job execution only after measuring pool/connection limits;
  the Hikari pool size bounds real parallelism.

## Persistence (PostgreSQL + Flyway)

- MUST: every schema change is a new Flyway migration `V<n>__<snake_case>.sql`; never edit an applied one.
  Declare `spring-boot-starter-flyway` + `flyway-database-postgresql`. Do not set `baseline-on-migrate`.
- MUST: `spring.jpa.hibernate.ddl-auto=validate`. Hibernate never creates schema.
- MUST: DB constraints enforce invariants (NOT NULL, CHECK on status values, UNIQUE on idempotency keys,
  FKs). Index every FK and every column used in claim/poll predicates; check `EXPLAIN` for the claim query.
- MUST: `@Enumerated(STRING)`, `@Version Long` on mutable aggregates, `FetchType.LAZY` on to-one,
  `timestamptz` + `Instant`, UUID keys.
- MUST: idempotent submission uses a DB unique constraint (`INSERT ... ON CONFLICT DO NOTHING`) in the same
  transaction as the job insert — not Redis, not check-then-insert.
- SHOULD: native SQL for queue operations (SKIP LOCKED, RETURNING) is expected; keep it in the
  repository of that module, tested against PostgreSQL. No H2.

## Redis

- MUST: justify each Redis use in an ADR. Losing all Redis data must not lose or duplicate a job.
- MUST NOT: enable Jackson polymorphic default typing for Redis values. Use `StringRedisTemplate` or a
  typed serializer for one known record type.
- MUST: every key has a TTL and a documented `forgeflow:<area>:<id>` name. Rate-limit counters use an
  atomic script, not `INCR` then `EXPIRE`.

## API and errors

- MUST: `/api/v1/...`, plural kebab-case resources, correct status codes (201 + `Location` on create,
  202 for accepted async work, 409 for state conflicts).
- MUST: plain DTO success responses (no `success/data` envelope) and **RFC 9457 Problem Details** for all
  errors, including 401/403 from the security filter chain. Stable `errorCode` extension; no stack traces
  or internal messages in responses.
- MUST: bean-validate request DTOs; cap page size (`spring.data.web.pageable.max-page-size`); allowlist
  sort fields; keyset pagination for large job lists.
- SHOULD: code-first OpenAPI; the published spec must match real behaviour (verified by a test).

## Security

- MUST: job payloads and handler parameters are untrusted data. Never pass them to a shell, `Runtime.exec`,
  `ProcessBuilder`, SpEL, reflection-based class loading, template engines or Java deserialization.
- MUST: API keys are stored hashed (never plaintext), compared in constant time, shown once on creation,
  and never logged. Secrets come from environment/config tree only; fail startup if missing.
- MUST: only `health` and `info` actuator endpoints are public; everything else is authenticated or unexposed.
- MUST: frontend never uses `dangerouslySetInnerHTML` with API data.

## Observability

- MUST: structured JSON logs with trace/span IDs; log job IDs, never payloads or keys.
- MUST: Micrometer metrics with low-cardinality tags only (`job_type`, `outcome`, `queue`); job IDs go to
  logs/traces. Core metrics: queue depth, claim latency, execution duration, retries, DLQ size, lease expiries.
- MUST: liveness does not depend on PostgreSQL/Redis; readiness does.

## Testing

- MUST: unit tests for pure domain logic (state machine, backoff); integration tests with Testcontainers
  for anything touching SQL, transactions, locking, Flyway or Redis; `@WebMvcTest` for HTTP contracts
  incl. error bodies. Prefer fewer meaningful integration tests over deep mock hierarchies.
- MUST: tests are deterministic — inject `Clock`, no `Thread.sleep` for synchronisation (use latches /
  Awaitility with bounded timeouts).
- MUST: AssertJ; names describe behaviour (`claim_whenTwoWorkersRace_onlyOneWins`).

## Frontend

- MUST: `strict: true`; no `any` without a comment explaining why; API types defined once and validated at
  the boundary.
- MUST: dense, readable developer-tool UI: tables, clear status colours with text labels (not colour
  alone), keyboard focus, no decorative gradients/glassmorphism/gratuitous animation.
- SHOULD: components are extracted on second real reuse, not in anticipation.

## Dependencies

Add a dependency only if it solves a concrete problem that the JDK / Spring cannot, is maintained, and you
can explain it in one sentence in the PR/commit. Record substantial ones in an ADR.

## Workflow and tools

- Significant features (job engine, workers, retries, scheduling, workflows, observability): run
  `/feature-dev`. Where the spec already answers a question, cite the spec instead of re-asking.
- Loop: plan → implement → `./mvnw verify` / `npm run build && npm run typecheck` → LSP diagnostics clean →
  review → fix → document. Concurrency features add concurrency tests and a race-condition review.
- After each milestone: `pr-review-toolkit` agents (code-reviewer, silent-failure-hunter, pr-test-analyzer,
  type-design-analyzer; code-simplifier when complexity grew).
- `/claude-security` scans at: (1) REST + persistence done, (2) distributed workers done,
  (3) authentication/API keys done, (4) before calling the project portfolio-ready. Triage findings;
  don't auto-apply low-confidence patches.
- UI work: `frontend-design` skill, constrained by the Frontend rules above.
- Use `jdtls` / `typescript-language-server` (LSP tool) for references and diagnostics before text search.
- Never mark work done from reading code: builds, tests, migrations and `docker compose up` must actually run.

## Recorded deviations from upstream skills

| Upstream says | ForgeFlow does | Why |
|---|---|---|
| Lombok `@Getter`/`@RequiredArgsConstructor` | No Lombok | Fewer build plugins; records/constructors suffice |
| Optional `success/data` envelope | Plain DTOs + RFC 9457 | One error policy, standard clients |
| Redis default typing with allowlist | No default typing | Avoid deserialization gadget surface |
| Redis `INCR` then `EXPIRE` rate limiter | Atomic Lua script | The two-step version can leave a key with no TTL |
| Flyway `baseline-on-migrate: true` | Not set | Greenfield DB; baseline hides mistakes |
| 70/20/10 test pyramid | Integration-heavy where SQL/concurrency matter | Correctness lives in the DB |
| `@Retryable` for transient failures | Only for infra conflicts; job retries are durable | Retries must survive crashes |
