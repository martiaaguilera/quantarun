# Threat model

What QuantaRun protects, who it trusts, and how each threat in the brief is handled. Every mitigation names the test
that proves it. Residual risks are listed as such, not hidden. Reviewed in Phase 14 (2026-10-06).

## What is worth protecting

| Asset | Why it matters |
|---|---|
| Job payloads and results | Tenant data. A payload can carry a prompt or a URL; a result can carry a provider's answer. |
| Project API keys, worker credentials, the admin token | Whoever holds one acts as that tenant, that worker or the operator. |
| The fleet's capacity | Shared by every tenant; starving it is an outage for all of them. |
| The control plane's correctness | Leases, reservations and the state machine (INVARIANTS.md). A forged report or a double placement is data corruption. |
| The internal network the workers sit on | The http workload makes outbound calls on a tenant's behalf. |

## Actors and trust boundaries

```
 tenant (project key) ──┐
 operator (admin token) ┼─HTTPS?─▶ nginx (web) ──▶ control plane ──JDBC──▶ PostgreSQL
                        │                            ▲
 workers (worker cred.) ┴────────── /worker-api ─────┘
                                     workers ──http workload──▶ the internet (never the internal network)
```

- **Tenants** hold a project API key. They may submit and read their own project's jobs, run simulations and read
  their own stream. They are not trusted with other tenants' data, the fleet's shape or the configuration.
- **The operator** holds the admin token: projects, keys, quotas, workers, chaos, settings, every project's data.
- **Workers** register with a shared bootstrap token and then use a per-worker credential. A worker is trusted to run
  the payloads it is given; that is its job. It is not trusted to touch work that is not its own: every write is
  fenced by attempt id + worker id + lease (I6, I10, I11).
- **PostgreSQL** is trusted and reachable only from the control plane (ADR-0005: workers have no database access).
- **TLS** is out of scope for the local stack. Every published port binds to `127.0.0.1` (docker-compose.yml). A
  deployment beyond one machine must terminate TLS in front of nginx and the worker API.

## Threats from the brief

| Threat | Mitigation | Proof |
|---|---|---|
| **SSRF** through the http workload | Addresses are filtered inside the HTTP client's DNS resolver, so DNS rebinding cannot swap a private address in after a check. Loopback, private, link-local (cloud metadata), CGNAT, multicast and reserved ranges are refused, and IP literals are refused up front as well. No redirects; http(s) only; GET and HEAD only; no credentials in URLs; 1 MiB response cap, of which only a hash is kept. An operator may allow specific IPs or CIDRs, never hostnames. | `HttpWorkloadTest` (22 cases: the metadata address `169.254.169.254`, RFC 1918, CGNAT, `0.0.0.0`, IPv6 loopback, unique-local and IPv4-mapped forms, a hostname resolving to loopback and blocked at connect time, redirects, methods, schemes, URLs with credentials, the response cap) |
| **Arbitrary code execution** | There is none to reach. Workloads are a fixed set of built-in executors chosen by name (`delay`, `cpu-hash`, `mock-inference`, `fail`, `memory`, `staged`, `http`); a payload is data for one of them. No endpoint runs a command, a script or a class name from a request. | `WorkloadType` is an enum; an unknown type is `400 UNKNOWN_WORKLOAD_TYPE` (`JobApiTest$Validation`) |
| **Oversized payloads** | 64 KiB per API and worker-API request body, enforced before authentication: a declared length over the limit is refused unread, and a chunked body is counted as it is read. Checkpoints are capped at 8 KiB, report results at 16 KiB (larger ones are replaced by a marker), messages at 1,000 characters, URLs at 2,048. Page sizes and decision candidate lists are capped. | `JobApiTest$Validation.bodyOverTheRequestLimit_is413…`, `RequestBodyLimitFilterTest` (chunked), `WorkerExecutionApiTest` (checkpoint size) |
| **Secret leakage** | API keys and worker credentials are 256-bit random secrets with a scanner-friendly marker (`qr_`, `qw_`). Only their SHA-256 is stored, and they are compared in constant time. The admin token is compared in constant time and must be at least 32 characters. Property records redact their tokens in `toString`. No log line carries a credential. | `JobApiTest$Authentication.storedKeyMaterial_isHashNotPlaintext`, `SecretsNotLoggedTest` (fails when the filter logs the presented credential) |
| **API key exposure** | A key is shown once, in the response that issues it; listing keys returns only the lookup prefix. Keys are revocable and stop working at once. The console keeps the credential in `sessionStorage`, gone with the tab, and sends it only to its own origin. | `listingKeys_neverReturnsTheSecret`, `revokedKey_stopsWorkingImmediately` |
| **Tenant isolation** | Every API call resolves a `Caller` before any controller runs; a mapping that escapes the filter fails loudly instead of running anonymously. Project data is filtered by the caller's project in the query; another project's job is `404`, not `403`, so ids cannot be probed. The live stream filters every event by project. Fleet, decisions across projects, settings, projects, keys and chaos are operator-only. | `JobApiTest$Isolation`, `JobEventStreamTest.eachCallerSeesItsOwnProjects_inIdOrder`, `SimulationApiTest` (stranger gets 404), `ConsoleApiTest.workers_areVisibleToOperatorsOnly`, `settings_…ToOperatorsOnly` |
| **Denial of service** | Bodies, pages and stream buffers are bounded. A slow stream subscriber is disconnected rather than buffered without limit. At most 50 streams in total, and 5 per project. At most 2 simulations run at once; the rest get `429`. Per-project quotas cap queued and running jobs. The database pool gives up after 3 s and the API answers `503`, so an outage does not pile up requests. | `JobEventStreamTest.aSlowSubscriber_…`, `aProject_cannotHoldMoreThanItsShareOfStreams_…`, `SimulationApiTest.simulationsBeyondTheConcurrencyBound_…`, `ProjectLimitsTest`, `DatabaseUnavailableFilterTest` |
| **Unsafe chaos actions** | The chaos API exists only when `quantarun.chaos.enabled` is set, is operator-only, and offers eight predefined faults with bounded parameters. A worker applies faults only if it was itself started with chaos enabled. Faults act on QuantaRun's own workers, never on the host or the network. | `ChaosExperimentsTest` (disabled deployment), `ChaosApiTest` (operator-only, bounds), `ChaosInjectorTest` (opt-in) |
| **Log injection** | Logs are structured JSON (ECS): every value is a JSON-escaped field, so a payload or a job reason containing newlines or fake log lines cannot forge an entry. Messages are fixed strings; variable data goes in fields. | Boot's structured logging; no free-text concatenation of request data into messages (reviewed) |

## Findings of the Phase 14 review

All four came from a manual review of every endpoint against the threats above. All are fixed on this branch.

| Finding | Severity | Fix |
|---|---|---|
| Project keys could list and read every worker: names, labels, capacity, live reservations. SPEC §12 says operator-only. | Medium (information disclosure across tenants) | `cc794c8` |
| Any project key could start any number of 12 s CPU-bound simulations at once and starve the scheduler for all tenants. | Medium (denial of service) | `8b0c092` |
| One project key could hold all 50 event streams and lock the operator's console out. | Low (denial of service) | `6d8c854` |
| The console was served without a Content-Security-Policy or `nosniff`. | Low (defence in depth against XSS) | `9d4e62e` |

## Residual risks, accepted and stated

- **The bootstrap token admits workers.** Anyone holding it can register a worker and receive the payloads placed on
  it. It is an operator secret, like the admin token, and belongs in a secret store in any real deployment.
- **No rate limiting per tenant** beyond the quotas, the stream cap and the simulation cap. A tenant can still send
  many cheap requests. For a local, single-operator system this is accepted. A shared deployment would put a rate
  limiter in front of nginx.
- **API key prefixes are 32 bits.** The prefix only finds the row (the 256-bit secret is what authenticates), but it
  is unique. At tens of thousands of keys, issuing one could hit a collision and fail with 500. Not reached at this
  scale; a retry on conflict is the fix if it ever is.
- **An HTTP proxy would bypass the SSRF filter's view.** If the worker JVM were configured with an outbound proxy, the
  resolver filter would see the proxy's address, not the target's. The compose stack sets none. Do not add one
  without moving the check to the proxy.
- **`/actuator/prometheus` is unauthenticated** on the control plane and the workers. Metrics carry only
  low-cardinality tags, no ids or payloads, and the ports bind to `127.0.0.1`. Scraping across machines needs a
  network policy or authentication.
- **The automated Claude Security scan has not run.** It needs the owner's go-ahead for its time and token cost. This
  review was manual, and so was the Phase 15 final review (FINAL_REVIEW.md). The scan still waits for that
  go-ahead; anything high or critical it finds is to be fixed before it is called done.

## Out of scope

TLS termination, secret storage (keys come from the environment, as `.env.example` shows), multi-region deployment,
and supply-chain controls beyond `npm audit` in CI and pinned versions. The Dependency review check needs the
repository's dependency graph switched on.
