---
name: quantarun-engineering
description: >
  Binding engineering rules for the QuantaRun repository. Use before planning, writing, reviewing or testing
  any QuantaRun code: control-plane or worker (Java 25, Spring Boot 4.1, PostgreSQL 18, explicit SQL),
  web (React/TypeScript), compose/CI, scheduler, leases, retries, simulation. Overrides generic skills.
---

# QuantaRun engineering rules

Precedence: owner brief (`docs/brief/`) > `docs/SPEC.md` / ADRs > this skill > the generic Spring skills in
`.claude/skills/` > habit. If you break a rule on purpose, say why in an ADR or in ENGINEERING_LOG.

## Stack (verified 2026-09-30, ADR-0006)
Java 25 · Spring Boot 4.1.1 (Framework 7, Jackson 3 `tools.jackson`) · PostgreSQL 18.6 · Flyway · Testcontainers ·
Spring Modulith 2.x · React 19.3 · TypeScript 6.0 strict (not 7; typescript-eslint support) · Vite 8 · Node 24 LTS.
Check the official docs before relying on an API you are not sure exists in these versions.

## Data access
- MUST: explicit SQL through `JdbcClient`, in the repository class of the owning module. Map rows to records. No JPA.
- MUST: state changes are `UPDATE ... WHERE id = :id AND status = :expected` (plus the fencing columns), and callers
  act on the affected-row count. A lost race is an expected outcome with a typed result.
- MUST: invariants have a DB guard where one exists (CHECK, unique, partial unique index). Application logic alone is
  not enough.
- MUST: lock order is job rows first, then worker rows in ascending id. Use `SKIP LOCKED` for work windows (jobs,
  expired leases) and plain `FOR UPDATE` where waiting is correct (worker capacity).
- MUST: use the DB clock (`now()`) for leases and availability. Pass `Instant now` into pure code as a value.
- MUST NOT: open a transaction around HTTP calls, workload execution or sleeps. Don't annotate whole classes with
  `@Transactional`; every transactional method protects a named invariant.
- MUST: every new query that sits on a hot path gets an `EXPLAIN ANALYZE` check, and interesting plans go in
  ENGINEERING_LOG.
- MUST: Flyway migrations `V<n>__<snake_case>.sql`, append-only. JSONB only for payloads, checkpoint results, event
  details and decision candidates, never for fields the scheduler filters or sorts on.

## Domain and code shape
- MUST: `JobStatus` (and `AttemptStatus`, `WorkerLifecycle`) own their transition tables. No `if (status == X)`
  checks scattered around.
- MUST: scheduling policies are pure (no Spring, SQL, clock or randomness), so the simulator runs the same code
  (ADR-0004).
- MUST: attempts are append-only history; a retry is a new attempt row.
- MUST: bounded everything, including thread pools, queues, SSE buffers, page sizes, payload sizes, decision
  candidate lists and event batches.
- MUST NOT: Lombok; `FooService` + `FooServiceImpl`; `*Manager`, `*Helper`, `*Utils` or factories without a second
  real use; `catch (Exception)` that continues; returning `null` to signal failure; `System.out`.
- SHOULD: records for values, sealed types for closed sets, constructor injection, package-private by default,
  and short methods named for their domain intent (`claimEligibleJob`, `releaseWorkerCapacity`).
- Comments explain *why* (locks, isolation, trade-offs, rejected alternatives), never *what*.

## API
- `/api/v1/...` with DTO records at the boundary. Errors are RFC 9457 Problem Details with a stable `code`
  extension. Bean Validation on every request. Size caps on bodies and payloads. Cap page sizes.
- Idempotent submission via the `Idempotency-Key` header (SPEC §9). Worker writes are fenced by attempt id + worker id.

## Testing
- MUST: anything that touches SQL, locking, transactions or Flyway is tested against real PostgreSQL 18
  (Testcontainers, the same image tag as compose).
- MUST: concurrency features get a multi-threaded test that tries to break them (latch start, many threads,
  repeated runs), and each INVARIANTS.md row names its proving test.
- SHOULD: jqwik property tests for policies and state machines. Pure code gets fast unit tests.
- MUST: deterministic tests. Inject a `Clock` or `Instant`; no `Thread.sleep` for synchronization (use latches or
  Awaitility with bounds).
- MUST NOT: weaken, skip or `@Disabled` a failing invariant test to get green.

## Observability and security
- Micrometer metrics with low-cardinality tags only. IDs go in logs and traces, never in tags. Structured logs
  carry `jobId`, `attemptId`, `workerId`, `projectId` and `traceId`.
- Never log payloads, API keys or worker tokens. API keys are stored hashed and shown once.
- No endpoint executes client-supplied commands. The HTTP workload resolves DNS and blocks private, loopback and
  link-local targets.

## Frontend
- `strict: true`; no `any` without a justifying comment. Server state lives in TanStack Query. Loading, empty and
  error states everywhere.
- The UI is a dense operations console: tables, timelines and real charts. No gradients, glassmorphism, hero
  sections or gratuitous motion. Use the `frontend-design` skill within these constraints.

## Workflow
1. Plan against SPEC and INVARIANTS.
2. Implement.
3. `./mvnw verify` / `npm run check`, with clean LSP diagnostics.
4. Try to break it.
5. Self-review, plus the pr-review-toolkit agents at milestones.
6. Update docs and INVARIANTS proofs.
7. Commit with a Conventional Commit.

Run `/claude-security` checkpoints after: API + persistence (Phase 2), the worker protocol and leases (Phase 5),
API keys (Phase 14), and before release.
