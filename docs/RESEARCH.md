# Research: where QuantaRun sits

Reviewed on 2026-09-30. This is a positioning document, not a survey. Its purpose is to keep the
project from becoming yet another "jobs + workers + retries + dashboard" repository.

## Categories reviewed

| Category | Representative projects | What they already do well |
|---|---|---|
| PostgreSQL-backed job queues | River (Go), Oban (Elixir), pg-boss (Node), Que (Ruby), Graphile Worker, JobRunr (Java), db-scheduler (Java), PgQue | `FOR UPDATE SKIP LOCKED` claiming, retries with backoff, cron, dead-letter handling, transactional enqueue |
| Distributed task queues | Celery, Sidekiq, Hatchet | Broker-backed fan-out, rate limits, worker pools |
| Workflow / durable execution | Temporal, Cadence, Restate, Inngest | Durable workflow state, deterministic replay of *workflow code*, activity retries |
| Cluster / batch schedulers | Kubernetes scheduler, Kueue, Volcano, Apache YuniKorn, Slurm, Nomad, Armada | Resource-aware placement, queues and quotas, fair share, gang scheduling, preemption |
| AI workload and inference infrastructure | Ray, SkyPilot, vLLM, NVIDIA KAI Scheduler | GPU-aware placement, autoscaling, batched inference |
| AI observability | Langfuse, Opik, Arize Phoenix, OpenLLMetry | LLM traces, token and cost accounting, evaluations |
| Chaos / fault injection | Chaos Mesh, LitmusChaos, Toxiproxy, Pumba | Process, network and dependency faults |
| Scheduling simulators | SimGrid, Batsim, Alibaba/Google cluster traces, Kubernetes scheduler-simulator | Trace-driven comparison of scheduling algorithms |

## What is commoditized, and therefore not the point

- **A Postgres queue with `SKIP LOCKED`, retries and a DLQ.** A dozen mature libraries do this. QuantaRun
  uses the technique but does not present it as the achievement.
- **Workflow orchestration and durable execution of arbitrary code** (Temporal's territory). QuantaRun only
  checkpoints workloads that explicitly declare stages.
- **Production cluster scheduling** (Kubernetes, Kueue, Volcano). QuantaRun does not manage containers, nodes
  or real GPUs.
- **LLM observability** (Langfuse, Opik). QuantaRun records AI metadata such as tokens, model and rate-limit
  events on attempts, but it is not a prompt/trace analytics product.
- **Chaos platforms.** QuantaRun's fault injection targets only its own components, for demonstration and
  testing.

## The gap QuantaRun targets

Queue libraries answer "did the job run?". Cluster schedulers answer "where does this pod go?", but they are
heavy to run locally and hard to inspect. Very few small systems let you **see and defend** scheduling and
reliability behaviour end to end:

1. **Resource-aware placement with explanations.** Every decision records the chosen worker, the rejected
   candidates and the reasons ("worker-b rejected: 0 of 2 accelerator slots free").
2. **Lease-based crash recovery that a reviewer can trigger live**: kill a worker mid-job, then watch the lease
   expire, resources get reclaimed and the job resume (from a checkpoint if it has one).
3. **Deterministic policy replay.** The same workload trace and seed are replayed through a discrete-event
   simulator against several policies (FIFO, priority, fair-share, deadline, bin-packing), and the
   trade-offs are shown. The simulator runs **the same policy code** as the live scheduler.
4. **Invariants proven with tests**: no capacity overcommit, a single committed success, bounded retries and
   idempotent submission, verified against real PostgreSQL under concurrency and with property-based tests.

This combination is scheduling explainability, reliability semantics and reproducible policy experiments on
a small, local, zero-cost stack. It is the deliberate niche.

## What we will not reproduce

- A general workflow DSL or durable execution of user code.
- Container or process orchestration, and arbitrary command execution on workers. Workloads are built-in,
  registered executors only.
- A message broker. PostgreSQL coordinates everything until a measurement shows it cannot (ADR-0001).
- Multi-region scheduling, preemption and gang scheduling. These are candidates for the roadmap, not the
  first release.

## Why this demonstrates useful engineering skills

The hard parts are the same ones production platform teams deal with:
- concurrency control in a relational database (row locks, `SKIP LOCKED`, constraints as guards);
- fencing and leases under process failure;
- idempotency under duplicate delivery;
- fairness versus utilization trade-offs;
- deterministic simulation for evaluating policies;
- tracing a request across processes with OpenTelemetry.

Each of these can be demonstrated, tested and discussed in an interview without hand-waving.
