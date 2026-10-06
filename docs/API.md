# API

How to use the QuantaRun HTTP API: who may call what, how to submit and follow work, and what each error means.
The exact schemas are in the generated OpenAPI document; the semantics behind each call are in [SPEC.md](SPEC.md).

## Where it is

| Entry point | URL in the compose stack | Use |
|---|---|---|
| Public API, through nginx | `http://localhost:3000/api/v1` | What the console uses; same origin, no CORS |
| Control plane, direct | `http://localhost:8080/api/v1` | Scripts and benchmarks |
| OpenAPI 3.1 document | `http://localhost:8080/v3/api-docs` | The contract: every path, schema and constraint |
| Worker protocol | `http://control-plane:8080/worker-api/v1` | Workers only, inside the compose network |

Both ports bind to `127.0.0.1` only.

## Authentication

Every call carries `Authorization: Bearer <credential>`. There are three kinds of credential, and they never
overlap:

| Credential | Who | Grants |
|---|---|---|
| Operator token (`QUANTARUN_ADMIN_TOKEN`, ≥ 32 chars) | The operator | Everything under `/api/v1`, across all projects |
| Project API key (`qr_…`) | A tenant | Its own project's jobs, events and simulations; nothing about the fleet or other projects |
| Worker credential (`qw_…`) | One registered worker | Its own heartbeats, claims, reports and checkpoints under `/worker-api/v1` |

Project keys are 256-bit random values, shown once at creation and stored as SHA-256 hashes. A missing or unknown
credential is `401 UNAUTHENTICATED` with `WWW-Authenticate: Bearer`. A valid credential without the right is
`403 FORBIDDEN`. A project key asking for another project's job gets `404`, so it cannot learn that the job exists.

```bash
curl -s localhost:3000/api/v1/session -H "Authorization: Bearer $TOKEN"
# {"role":"OPERATOR","projectId":null}
```

## Endpoints

**Projects and keys** (operator)

| Method and path | Does |
|---|---|
| `POST /projects` | Create a project: `{"name": "team-a", "weight": 2}`. Names are `^[a-z0-9][a-z0-9-]{1,62}$`; weight 1–1000 sets its fair share |
| `GET /projects`, `GET /projects/{id}` | List, read |
| `PUT /projects/{id}/limits` | Weight and quotas, replaced as a whole: `{"weight": 1, "maxQueuedJobs": 1000, "maxRunningJobs": 20, "maxAccelerators": null}`; `null` is unlimited |
| `POST /projects/{id}/api-keys` | Create a key: `{"label": "ci"}`. The response holds `secret` once |
| `GET /projects/{id}/api-keys` | List keys (prefix and label, never the secret) |
| `DELETE /projects/{id}/api-keys/{keyId}` | Revoke |

**Jobs** (a project key; the operator sees every project)

| Method and path | Does |
|---|---|
| `POST /jobs` | Submit. `201` with `Location`; `200` with `Idempotent-Replayed: true` when the same `Idempotency-Key` and body were seen before |
| `GET /jobs` | List, newest first. Filters: `projectId`, `status`, `workloadType`, `priority`, `workerId`, `createdFrom`, `createdTo`. Keyset pages: `limit` (≤ 200, default 50) and `before=<nextBefore>` |
| `GET /jobs/{id}` | The job, with its scheduling reason while it waits and its trace id |
| `GET /jobs/{id}/attempts` | Every attempt, oldest first: worker, lease, failure class, retry decision, result |
| `GET /jobs/{id}/events` | The job's history, in commit order |
| `GET /jobs/{id}/decisions` | Every scheduling decision, with each candidate worker's verdict |
| `GET /jobs/{id}/checkpoints` | Committed stages of a `staged` job |
| `POST /jobs/{id}/cancel` | `202 CANCEL_REQUESTED` if it is running (the worker stops at its next heartbeat), `200 CANCELLED` if it was not |
| `POST /jobs/{id}/revive` | A `DEAD` job gets a fresh attempt budget, at most 10 times |

**Fleet and scheduler** (operator)

| Method and path | Does |
|---|---|
| `GET /workers`, `GET /workers/{id}` | Workers with capacity, reservations, labels, lifecycle and derived health |
| `POST /workers/{id}/drain` | Finish current work, take no new work |
| `GET /scheduler` | Active policy and loop settings (any caller) |
| `GET /scheduler/decisions?limit=` | Recent decisions across all projects |
| `GET /scheduler/fairness` | Each project's weight, virtual time and quotas |
| `GET /overview` | Queue and outcome counts, retries, time-to-start p95; fleet reservations for the operator |
| `GET /settings` | Effective scheduler, worker, retry and chaos configuration |

**Simulation** (any caller; a project sees only its own runs)

| Method and path | Does |
|---|---|
| `GET /simulations/scenarios` | The eight scenarios and their default size |
| `POST /simulations` | `{"scenario": "NOISY_NEIGHBOR", "seed": 42, "jobCount": 2000, "policies": ["FIFO", "FAIR_SHARE"]}`. Runs synchronously, stores and returns the result with one SHA-256 hash per policy. At most 2 at once (`429 SIMULATION_BUSY`) |
| `GET /simulations`, `GET /simulations/{id}` | Stored runs |

**Chaos** (operator; `403 CHAOS_DISABLED` unless `QUANTARUN_CHAOS_ENABLED=true`)

| Method and path | Does |
|---|---|
| `GET /chaos/faults` | The eight faults, their parameters and bounds |
| `POST /chaos/experiments` | `{"fault": "KILL_WORKER", "jobId": "…", "delayMs": 2000}`: aim at a worker, or at whichever worker runs a job |
| `GET /chaos/experiments`, `GET /chaos/experiments/{id}` | Experiments with their recovery timeline |
| `POST /chaos/experiments/{id}/cancel` | Cancel one not yet delivered |

**Live events**

`GET /events/stream` is Server-Sent Events. Each `job` event carries `id`, `jobId`, `projectId`, `type`,
`occurredAt` and `details`; reconnect with `Last-Event-ID` to resume without gaps or duplicates. A client too far
behind gets a `reset` event and should reload. A project key sees its own jobs, at most 5 streams per project
(`429 EVENT_STREAMS_PER_PROJECT`).

## Submitting a job

```json
{
  "workloadType": "mock-inference",
  "payload": {"inputTokens": 2000, "outputTokens": 400, "latencyMs": 800},
  "priority": 7,
  "resources": {"cpuMillis": 2000, "memoryMib": 4096, "accelerators": 1},
  "requiredLabels": ["cuda"],
  "maxAttempts": 3,
  "timeoutSeconds": 600,
  "notBefore": "2026-10-06T18:00:00Z",
  "deadline": "2026-10-06T19:00:00Z"
}
```

Only `workloadType`, `payload` and `resources.cpuMillis`/`memoryMib` are required. Defaults: priority 4 (range
0–9), 0 accelerators, no labels, 3 attempts (range 1–10), a 300 s timeout per attempt (range 1–86,400 s).
`notBefore` may be at most 30 days ahead; `deadline` may not be in the past. The payload is capped at 16 KiB and the
request body at 64 KiB.

Send `Idempotency-Key: <1–200 printable ASCII>` to make a retry safe: the same key and body return the same job, a
different body with the same key is `409 IDEMPOTENCY_KEY_REUSED`.

### Workloads

Workloads are built-in executors chosen by name; the payload is data, never code.

| `workloadType` | Payload | Does |
|---|---|---|
| `delay` | `durationMs` (≤ 600,000) | Sleeps; a stand-in for any I/O-bound step |
| `cpu-hash` | `iterations` (≤ 10⁸), `seed?` | Burns CPU on a hash chain |
| `mock-inference` | `inputTokens`, `outputTokens`, `latencyMs`, `seed?` | A local mock of a model call: deterministic output digest and token usage; provider faults under chaos |
| `fail` | `failureClass`, `message?`, `succeedOnAttempt?`, `retryAfterMillis?` | Fails with a chosen class, to exercise retries |
| `memory` | `mib` (≤ 1,024), `holdMs` | Holds memory, from a budget of half the worker's heap |
| `staged` | `stages: [{name?, durationMs}]` (1–20), `failAtStage?`, `failOnAttempts?` | A pipeline with a checkpoint after each stage; a retry resumes after the last one |
| `http` | `url`, `method?`, `timeoutMs?` (≤ 30,000) | An outbound call. Private, loopback and link-local addresses are refused after DNS resolution, redirects included (SSRF guard) |

The worker validates the payload; an invalid one ends the job as `INVALID_INPUT` without retrying.

## Errors

Every error is `application/problem+json` with a stable `code`:

```json
{"detail": "Project quota-probe already has 2 unfinished jobs; its quota is 2. Wait for some to finish or ask an operator to raise it.",
 "instance": "/api/v1/jobs", "status": 429, "title": "Too Many Requests", "code": "QUOTA_EXCEEDED"}
```

| Status | Codes |
|---|---|
| 400 | `VALIDATION_FAILED` (with `violations: [{field, message}]`), `UNKNOWN_WORKLOAD_TYPE`, `PAYLOAD_TOO_LARGE`, `DEADLINE_IN_PAST`, `NOT_BEFORE_TOO_FAR`, `INVALID_CHAOS_TARGET`, `INVALID_FAULT_PARAMETERS` |
| 401 | `UNAUTHENTICATED` |
| 403 | `FORBIDDEN`, `CHAOS_DISABLED` |
| 404 | `JOB_NOT_FOUND`, `PROJECT_NOT_FOUND`, `API_KEY_NOT_FOUND`, `WORKER_NOT_FOUND`, `SIMULATION_NOT_FOUND`, `EXPERIMENT_NOT_FOUND` |
| 409 | `IDEMPOTENCY_KEY_REUSED`, `JOB_NOT_CANCELLABLE`, `JOB_NOT_DEAD`, `REVIVE_LIMIT_REACHED`, `JOB_STATE_CHANGING`, `JOB_NOT_ON_A_WORKER`, `PROJECT_NAME_TAKEN`, `WORKER_NOT_ACTIVE`, `EXPERIMENT_NOT_PENDING`, `TOO_MANY_PENDING_FAULTS` |
| 413 | `REQUEST_TOO_LARGE` |
| 429 | `QUOTA_EXCEEDED`, `SIMULATION_BUSY`, `EVENT_STREAMS_PER_PROJECT` |
| 503 | `DATABASE_UNAVAILABLE` (with `Retry-After`), `EVENT_STREAM_FULL` |

A `409` means the request was valid but the job's state forbids it now; read the job again before retrying. A `503`
is safe to retry after `Retry-After`; with an `Idempotency-Key`, so is a submission whose answer was lost.

## The worker protocol

Workers speak only HTTP, under `/worker-api/v1`, and never touch the database (ADR-0005). The request and response
records are shared Java code in `apps/worker-protocol`.

| Call | Credential | Does |
|---|---|---|
| `POST /register` | Bootstrap token | Name, capacity, labels; returns the worker id and its own `qw_` credential |
| `POST /heartbeat` | Worker | Every 3 s, with the active attempt ids: renews their leases; returns the worker's lifecycle, attempts to cancel, attempts already lost and chaos faults |
| `POST /claim` | Worker | Takes the assignments placed on this worker; may wait up to 5 s (`waitMillis`) for one |
| `POST /attempts/{id}/checkpoints` | Worker | Commits a stage, fenced by attempt and lease |
| `POST /attempts/{id}/report` | Worker | Success or a classified failure, fenced: `409 LEASE_EXPIRED` once the lease has run out |
| `POST /deregister` | Worker | Graceful exit |

A worker whose registration was retired gets `409` on heartbeat and claim, and registers again as a new member.
Report and checkpoint answers of `404` and `409` are final; `5xx` and I/O errors are retried with backoff. The full
semantics are in [FAILURE_SEMANTICS.md](FAILURE_SEMANTICS.md).
