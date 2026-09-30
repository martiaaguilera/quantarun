import { useQuery } from '@tanstack/react-query'
import { fetchProbe, type ProbeResult } from '../../api/health'

const PROBES: readonly ProbeResult['probe'][] = ['liveness', 'readiness']
const PROBE_DESCRIPTIONS: Record<ProbeResult['probe'], string> = {
  liveness: 'Process is running and responsive',
  readiness: 'Accepting traffic: database reachable, migrations applied',
}

function ProbeRow({ probe }: { probe: ProbeResult['probe'] }) {
  const query = useQuery({
    queryKey: ['health', probe],
    queryFn: ({ signal }) => fetchProbe(probe, signal),
    refetchInterval: 10_000,
  })

  let statusCell
  if (query.isPending) {
    statusCell = <span className="status status--muted">checking…</span>
  } else if (query.isError) {
    statusCell = <span className="status status--down">unreachable</span>
  } else {
    const up = query.data.status === 'UP'
    statusCell = <span className={up ? 'status status--up' : 'status status--down'}>{query.data.status}</span>
  }

  return (
    <tr>
      <th scope="row">{probe}</th>
      <td>{statusCell}</td>
      <td className="muted">{query.isError ? query.error.message : PROBE_DESCRIPTIONS[probe]}</td>
    </tr>
  )
}

export function ControlPlaneStatus() {
  return (
    <section aria-labelledby="control-plane-status-heading" className="panel">
      <h2 id="control-plane-status-heading">Control plane</h2>
      <table className="data-table">
        <thead>
          <tr>
            <th scope="col">Probe</th>
            <th scope="col">Status</th>
            <th scope="col">Meaning</th>
          </tr>
        </thead>
        <tbody>
          {PROBES.map((probe) => (
            <ProbeRow key={probe} probe={probe} />
          ))}
        </tbody>
      </table>
    </section>
  )
}
