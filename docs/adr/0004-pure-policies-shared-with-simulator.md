# ADR-0004: Scheduling policies are pure functions shared by the scheduler and the simulator

- Status: accepted (2026-09-30)

## Context
Policy comparison is only credible if the simulated policy is the *same code* as the live one. Policies
that query the database or read the wall clock cannot be simulated deterministically.

## Decision
A policy receives an immutable snapshot and returns placements with explanations. The snapshot contains
the runnable jobs, each worker's free capacity and labels, per-project fair-share state, and the current
time as a value. Policies have no Spring, SQL, clock or randomness. They are a sealed set of implementations
(ordering × placement) selected by configuration.
- The live scheduler builds the snapshot from locked rows and persists the result.
- The simulator builds snapshots from its in-memory state and advances simulated time.

## Consequences
- Determinism is testable: the same snapshot always yields the same decision. Property-based tests run
  directly against policies.
- The live and simulated behaviour differ only in what the snapshot omits (DB latency, contention), and that
  difference is measured in benchmarks, not ignored.
- This interface has several real implementations, so it earns its place.
