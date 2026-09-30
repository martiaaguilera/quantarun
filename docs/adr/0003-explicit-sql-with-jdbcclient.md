# ADR-0003: Explicit SQL through Spring JdbcClient, no JPA

- Status: accepted (2026-09-30)

## Context
The critical paths are concurrency-sensitive statements: `SKIP LOCKED` windows, conditional updates whose
row counts are the result, and `ON CONFLICT ... RETURNING`. JPA hides when SQL is emitted (flush timing,
dirty checking) and makes these statements awkward to express and review. jOOQ gives typed SQL but adds code
generation against a live schema to the build.

## Decision
Use Spring Framework's `JdbcClient` with hand-written SQL in repository classes, one per module. Map rows
to Java records explicitly. Flyway owns the schema.

## Consequences
- Every statement that matters is visible in code review, and `EXPLAIN ANALYZE` can run on it verbatim.
- More mapping code than JPA. Mitigation: records, and small row mappers next to their queries.
- SQL typos surface only in tests, not at compile time. Mitigation: every repository method is covered by
  Testcontainers integration tests against PostgreSQL 18.
- Revisit jOOQ if the query count or the refactoring pain grows enough to justify code generation.
