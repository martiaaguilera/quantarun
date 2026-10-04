import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import { Link, useSearchParams } from 'react-router'
import { api, keys, type JobFilter } from '../api/endpoints'
import { JOB_STATUSES, WORKLOAD_TYPES, type Job, type JobStatus } from '../api/types'
import { useSession } from '../app/context'
import { Empty, ErrorNotice, Loading, Panel, StatusChip } from '../components/ui'
import { formatAgo, formatDateTime, humanize, shortId } from '../format'

const PAGE_SIZE = 50

const TIME_RANGES: Record<string, { label: string; ms: number | null }> = {
  any: { label: 'Any time', ms: null },
  '15m': { label: 'Last 15 minutes', ms: 15 * 60_000 },
  '1h': { label: 'Last hour', ms: 60 * 60_000 },
  '24h': { label: 'Last 24 hours', ms: 24 * 60 * 60_000 },
}

function filterFrom(params: URLSearchParams): JobFilter & { range: string } {
  const range = params.get('range') ?? 'any'
  const ms = TIME_RANGES[range]?.ms ?? null
  const priority = params.get('priority')
  return {
    status: (params.get('status') as JobStatus | null) ?? undefined,
    workloadType: params.get('workloadType') ?? undefined,
    priority: priority === null || priority === '' ? undefined : Number(priority),
    workerId: params.get('workerId') ?? undefined,
    // Rounded to the minute, so the query key stays stable while the page is open.
    createdFrom: ms === null ? undefined : new Date(Math.floor((Date.now() - ms) / 60_000) * 60_000).toISOString(),
    range,
  }
}

export function JobTable({ jobs }: { jobs: Job[] }) {
  return (
    <table className="data-table">
      <thead>
        <tr>
          <th scope="col">Job</th>
          <th scope="col">State</th>
          <th scope="col">Workload</th>
          <th scope="col" className="num">
            Priority
          </th>
          <th scope="col" className="num">
            Attempts
          </th>
          <th scope="col">Submitted</th>
          <th scope="col">Latest scheduling verdict</th>
        </tr>
      </thead>
      <tbody>
        {jobs.map((job) => (
          <tr key={job.id}>
            <td>
              <Link to={`/jobs/${job.id}`}>
                <code title={job.id}>{shortId(job.id)}</code>
              </Link>
            </td>
            <td>
              <StatusChip value={job.status} />
            </td>
            <td>{job.workloadType}</td>
            <td className="num">{job.priority}</td>
            <td className="num">{`${String(job.attemptsInBudget)} / ${String(job.maxAttempts)}`}</td>
            <td title={formatDateTime(job.createdAt)}>{formatAgo(job.createdAt)}</td>
            <td className="truncate" title={job.schedulingReason ?? undefined}>
              {job.schedulingReason ?? <span className="muted">–</span>}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

export function JobsPage() {
  const [params, setParams] = useSearchParams()
  const { session } = useSession()
  const { range, ...filter } = filterFrom(params)
  const workers = useQuery({
    queryKey: keys.workers,
    queryFn: ({ signal }) => api.workers(signal),
    enabled: session?.role === 'OPERATOR',
  })
  const jobs = useInfiniteQuery({
    queryKey: keys.jobs(filter),
    queryFn: ({ pageParam, signal }) => api.jobs(filter, pageParam, PAGE_SIZE, signal),
    initialPageParam: null as string | null,
    getNextPageParam: (last) => last.nextBefore,
  })

  const set = (key: string, value: string) => {
    const next = new URLSearchParams(params)
    if (value === '' || value === 'any') {
      next.delete(key)
    } else {
      next.set(key, value)
    }
    setParams(next, { replace: true })
  }

  const filtered = [...params.keys()].length > 0
  const rows = jobs.data?.pages.flatMap((page) => page.items) ?? []

  return (
    <>
      <h1>Jobs</h1>
      <div className="filters" role="search">
        <label>
          State
          <select value={filter.status ?? ''} onChange={(event) => { set('status', event.target.value) }}>
            <option value="">Any</option>
            {JOB_STATUSES.map((status) => (
              <option key={status} value={status}>
                {humanize(status)}
              </option>
            ))}
          </select>
        </label>
        <label>
          Workload
          <select value={filter.workloadType ?? ''} onChange={(event) => { set('workloadType', event.target.value) }}>
            <option value="">Any</option>
            {WORKLOAD_TYPES.map((type) => (
              <option key={type} value={type}>
                {type}
              </option>
            ))}
          </select>
        </label>
        <label>
          Priority
          <select
            value={filter.priority === undefined ? '' : String(filter.priority)}
            onChange={(event) => { set('priority', event.target.value) }}
          >
            <option value="">Any</option>
            {Array.from({ length: 10 }, (_, priority) => (
              <option key={priority} value={priority}>
                {priority}
              </option>
            ))}
          </select>
        </label>
        {session?.role === 'OPERATOR' && (
          <label>
            Ran on
            <select value={filter.workerId ?? ''} onChange={(event) => { set('workerId', event.target.value) }}>
              <option value="">Any worker</option>
              {(workers.data ?? []).map((worker) => (
                <option key={worker.id} value={worker.id}>
                  {worker.name} ({shortId(worker.id)})
                </option>
              ))}
            </select>
          </label>
        )}
        <label>
          Submitted
          <select value={range} onChange={(event) => { set('range', event.target.value) }}>
            {Object.entries(TIME_RANGES).map(([key, option]) => (
              <option key={key} value={key}>
                {option.label}
              </option>
            ))}
          </select>
        </label>
        {filtered && (
          <button type="button" className="link-button" onClick={() => { setParams(new URLSearchParams(), { replace: true }) }}>
            Clear filters
          </button>
        )}
      </div>
      <Panel>
        {jobs.isPending ? (
          <Loading label="Loading jobs" />
        ) : jobs.isError ? (
          <ErrorNotice error={jobs.error} retry={() => void jobs.refetch()} />
        ) : rows.length === 0 ? (
          <Empty>
            {filtered
              ? 'No job matches these filters. Clear them to see every job.'
              : 'No jobs yet. Submit one with POST /api/v1/jobs and it appears here as it moves.'}
          </Empty>
        ) : (
          <>
            <JobTable jobs={rows} />
            {jobs.hasNextPage && (
              <button
                type="button"
                className="button"
                disabled={jobs.isFetchingNextPage}
                onClick={() => void jobs.fetchNextPage()}
              >
                {jobs.isFetchingNextPage ? 'Loading…' : 'Load older jobs'}
              </button>
            )}
          </>
        )}
      </Panel>
    </>
  )
}
