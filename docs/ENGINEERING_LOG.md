# Engineering log

Notable discoveries, dead ends and trade-offs, newest first. Not a changelog.

## 2026-09-30 — Jackson 3 changed a default: missing primitives are now errors
Every job submission that omitted the optional `accelerators` field failed with 400 "Failed to read request".
An isolated reproduction showed the cause: Jackson 3 enables `FAIL_ON_NULL_FOR_PRIMITIVES` by default, so a
missing field bound to a primitive `int` is rejected instead of becoming 0. Rather than turning the feature off
globally, which would silently turn missing required numbers into zeros, request DTOs use `@NotNull Integer` for
required fields and an explicit default for optional ones. A second surprise: once any controller parameter carries
a constraint (the `Idempotency-Key` pattern), Spring 7 validates the whole method and reports `@Valid` body errors
as `HandlerMethodValidationException`, not `MethodArgumentNotValidException`. The error handler now unpacks
`ParameterErrors` so clients still get per-field violations. While the handler still used the old code path, one
request produced a `TypeNotPresentException: Type E not present` (a 500). The rewrite removed that path and the
error has not recurred in any report; the exact trigger was not isolated.

## 2026-09-30 — Idempotency: ON CONFLICT DO NOTHING instead of catching unique violations
The obvious implementation (INSERT, catch the unique violation, SELECT the winner) is wrong in PostgreSQL: the
failed INSERT aborts the surrounding transaction, so the SELECT cannot run in it. `INSERT ... ON CONFLICT DO
NOTHING RETURNING` makes a losing insert wait for the winner's transaction and then return no row, without an
error. The follow-up SELECT runs as a new statement under READ COMMITTED, so it sees the committed winner. The
fingerprint is computed over the *normalised* request with sorted JSON keys, so an omitted default and an explicit
default are the same request. Proven by 500 racing submissions.

## 2026-09-30 — Project API keys moved forward from the security phase
Jobs are project-scoped, so Phase 2 needed a caller identity. A temporary "X-Project" header would have been
exactly the kind of placeholder the brief forbids. Keys are 256-bit random secrets hashed with SHA-256 (a slow
password hash adds nothing for high-entropy secrets) and looked up by a public 8-hex prefix. They are verified in
constant time and shown once. Keys live in a `security` module rather than `projects`: `projects` needs the caller
identity for its admin checks, and putting keys there would have created a module cycle.

## 2026-09-30 — Heartbeats must not live on the row the scheduler locks
While designing the scheduling transaction: the scheduler locks worker rows `FOR UPDATE` to reserve capacity
safely. In PostgreSQL an ordinary `UPDATE` of the same row waits for that lock. If `last_seen_at` lived on
`workers`, every heartbeat would queue behind scheduling cycles, and slow heartbeats could even make healthy
workers look LATE. Decision: a separate `worker_heartbeats` table (ARCHITECTURE.md §4).

## 2026-09-30 — Toolchain: TypeScript 7 cannot be used with typescript-eslint yet
npm's `latest` tag for TypeScript is 7.0.2, but typescript-eslint 8.71 declares `typescript <6.1`. Taking the
newest compiler would silently break linting in CI. Pinned TypeScript 6.0.3 (ADR-0006).

## 2026-09-30 — Spring Boot 3.5 is out of OSS support
Boot 3.5's open-source support ended on 2026-06-30, and start.spring.io no longer offers 3.x. The project uses
Boot 4.1.1 (Framework 7, Jackson 3, Hibernate is not used).

## 2026-09-29/30 — Local Docker would not start: half-installed Windows feature
Symptoms: WSL2 reported "virtualization not enabled" even though firmware virtualization was on. Root causes,
in order:
1. The boot configuration had no `hypervisorlaunchtype`, so the hypervisor never started.
2. "Virtual Machine Platform" showed as *Enabled*, but its payload (`vmcompute.exe`, `vmwp.exe`) was
   missing. The CBS log showed the package *staged* but not installed, with a pending transaction.
3. A plain "Restart" deferred the pending servicing ("Deferring startup processing at users request").
   Only "Update and restart" applied it.

Lesson: trust the servicing log (`CBS.log`) and package states over `Get-WindowsOptionalFeature`, which reported
`RestartNeeded: False` while a reboot was actually required.
