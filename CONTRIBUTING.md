# Contributing

QuantaRun is a portfolio project maintained by one person, but it is built the way a team project should be: every
change is reviewed against written rules, and every guarantee has a test. Issues and pull requests are welcome. This
page says what a change needs to be accepted.

## Before you start

Read the documents for the area you are changing. They are short, and they are the source of truth:

| If you change… | Read first |
|---|---|
| Job, attempt or worker states | [SPEC.md](docs/SPEC.md) §3–5, [INVARIANTS.md](docs/INVARIANTS.md) |
| Scheduling or a policy | [SCHEDULER.md](docs/SCHEDULER.md), [ADR-0004](docs/adr/0004-pure-policies-shared-with-simulator.md) |
| Leases, retries, recovery | [FAILURE_SEMANTICS.md](docs/FAILURE_SEMANTICS.md), [ADR-0002](docs/adr/0002-leases-and-fencing.md) |
| The worker protocol | [ARCHITECTURE.md](docs/ARCHITECTURE.md), [ADR-0005](docs/adr/0005-workers-use-http-not-the-database.md) |
| Anything a tenant can send | [THREAT_MODEL.md](docs/THREAT_MODEL.md) |
| The HTTP API | [API.md](docs/API.md) |

For anything larger than a bug fix, open an issue first and describe the problem, not only the solution.

## Setting up

You need Docker (for Testcontainers and the compose stack), JDK 25 and Node 24.

```bash
./mvnw verify                          # format check, compile, unit + Testcontainers tests
cd apps/web && npm ci && npm run check # typecheck, lint, unit tests, build
cp .env.example .env && docker compose up --build --wait
cd apps/web && npx playwright install chromium && npm run e2e   # console end-to-end tests, against the stack
```

`./mvnw spotless:apply` formats the Java code (Palantir Java Format). The web code is checked by ESLint with
type-aware rules.

## Rules a change must follow

These are not style preferences. Each one exists because breaking it caused, or would cause, a real defect.

- **Every state change is a conditional write.** `UPDATE … WHERE status = …`, with the affected-row count as the
  result. Never read, decide in Java, then write unconditionally.
- **Schema changes go in a new Flyway migration.** Never edit an applied one. There is no ORM and no generated DDL.
- **No network I/O, workload execution or sleep inside a database transaction.** Keep transactions short. Lock jobs
  before workers, and workers in ascending id order.
- **Concurrency is tested against real PostgreSQL.** Locking, `SKIP LOCKED` and constraints cannot be mocked. A new
  mechanism comes with the test that tries to break it, and a new invariant goes into INVARIANTS.md with that test.
- **Never weaken a test to make it pass.** If an invariant test fails, the design is wrong until proven otherwise.
  Explain what you found in [ENGINEERING_LOG.md](docs/ENGINEERING_LOG.md).
- **No arbitrary code execution.** Workloads are built-in executors chosen by name. The `http` workload keeps its SSRF
  guard.
- **No new infrastructure** (brokers, caches, orchestrators) without a measured need recorded in an ADR.
- **Numbers are measured.** A benchmark figure comes with its date, commit, hardware and command, or it says "not
  measured".
- **TypeScript stays `strict`**, with no `any`.
- **Plain code.** Records, constructor injection, package-private by default. No Service/ServiceImpl pairs, managers
  or utils. Comments explain why, not what.

## Tests

| Kind | Where | Runs in |
|---|---|---|
| Unit and property tests (planner, retry policy, simulator) | `apps/*/src/test` | `./mvnw verify` |
| Integration and race tests on PostgreSQL 18 (Testcontainers) | `apps/control-plane/src/test` | `./mvnw verify` |
| Console unit tests (Vitest) | `apps/web/src/**/*.test.ts(x)` | `npm run check` |
| Console end-to-end tests (Playwright) | `apps/web/e2e` | `npm run e2e`, against a running stack |
| Repeated race suites | `scripts/repeat-race-tests.sh` | by hand, before merging a concurrency change |

Run `scripts/repeat-race-tests.sh 10` before merging anything that touches locking, leases or the scheduler, and
record the result in INVARIANTS.md.

## Commits and pull requests

- [Conventional Commits](https://www.conventionalcommits.org/): `feat(scheduler): …`, `fix(worker): …`,
  `test(execution): …`, `docs: …`. One coherent change per commit.
- A pull request says what changed, why, and how it was verified: which tests, and what was run live.
- Architecture changes update ARCHITECTURE.md, and an ADR where the decision matters, in the same pull request.
- CI must be green: backend, web, the compose smoke test with the end-to-end tests, and dependency review.

## Security issues

Do not open a public issue. Follow [SECURITY.md](SECURITY.md).
