<!--
Extract of the owner's FIRST QuantaRun brief (version 1, received 2026-09-30).
Only the requirements that version 2 (PROJECT_BRIEF.md) does not repeat are kept here, faithfully
paraphrased. Everything else in v1 is covered by v2. If v1 and v2 conflict, PROJECT_BRIEF.md wins.
-->

# QuantaRun brief v1: requirements not repeated in v2

## Research deliverable (v1 §1)
- Write `docs/RESEARCH.md`. It must cover the categories reviewed (distributed job queues, AI workload
  schedulers, agent runtimes, AI observability platforms, workflow engines, chaos/fault-injection tools,
  inference infrastructure, workload simulators), what is already commoditized, what we deliberately
  will not reproduce, the technical gap targeted, and why the project demonstrates useful skills.
  Keep it concise and factual.
- Not a clone of Celery, RabbitMQ, Temporal, Langfuse, Opik or Kubernetes, and not a generic
  "jobs + workers + retries + dashboard" repository.

## Questions the system must answer (v1 §3)
- Where should this workload run? Which worker has the capabilities and resources?
- What happens if a worker disappears, execution times out, or a provider returns 429s?
- Can the workload be retried safely? Did we execute a side effect twice?
- Is a tenant starved? Are high-priority jobs starving everything else?
- Which policy performs better under this workload? How much time was queued versus executing?
- Why was this worker selected? What caused this run to fail?
- Can we reproduce the workload and compare another policy?
- Can we prove the concurrency assumptions with tests?

## Scheduling (v1 §4)
- Optional advanced policy: shortest estimated workload first, only if estimates can be modeled honestly.
- The decision record must answer "why did job X run on worker Y instead of worker Z?". Factors include
  labels, project quota, fairness debt and rejected candidates. Use a structured record, not huge blobs.

## Persistence and API choices (v1 §6, §23, §29)
- Do NOT choose JPA automatically. Use jOOQ or carefully designed Spring Data JDBC / explicit SQL, and
  document the decision.
- Maven, unless there is a clearly better reason.
- Infrastructure: OpenTelemetry Collector, Prometheus, and Grafana and/or Tempo only where justified.
  The project's own React UI is the primary demo; Grafana is supplemental.
- Candidate API domains: /api/v1/jobs, workers, projects, scheduler, scenarios, simulations, chaos,
  events, metrics. Use a cleaner design if one emerges.
- Candidate tables: projects, api_keys, jobs, job_attempts, workers, worker_resources, assignments,
  leases, checkpoints, job_events, scheduler_decisions, simulation_runs, simulation_results,
  chaos_experiments. Normalize by actual access patterns; do not follow this list blindly.

## Invariants document (v1 §7)
- `docs/INVARIANTS.md`: each invariant points to its implementation mechanism, its database
  constraint/transaction, and the tests that prove it. "This document should be excellent."
- Example invariants: one active assignment has at most one worker; capacity is never overcommitted;
  terminal jobs never transition back; successful completion is idempotent; stale workers cannot keep
  reservations forever; lease expiry has a deterministic recovery path; a duplicate idempotency key
  creates no duplicate work; retries never exceed policy; a DEAD job runs only if explicitly
  replayed/revived; cancelled jobs cannot be newly assigned.
- Worker states could include STARTING, READY, BUSY, DRAINING, UNHEALTHY, OFFLINE.
- An attempt also records its classified failure and trace/correlation IDs.

## AI-aware workload metadata (v1 §10)
- Where applicable, capture: provider, model, input/output tokens, estimated/actual cost, latency,
  time to first token, finish reason, rate-limit events, retryable vs non-retryable provider errors.
- Never fake token counts or prices. Label estimated costs as estimated. Prices are not hard-coded in
  business logic; use versioned configuration.
- Provide `.env.example`. Never commit secrets.

## Budgets, quotas and admission control (v1 §11)
- Per project: max concurrent workloads, accelerator slot quota, optional token budget, optional
  estimated cost budget, optional request-rate limits.
- Distinguish "cannot ever run" (for example, it needs an accelerator and no registered worker has one:
  unschedulable, with a reason) from "cannot run right now" (resources exhausted: stays queued).
  When a budget is exceeded, reject or pause according to policy.
- Show these reasons in the UI. Never hide them behind generic errors.

## Failure semantics (v1 §12)
- Circuit breaker for unreliable external providers, if provider integrations are implemented.
- `docs/FAILURE_SEMANTICS.md`: exactly what happens for each failure class.

## Showcase crash-recovery demo (v1 §13)
1. Worker A starts a long workload.
2. Worker A is killed.
3. The control plane detects lease expiry.
4. Resources are reclaimed.
5. The workload is rescheduled and worker B runs it.
6. The timeline shows the whole incident.

## Idempotency (v1 §14)
- Worker completion/reporting also tolerates duplicate calls.
- Concurrency test: send the same idempotency key simultaneously and prove that only one logical job exists.

## Checkpointing (v1 §15)
- Example flow: dataset-preprocess, then inference-batch-1, then inference-batch-2, then aggregate,
  with a checkpoint after each step.
- The dashboard visibly distinguishes restart-from-zero from resume-from-checkpoint.
- If the abstraction grows too broad, constrain it rather than fake generality.

## Replay (v1 §16–17)
- An exportable workload trace captures arrival times, tenant/project, requirements, priority,
  duration distribution or fixed duration, failure-injection points, deadlines and workload type.
- Do not base policy comparisons only on wall-clock execution. Use a discrete-event simulation layer.
- Scenario names in v1: BURST, STEADY, MIXED_RESOURCES, GPU_SCARCE, NOISY_NEIGHBOR,
  DEADLINE_HEAVY, WORKER_FAILURE, PROVIDER_RATE_LIMIT. (v2 renames GPU_SCARCE to
  ACCELERATOR_SCARCE and PROVIDER_RATE_LIMIT to RATE_LIMIT.)
- UI flow: select scenario, select policies, run simulation, compare results.
- Fairness test example: tenant A sends 10,000 jobs and tenant B sends 10. B must not wait behind all
  10,000. Also show the trade-off between fairness, priority and utilization.

## Chaos (v1 §19)
- Extra faults: database connection interruption (integration tests), long-running job, worker
  capacity drop. Consider Toxiproxy with Testcontainers for network faults.
- Example UI scenario: "Kill active worker after 40% progress", then show detection, lease expiry,
  rescheduling and recovery.

## Observability (v1 §20)
- Trace path: API request, admission, queue, scheduler decision, assignment, worker claim, execution,
  provider call, result persistence.
- Extra metrics: worker count, provider error rate, queue wait and execution duration histograms.
- Tracing backend only if lightweight enough.

## Dashboard views (v1 §21)
- Overview: queued, running, success rate, active workers, resource and accelerator utilization,
  retries, dead jobs, p95 queue latency.
- Jobs: filter by state, project, priority, worker, model/provider and time range.
- Job detail timeline adds "admitted", plus lease history and resource allocation.
- Workers: status, heartbeat, slots, capacity, accelerators, labels, active assignments, drain action.
- Scheduler: active policy, recent placements, queue age, fairness, unschedulable workloads, rejected
  candidates and reasons.
- Dead jobs: inspect, and explicitly replay/revive.
- Settings: only settings that really exist; no fake toggles.
- SSE is likely enough; design reconnect behavior.

## Security (v1 §24–25)
- API keys: a prefix for lookup if helpful; the local dashboard may use a simple development/admin path.
- Threat-model extra items: malicious job payloads, unsafe chaos endpoints.

## Testing (v1 §26–27)
- Playwright for key dashboard flows.
- Stress examples: 25 scheduler threads competing, many workers claiming, 500 simultaneous duplicate
  submissions, heartbeats racing the lease reaper, cancellation racing scheduling, completion racing
  lease expiry.
- Repeat tests enough to expose races, deterministic where possible. Document exactly what is proven
  and what is not.
- If an invariant exposes a flaw, fix the architecture; never weaken the test.

## Benchmarks (v1 §28)
- Measure API throughput, scheduler decision latency, assignment throughput, DB contention and
  simulation speed.
- Record date, commit, hardware, OS/WSL, Docker config, dataset/scenario and command. Say
  "not measured" where applicable.

## Event history (v1 §30)
- Append-oriented operational event history, for example JOB_SUBMITTED, JOB_ADMITTED,
  JOB_SCHEDULED, WORKER_CLAIMED, EXECUTION_STARTED, CHECKPOINT_COMMITTED, ATTEMPT_FAILED,
  RETRY_SCHEDULED, LEASE_EXPIRED, JOB_RESCHEDULED, JOB_SUCCEEDED.
- This is NOT full event sourcing. State tables remain the operational model; events serve debugging,
  audit, timelines and replay metadata.

## Code quality (v1 §31)
- Automated formatting plus static analysis.

## Demo data (v1 §34–36)
- One command (`docker compose up --build`, optionally `make demo`) starts PostgreSQL, the control plane,
  3+ workers with different resources, the observability stack and the frontend, then seeds a
  realistic workload mix.
- The demo shows: a CPU worker, a mixed worker, an accelerator worker, competing projects, queued work,
  retries, an unschedulable job, scheduling decisions, a worker failure, recovery, and policy comparison.
- Screenshots are real, generated with Playwright once the UI is stable, stored in `docs/assets/`.
  Never mock them.

## Documentation extras (v1 §37–39)
- LICENSE, docs/API.md, docs/RESEARCH.md, docs/adr/ (important decisions only; no ADR spam).
  Candidate ADRs: PostgreSQL before a broker, lease semantics, resource model, SSE vs WebSocket,
  explicit SQL, simulation architecture, multi-process architecture.
- README: a Mermaid architecture diagram and only a few badges (CI, Java version, license).
- Known limitations to acknowledge: single PostgreSQL primary, local-first, simulated accelerator
  accounting rather than GPU isolation, no arbitrary container execution, no Kubernetes initially,
  no multi-region scheduler, not a replacement for production orchestrators.

## What not to build (v1 §40)
- No social network, todo, ecommerce, chatbot, generic multi-agent system, LangChain showcase, RAG demo,
  generic SaaS dashboard, Kubernetes/Celery/Temporal/Langfuse clone, prompt manager or blockchain.
- No Kubernetes manifests until the application itself is mature.

## GitHub (v1 §41–43)
- Main branch `main`. Tests, formatter and static analysis pass before every major commit.
- Check `gh auth status`. Create the remote automatically only if gh is authenticated as exactly
  `martiaaguilera`, no conflicting repository exists, and a reasonable quality gate is reached.
  The target repo is public. Without auth, continue locally and give the exact publish command at the end.
- CI also runs a security/dependency scan and a Docker image build smoke test. Benchmarks are kept
  separate from PR checks.

## Environment (v1 §44)
- Record a reproducible setup in the docs. Install only what is genuinely missing.

## Final review and portfolio (v1 §49–51)
- `docs/FINAL_REVIEW.md` lists findings, severity, fix and verification. Fix every high and critical finding.
- Extra review questions: is this just CRUD disguised as infrastructure? Are charts showing real data?
  Could a recruiter understand the value in 60 seconds? Could Martí explain every decision?
- The interview guide also covers transaction isolation, the biggest bugs encountered, and when Kafka,
  RabbitMQ or Kubernetes might become justified. No fake stories.
- PORTFOLIO.md: measured benchmark highlights only if real. Avoid phrases like "leveraged cutting-edge
  technologies". Good pattern: "Built a PostgreSQL-coordinated distributed workload scheduler with
  lease-based crash recovery, resource-aware placement and deterministic policy replay; verified
  concurrency invariants using Testcontainers and property-based tests."

## Autonomy (v1 §52–53)
- Never swap a hard part for an easier fake: no ThreadPoolExecutor instead of distributed scheduling,
  no in-memory status instead of leases, no frontend mock data instead of resource accounting, no random
  reruns instead of replay, no hard-coded screenshots instead of failure injection, no log strings instead
  of observability.
- When context grows, update CLAUDE.md, SPEC.md and ENGINEERING_LOG.md so another session can resume.
- End-of-session status (v1 wording): COMPLETED, VERIFIED, CURRENT ARCHITECTURE, TEST STATUS,
  NEXT ENGINEERING TASK, BLOCKERS. v2 uses a slightly different list, and v2 wins.
