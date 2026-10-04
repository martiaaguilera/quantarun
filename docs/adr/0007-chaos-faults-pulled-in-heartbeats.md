# ADR-0007: Chaos faults are pulled by workers in heartbeat responses

- Status: accepted (2026-10-04)

## Context
The chaos lab must make workers misbehave on request: crash, go silent, stop claiming, slow down, fail provider
calls. The obvious design is a control-plane call to an endpoint on each worker. But workers expose only actuator
health today, and ADR-0005 keeps all coordination in one direction, from worker to control plane. An inbound
"break yourself" endpoint would also be the most dangerous endpoint in the system: it would need its own
authentication, and anything that can reach it could trigger it.

## Decision
The operator records an experiment in the control plane, and the worker receives it in the response to its next
heartbeat (`HeartbeatResponse.chaos`). It is a list of directives naming a closed `ChaosFault` with bounded integer
parameters. The worker applies a directive to its own process only, and only if it was started with chaos enabled.
Delivery is a conditional update, so each experiment reaches its worker at most once, and never after its delivery
deadline.

## Consequences
- No new listener, port or credential on workers. Chaos uses the authenticated channel that already exists, and a
  worker that did not opt in cannot be made to do anything.
- A fault arrives within one heartbeat interval (3 s), not instantly. That is fine, because recovery is measured
  from delivery, which is recorded.
- A worker that is not heartbeating cannot receive a fault, and the experiment expires instead. That is the right
  outcome: such a worker is already failing.
- A heartbeat now runs one more statement, an index-only probe of a partial index that is almost always empty
  (0.16 ms measured).
