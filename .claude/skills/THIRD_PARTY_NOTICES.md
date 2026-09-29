# Third-party skills

The following skill directories are copied **unmodified** from
[rrezartprebreza/spring-boot-skills](https://github.com/rrezartprebreza/spring-boot-skills),
tree `skills/spring-boot-4/`, commit `f0c06a01b0b7571b519cd43e16692b2483a24514` (2026-09-21).
License: MIT, Copyright (c) 2026 Rrezart Prebreza — see [LICENSE-spring-boot-skills](LICENSE-spring-boot-skills).

Only `SKILL.md`, `examples/` and `templates/` were copied. The Codex-only `agents/openai.yaml`
files were omitted. Cross-links to upstream skills that were not selected (for example
`../resilience-retry/SKILL.md`, `../event-driven-messaging/SKILL.md`) intentionally dangle.

| Skill | Why ForgeFlow needs it |
|---|---|
| rest-api-conventions | REST resources, status codes, pagination caps, Boot 4 API versioning |
| problem-details-rfc9457 | Single RFC 9457 error contract, incl. security-filter 401/403 |
| spring-data-jpa | Hibernate 7 entity rules, `@Version`, N+1, keyset pagination |
| flyway-migrations | Boot 4 Flyway starter + PostgreSQL module, safe migrations |
| transactional-patterns | Transaction boundaries, self-invocation, optimistic-lock retry facade |
| idempotency-patterns | DB-enforced idempotency keys for job submission |
| testing-pyramid | Slice tests, Testcontainers, `@MockitoBean`, `RestTestClient` |
| production-observability | Actuator, Micrometer, OTLP, low-cardinality tags, probes |
| configuration-properties | Typed, validated `@ConfigurationProperties` records |
| spring-data-redis | Redis usage rules (only where Redis is justified) |
| container-native-deployment | Layered, non-root JVM images for Docker Compose |

Where these skills conflict with ForgeFlow decisions, `forgeflow-engineering/SKILL.md` wins.
Upstream content is never edited in place; deviations are recorded there instead.

Considered and **not** selected: spring-security-jwt (ForgeFlow uses API keys; jjwt not justified),
openapi-first (code-first is simpler here), spring-modulith (revisit if package-boundary checks are
needed), resilience-retry (job retries are durable DB state, not in-process retries), null-safety,
event-driven-messaging/Kafka, spring-batch, spring-ai-*, mcp-server, webflux, gateway, hateoas,
multi-tenancy, oauth2-resource-server, hexagonal/DDD/layered/multi-module templates.
