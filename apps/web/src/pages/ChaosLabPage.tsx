import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type SyntheticEvent } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { api, keys } from '../api/endpoints'
import type { ChaosFault, FaultDefinition, FaultParameter } from '../api/types'
import { useSession } from '../app/context'
import { Empty, Id, Panel, QueryView, StatusChip } from '../components/ui'
import { errorMessage } from '../components/errors'
import { formatAgo, formatMs, formatTime, humanize, shortId } from '../format'

const PARAMETER_FIELDS: Record<FaultParameter, { field: string; label: string; unit: string }> = {
  DELAY_MS: { field: 'delayMs', label: 'Delay before it fires', unit: 'ms' },
  DURATION_MS: { field: 'durationMs', label: 'Lasts', unit: 'ms' },
  COUNT: { field: 'count', label: 'Affects the next', unit: 'calls or attempts' },
  RETRY_AFTER_MS: { field: 'retryAfterMs', label: 'Retry-After sent', unit: 'ms' },
  LATENCY_MS: { field: 'latencyMs', label: 'Added latency', unit: 'ms' },
}

function NewExperiment({ faults }: { faults: FaultDefinition[] }) {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const workers = useQuery({ queryKey: keys.workers, queryFn: ({ signal }) => api.workers(signal) })
  const [fault, setFault] = useState<ChaosFault>(faults[0]?.fault ?? 'KILL_WORKER')
  const [target, setTarget] = useState<'worker' | 'job'>('worker')
  const [workerId, setWorkerId] = useState('')
  const [jobId, setJobId] = useState('')
  const [values, setValues] = useState<Partial<Record<FaultParameter, number>>>({})
  const definition = faults.find((candidate) => candidate.fault === fault)
  const create = useMutation({
    mutationFn: api.createExperiment,
    onSuccess: (experiment) => {
      void queryClient.invalidateQueries({ queryKey: keys.experiments })
      void navigate(`/chaos/${experiment.id}`)
    },
  })
  const live = (workers.data ?? []).filter((worker) => worker.lifecycle === 'ACTIVE' || worker.lifecycle === 'DRAINING')

  const submit = (event: SyntheticEvent) => {
    event.preventDefault()
    const parameters: Record<string, number> = {}
    for (const parameter of definition?.parameters ?? []) {
      const value = values[parameter.parameter]
      if (value !== undefined) {
        parameters[PARAMETER_FIELDS[parameter.parameter].field] = value
      }
    }
    create.mutate({ fault, ...(target === 'worker' ? { workerId } : { jobId: jobId.trim() }), ...parameters })
  }

  return (
    <form className="form-grid" onSubmit={submit}>
      <label>
        Fault
        <select
          value={fault}
          onChange={(event) => {
            setFault(event.target.value as ChaosFault)
            setValues({})
          }}
        >
          {faults.map((option) => (
            <option key={option.fault} value={option.fault}>
              {humanize(option.fault)}
            </option>
          ))}
        </select>
        <span className="hint">{definition?.description}</span>
      </label>
      <fieldset>
        <legend>Aim at</legend>
        <label className="radio">
          <input type="radio" checked={target === 'worker'} onChange={() => { setTarget('worker') }} /> A worker
        </label>
        <label className="radio">
          <input type="radio" checked={target === 'job'} onChange={() => { setTarget('job') }} /> The worker running a job
        </label>
        {target === 'worker' ? (
          <select value={workerId} onChange={(event) => { setWorkerId(event.target.value) }} required aria-label="Worker">
            <option value="" disabled>
              Choose a live worker
            </option>
            {live.map((worker) => (
              <option key={worker.id} value={worker.id}>
                {worker.name} ({shortId(worker.id)})
              </option>
            ))}
          </select>
        ) : (
          <input
            aria-label="Job id"
            placeholder="Job id"
            value={jobId}
            onChange={(event) => { setJobId(event.target.value) }}
            required
            pattern="[0-9a-fA-F-]{36}"
          />
        )}
      </fieldset>
      {definition?.parameters.map((parameter) => {
        const field = PARAMETER_FIELDS[parameter.parameter]
        return (
          <label key={parameter.parameter}>
            {field.label}
            <input
              type="number"
              min={parameter.min}
              max={parameter.max}
              placeholder={String(parameter.defaultValue)}
              value={values[parameter.parameter] ?? ''}
              onChange={(event) => {
                const raw = event.target.value
                setValues((current) => {
                  const rest = Object.fromEntries(
                    Object.entries(current).filter(([key]) => key !== parameter.parameter),
                  ) as Partial<Record<FaultParameter, number>>
                  return raw === '' ? rest : { ...rest, [parameter.parameter]: Number(raw) }
                })
              }}
            />
            <span className="hint">
              {field.unit}, {parameter.min.toLocaleString()}–{parameter.max.toLocaleString()}; default{' '}
              {parameter.defaultValue.toLocaleString()}
            </span>
          </label>
        )
      })}
      <div className="form-actions">
        <button type="submit" className="button button--danger" disabled={create.isPending}>
          {create.isPending ? 'Starting…' : 'Inject fault'}
        </button>
        {create.isError && (
          <span className="form-error" role="alert">
            {errorMessage(create.error)}
          </span>
        )}
      </div>
    </form>
  )
}

export function ChaosLabPage() {
  const { session } = useSession()
  const operator = session?.role === 'OPERATOR'
  const catalog = useQuery({ queryKey: keys.chaosCatalog, queryFn: ({ signal }) => api.chaosCatalog(signal), enabled: operator })
  const experiments = useQuery({
    queryKey: keys.experiments,
    queryFn: ({ signal }) => api.experiments(signal),
    enabled: operator,
    refetchInterval: 3_000,
  })

  if (!operator) {
    return (
      <>
        <h1>Chaos lab</h1>
        <Panel>
          <Empty>A fault reaches the shared fleet, so only the operator can inject one.</Empty>
        </Panel>
      </>
    )
  }
  return (
    <>
      <h1>Chaos lab</h1>
      <p className="lede">
        Inject one of the predefined faults into a worker and watch the system detect it and recover. A worker receives the
        fault in its next heartbeat and applies it only if it was started with chaos enabled.
      </p>
      <QueryView query={catalog}>
        {(data) =>
          data.enabled ? (
            <Panel title="New experiment">
              <NewExperiment faults={data.faults} />
            </Panel>
          ) : (
            <Panel>
              <Empty>
                Chaos is off in this deployment. Start the stack with QUANTARUN_CHAOS_ENABLED=true to turn it on for the
                control plane and the workers.
              </Empty>
            </Panel>
          )
        }
      </QueryView>
      <Panel title="Experiments">
        <QueryView query={experiments}>
          {(rows) =>
            rows.length === 0 ? (
              <Empty>No experiments yet.</Empty>
            ) : (
              <table className="data-table data-table--compact">
                <thead>
                  <tr>
                    <th scope="col">Experiment</th>
                    <th scope="col">Fault</th>
                    <th scope="col">State</th>
                    <th scope="col">Worker</th>
                    <th scope="col">Job</th>
                    <th scope="col">Created</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((experiment) => (
                    <tr key={experiment.id}>
                      <td>
                        <Link to={`/chaos/${experiment.id}`}>
                          <code>{shortId(experiment.id)}</code>
                        </Link>
                      </td>
                      <td>{humanize(experiment.fault)}</td>
                      <td>
                        <StatusChip value={experiment.status} />
                      </td>
                      <td>
                        <Id value={experiment.workerId} />
                      </td>
                      <td>
                        <Id value={experiment.jobId} {...(experiment.jobId ? { href: `/jobs/${experiment.jobId}` } : {})} />
                      </td>
                      <td>{formatAgo(experiment.createdAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )
          }
        </QueryView>
      </Panel>
    </>
  )
}

export function ExperimentPage() {
  const { experimentId = '' } = useParams()
  const queryClient = useQueryClient()
  const experiment = useQuery({
    queryKey: keys.experiment(experimentId),
    queryFn: ({ signal }) => api.experiment(experimentId, signal),
    // Recovery unfolds over tens of seconds after delivery; refresh while it is still recent.
    refetchInterval: (query) => {
      const created = query.state.data?.createdAt
      return created && Date.now() - Date.parse(created) < 5 * 60_000 ? 2_000 : false
    },
  })
  const cancel = useMutation({
    mutationFn: () => api.cancelExperiment(experimentId),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: keys.experiment(experimentId) }),
  })

  return (
    <QueryView query={experiment} loadingLabel="Loading experiment">
      {(data) => {
        const timeline = data.timeline
        const delivered = data.deliveredAt ? Date.parse(data.deliveredAt) : null
        return (
          <>
            <p className="breadcrumb">
              <Link to="/chaos">Chaos lab</Link>
            </p>
            <h1>
              {humanize(data.fault)} <StatusChip value={data.status} />
            </h1>
            <p className="lede">
              Aimed at worker <Id value={data.workerId} />
              {data.jobId && (
                <>
                  {' '}
                  running job <Id value={data.jobId} href={`/jobs/${data.jobId}`} />
                </>
              )}
              . Created {formatTime(data.createdAt)}
              {data.deliveredAt ? `, delivered ${formatTime(data.deliveredAt)}` : ''}.
              {timeline?.targetLifecycle && ` The worker is now ${humanize(timeline.targetLifecycle).toLowerCase()}.`}
            </p>
            {data.status === 'PENDING' && (
              <button type="button" className="button" disabled={cancel.isPending} onClick={() => { cancel.mutate() }}>
                Cancel before delivery
              </button>
            )}
            {timeline && (
              <>
                <div className="figures">
                  <div className="figure">
                    <span className="figure__value">{timeline.summary.affectedJobs}</span>
                    <span className="figure__label">Jobs on the worker</span>
                  </div>
                  <div className="figure">
                    <span className="figure__value">{timeline.summary.disruptedJobs}</span>
                    <span className="figure__label">Disrupted</span>
                  </div>
                  <div className="figure">
                    <span className="figure__value">{timeline.summary.recoveredJobs}</span>
                    <span className="figure__label">Recovered</span>
                  </div>
                  <div className="figure">
                    <span className="figure__value">{formatMs(timeline.summary.maxRecoveryMs)}</span>
                    <span className="figure__label">Slowest recovery</span>
                  </div>
                </div>
                <div className="grid grid--detail">
                  <Panel title="Timeline">
                    <ol className="story story--scroll" tabIndex={0} aria-label="Experiment timeline">
                      {timeline.entries.map((entry, index) => (
                        <li key={`${entry.at}-${String(index)}`} className={`story__row source--${entry.source.toLowerCase()}`}>
                          <span className="story__time num">
                            {delivered === null ? formatTime(entry.at) : `${Date.parse(entry.at) >= delivered ? '+' : ''}${formatMs(Date.parse(entry.at) - delivered)}`}
                          </span>
                          <span className="story__title">
                            {humanize(entry.type)}
                            {entry.jobId && entry.source === 'JOB' && (
                              <>
                                {' '}
                                <Link to={`/jobs/${entry.jobId}`}>
                                  <code>{shortId(entry.jobId)}</code>
                                </Link>
                              </>
                            )}
                          </span>
                          {entry.source !== 'JOB' && <span className="story__detail">{entry.detail}</span>}
                          {entry.source === 'JOB' && entry.workerId && (
                            <span className="story__detail muted">worker {shortId(entry.workerId)}</span>
                          )}
                        </li>
                      ))}
                    </ol>
                  </Panel>
                  <Panel title="Recovery per job">
                    {timeline.jobs.length === 0 ? (
                      <Empty>No job was on the worker during the fault's window.</Empty>
                    ) : (
                      <table className="data-table data-table--compact">
                        <thead>
                          <tr>
                            <th scope="col">Job</th>
                            <th scope="col">Now</th>
                            <th scope="col" className="num">Detected after</th>
                            <th scope="col" className="num">Recovered after</th>
                          </tr>
                        </thead>
                        <tbody>
                          {timeline.jobs.map((job) => (
                            <tr key={job.jobId}>
                              <td>
                                <Link to={`/jobs/${job.jobId}`}>
                                  <code>{shortId(job.jobId)}</code>
                                </Link>
                              </td>
                              <td>
                                <StatusChip value={job.status} />
                              </td>
                              <td className="num">{formatMs(job.detectionMs)}</td>
                              <td className="num">{formatMs(job.recoveryMs)}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    )}
                    <p className="muted small">
                      Times run from the moment the fault fired. Every disruption on the worker during the window is
                      counted, including those of other experiments aimed at it at the same time.
                    </p>
                  </Panel>
                </div>
              </>
            )}
          </>
        )
      }}
    </QueryView>
  )
}
