# ADR-0005: Workers talk to the control plane over HTTP and never touch the database

- Status: accepted (2026-09-30)

## Context
Workers could claim jobs straight from PostgreSQL, as many queue libraries do. But QuantaRun workers are
the component we kill on purpose, they can be numerous, and they run workload code.

## Decision
Workers have no database credentials. They use a small versioned HTTP protocol (register, heartbeat, claim,
checkpoint, report), whose request/response records live in `apps/worker-protocol`. Placement happens in the
control plane; the worker only claims what was assigned to it.

## Consequences
- Worker connections don't count against the database connection pool, and a compromised worker cannot
  read other tenants' data.
- Placement stays centralized and explainable. Claiming is a fenced state change, not a queue race.
- Cost: an extra hop and a long-poll endpoint. That's fine at local-first scale, and assignment latency is
  measured in Phase 13.
