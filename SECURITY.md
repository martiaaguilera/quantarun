# Security

## Reporting a vulnerability

Please report security issues privately, through GitHub's
[private vulnerability reporting](https://github.com/martiaaguilera/quantarun/security/advisories/new) for this
repository, not in a public issue. Include what you found, how to reproduce it, and the commit you tested. Expect an
answer within a week. QuantaRun is a portfolio project maintained by one person, so there is no bounty and no SLA
beyond that.

## Supported versions

Only `main` is supported. There are no released versions yet.

## How QuantaRun is secured

The threat model, its mitigations, the tests that prove them, and the residual risks are in
[docs/THREAT_MODEL.md](docs/THREAT_MODEL.md). In short:

- **No arbitrary code execution:** workloads are built-in executors chosen by name; a payload is data.
- **The http workload** cannot reach private, loopback, link-local or reserved addresses, with the check done in the
  DNS resolver itself.
- **API keys and worker credentials** are 256-bit secrets stored only as SHA-256 hashes, shown once, revocable, and
  never logged.
- **Every request is authenticated before any controller runs,** and tenants see only their own project's data.
- **Request bodies, payloads, results, pages and streams are size-capped;** expensive operations are rate-bounded.
- **Chaos testing** is off unless explicitly enabled, operator-only, and limited to QuantaRun's own workers.

## Running it safely

The compose stack is for local use: every port binds to `127.0.0.1`. Set your own `QUANTARUN_ADMIN_TOKEN` and
`QUANTARUN_WORKER_BOOTSTRAP_TOKEN` (at least 32 characters) in `.env`. The defaults in `.env.example` are public
placeholders. Before exposing it beyond one machine, put TLS and a rate limiter in front of it and read the residual
risks in the threat model.
