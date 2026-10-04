import { useInfiniteQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import { api, keys } from '../api/endpoints'
import { Empty, ErrorNotice, Loading, Panel } from '../components/ui'
import { errorMessage } from '../components/errors'
import { formatAgo, formatDateTime, shortId } from '../format'

const FILTER = { status: 'DEAD' as const }

/** Jobs that used up their attempt budget. Each can be inspected, then revived with a fresh budget. */
export function DeadJobsPage() {
  const queryClient = useQueryClient()
  const jobs = useInfiniteQuery({
    queryKey: keys.jobs(FILTER),
    queryFn: ({ pageParam, signal }) => api.jobs(FILTER, pageParam, 50, signal),
    initialPageParam: null as string | null,
    getNextPageParam: (last) => last.nextBefore,
  })
  const [error, setError] = useState<string | null>(null)
  const revive = useMutation({
    mutationFn: (id: string) => api.reviveJob(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: keys.jobsAll }),
    onError: (failure) => { setError(errorMessage(failure)) },
  })
  const rows = jobs.data?.pages.flatMap((page) => page.items) ?? []

  return (
    <>
      <h1>Dead jobs</h1>
      <p className="lede">
        A job is dead when every attempt in its budget failed with a retryable error. Reviving one grants a fresh
        budget; its earlier attempts stay in its history.
      </p>
      {error && (
        <p className="notice notice--error" role="alert">
          {error}
        </p>
      )}
      <Panel>
        {jobs.isPending ? (
          <Loading label="Loading dead jobs" />
        ) : jobs.isError ? (
          <ErrorNotice error={jobs.error} retry={() => void jobs.refetch()} />
        ) : rows.length === 0 ? (
          <Empty>No dead jobs. Jobs land here when every attempt in their budget fails.</Empty>
        ) : (
          <table className="data-table">
            <thead>
              <tr>
                <th scope="col">Job</th>
                <th scope="col">Workload</th>
                <th scope="col" className="num">Attempts</th>
                <th scope="col">Died</th>
                <th scope="col" className="num">Revived before</th>
                <th scope="col" />
              </tr>
            </thead>
            <tbody>
              {rows.map((job) => (
                <tr key={job.id}>
                  <td>
                    <Link to={`/jobs/${job.id}`}>
                      <code title={job.id}>{shortId(job.id)}</code>
                    </Link>
                  </td>
                  <td>{job.workloadType}</td>
                  <td className="num">{job.attemptCount}</td>
                  <td title={formatDateTime(job.finishedAt)}>{formatAgo(job.finishedAt)}</td>
                  <td className="num">{job.reviveCount}</td>
                  <td className="actions">
                    <button
                      type="button"
                      className="button"
                      disabled={revive.isPending && revive.variables === job.id}
                      onClick={() => { revive.mutate(job.id) }}
                    >
                      Revive
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {jobs.hasNextPage && (
          <button type="button" className="button" onClick={() => void jobs.fetchNextPage()}>
            Load older dead jobs
          </button>
        )}
      </Panel>
    </>
  )
}
