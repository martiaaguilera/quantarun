import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { api, keys } from '../api/endpoints'
import { useLiveEvents, useSession } from '../app/context'
import { StackedBar } from '../components/charts'
import { Empty, Meter, Panel, QueryView } from '../components/ui'
import { ControlPlaneStatus } from '../features/system/ControlPlaneStatus'
import { formatMs, formatPercent, formatTime, humanize, shortId } from '../format'

function Figure({ value, label, hint }: { value: string; label: string; hint?: string }) {
  return (
    <div className="figure" title={hint}>
      <span className="figure__value">{value}</span>
      <span className="figure__label">{label}</span>
    </div>
  )
}

export function OverviewPage() {
  const { session } = useSession()
  const overview = useQuery({ queryKey: keys.overview, queryFn: ({ signal }) => api.overview(signal) })
  const { events } = useLiveEvents()

  return (
    <>
      <h1>Overview</h1>
      <QueryView query={overview}>
        {(data) => (
          <>
            <div className="figures">
              <Figure value={data.jobs.queued.toLocaleString()} label="Waiting" hint="Queued or waiting to retry" />
              <Figure value={data.jobs.running.toLocaleString()} label="Placed or running" />
              <Figure
                value={formatPercent(data.jobs.successRate, 1)}
                label="Success, last hour"
                hint="Succeeded out of every job that finished in the last hour"
              />
              <Figure value={data.jobs.retriesLastHour.toLocaleString()} label="Retries, last hour" />
              <Figure value={data.jobs.deadLastHour.toLocaleString()} label="Dead, last hour" />
              <Figure
                value={
                  data.jobs.timeToStartP95Seconds === null ? '–' : formatMs(data.jobs.timeToStartP95Seconds * 1000)
                }
                label="Time to start, p95"
                hint="From submission to a worker starting it, first attempts started in the last 15 minutes"
              />
              {data.fleet && (
                <Figure
                  value={String(data.fleet.healthyWorkers)}
                  label="Healthy workers"
                  hint={`${String(data.fleet.lateWorkers)} late, ${String(data.fleet.drainingWorkers)} draining`}
                />
              )}
            </div>

            <div className="grid grid--2">
              <Panel title="Unfinished jobs">
                <StackedBar
                  ariaLabel="Unfinished jobs by state"
                  segments={[
                    { key: 'queued', label: 'Queued', value: data.jobs.byStatus.QUEUED },
                    { key: 'retry', label: 'Waiting to retry', value: data.jobs.byStatus.RETRY_WAIT },
                    { key: 'scheduled', label: 'Placed', value: data.jobs.byStatus.SCHEDULED },
                    { key: 'running', label: 'Running', value: data.jobs.byStatus.RUNNING },
                  ]}
                />
                <p className="muted small">
                  Finished in the last hour: {data.jobs.succeededLastHour.toLocaleString()} succeeded,{' '}
                  {data.jobs.failedLastHour.toLocaleString()} failed, {data.jobs.deadLastHour.toLocaleString()} dead,{' '}
                  {data.jobs.cancelledLastHour.toLocaleString()} cancelled.
                </p>
              </Panel>
              {data.fleet ? (
                <Panel title="Fleet reservations" actions={<Link to="/workers">Workers</Link>}>
                  <Meter label="Slots" used={data.fleet.reserved.slots} total={data.fleet.capacity.slots} />
                  <Meter label="CPU" used={data.fleet.reserved.cpuMillis} total={data.fleet.capacity.cpuMillis} unit="m" />
                  <Meter
                    label="Memory"
                    used={data.fleet.reserved.memoryMib}
                    total={data.fleet.capacity.memoryMib}
                    unit="MiB"
                  />
                  <Meter
                    label="Accelerators"
                    used={data.fleet.reserved.accelerators}
                    total={data.fleet.capacity.accelerators}
                  />
                </Panel>
              ) : (
                <Panel title="Fleet">
                  <Empty>The fleet is shared by every project; sign in with the operator token to see it.</Empty>
                </Panel>
              )}
            </div>
          </>
        )}
      </QueryView>

      <div className="grid grid--2">
        <Panel title="Latest activity" actions={<Link to="/events">All events</Link>}>
          {events.length === 0 ? (
            <Empty>Nothing has happened since this page opened. Submitted jobs appear here as they move.</Empty>
          ) : (
            <table className="data-table data-table--compact">
              <tbody>
                {events.slice(0, 12).map((event) => (
                  <tr key={event.id}>
                    <td className="num muted">{formatTime(event.occurredAt)}</td>
                    <td>
                      <Link to={`/jobs/${event.jobId}`}>
                        <code title={event.jobId}>{shortId(event.jobId)}</code>
                      </Link>
                    </td>
                    <td>{humanize(event.type)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Panel>
        {session?.role === 'OPERATOR' && <ControlPlaneStatus />}
      </div>
    </>
  )
}
