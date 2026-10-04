import { useState } from 'react'
import { Link } from 'react-router'
import type { JobEventType } from '../api/types'
import { MAX_LIVE_EVENTS, useLiveEvents } from '../app/context'
import { Empty, Panel } from '../components/ui'
import { formatTime, humanize, shortId } from '../format'

const GROUPS: Record<string, JobEventType[]> = {
  All: [],
  Failures: ['ATTEMPT_FAILED', 'ATTEMPT_LOST', 'FAILED', 'DEAD'],
  Placement: ['SCHEDULED', 'STARTED'],
  Completion: ['SUCCEEDED', 'CANCELLED'],
}

function summary(details: Record<string, unknown>): string {
  return Object.entries(details)
    .filter(([key]) => key !== 'workerId')
    .map(([key, value]) => `${key} ${typeof value === 'string' ? value : JSON.stringify(value)}`)
    .join(', ')
}

/** The live job event stream, newest first. Each event links to its job; the job links to its trace. */
export function EventsPage() {
  const { events, state } = useLiveEvents()
  const [group, setGroup] = useState('All')
  const types = GROUPS[group] ?? []
  const shown = types.length === 0 ? events : events.filter((event) => types.includes(event.type))

  return (
    <>
      <h1>Events</h1>
      <p className="lede">
        Every job event as it is committed, streamed from the control plane. After a dropped connection the stream
        resumes where it left off. This page keeps the latest {MAX_LIVE_EVENTS} events; each job keeps its full history.
      </p>
      <div className="filters">
        {Object.keys(GROUPS).map((name) => (
          <label key={name} className="radio">
            <input type="radio" name="group" checked={group === name} onChange={() => { setGroup(name) }} />
            {name}
          </label>
        ))}
      </div>
      <Panel>
        {shown.length === 0 ? (
          <Empty>
            {state.kind === 'open'
              ? 'Waiting for events. They appear here the moment a job moves.'
              : 'Not connected to the event stream yet.'}
          </Empty>
        ) : (
          <table className="data-table data-table--compact">
            <thead>
              <tr>
                <th scope="col">Time</th>
                <th scope="col">Job</th>
                <th scope="col">Event</th>
                <th scope="col">Worker</th>
                <th scope="col">Details</th>
              </tr>
            </thead>
            <tbody>
              {shown.map((event) => (
                <tr key={event.id}>
                  <td className="num">{formatTime(event.occurredAt)}</td>
                  <td>
                    <Link to={`/jobs/${event.jobId}`}>
                      <code title={event.jobId}>{shortId(event.jobId)}</code>
                    </Link>
                  </td>
                  <td>{humanize(event.type)}</td>
                  <td>
                    {typeof event.details['workerId'] === 'string' ? (
                      <code>{shortId(event.details['workerId'])}</code>
                    ) : (
                      <span className="muted">–</span>
                    )}
                  </td>
                  <td className="truncate muted" title={summary(event.details)}>
                    {summary(event.details)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Panel>
    </>
  )
}
