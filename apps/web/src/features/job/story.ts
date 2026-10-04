import type { Attempt, Checkpoint, Decision, Job, JobEvent } from '../../api/types'
import { formatMs, humanize, shortId } from '../../format'

export type Tone = 'neutral' | 'active' | 'warn' | 'good' | 'bad' | 'muted'

export interface StoryRow {
  key: string
  at: string
  /** Absent for a row that stands for a span of repeated decisions. */
  until?: string
  title: string
  detail?: string
  tone: Tone
  attemptNo?: number
}

function text(value: unknown): string | undefined {
  return typeof value === 'string' && value.length > 0 ? value : undefined
}

/** Spread into a row: adds the detail only when there is one, so optional fields stay absent rather than undefined. */
function detailOf(detail: string | undefined): { detail?: string } {
  return detail ? { detail } : {}
}

function number(value: unknown): number | undefined {
  return typeof value === 'number' ? value : undefined
}

/**
 * The job's history in the words an operator uses, oldest first: its events, and the scheduler's decisions about it.
 * A placement merges with its decision (who was considered and why this worker won). Repeated "still waiting"
 * verdicts collapse into one row, since a job can be evaluated every cycle for minutes.
 */
export function buildStory(
  events: JobEvent[],
  decisions: Decision[],
  workerName: (id: string) => string,
): StoryRow[] {
  const placedBy = new Map<string, Decision>()
  for (const decision of decisions) {
    if (decision.outcome === 'PLACED' && decision.attemptId) {
      placedBy.set(decision.attemptId, decision)
    }
  }
  const rows: StoryRow[] = []

  for (const event of events) {
    const details = event.details
    const attemptNo = number(details['attemptNo'])
    const worker = text(details['workerId'])
    const base = { key: `e${String(event.id)}`, at: event.occurredAt, ...(attemptNo === undefined ? {} : { attemptNo }) }
    switch (event.type) {
      case 'SUBMITTED':
        rows.push({ ...base, title: 'Submitted', detail: `${String(details['workloadType'])}, priority ${String(details['priority'])}`, tone: 'neutral' })
        break
      case 'SCHEDULED': {
        const decision = event.attemptId ? placedBy.get(event.attemptId) : undefined
        const considered = decision ? `${String(decision.candidates.length)} workers evaluated. ` : ''
        rows.push({
          ...base,
          title: `Assigned to ${worker ? workerName(worker) : 'a worker'}${attemptNo && attemptNo > 1 ? ' (reassigned)' : ''}`,
          detail: `${considered}${text(details['reason']) ?? decision?.reason ?? ''}`.trim(),
          tone: 'active',
        })
        break
      }
      case 'STARTED':
        rows.push({
          ...base,
          title: 'Claimed; execution started',
          ...detailOf(text(details['resume']) && `Resumes ${String(details['resume'])}`),
          tone: 'active',
        })
        break
      case 'CHECKPOINT_COMMITTED':
        rows.push({ ...base, title: `Checkpoint: stage ${String(details['stageIndex'])} committed`, tone: 'neutral' })
        break
      case 'ATTEMPT_FAILED':
        rows.push({
          ...base,
          title: `Attempt failed: ${humanize(text(details['failureClass']) ?? 'INTERNAL')}`,
          ...detailOf(text(details['message'])),
          tone: 'bad',
        })
        break
      case 'ATTEMPT_LOST':
        rows.push({
          ...base,
          title: 'Lease expired: worker presumed lost',
          detail: `${worker ? workerName(worker) : 'The worker'} stopped renewing the attempt's lease; its reservation was released.`,
          tone: 'bad',
        })
        break
      case 'RETRY_SCHEDULED':
        rows.push({ ...base, title: 'Retry scheduled', ...detailOf(text(details['decision'])), tone: 'warn' })
        break
      case 'SUCCEEDED':
        rows.push({ ...base, title: 'Succeeded', tone: 'good' })
        break
      case 'FAILED':
        rows.push({ ...base, title: 'Failed for good', ...detailOf(text(details['decision'])), tone: 'bad' })
        break
      case 'DEAD':
        rows.push({ ...base, title: 'Dead: attempt budget used up', ...detailOf(text(details['decision'])), tone: 'bad' })
        break
      case 'CANCEL_REQUESTED':
        rows.push({ ...base, title: 'Cancellation requested', detail: 'The worker is told in its next heartbeat.', tone: 'warn' })
        break
      case 'CANCELLED':
        rows.push({ ...base, title: 'Cancelled', ...detailOf(text(details['reason'])), tone: 'muted' })
        break
      case 'REVIVED':
        rows.push({ ...base, title: 'Revived with a fresh attempt budget', tone: 'active' })
        break
    }
  }

  // Waiting verdicts: one row per run of identical outcome and reason.
  const waiting = decisions
    .filter((decision) => decision.outcome !== 'PLACED')
    .sort((a, b) => a.decidedAt.localeCompare(b.decidedAt))
  let run: Decision[] = []
  const flush = () => {
    const first = run[0]
    const last = run.at(-1)
    if (first && last) {
      rows.push({
        key: `d${String(first.id)}`,
        at: first.decidedAt,
        ...(run.length > 1 ? { until: last.decidedAt } : {}),
        title: `${humanize(first.outcome)}${run.length > 1 ? ` (${String(run.length)} evaluations)` : ''}`,
        detail: first.reason,
        tone: first.outcome === 'UNSCHEDULABLE' ? 'bad' : 'warn',
      })
    }
    run = []
  }
  for (const decision of waiting) {
    const previous = run.at(-1)
    if (previous && (previous.outcome !== decision.outcome || previous.reason !== decision.reason)) {
      flush()
    }
    run.push(decision)
  }
  flush()

  return rows.sort((a, b) => a.at.localeCompare(b.at) || a.key.localeCompare(b.key))
}

export interface LaneSegment {
  kind: 'queued' | 'claim' | 'run'
  from: number
  to: number
  tone: Tone
  label: string
}

export interface Lane {
  key: string
  label: string
  segments: LaneSegment[]
  marks: { at: number; kind: 'checkpoint' | 'lost'; label: string }[]
}

export interface Lifeline {
  start: number
  end: number
  lanes: Lane[]
}

const OUTCOME_TONE: Record<string, Tone> = {
  SUCCEEDED: 'good',
  FAILED: 'bad',
  LOST: 'bad',
  CANCELLED: 'muted',
  RUNNING: 'active',
  ASSIGNED: 'neutral',
}

/**
 * The job on one time axis: a queue lane for every wait (submission or backoff until placement), and a lane per
 * attempt from assignment through claim and execution to its end. Open intervals run to {@code now}.
 */
export function buildLifeline(job: Job, attempts: Attempt[], checkpoints: Checkpoint[], now: number, workerName: (id: string) => string): Lifeline {
  const start = Date.parse(job.createdAt)
  const sorted = [...attempts].sort((a, b) => a.attemptNo - b.attemptNo)
  const queue: LaneSegment[] = []
  let waitingSince = start
  for (const attempt of sorted) {
    const assigned = Date.parse(attempt.assignedAt)
    if (assigned > waitingSince) {
      queue.push({ kind: 'queued', from: waitingSince, to: assigned, tone: 'neutral', label: `Waited ${formatMs(assigned - waitingSince)}` })
    }
    waitingSince = attempt.finishedAt ? Date.parse(attempt.finishedAt) : now
  }
  const finished = job.finishedAt ? Date.parse(job.finishedAt) : null
  if (finished === null && sorted.every((attempt) => attempt.finishedAt !== null)) {
    // Queued again (first wait, or a retry backoff) and not placed yet.
    queue.push({ kind: 'queued', from: waitingSince, to: now, tone: 'warn', label: `Waiting for ${formatMs(now - waitingSince)}` })
  }

  const lanes: Lane[] = [{ key: 'queue', label: 'Queue', segments: queue, marks: [] }]
  for (const attempt of sorted) {
    const assigned = Date.parse(attempt.assignedAt)
    const started = attempt.startedAt ? Date.parse(attempt.startedAt) : null
    const ended = attempt.finishedAt ? Date.parse(attempt.finishedAt) : now
    const segments: LaneSegment[] = [
      {
        kind: 'claim',
        from: assigned,
        to: started ?? ended,
        tone: 'neutral',
        label: started ? `Claimed after ${formatMs(started - assigned)}` : 'Never claimed',
      },
    ]
    if (started !== null) {
      segments.push({
        kind: 'run',
        from: started,
        to: ended,
        tone: OUTCOME_TONE[attempt.status] ?? 'neutral',
        label: `${humanize(attempt.status)} after ${formatMs(ended - started)}`,
      })
    }
    const marks: Lane['marks'] = checkpoints
      .filter((checkpoint) => checkpoint.attemptId === attempt.id)
      .map((checkpoint) => ({ at: Date.parse(checkpoint.committedAt), kind: 'checkpoint', label: `Stage ${String(checkpoint.stageIndex)}` }))
    if (attempt.status === 'LOST' && attempt.finishedAt) {
      marks.push({ at: ended, kind: 'lost', label: 'Lease expired' })
    }
    lanes.push({ key: attempt.id, label: `Attempt ${String(attempt.attemptNo)} on ${workerName(attempt.workerId)}`, segments, marks })
  }
  const end = Math.max(finished ?? now, ...lanes.flatMap((lane) => lane.segments.map((segment) => segment.to)), start + 1)
  return { start, end, lanes }
}

export function workerLabel(names: Map<string, string>) {
  return (id: string) => names.get(id) ?? `worker ${shortId(id)}`
}
