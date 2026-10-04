import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { api, keys } from '../api/endpoints'
import { FINAL_STATUSES, type Decision } from '../api/types'
import { useSession } from '../app/context'
import { Empty, Id, Panel, QueryView, StatusChip } from '../components/ui'
import { errorMessage } from '../components/errors'
import { buildLifeline, buildStory, workerLabel } from '../features/job/story'
import { Lifeline } from '../features/job/Lifeline'
import { traceUrl } from '../features/settings/traceViewer'
import { between, formatDateTime, formatMs, formatTime, humanize } from '../format'

function useJobData(jobId: string) {
  const base = keys.job(jobId)
  return {
    job: useQuery({ queryKey: [...base, 'job'], queryFn: ({ signal }) => api.job(jobId, signal) }),
    events: useQuery({ queryKey: [...base, 'events'], queryFn: ({ signal }) => api.jobEvents(jobId, signal) }),
    attempts: useQuery({ queryKey: [...base, 'attempts'], queryFn: ({ signal }) => api.jobAttempts(jobId, signal) }),
    decisions: useQuery({ queryKey: [...base, 'decisions'], queryFn: ({ signal }) => api.jobDecisions(jobId, signal) }),
    checkpoints: useQuery({ queryKey: [...base, 'checkpoints'], queryFn: ({ signal }) => api.jobCheckpoints(jobId, signal) }),
  }
}

function Candidates({ decision }: { decision: Decision }) {
  return (
    <table className="data-table data-table--compact">
      <thead>
        <tr>
          <th scope="col">Worker</th>
          <th scope="col">Verdict</th>
          <th scope="col">Why</th>
          <th scope="col" className="num" title="Lower wins">
            Score
          </th>
        </tr>
      </thead>
      <tbody>
        {decision.candidates.map((candidate) => (
          <tr key={candidate.workerId}>
            <td>{candidate.workerName}</td>
            <td>
              <StatusChip value={candidate.verdict} />
            </td>
            <td>{candidate.detail}</td>
            <td className="num">{candidate.score === null ? '–' : candidate.score.toFixed(3)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

export function JobDetailPage() {
  const { jobId = '' } = useParams()
  const { session } = useSession()
  const queryClient = useQueryClient()
  const data = useJobData(jobId)
  const workers = useQuery({
    queryKey: keys.workers,
    queryFn: ({ signal }) => api.workers(signal),
    enabled: session?.role === 'OPERATOR',
  })
  const [actionError, setActionError] = useState<string | null>(null)
  const refresh = () => queryClient.invalidateQueries({ queryKey: keys.job(jobId) })
  const cancel = useMutation({
    mutationFn: () => api.cancelJob(jobId),
    onSuccess: refresh,
    onError: (error) => { setActionError(errorMessage(error)) },
  })
  const revive = useMutation({
    mutationFn: () => api.reviveJob(jobId),
    onSuccess: refresh,
    onError: (error) => { setActionError(errorMessage(error)) },
  })

  const names = new Map((workers.data ?? []).map((worker) => [worker.id, worker.name]))
  const workerName = workerLabel(names)

  return (
    <QueryView query={data.job} loadingLabel="Loading job">
      {(job) => {
        const final = FINAL_STATUSES.includes(job.status)
        const trace = job.traceId ? traceUrl(job.traceId) : null
        const attempts = data.attempts.data ?? []
        const latestDecision = (data.decisions.data ?? []).at(-1)
        const succeeded = attempts.find((attempt) => attempt.status === 'SUCCEEDED')
        return (
          <>
            <div className="page-header">
              <div>
                <p className="breadcrumb">
                  <Link to="/jobs">Jobs</Link>
                </p>
                <h1>
                  Job <code>{job.id}</code>
                </h1>
                <p className="page-header__facts">
                  <StatusChip value={job.status} /> {job.workloadType}, priority {job.priority}, attempt{' '}
                  {job.attemptsInBudget} of {job.maxAttempts}
                  {job.reviveCount > 0 ? `, revived ${String(job.reviveCount)} times` : ''}
                </p>
              </div>
              <div className="page-header__actions">
                {!final && (
                  <button type="button" className="button" disabled={cancel.isPending} onClick={() => { cancel.mutate() }}>
                    Cancel job
                  </button>
                )}
                {job.status === 'DEAD' && (
                  <button type="button" className="button button--primary" disabled={revive.isPending} onClick={() => { revive.mutate() }}>
                    Revive job
                  </button>
                )}
              </div>
            </div>
            {actionError && (
              <p className="notice notice--error" role="alert">
                {actionError}
              </p>
            )}

            <Panel title="Lifeline">
              {data.attempts.isSuccess && data.checkpoints.isSuccess ? (
                <Lifeline data={buildLifeline(job, attempts, data.checkpoints.data, data.attempts.dataUpdatedAt, workerName)} />
              ) : (
                <p className="loading">Loading attempts…</p>
              )}
            </Panel>

            <div className="grid grid--detail">
              <Panel title="What happened">
                <QueryView query={data.events}>
                  {(events) => {
                    const rows = buildStory(events, data.decisions.data ?? [], workerName)
                    const start = Date.parse(job.createdAt)
                    return (
                      <ol className="story">
                        {rows.map((row) => (
                          <li key={row.key} className={`story__row tone--${row.tone}`}>
                            <span className="story__time num" title={formatDateTime(row.at)}>
                              +{formatMs(Date.parse(row.at) - start)}
                            </span>
                            <span className="story__title">
                              {row.title}
                              {row.attemptNo !== undefined && <span className="muted"> · attempt {row.attemptNo}</span>}
                            </span>
                            {row.detail && <span className="story__detail">{row.detail}</span>}
                            {row.until && (
                              <span className="story__detail muted">
                                until {formatTime(row.until)}
                              </span>
                            )}
                          </li>
                        ))}
                      </ol>
                    )
                  }}
                </QueryView>
              </Panel>

              <Panel title="Facts">
                <dl className="facts">
                  <dt>Project</dt>
                  <dd>
                    <Id value={job.projectId} />
                  </dd>
                  <dt>Submitted</dt>
                  <dd>{formatDateTime(job.createdAt)}</dd>
                  <dt>Finished</dt>
                  <dd>{formatDateTime(job.finishedAt)}</dd>
                  {job.deadline && (
                    <>
                      <dt>Deadline</dt>
                      <dd>{formatDateTime(job.deadline)}</dd>
                    </>
                  )}
                  <dt>Requests</dt>
                  <dd>
                    {job.resources.cpuMillis} m CPU, {job.resources.memoryMib} MiB
                    {job.resources.accelerators > 0 ? `, ${String(job.resources.accelerators)} accelerators` : ''}
                  </dd>
                  <dt>Labels</dt>
                  <dd>{job.requiredLabels.length === 0 ? 'none required' : job.requiredLabels.join(', ')}</dd>
                  <dt>Timeout</dt>
                  <dd>{job.timeoutSeconds} s per attempt</dd>
                  <dt>Trace</dt>
                  <dd>
                    {job.traceId ? (
                      <>
                        <code>{job.traceId}</code>
                        {trace && (
                          <>
                            {' '}
                            <a href={trace} target="_blank" rel="noreferrer">
                              Open trace
                            </a>
                          </>
                        )}
                      </>
                    ) : (
                      <span className="muted">not recorded</span>
                    )}
                  </dd>
                  {job.idempotencyKey && (
                    <>
                      <dt>Idempotency key</dt>
                      <dd>
                        <code>{job.idempotencyKey}</code>
                      </dd>
                    </>
                  )}
                </dl>
                <details>
                  <summary>Payload</summary>
                  <pre className="code-block">{JSON.stringify(job.payload, null, 2)}</pre>
                </details>
                {succeeded?.result !== undefined && succeeded.result !== null && (
                  <details>
                    <summary>Result</summary>
                    <pre className="code-block">{JSON.stringify(succeeded.result, null, 2)}</pre>
                  </details>
                )}
              </Panel>
            </div>

            <Panel title="Attempts">
              <QueryView query={data.attempts}>
                {(rows) =>
                  rows.length === 0 ? (
                    <Empty>No attempt yet: the job has not been placed on a worker.</Empty>
                  ) : (
                    <table className="data-table">
                      <thead>
                        <tr>
                          <th scope="col" className="num">#</th>
                          <th scope="col">Worker</th>
                          <th scope="col">Outcome</th>
                          <th scope="col">Assigned</th>
                          <th scope="col" className="num">Claimed after</th>
                          <th scope="col" className="num">Ran for</th>
                          <th scope="col" className="num">Lease renewals</th>
                          <th scope="col">Failure</th>
                          <th scope="col">Retry decision</th>
                        </tr>
                      </thead>
                      <tbody>
                        {rows.map((attempt) => (
                          <tr key={attempt.id}>
                            <td className="num">{attempt.attemptNo}</td>
                            <td>{workerName(attempt.workerId)}</td>
                            <td>
                              <StatusChip value={attempt.status} />
                            </td>
                            <td>{formatTime(attempt.assignedAt)}</td>
                            <td className="num">{formatMs(between(attempt.assignedAt, attempt.startedAt))}</td>
                            <td className="num">{formatMs(between(attempt.startedAt, attempt.finishedAt))}</td>
                            <td className="num">{attempt.leaseRenewals}</td>
                            <td>
                              {attempt.failureClass ? (
                                <>
                                  {humanize(attempt.failureClass)}
                                  {attempt.failureMessage && <span className="muted">: {attempt.failureMessage}</span>}
                                </>
                              ) : (
                                <span className="muted">–</span>
                              )}
                            </td>
                            <td>{attempt.retryDecision ?? <span className="muted">–</span>}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  )
                }
              </QueryView>
            </Panel>

            <div className="grid grid--2">
              <Panel title="Latest scheduling decision">
                {latestDecision ? (
                  <>
                    <p>
                      <StatusChip value={latestDecision.outcome} /> by {latestDecision.policy} at{' '}
                      {formatTime(latestDecision.decidedAt)}, after {formatMs(latestDecision.queueWaitMs)} in the queue.
                    </p>
                    <p className="muted">{latestDecision.reason}</p>
                    <Candidates decision={latestDecision} />
                  </>
                ) : (
                  <Empty>The scheduler has not evaluated this job yet.</Empty>
                )}
              </Panel>
              <Panel title="Checkpoints">
                <QueryView query={data.checkpoints}>
                  {(checkpoints) =>
                    checkpoints.length === 0 ? (
                      <Empty>
                        {job.workloadType === 'staged'
                          ? 'No stage has committed yet.'
                          : 'Only staged workloads commit checkpoints.'}
                      </Empty>
                    ) : (
                      <table className="data-table data-table--compact">
                        <thead>
                          <tr>
                            <th scope="col" className="num">Stage</th>
                            <th scope="col">Committed</th>
                            <th scope="col">By attempt</th>
                          </tr>
                        </thead>
                        <tbody>
                          {checkpoints.map((checkpoint) => (
                            <tr key={checkpoint.stageIndex}>
                              <td className="num">{checkpoint.stageIndex}</td>
                              <td>{formatTime(checkpoint.committedAt)}</td>
                              <td>{attempts.find((attempt) => attempt.id === checkpoint.attemptId)?.attemptNo ?? '–'}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    )
                  }
                </QueryView>
              </Panel>
            </div>
          </>
        )
      }}
    </QueryView>
  )
}
