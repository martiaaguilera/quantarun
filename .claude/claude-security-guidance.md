# QuantaRun security rules

- Job payloads, workload type names and checkpoint data are attacker-controlled. They must never reach a shell,
  `Runtime.exec`/`ProcessBuilder`, expression evaluation (SpEL), reflection/`Class.forName`, template rendering or
  Java/polymorphic deserialization. Workload executors come from a fixed registry.
- The `http` workload must resolve DNS and reject loopback, private, link-local and metadata addresses (incl. IPv6
  and redirects) unless they are explicitly allowlisted.
- Project API keys: 256-bit random, stored only as hashes, compared in constant time, shown once, never logged or
  put in errors, metric tags or traces. Worker tokens get the same treatment.
- Every job, attempt, checkpoint and decision endpoint authorizes the caller's project. A UUID alone is not
  authorization.
- Worker protocol writes must be fenced by attempt id + worker id. A worker must not be able to act on another
  worker's attempt.
- Chaos endpoints act only on QuantaRun's own registered workers, behind an explicit profile flag. They never run
  host commands.
- Actuator: only health/info are public. Size limits apply to request bodies, payloads and checkpoint data.
