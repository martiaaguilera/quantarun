import { useQuery } from '@tanstack/react-query'
import { Fragment, useState } from 'react'
import { Link } from 'react-router'
import { api, keys } from '../api/endpoints'
import type { Decision } from '../api/types'
import { useSession } from '../app/context'
import { Empty, Panel, QueryView, StatusChip } from '../components/ui'
import { formatIsoDuration, formatMs, formatTime, shortId } from '../format'

function DecisionRows({ decisions }: { decisions: Decision[] }) {
  const [open, setOpen] = useState<number | null>(null)
  return (
    <table className="data-table data-table--compact">
      <thead>
        <tr>
          <th scope="col">Time</th>
          <th scope="col">Job</th>
          <th scope="col">Outcome</th>
          <th scope="col">Worker</th>
          <th scope="col" className="num">Waited</th>
          <th scope="col">Reason</th>
          <th scope="col" />
        </tr>
      </thead>
      <tbody>
        {decisions.map((decision) => {
          const chosen = decision.candidates.find((candidate) => candidate.verdict === 'CHOSEN')
          return (
            <Fragment key={decision.id}>
              <tr>
                <td className="num">{formatTime(decision.decidedAt)}</td>
                <td>
                  <Link to={`/jobs/${decision.jobId}`}>
                    <code title={decision.jobId}>{shortId(decision.jobId)}</code>
                  </Link>
                </td>
                <td>
                  <StatusChip value={decision.outcome} />
                </td>
                <td>{chosen?.workerName ?? <span className="muted">–</span>}</td>
                <td className="num">{formatMs(decision.queueWaitMs)}</td>
                <td className="truncate" title={decision.reason}>
                  {decision.reason}
                </td>
                <td>
                  <button
                    type="button"
                    className="link-button"
                    aria-expanded={open === decision.id}
                    onClick={() => { setOpen(open === decision.id ? null : decision.id) }}
                  >
                    {open === decision.id ? 'Hide' : `${String(decision.candidates.length)} candidates`}
                  </button>
                </td>
              </tr>
              {open === decision.id && (
                <tr className="expanded">
                  <td colSpan={7}>
                    <ul className="verdicts">
                      {decision.candidates.map((candidate) => (
                        <li key={candidate.workerId}>
                          <StatusChip value={candidate.verdict} /> <strong>{candidate.workerName}</strong>{' '}
                          <span className="muted">{candidate.detail}</span>
                        </li>
                      ))}
                    </ul>
                  </td>
                </tr>
              )}
            </Fragment>
          )
        })}
      </tbody>
    </table>
  )
}

export function SchedulerPage() {
  const { session } = useSession()
  const operator = session?.role === 'OPERATOR'
  const status = useQuery({ queryKey: keys.scheduler, queryFn: ({ signal }) => api.scheduler(signal) })
  const decisions = useQuery({
    queryKey: keys.decisions,
    queryFn: ({ signal }) => api.decisions(100, signal),
    enabled: operator,
    refetchInterval: 5_000,
  })
  const fairness = useQuery({
    queryKey: keys.fairness,
    queryFn: ({ signal }) => api.fairness(signal),
    enabled: operator,
    refetchInterval: 10_000,
  })
  const [onlyWaiting, setOnlyWaiting] = useState(false)

  return (
    <>
      <h1>Scheduler</h1>
      <QueryView query={status}>
        {(scheduler) => (
          <p className="lede">
            Placing with <strong>{scheduler.policy}</strong>, {scheduler.loops} loop{scheduler.loops === 1 ? '' : 's'},{' '}
            {scheduler.windowSize} jobs per cycle, {formatIsoDuration(scheduler.idleDelay)} between idle cycles.
            {scheduler.enabled ? '' : ' The background loop is off in this deployment.'} Policies are set at start-up;
            compare them on your own workload in the <Link to="/policy-lab">policy lab</Link>.
          </p>
        )}
      </QueryView>
      {!operator ? (
        <Panel>
          <Empty>Decisions span every project, so they are for the operator. Each of your jobs shows its own.</Empty>
        </Panel>
      ) : (
        <>
          <Panel
            title="Recent decisions"
            actions={
              <label className="toggle">
                <input type="checkbox" checked={onlyWaiting} onChange={(event) => { setOnlyWaiting(event.target.checked) }} />
                Only jobs that could not be placed
              </label>
            }
          >
            <QueryView query={decisions}>
              {(rows) => {
                const shown = onlyWaiting ? rows.filter((decision) => decision.outcome !== 'PLACED') : rows
                return shown.length === 0 ? (
                  <Empty>
                    {onlyWaiting ? 'Every recent decision placed its job.' : 'No decisions yet: nothing has been submitted.'}
                  </Empty>
                ) : (
                  <DecisionRows decisions={shown} />
                )
              }}
            </QueryView>
          </Panel>
          <Panel title="Fair share">
            <QueryView query={fairness}>
              {(view) =>
                view.projects.length === 0 ? (
                  <Empty>No project has received service under FAIR_SHARE yet.</Empty>
                ) : (
                  <>
                    <p className="muted small">
                      Virtual time is service received per unit of weight. The backlogged project with the lowest goes
                      next; a project returning from idle starts at the floor ({view.systemVirtualTime.toFixed(1)}).
                    </p>
                    <table className="data-table data-table--compact">
                      <thead>
                        <tr>
                          <th scope="col">Project</th>
                          <th scope="col" className="num">Weight</th>
                          <th scope="col" className="num">Virtual time</th>
                          <th scope="col">Quotas (queued / running / accelerators)</th>
                        </tr>
                      </thead>
                      <tbody>
                        {view.projects.map((project) => (
                          <tr key={project.projectId}>
                            <td>{project.name}</td>
                            <td className="num">{project.weight}</td>
                            <td className="num">{project.virtualTime.toFixed(1)}</td>
                            <td>
                              {[project.maxQueuedJobs, project.maxRunningJobs, project.maxAccelerators]
                                .map((limit) => (limit === null ? 'none' : String(limit)))
                                .join(' / ')}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </>
                )
              }
            </QueryView>
          </Panel>
        </>
      )}
    </>
  )
}
