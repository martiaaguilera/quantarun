import { describe, expect, it } from 'vitest'
import type { Attempt, Decision, Job, JobEvent } from '../../api/types'
import { buildLifeline, buildStory } from './story'

const T0 = Date.parse('2026-10-04T12:00:00.000Z')
const at = (ms: number) => new Date(T0 + ms).toISOString()
const names = (id: string) => ({ w1: 'worker-cpu', w2: 'worker-mixed' })[id] ?? id

function event(id: number, type: JobEvent['type'], ms: number, details: Record<string, unknown> = {}, attemptId: string | null = null): JobEvent {
  return { id, attemptId, type, occurredAt: at(ms), details }
}

function decision(id: number, outcome: Decision['outcome'], ms: number, reason: string, attemptId: string | null = null): Decision {
  return {
    id,
    jobId: 'j',
    attemptId,
    policy: 'FIFO',
    outcome,
    chosenWorkerId: null,
    reason,
    candidates: [
      { workerId: 'w1', workerName: 'worker-cpu', verdict: 'CHOSEN', detail: 'fits', score: 0.1 },
      { workerId: 'w2', workerName: 'worker-mixed', verdict: 'FITS', detail: 'fits', score: 0.4 },
    ],
    queueWaitMs: ms,
    decidedAt: at(ms),
  }
}

describe('buildStory', () => {
  it('tells a lost attempt and its recovery in order, with the placement decision merged in', () => {
    const rows = buildStory(
      [
        event(1, 'SUBMITTED', 0, { workloadType: 'delay', priority: 4 }),
        event(2, 'SCHEDULED', 100, { workerId: 'w1', attemptNo: 1, reason: 'oldest first' }, 'a1'),
        event(3, 'STARTED', 600, { workerId: 'w1' }, 'a1'),
        event(4, 'ATTEMPT_LOST', 16_000, { workerId: 'w1', attemptNo: 1, failureClass: 'WORKER_LOST' }, 'a1'),
        event(5, 'RETRY_SCHEDULED', 16_000, { decision: 'retry now: worker lost' }, 'a1'),
        event(6, 'SCHEDULED', 16_200, { workerId: 'w2', attemptNo: 2 }, 'a2'),
        event(7, 'SUCCEEDED', 26_000, { workerId: 'w2', attemptNo: 2 }, 'a2'),
      ],
      [decision(10, 'PLACED', 100, 'oldest first', 'a1')],
      names,
    )

    expect(rows.map((row) => row.title)).toEqual([
      'Submitted',
      'Assigned to worker-cpu',
      'Claimed; execution started',
      'Lease expired: worker presumed lost',
      'Retry scheduled',
      'Assigned to worker-mixed (reassigned)',
      'Succeeded',
    ])
    expect(rows[1]?.detail).toBe('2 workers evaluated. oldest first')
    expect(rows[4]?.detail).toBe('retry now: worker lost')
  })

  it('collapses a run of identical waiting verdicts into one row', () => {
    const rows = buildStory(
      [],
      [
        decision(1, 'WAITING_FOR_CAPACITY', 1_000, 'no free slot'),
        decision(2, 'WAITING_FOR_CAPACITY', 1_500, 'no free slot'),
        decision(3, 'WAITING_FOR_CAPACITY', 2_000, 'no free slot'),
        decision(4, 'UNSCHEDULABLE', 2_500, 'needs label cuda'),
      ],
      names,
    )

    expect(rows.map((row) => row.title)).toEqual(['Waiting for capacity (3 evaluations)', 'Unschedulable'])
    expect(rows[0]?.until).toBe(at(2_000))
    expect(rows[1]?.tone).toBe('bad')
  })
})

describe('buildLifeline', () => {
  const job = { createdAt: at(0), finishedAt: at(26_000) } as Job
  const attempt = (no: number, status: Attempt['status'], assigned: number, started: number | null, finished: number | null): Attempt =>
    ({
      id: `a${String(no)}`,
      attemptNo: no,
      workerId: no === 1 ? 'w1' : 'w2',
      status,
      assignedAt: at(assigned),
      startedAt: started === null ? null : at(started),
      finishedAt: finished === null ? null : at(finished),
    }) as Attempt

  it('draws the waits on a queue lane and each attempt from assignment through claim and run', () => {
    const lifeline = buildLifeline(
      job,
      [attempt(1, 'LOST', 100, 600, 16_000), attempt(2, 'SUCCEEDED', 16_200, 16_700, 26_000)],
      [],
      T0 + 30_000,
      names,
    )

    expect(lifeline.lanes.map((lane) => lane.label)).toEqual([
      'Queue',
      'Attempt 1 on worker-cpu',
      'Attempt 2 on worker-mixed',
    ])
    const [queue, first, second] = lifeline.lanes
    expect(queue?.segments.map((segment) => [segment.from - T0, segment.to - T0])).toEqual([
      [0, 100],
      [16_000, 16_200],
    ])
    expect(first?.segments.map((segment) => segment.kind)).toEqual(['claim', 'run'])
    expect(first?.segments[1]?.tone).toBe('bad')
    expect(first?.marks).toEqual([{ at: T0 + 16_000, kind: 'lost', label: 'Lease expired' }])
    expect(second?.segments[1]?.tone).toBe('good')
    expect(lifeline.end - T0).toBe(26_000)
  })

  it('shows a job still waiting as an open queue segment up to now', () => {
    const lifeline = buildLifeline({ createdAt: at(0), finishedAt: null } as Job, [], [], T0 + 5_000, names)

    expect(lifeline.lanes[0]?.segments).toEqual([
      { kind: 'queued', from: T0, to: T0 + 5_000, tone: 'warn', label: 'Waiting for 5.00 s' },
    ])
  })
})
