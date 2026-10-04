import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import { api, keys } from '../api/endpoints'
import type { Worker } from '../api/types'
import { Empty, Panel, QueryView, StatusChip } from '../components/ui'
import { errorMessage } from '../components/errors'
import { formatAgo, formatDateTime, shortId } from '../format'

function Usage({ used, total, unit }: { used: number; total: number; unit?: string }) {
  const ratio = total === 0 ? 0 : used / total
  return (
    <span className="usage" title={`${String(used)} of ${String(total)}${unit ? ` ${unit}` : ''}`}>
      <span className="usage__track">
        <span className="usage__fill" style={{ width: `${String(Math.min(1, ratio) * 100)}%` }} />
      </span>
      <span className="num">
        {used}/{total}
      </span>
    </span>
  )
}

function isLive(worker: Worker): boolean {
  return worker.lifecycle === 'ACTIVE' || worker.lifecycle === 'DRAINING'
}

export function WorkersPage() {
  const queryClient = useQueryClient()
  const workers = useQuery({
    queryKey: keys.workers,
    queryFn: ({ signal }) => api.workers(signal),
    // Health is derived from heartbeat age, which changes without any job event; a slow refresh keeps it honest.
    refetchInterval: 5_000,
  })
  const [showRetired, setShowRetired] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const drain = useMutation({
    mutationFn: (id: string) => api.drainWorker(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: keys.workers }),
    onError: (failure) => { setError(errorMessage(failure)) },
  })

  return (
    <>
      <h1>Workers</h1>
      {error && (
        <p className="notice notice--error" role="alert">
          {error}
        </p>
      )}
      <Panel
        actions={
          <label className="toggle">
            <input type="checkbox" checked={showRetired} onChange={(event) => { setShowRetired(event.target.checked) }} />
            Show retired registrations
          </label>
        }
      >
        <QueryView query={workers} loadingLabel="Loading workers">
          {(all) => {
            const rows = all.filter((worker) => showRetired || isLive(worker))
            return rows.length === 0 ? (
              <Empty>
                No live workers. Start one with docker compose; it registers and appears here within a heartbeat.
              </Empty>
            ) : (
              <table className="data-table">
                <thead>
                  <tr>
                    <th scope="col">Worker</th>
                    <th scope="col">Lifecycle</th>
                    <th scope="col">Health</th>
                    <th scope="col">Last heartbeat</th>
                    <th scope="col">Labels</th>
                    <th scope="col">Slots</th>
                    <th scope="col">CPU</th>
                    <th scope="col">Memory</th>
                    <th scope="col">Accelerators</th>
                    <th scope="col" />
                  </tr>
                </thead>
                <tbody>
                  {rows.map((worker) => (
                    <tr key={worker.id}>
                      <td>
                        <strong>{worker.name}</strong> <code title={worker.id}>{shortId(worker.id)}</code>
                        <div className="muted small">registered {formatAgo(worker.registeredAt)}</div>
                      </td>
                      <td>
                        <StatusChip value={worker.lifecycle} />
                      </td>
                      <td>
                        <StatusChip value={worker.health} />
                      </td>
                      <td title={formatDateTime(worker.lastSeenAt)}>{formatAgo(worker.lastSeenAt)}</td>
                      <td>{worker.labels.length === 0 ? <span className="muted">none</span> : worker.labels.join(', ')}</td>
                      <td>
                        <Usage used={worker.reserved.slots} total={worker.capacity.slots} />
                      </td>
                      <td>
                        <Usage used={worker.reserved.cpuMillis} total={worker.capacity.cpuMillis} unit="m" />
                      </td>
                      <td>
                        <Usage used={worker.reserved.memoryMib} total={worker.capacity.memoryMib} unit="MiB" />
                      </td>
                      <td>
                        <Usage used={worker.reserved.accelerators} total={worker.capacity.accelerators} />
                      </td>
                      <td className="actions">
                        <Link to={`/jobs?workerId=${worker.id}&status=RUNNING`}>Running jobs</Link>
                        {worker.lifecycle === 'ACTIVE' && (
                          <button
                            type="button"
                            className="button"
                            title="Finish current work, take no new work"
                            disabled={drain.isPending && drain.variables === worker.id}
                            onClick={() => { drain.mutate(worker.id) }}
                          >
                            Drain
                          </button>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )
          }}
        </QueryView>
      </Panel>
    </>
  )
}
