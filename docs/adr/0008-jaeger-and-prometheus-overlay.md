# ADR-0008: Traces in Jaeger and metrics in Prometheus, as an optional compose overlay

- Status: accepted (2026-10-04)

## Context
Phase 10 asks for OpenTelemetry traces across the control plane and the workers, and Micrometer metrics exposed
through Prometheus, with "a tracing backend only if lightweight enough". CLAUDE.md allows new infrastructure only
with a measured need. The default stack must stay small: Postgres, the control plane, three workers and the web app.

## Decision
- The applications emit standard signals only: OTLP over HTTP for traces, and the Prometheus endpoint for metrics.
  Nothing in the code depends on a backend.
- A separate compose file, `docker-compose.observability.yml`, adds Jaeger v2 all-in-one (OTLP receiver, in-memory
  storage and UI in one container) and Prometheus. It also sets the trace exporter endpoint on every process. The
  base `docker-compose.yml` is unchanged and starts without either.
- No OpenTelemetry Collector, Tempo, Loki or Grafana. Jaeger accepts OTLP directly, so a collector would add a hop
  with nothing to do yet. A Grafana dashboard would be a fourth container for queries that Prometheus's own UI can
  run.

## Measured need and cost
- Need: the first live run showed that traces without a backend cannot be inspected at all, and it found a defect
  only a trace view made obvious: polling calls each made a trace of their own (ENGINEERING_LOG, 2026-10-04).
- Cost, measured on 2026-10-04 after a 300-job run: Jaeger 27 MiB and Prometheus 21 MiB resident, both under 1 % CPU
  at rest (`docker stats`). Images: 173 MB and 507 MB.

## Consequences
- `docker compose up` stays as it was; observability is one extra `-f`.
- Both backends lose their data on restart. That is acceptable for a local tool, and documented in OBSERVABILITY.md.
- Replacing either is configuration only (an OTLP endpoint, a scrape config).
