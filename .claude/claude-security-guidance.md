# ForgeFlow security rules

- Job payloads, job type names and handler parameters are attacker-controlled. They must never reach a
  shell, `Runtime.exec`/`ProcessBuilder`, SpEL/expression evaluation, `Class.forName`/reflection, template
  rendering, or Java/polymorphic JSON deserialization. Handlers are resolved from a fixed registry by name.
- API keys: stored only as hashes, compared in constant time, returned once at creation, never logged or
  included in errors, metrics tags or traces.
- Every job/worker/DLQ endpoint must authorize the caller before reading or mutating a job; a job ID alone
  is not authorization.
- Redis values must not use Jackson default typing; Redis loss must never cause a job to run twice or be lost.
- Actuator: only health/info are public. No `env`, `configprops`, `heapdump` exposure.
- Frontend must not render API data with `dangerouslySetInnerHTML`.
