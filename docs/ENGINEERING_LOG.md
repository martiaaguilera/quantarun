# Engineering log

Notable discoveries, dead ends and trade-offs, newest first. Not a changelog.

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
