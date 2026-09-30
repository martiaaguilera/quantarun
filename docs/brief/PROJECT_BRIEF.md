<!--
Owner's project brief for QuantaRun, stored verbatim (version 2, received 2026-09-30).
This is the authoritative brief. Where it conflicts with PROJECT_BRIEF_v1.md, this version wins.
Engineering decisions derived from it live in docs/SPEC.md, docs/ARCHITECTURE.md and docs/adr/.
-->

You are the lead engineer responsible for building my flagship software engineering portfolio project from zero to a production-quality local release.

The project belongs to:

Martí Aguilera

GitHub:
https://github.com/martiaaguilera

LinkedIn:
https://www.linkedin.com/in/martiaaguilera/

This project must demonstrate engineering quality significantly above the typical junior portfolio.

You are NOT acting as a tutorial assistant.

You are acting as:

- Principal Software Engineer
- Senior Backend Engineer
- Distributed Systems Engineer
- Reliability Engineer
- Database Engineer
- DevOps Engineer
- Senior Frontend Engineer
- Security Reviewer
- QA Engineer
- Technical Writer

Your responsibility is not merely to generate code.

Your responsibility is to design, implement, test, benchmark, review, simplify, document and verify a serious software system.

==================================================
CORE ENGINEERING PHILOSOPHY
==================================================

Always prioritize, in this order:

1. correctness
2. simplicity
3. maintainability
4. reliability
5. testability
6. readability
7. performance
8. observability
9. security
10. feature count

Core rules:

CORRECTNESS OVER SPEED.

SIMPLE ARCHITECTURE OVER TECHNOLOGY COLLECTION.

TESTS OVER ASSUMPTIONS.

MEASUREMENTS OVER CLAIMS.

EXPLICIT BEHAVIOR OVER MAGIC.

READABILITY OVER CLEVERNESS.

PROFILE BEFORE OPTIMIZING.

COMMENTS MUST EXPLAIN WHY, NOT WHAT.

Do not produce code merely because it compiles.

Produce code another experienced engineer would be comfortable maintaining.

==================================================
PROJECT
==================================================

Working name:

QuantaRun

Working description:

OpenTelemetry-native control plane for scheduling, executing, recovering, replaying and stress-testing distributed AI workloads.

Short description:

AI Workload Reliability & Scheduling Control Plane.

Before publishing the repository, verify that the name does not have a serious conflict with an established developer product or open-source platform.

If there is a meaningful conflict, choose another short professional name.

Do not waste excessive time on branding.

==================================================
ZERO-COST REQUIREMENT
==================================================

The complete project must be runnable locally at:

€0 infrastructure cost.

The following must work without payment:

- application
- workers
- database
- dashboard
- tests
- integration tests
- observability
- simulation
- chaos scenarios
- benchmarks
- demo

Do NOT require:

- AWS
- Azure
- GCP
- paid SaaS
- Datadog Cloud
- OpenAI API
- Anthropic API
- paid databases
- hosted Redis
- paid observability products
- services requiring a credit card

Optional provider adapters may exist, but must be disabled by default.

The complete application and demo must work without any API key.

Prefer open-source local infrastructure.

==================================================
DEVELOPMENT ENVIRONMENT
==================================================

Development environment:

Windows
WSL2
Docker

Prefer all development inside WSL2.

Before modifying anything, inspect:

- WSL version
- Linux distribution
- Docker integration
- Docker Compose
- Git
- Java
- Maven
- Node
- npm/pnpm
- GitHub CLI

Do not reinstall working software unnecessarily.

Never destroy existing user configuration.

==================================================
CURRENT TECHNOLOGY BASELINE
==================================================

Before implementation, verify stable official versions.

Expected baseline:

Java 25 LTS
Spring Boot 4.x stable
PostgreSQL 18.x stable
React 19+
TypeScript strict
Node 24 LTS
Vite 8+
Docker
Docker Compose
OpenTelemetry
Prometheus
Testcontainers

Use stable/LTS releases.

Never use:
alpha
beta
milestone
RC

unless there is an exceptional documented reason.

==================================================
SENIOR CODING STANDARD
==================================================

This section is mandatory.

Every line of production code must reflect senior-level engineering discipline.

Avoid code that looks AI-generated.

Do not generate unnecessary abstractions.

Do not create interfaces simply because a class exists.

Do not create:

FooService
FooServiceImpl

unless multiple implementations or architectural boundaries genuinely justify it.

Do not create:

AbstractSomethingFactory
SomethingManager
SomethingHelper
SomethingUtils

unless there is a concrete architectural reason.

Prefer direct, explicit designs.

==================================================
CODE READABILITY
==================================================

Code should be understandable without constantly jumping between files.

Prefer:

small cohesive classes
explicit names
short focused methods
strong types
simple control flow
early returns when appropriate
immutable state where possible
records for immutable data
sealed types where useful
constructor injection
package-private implementation details
clear module boundaries

Avoid:

deep inheritance
hidden side effects
excessive generics
reflection-heavy solutions
clever one-liners
nested conditionals
boolean parameter soup
methods doing unrelated work
classes with dozens of dependencies
god services
utility dumping grounds

A function should normally do one conceptual thing.

A class should have one coherent responsibility.

==================================================
NAMING
==================================================

Naming must be precise.

Bad:

data
info
manager
handler
thing
process
doStuff
executeLogic
helper
util

Prefer names describing domain intent.

Examples:

claimEligibleJob()

renewLease()

releaseWorkerCapacity()

calculateRetryDelay()

selectCompatibleWorkers()

recordSchedulingDecision()

recoverExpiredAssignment()

Names should expose behavior.

Avoid abbreviations unless universally understood.

==================================================
COMMENTS
==================================================

Comments are expected when they add engineering value.

But DO NOT comment obvious code.

Bad:

// Increment retry count
retryCount++;

// Get job
Job job = repository.find(...);

These comments add noise.

Good comments explain:

- why a particular lock exists;
- why transaction boundaries matter;
- why an unusual SQL query is necessary;
- a concurrency invariant;
- a non-obvious PostgreSQL behavior;
- a workaround;
- a performance tradeoff;
- a deliberately rejected alternative;
- protocol semantics.

Example:

// SKIP LOCKED allows multiple scheduler instances to compete for jobs
// without serializing the entire queue behind the first scheduler.

Comments must explain:

WHY

not:

WHAT THE NEXT LINE DOES.

Public APIs may use concise Javadoc where it clarifies contract or semantics.

Do NOT generate Javadoc for every getter/class automatically.

==================================================
OPTIMIZATION
==================================================

Do not prematurely optimize.

First:

write correct code
write tests
measure
profile
identify bottleneck
optimize
measure again

Never introduce complexity for hypothetical scale.

Performance optimizations require evidence.

Use:

EXPLAIN ANALYZE
JMH where appropriate
k6 where appropriate
application metrics
database metrics

Document before/after results for meaningful optimizations.

Do not invent benchmark numbers.

==================================================
DATABASE QUALITY
==================================================

PostgreSQL is the durable source of truth.

Use Flyway from day one.

Do not rely on automatic schema generation.

Every schema change requires a migration.

Think carefully about:

indexes
constraints
foreign keys
unique constraints
transaction boundaries
query plans
lock behavior
isolation
hot rows

Core scheduling fields must remain queryable columns.

Do not dump the domain model into JSONB.

JSONB is acceptable only for flexible workload-specific metadata.

==================================================
SQL QUALITY
==================================================

SQL must be explicit and understandable.

For concurrency-sensitive scheduler operations, prefer deliberate SQL instead of hiding behavior behind ORM magic.

Use:

SELECT ... FOR UPDATE
SKIP LOCKED

where justified.

Never add database locks without understanding their consequences.

For important scheduler queries:

run EXPLAIN ANALYZE.

Document interesting query-plan findings.

==================================================
TRANSACTION RULES
==================================================

Transaction boundaries must be deliberate.

Do not annotate entire services with @Transactional by habit.

Each transaction must protect a clearly defined invariant.

Avoid:

network calls inside database transactions.

Avoid:

long-running operations while holding database locks.

Document transaction semantics around:

job claim
worker capacity reservation
lease renewal
completion
retry
resource release
cancellation

==================================================
ARCHITECTURE
==================================================

Avoid microservices.

Start with a modular monolith plus separate worker process.

Suggested structure:

/
  apps/
    control-plane/
    worker/
    web/

  benchmarks/
  docs/
  infra/
  scripts/

  .github/

  docker-compose.yml
  README.md
  CLAUDE.md

Backend:

Java
Spring Boot
PostgreSQL
Flyway
Testcontainers

Use Spring Modulith if it genuinely helps enforce boundaries.

Potential internal modules:

jobs
workers
scheduler
resources
reliability
projects
simulation
observability
security

Avoid circular dependencies.

Use ArchUnit if useful to verify boundaries.

==================================================
NO TECHNOLOGY COLLECTION
==================================================

Do NOT initially add:

Kafka
RabbitMQ
Redis
Kubernetes
Elasticsearch
ClickHouse
MongoDB

unless a measured need later justifies one.

The objective is not to maximize technologies on the README.

The objective is to show engineering judgment.

If PostgreSQL solves the problem correctly:

use PostgreSQL.

==================================================
CORE PRODUCT
==================================================

QuantaRun is a distributed workload control plane.

Clients submit workloads.

Workers register capabilities.

The scheduler chooses appropriate workers.

The system manages:

resource allocation
priorities
fairness
leases
heartbeats
retries
timeouts
failures
recovery
dead jobs
checkpoints
observability
simulation
policy comparison

The difficult engineering problems ARE the project.

Do not simplify them away.

==================================================
JOB MODEL
==================================================

Design a precise state machine.

Possible states:

SUBMITTED
QUEUED
SCHEDULED
RUNNING
RETRY_WAIT
SUCCEEDED
FAILED
DEAD
CANCELLED

Do not use every state automatically.

Choose only states that provide real semantics.

Centralize legal transitions.

Illegal state transitions must fail explicitly.

Do not scatter status manipulation throughout services.

==================================================
ATTEMPTS
==================================================

A logical job and an execution attempt are different concepts.

A retry creates a new attempt.

Each attempt should capture:

attempt number
worker
start time
end time
lease
failure type
retry decision
trace identifier

Do not overwrite historical attempts.

==================================================
WORKER MODEL
==================================================

Workers advertise:

CPU capacity
memory capacity
generic accelerator capacity
concurrency slots
labels
software version
heartbeat timestamp
worker status

No physical GPU is required.

Support simulated accelerator capacity.

This allows the entire project to run on a normal computer.

==================================================
RESOURCE CORRECTNESS
==================================================

The scheduler must never assign more resources than available.

This is a hard invariant.

It must remain correct with:

multiple scheduler threads
multiple processes
worker failure
retry
cancellation
lease expiration

Do not rely on Java in-memory counters as the source of truth.

Persistent state must survive restart.

==================================================
SCHEDULER
==================================================

The scheduling system must support multiple policies.

Minimum policies:

FIFO
priority
least-loaded compatible worker
fair-share between projects
deadline-aware
resource-aware placement

Every scheduling decision should be explainable.

For a decision, capture enough information to show:

chosen worker
compatible workers
rejected workers
rejection reasons
active policy
priority
queue age
deadline
fairness considerations
resource availability

Do not persist excessive useless diagnostic data.

==================================================
FAIRNESS
==================================================

Implement real fair scheduling.

Do not simply call something "fair-share".

Research a simple defendable strategy such as:

deficit scheduling
weighted fair queue concepts
virtual runtime
fair-share debt

Choose one.

Document the algorithm.

Write tests demonstrating that a tenant flooding the system does not completely starve another tenant.

==================================================
LEASES
==================================================

Assignments use leases.

Worker behavior:

claim job
start lease
periodically renew lease

If worker disappears:

lease expires
worker resources become reclaimable
job becomes recoverable
scheduler may reassign

Test this with real concurrency/integration tests.

==================================================
HEARTBEATS
==================================================

Workers heartbeat periodically.

Detect:

healthy
late
offline
draining

Avoid false state changes from one missed heartbeat.

Use reasonable thresholds.

Make values configurable.

==================================================
FAILURE RECOVERY
==================================================

Implement explicit failure categories.

Examples:

TRANSIENT
TIMEOUT
RATE_LIMITED
WORKER_LOST
INVALID_INPUT
RESOURCE_EXHAUSTED
PROVIDER_UNAVAILABLE
NON_RETRYABLE
INTERNAL

Retry policy must be explicit.

Support:

maximum attempts
exponential backoff
jitter
Retry-After where applicable
timeouts
dead-job state

Avoid retry storms.

==================================================
IDEMPOTENCY
==================================================

Job submission supports idempotency keys.

If two identical concurrent requests use the same key:

only one logical job must exist.

Prove this with a concurrency test.

Do not rely exclusively on:

"check then insert"

because it races.

Use database constraints and transactional logic.

==================================================
CHECKPOINTING
==================================================

Implement checkpointing only for workloads explicitly designed for it.

Do NOT claim arbitrary processes can resume magically.

Example:

stage 1
checkpoint
stage 2
checkpoint
stage 3

When recovery occurs:

resume from the last committed checkpoint if the workload supports it.

Otherwise restart.

Make this distinction visible.

==================================================
SIMULATION ENGINE
==================================================

Create a deterministic workload simulation engine.

Same:

scenario
seed
scheduler configuration

must produce the same simulation result.

Use a discrete-event simulation design if appropriate.

Do not use sleeps to simulate thousands of jobs.

Scenarios should include:

BURST
STEADY
MIXED_RESOURCES
ACCELERATOR_SCARCE
NOISY_NEIGHBOR
DEADLINE_HEAVY
WORKER_FAILURE
RATE_LIMIT

==================================================
POLICY COMPARISON
==================================================

The same workload trace must be replayable against multiple scheduler policies.

Compare metrics such as:

throughput
average queue wait
p50
p95
p99
deadline misses
resource utilization
accelerator utilization
starvation
fairness
scheduler decision latency

Do not declare a universally best scheduler.

Show tradeoffs.

==================================================
BUILT-IN WORKLOADS
==================================================

The project must work completely offline.

Implement deterministic workloads such as:

delay
CPU hashing
controlled memory work
HTTP request
mock AI inference
intentional failure
checkpointable staged workload

Optional later adapters:

OpenAI-compatible API
Anthropic
Ollama

They must not be required.

==================================================
CHAOS LAB
==================================================

Implement safe local failure injection.

Possible scenarios:

kill worker
pause worker heartbeat
artificial execution timeout
provider returns 429
provider returns 500
worker capacity disappears
network latency
malformed provider response

Never build arbitrary host command execution.

Chaos actions must only control project-owned components.

==================================================
OBSERVABILITY
==================================================

Observability is core.

Use OpenTelemetry.

Trace:

HTTP request
admission
queueing
scheduler decision
assignment
worker claim
workload execution
provider call
completion

Use correlation IDs.

Use structured logs.

Never use random System.out.println debugging in production code.

Metrics should include:

queue depth
jobs submitted
jobs running
jobs succeeded
jobs failed
jobs dead
retry count
lease expirations
active workers
worker capacity
resource utilization
scheduler latency
queue latency
execution latency
deadline misses

Expose through Micrometer/Prometheus.

==================================================
FRONTEND
==================================================

React + TypeScript strict.

The UI must look like a professional internal engineering tool.

Avoid typical AI-generated UI:

huge gradients
excessive cards
glassmorphism
marketing landing-page style
meaningless animations

Prioritize:

dense data
clarity
tables
filters
timelines
charts
operational state

Views:

Overview
Jobs
Job detail
Workers
Scheduler
Policy Lab
Chaos Lab
Dead Jobs
Events/Traces
Settings

==================================================
JOB DETAIL VIEW
==================================================

The job page should be one of the best parts of the application.

Display timeline such as:

Submitted
Queued
Scheduler evaluated candidates
Assigned to worker
Claimed
Execution started
Checkpoint
Worker failure
Lease expired
Retry scheduled
Reassigned
Succeeded

Display:

attempt history
worker history
resource allocation
scheduler decision
failures
retry reasoning
checkpoint information
trace identifiers

==================================================
REAL-TIME UI
==================================================

Prefer Server-Sent Events unless bidirectional communication is genuinely required.

Implement reconnect behavior.

Do not poll the complete application state every second.

==================================================
API QUALITY
==================================================

Use versioned APIs.

Use DTOs at API boundaries.

Never return persistence entities directly.

Use validation.

Use structured error responses.

Prefer RFC Problem Details where appropriate.

Generate OpenAPI documentation.

==================================================
AUTH
==================================================

Keep authentication focused.

Use project-scoped API keys.

Requirements:

secure random generation
hash before storage
show secret once
revoke keys
optional scopes

Do not waste project scope implementing social login.

==================================================
SECURITY
==================================================

Threat-model:

SSRF
arbitrary code execution
oversized payloads
secret leakage
API key exposure
tenant isolation
denial of service
unsafe chaos actions
log injection

Never allow arbitrary remote shell execution.

HTTP workload executor must include SSRF protections.

Create:

SECURITY.md
docs/THREAT_MODEL.md

==================================================
TESTING
==================================================

Tests are mandatory.

Use:

JUnit 5
Testcontainers
real PostgreSQL
Playwright
property-based testing where valuable

Potential property-testing library:

jqwik

Do not mock PostgreSQL for concurrency semantics.

==================================================
MANDATORY TESTS
==================================================

Test:

concurrent job claiming
idempotent concurrent submission
capacity cannot be exceeded
worker failure recovery
lease expiration
duplicate completion
retry limits
dead-job transitions
cancellation race
fair scheduling
resource compatibility
deadline scheduling
illegal state transitions
deterministic simulation
same seed gives same result
Flyway clean migration
API validation
API-key authentication

==================================================
PROPERTY TESTS
==================================================

Create important invariant tests.

Examples:

For every generated workload and worker topology:

allocated CPU <= available CPU

allocated memory <= available memory

allocated accelerators <= available accelerators

A successful logical job has at most one committed successful result.

Terminal states cannot transition illegally.

Retries never exceed configured maximum.

==================================================
CONCURRENCY TESTING
==================================================

Actively try to break the system.

Test:

many scheduler threads
many workers
duplicate submissions
heartbeat vs lease expiration
completion vs lease expiration
cancellation vs scheduling
retry vs cancellation

When a race condition is discovered:

do not patch around the failing test.

Understand the invariant.

Fix the architecture.

==================================================
ERROR HANDLING
==================================================

Never:

catch Exception and ignore it

Never:

return null to hide failure

Never:

log and continue when state correctness may be broken

Use typed domain errors where useful.

Differentiate:

expected domain condition

from:

unexpected system failure.

==================================================
LOGGING
==================================================

Logs must be structured.

Use appropriate levels:

TRACE
DEBUG
INFO
WARN
ERROR

Do not spam INFO logs in hot loops.

Do not log secrets.

Do not log full sensitive payloads.

Include relevant IDs:

jobId
attemptId
workerId
projectId
traceId

==================================================
DEPENDENCY RULES
==================================================

Every new dependency must answer:

What problem does this solve?

Could existing platform/library functionality solve it?

Is this dependency maintained?

Does it introduce meaningful complexity?

Avoid libraries for trivial tasks.

==================================================
FRONTEND QUALITY
==================================================

TypeScript strict must remain enabled.

Avoid any.

Prefer explicit types.

Use server-state tooling such as TanStack Query if justified.

Do not add Redux unless the state model genuinely requires it.

Implement:

loading states
empty states
error states
retry states
responsive layout
keyboard usability
basic accessibility

==================================================
NO PLACEHOLDER CODE
==================================================

Before calling a feature complete, remove:

TODO placeholders
fake statistics
fake charts
Lorem Ipsum
temporary hard-coded responses
mock frontend data

unless explicitly labeled as demo fixture data.

==================================================
CI
==================================================

GitHub Actions should verify:

backend compile
backend unit tests
integration tests
frontend typecheck
frontend lint
frontend tests
frontend build
Docker build
formatting

Keep expensive benchmarks outside normal PR CI.

==================================================
GIT QUALITY
==================================================

Use Conventional Commits.

Examples:

feat(scheduler): add resource-aware worker selection

fix(reliability): avoid lease recovery race

test(scheduler): prove worker capacity cannot overcommit

perf(database): reduce scheduler contention

refactor(jobs): centralize state transitions

docs: explain lease recovery semantics

Commits should be meaningful.

Do not create dozens of meaningless commits.

Do not fake development history.

==================================================
DOCUMENTATION
==================================================

Maintain:

README.md
CLAUDE.md
CONTRIBUTING.md
SECURITY.md

docs/

SPEC.md
ARCHITECTURE.md
INVARIANTS.md
SCHEDULER.md
FAILURE_SEMANTICS.md
OBSERVABILITY.md
THREAT_MODEL.md
BENCHMARKS.md
DEMO.md
ENGINEERING_LOG.md
INTERVIEW_GUIDE.md
PORTFOLIO.md

Keep docs synchronized with reality.

Delete stale documentation.

==================================================
CLAUDE.md
==================================================

Create a project CLAUDE.md containing the most important engineering constraints.

It should remind future sessions:

correctness over speed
no unnecessary infrastructure
no arbitrary code execution
Flyway only for schema
TypeScript strict remains enabled
PostgreSQL concurrency tests use real PostgreSQL
tests cannot be weakened
benchmark numbers must be real
architecture changes require documentation
comments explain why
avoid AI-generated abstraction patterns
run tests before declaring completion

Keep CLAUDE.md useful and concise.

==================================================
ENGINEERING LOG
==================================================

Maintain:

docs/ENGINEERING_LOG.md

Record meaningful discoveries:

race conditions
design mistakes
failed approaches
interesting PostgreSQL behavior
scheduler tradeoffs
performance bottlenecks
benchmark discoveries
security findings

Do NOT record every file edit.

This document should show actual engineering thought.

==================================================
CODE REVIEW LOOP
==================================================

After implementing every significant feature, perform a self-review.

Review for:

correctness
race conditions
transaction boundaries
naming
unnecessary abstractions
duplicate logic
N+1 queries
unbounded collections
resource leaks
security
error handling
test coverage
observability
performance

Then improve the implementation before continuing.

==================================================
PERFORMANCE REVIEW
==================================================

Inspect for:

unbounded loops
unbounded thread creation
unbounded queues
unnecessary allocations
N+1 queries
missing indexes
slow serialization
database polling frequency
lock contention

Do not optimize unless useful, but do not ignore obvious inefficiencies.

==================================================
MEMORY AND RESOURCE MANAGEMENT
==================================================

Never create:

unbounded caches
unbounded event buffers
unbounded thread pools

Set explicit limits.

Use backpressure where needed.

Release resources correctly.

==================================================
BENCHMARKING
==================================================

Use tools such as:

JMH
k6
custom deterministic scheduler benchmark

Record:

date
commit
hardware
OS
command
configuration
scenario

Never fabricate benchmark numbers.

==================================================
DEMO
==================================================

The strongest demo should be approximately five minutes.

Example:

1. Start stack.
2. Show three heterogeneous workers.
3. Submit mixed workload.
4. Show scheduling decision.
5. Kill active worker.
6. Observe heartbeat/lease failure.
7. Observe automatic recovery.
8. Inspect job timeline.
9. Replay same scenario under another policy.
10. Compare fairness/latency/resource usage.

Create:

docs/DEMO.md

==================================================
README
==================================================

README must immediately communicate:

what QuantaRun is
why it exists
why it is technically interesting

Include real screenshots after UI exists.

Sections:

Overview
Engineering Highlights
Architecture
Reliability Model
Scheduling
Failure Recovery
Policy Simulation
Observability
Testing
Benchmarks
Quick Start
Technology
Design Decisions
Known Limitations
Author

Author:

Martí Aguilera

GitHub:
https://github.com/martiaaguilera

LinkedIn:
https://www.linkedin.com/in/martiaaguilera/

Avoid exaggerated marketing language.

==================================================
PORTFOLIO MATERIAL
==================================================

When the project genuinely works, create:

docs/PORTFOLIO.md

Include:

project description
CV description
3 strong CV bullet points
technology list
LinkedIn description
GitHub repository description

Only mention engineering achievements actually implemented.

Never invent scale.

==================================================
INTERVIEW PREPARATION
==================================================

Create:

docs/INTERVIEW_GUIDE.md

Teach Martí to explain:

architecture
PostgreSQL coordination
SKIP LOCKED
leases
heartbeats
idempotency
transactions
resource scheduling
fair scheduling
failure recovery
retry semantics
simulation
OpenTelemetry
benchmarking
tradeoffs

Include:

30-second project explanation

2-minute technical explanation

deep-dive questions

"What would you change at 100x scale?"

"When would Kafka become useful?"

"When would Kubernetes become useful?"

"What was the hardest race condition?"

Use real implementation discoveries.

==================================================
PHASES
==================================================

PHASE 0
Research and architecture

PHASE 1
Project foundation

PHASE 2
Jobs and state machine

PHASE 3
Workers and resources

PHASE 4
Scheduler

PHASE 5
Leases and reliability

PHASE 6
Retries and failure semantics

PHASE 7
Fairness and advanced scheduling

PHASE 8
Simulation and replay

PHASE 9
Chaos testing

PHASE 10
Observability

PHASE 11
Professional frontend

PHASE 12
Concurrency hardening

PHASE 13
Performance tuning

PHASE 14
Security review

PHASE 15
Portfolio release

Do not require my approval between obvious phases.

Continue autonomously.

==================================================
QUALITY GATE
==================================================

A feature is only complete when:

implementation exists

tests exist

tests pass

error behavior is handled

observability exists where needed

documentation matches behavior

no fake data remains

code has been self-reviewed

==================================================
FINAL SENIOR REVIEW
==================================================

Before considering the project finished, review it as if you were a skeptical senior engineer interviewing the candidate.

Ask:

Is this architecture unnecessarily complex?

Is this actually distributed?

Are concurrency guarantees real?

Can resources be overallocated?

Can two workers execute the same assignment?

What happens when a worker dies?

What happens when completion races lease expiration?

What happens if PostgreSQL restarts?

Are retries bounded?

Can a tenant starve another?

Can priority jobs starve normal jobs?

Are database indexes appropriate?

Are transaction boundaries short?

Are network calls inside transactions?

Are there hidden N+1 queries?

Are logs useful?

Are metrics meaningful?

Are secrets protected?

Are benchmark results reproducible?

Does Docker start from a clean machine?

Can another engineer understand the project?

Would I approve this pull request?

Create:

docs/FINAL_REVIEW.md

Fix every critical/high severity issue before release.

==================================================
IMPORTANT: DO NOT WRITE LIKE AN AI
==================================================

Actively remove common AI-code characteristics.

Do not produce:

excessive abstraction
huge comments
obvious comments
verbose Javadocs
unnecessary factories
unnecessary interfaces
duplicate DTO layers
massive service classes
generic helper classes
overly defensive boilerplate
random design patterns

The code should look like it was written by a careful experienced engineer.

When a solution can be:

20 clear lines

instead of:

120 abstract lines

prefer the 20 clear lines.

==================================================
AUTONOMY
==================================================

You are expected to make engineering decisions.

Research when uncertain.

Choose sensible defaults.

Document important tradeoffs.

Do not constantly ask me questions.

Only stop when blocked by something requiring:

credentials
payment
external account authorization
legal acceptance
destructive irreversible action

Otherwise continue.

==================================================
START
==================================================

Start now.

Do not merely explain what you are going to do.

Perform the work.

First:

1. inspect current directory;
2. inspect environment;
3. verify Docker and WSL;
4. verify stable tool versions;
5. research comparable projects;
6. validate project name;
7. define invariants;
8. write specification;
9. write architecture;
10. initialize Git;
11. create repository structure;
12. implement Phase 1;
13. run tests.

At the end of every work session report:

COMPLETED

VERIFIED

TESTS

ARCHITECTURE CHANGES

IMPORTANT DISCOVERIES

CURRENT LIMITATIONS

NEXT TASK

BLOCKERS

Do not ask:

"What should I do next?"

when the answer is obvious.

Continue building the next logical component.

The final repository must be something Martí Aguilera can confidently put at the top of his GitHub and explain technically in a software engineering interview.

Build it accordingly.
