# Observability

Every job is one trace, from the request that submitted it to the report that finished it, across the control plane
and the worker. Metrics count what was committed, with low-cardinality tags only. Logs are structured JSON that carry
the trace id and the job, attempt and worker ids. Nothing here needs a paid service.

## Running it

```
docker compose -f docker-compose.yml -f docker-compose.observability.yml up --build
```

The overlay adds Jaeger (all-in-one: OTLP receiver, in-memory storage and UI) at http://localhost:16686, and
Prometheus at http://localhost:9090, which scrapes the control plane and all three workers every 5 s
(`infra/prometheus/prometheus.yml`). It also points every process's span exporter at Jaeger. Without the overlay,
spans are created but not exported, and `/actuator/prometheus` still serves the metrics. ADR-0008 explains the
choice of backends.

A job's trace id is in the API: `GET /api/v1/jobs/{id}` returns `traceId`, and each attempt in
`GET /api/v1/jobs/{id}/attempts` carries its `traceParent`. Paste the id into Jaeger's search.

## Traces

A client may send a W3C `traceparent` with the submission; the job then joins the client's trace. Otherwise the
submission request starts one. The job stores the context of its submission span (`jobs.trace_parent`, V10), so
spans created later, in other threads and other processes, still land in that trace.

```
http post /api/v1/jobs                         control plane: submission and admission
  job.queued                                   from the job becoming runnable to its placement
  job.schedule                                 the decision: worker, attempt number, the scheduler's reason
    attempt.run                                worker: from claim to the end of the report; tag quantarun.stop
      provider.call                            mock-inference or http: the provider round trip; tag outcome
      http post                                the report, carrying the trace context
        http post /worker-api/v1/attempts/{attemptId}/report   control plane: result persisted, job moved on
    attempt.lost                               only if the lease expired: the reaper's recovery
  job.queued / job.schedule / attempt.run ...  once per retry
```

How it is put together:
- **Placement** creates `job.queued` (started at `available_at`, the submission or the end of a retry backoff) and
  `job.schedule` as children of the stored submission context. Placement happens inside the scheduling transaction,
  so both spans end only **after commit** and are abandoned on rollback. A rolled-back placement leaves no span (I20).
  `job.schedule`'s context is stored with the attempt (`job_attempts.trace_parent`).
- **Claim** hands that context to the worker (`Assignment.traceParent`). The worker opens `attempt.run` as its child
  and keeps it current while the workload runs and the report is sent. The worker's HTTP client propagates the
  context, so the control plane's report handling is a child as well.
- **Provider calls** (`mock-inference` and `http`) are Micrometer observations: a span, and the timer in the metrics
  below.

Not traced, on purpose: worker registration, heartbeats and claim polling (every 500 ms), actuator requests and
`@Scheduled` housekeeping (the reaper, the liveness monitor, gauge refreshes). In the first live run each of these
became a trace of its own, several per second, and buried the job traces. An `ObservationPredicate` in each process
skips them. This also removes them from `http.server.requests`. Their health is visible in worker liveness, lease
expirations and the scheduler metrics instead.

Span times are wall-clock instants from the process that records them. `job.queued` starts at the database's
`available_at` and ends on the control plane's clock, which is the same machine in compose.

### Measured: one job with a retry

Measured on 2026-10-04 with the branch on main `c8db100`. Host-process stack (control plane and three workers)
exporting to Jaeger 2.11.0. A `mock-inference` job (400 ms latency) was submitted while a PROVIDER_RATE_LIMITED
fault (Retry-After 1 s) was pending on every worker. The trace read back from Jaeger's API:

```
     0.0 ms    90.6 ms  control-plane  http post /api/v1/jobs
    48.4 ms   264.0 ms  control-plane    job.queued            attempt 1
   313.8 ms    17.8 ms  control-plane    job.schedule          attempt 1
   838.0 ms   513.0 ms  worker             attempt.run         stop=COMPLETED
   850.4 ms   418.4 ms  worker               provider.call     outcome=rate_limited, error
  1273.5 ms    76.6 ms  worker               http post
  1292.1 ms    52.5 ms  control-plane          http post /worker-api/v1/attempts/{attemptId}/report
  2319.4 ms    31.0 ms  control-plane    job.queued            attempt 2 (after the Retry-After)
  2351.8 ms     6.0 ms  control-plane    job.schedule          attempt 2
  2874.2 ms   432.0 ms  worker             attempt.run         stop=COMPLETED
  2874.4 ms   404.9 ms  worker               provider.call     outcome=success
  3280.1 ms    25.5 ms  worker               http post
  3287.4 ms    16.0 ms  control-plane          http post /worker-api/v1/attempts/{attemptId}/report
```

13 spans from two services, one trace. The gap between `job.schedule` and `attempt.run` (about 520 ms) is the
worker's claim poll interval. It is now visible, and it is the first thing Phase 13 will look at.

## Metrics

All are under `/actuator/prometheus`. Prometheus names add the unit and `_total` (for example
`quantarun_jobs_queue_wait_seconds_bucket`).

**Control plane.** Counters and timers are recorded **after the transaction commits**, so a rolled-back or replayed
operation is never counted (I20).

| Metric | Type | Tags | Meaning |
|---|---|---|---|
| `quantarun.jobs.submitted` | counter | `workload_type` | Jobs admitted; a replayed idempotent submission is not counted |
| `quantarun.jobs.finished` | counter | `workload_type`, `status` | Jobs reaching SUCCEEDED, FAILED, DEAD or CANCELLED |
| `quantarun.jobs.deadline.missed` | counter | `workload_type` | Jobs with a deadline that did not succeed by it (cancelled jobs excluded) |
| `quantarun.attempts.ended` | counter | `workload_type`, `outcome`, `failure_class`, `decision` | Every ended attempt. `decision=retry` is the retry count; `final` means the job gave up |
| `quantarun.leases.expired` | counter | – | Attempts recovered by the reaper |
| `quantarun.jobs.queue.wait` | timer (histogram) | `workload_type` | From the job becoming runnable to a worker starting it |
| `quantarun.attempts.execution` | timer (histogram) | `workload_type`, `outcome` | From a worker starting an attempt to its end |
| `quantarun.jobs.active` | gauge | `status` | QUEUED and RETRY_WAIT (the queue depth), SCHEDULED, RUNNING |
| `quantarun.workers` | gauge | `lifecycle` | Live registrations, ACTIVE and DRAINING |
| `quantarun.fleet.capacity`, `quantarun.fleet.reserved` | gauge | `resource` | Summed over live workers: `cpu_millis`, `memory_mib`, `accelerators`, `slots`. Utilization is reserved / capacity |
| `quantarun.scheduler.cycle` | timer (histogram) | `policy`, `result` | One scheduling cycle including its commit (`placed` or `idle`) |
| `quantarun.scheduler.placements` | counter | `policy` | Attempts placed |
| `quantarun.chaos.faults.delivered` | counter | `fault` | Chaos faults handed to a worker |

Gauges are refreshed every 10 s (`quantarun.metrics.gauge-refresh-interval`) by one grouped query each, not on every
scrape, so a scrape never waits on the database.

**Worker**

| Metric | Type | Tags | Meaning |
|---|---|---|---|
| `quantarun.worker.attempts` | timer | `workload_type`, `stop` | Attempts from claim to the end of the report, by how they stopped |
| `quantarun.worker.provider.call` | timer | `workload_type`, `outcome` | Provider round trips. `outcome` is `success` or the failure class, so the provider error rate is `sum(rate(...{outcome!="success"})) / sum(rate(...))` |
| `quantarun.worker.slots`, `quantarun.worker.slots.busy` | gauge | – | Offered and busy execution slots |

Plus what Spring Boot provides: `http.server.requests` and `http.client.requests` (polling excluded, see above), JVM,
process and HikariCP metrics.

No tag carries an id. Job, attempt, worker and project ids are unbounded; they live in traces and logs, where they cost
nothing.

### Measured: 300 jobs

Same stack and date. 300 `delay` jobs of 300 ms each were submitted in 6 s to 10 slots across the three workers,
under BIN_PACKING. Read from Prometheus:

| Query | Value |
|---|---|
| Queue wait p50 / p95 | 12.4 s / 21.5 s |
| Execution p95 | 0.36 s |
| Scheduler cycle p99 (cycles that placed) | 214 ms |
| Peak queue depth (`quantarun_jobs_active{status="QUEUED"}`) | 233 |
| Peak slot utilization | 100 % |

The work was 9 s of slot time, and the queue drained in about 24 s. Each slot turnover cost far more than the job's
300 ms: the report, then the scheduler's idle delay when the previous cycle found no free slot, then the worker's
500 ms claim poll. That is where Phase 13 starts. These are single runs, not benchmarks; the benchmark methodology is
in BENCHMARKS.md once it exists.

## Logs

Structured JSON (ECS) on stdout. Inside a span, every line carries `traceId` and `spanId`, and lines about a job
carry `jobId`, `attemptId`, `workerId` and `projectId` as fields. For the job above, the control plane's "Job
submitted" and each worker's "Attempt reported" carry the same `traceId`, so `grep <traceId>` across all processes'
logs gives the job's log story. Payloads, API keys and worker credentials are never logged. Exporting logs over OTLP
(`QUANTARUN_OTLP_ENABLED=true`) is opt-in.

## Limits

- Jaeger and Prometheus keep data in memory: a restart starts empty. That is right for a local tool and wrong for
  production, which would use a durable backend behind the same OTLP and Prometheus interfaces.
- Sampling is 100 %. At the job rates this project runs, that is affordable, and every job trace is complete. A
  busier deployment would sample at the root, which keeps whole traces.
- The submission span is the root of a job's trace. A job submitted hours before it runs has a trace that spans hours;
  Jaeger shows it, and its duration reads as the job's life.
