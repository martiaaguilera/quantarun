import { useQuery } from '@tanstack/react-query'
import { useState, type SyntheticEvent } from 'react'
import { Link } from 'react-router'
import { api, keys } from '../api/endpoints'
import { useSession } from '../app/context'
import { Empty, Panel, QueryView } from '../components/ui'
import { DEFAULT_TRACE_VIEWER, saveTraceViewerTemplate, traceUrl, traceViewerTemplate } from '../features/settings/traceViewer'
import { formatIsoDuration } from '../format'

function Row({ label, value, note }: { label: string; value: string; note?: string }) {
  return (
    <tr>
      <th scope="row">{label}</th>
      <td className="num">{value}</td>
      <td className="muted">{note}</td>
    </tr>
  )
}

function TraceViewerSetting() {
  const [template, setTemplate] = useState(traceViewerTemplate)
  const [saved, setSaved] = useState(false)
  const valid = traceUrl('0123456789abcdef0123456789abcdef', template) !== null
  const submit = (event: SyntheticEvent) => {
    event.preventDefault()
    saveTraceViewerTemplate(template)
    setSaved(true)
  }
  return (
    <form className="form-grid" onSubmit={submit}>
      <label>
        Trace viewer address
        <input
          value={template}
          onChange={(event) => {
            setTemplate(event.target.value)
            setSaved(false)
          }}
        />
        <span className="hint">
          Where a job's “Open trace” link goes, with {'{traceId}'} for the trace id. Kept in this browser only. Default:{' '}
          <code>{DEFAULT_TRACE_VIEWER}</code> (Jaeger from docker-compose.observability.yml).
        </span>
      </label>
      <div className="form-actions">
        <button type="submit" className="button" disabled={!valid}>
          Save address
        </button>
        {!valid && <span className="form-error">The address must start with http:// or https://.</span>}
        {saved && <span className="muted">Saved.</span>}
      </div>
    </form>
  )
}

/**
 * What this control plane runs with. Read-only on purpose: a setting changes with a restart and new configuration,
 * not from a browser. The one thing a browser does own, where it opens traces, is editable here.
 */
export function SettingsPage() {
  const { session } = useSession()
  const operator = session?.role === 'OPERATOR'
  const settings = useQuery({ queryKey: keys.settings, queryFn: ({ signal }) => api.settings(signal), enabled: operator })

  return (
    <>
      <h1>Settings</h1>
      <p className="lede">
        The control plane's effective configuration. To change a value, restart it with new configuration
        (environment variables in docker compose); the console only shows what is running.
      </p>
      {operator ? (
        <QueryView query={settings}>
          {(data) => (
            <div className="grid grid--2">
              <Panel title="Scheduling">
                <table className="data-table data-table--compact">
                  <tbody>
                    <Row label="Policy" value={data.scheduler.policy} note="QUANTARUN_SCHEDULER_POLICY" />
                    <Row label="Background loop" value={data.scheduler.loopEnabled ? 'on' : 'off'} />
                    <Row label="Loops" value={String(data.scheduler.loops)} />
                    <Row label="Window" value={`${String(data.scheduler.windowSize)} jobs`} note="Runnable jobs considered per cycle" />
                    <Row label="Idle pause" value={formatIsoDuration(data.scheduler.idleDelay)} note="After a cycle that placed nothing" />
                  </tbody>
                </table>
              </Panel>
              <Panel title="Workers and leases">
                <table className="data-table data-table--compact">
                  <tbody>
                    <Row label="Heartbeat" value={formatIsoDuration(data.workers.heartbeatInterval)} />
                    <Row label="Late after" value={formatIsoDuration(data.workers.lateAfter)} note="No new placements" />
                    <Row label="Retired after" value={formatIsoDuration(data.workers.offlineAfter)} />
                    <Row label="Lease" value={formatIsoDuration(data.workers.leaseDuration)} note="An attempt survives this long without renewal" />
                    <Row label="Claim timeout" value={formatIsoDuration(data.workers.claimTimeout)} note="Unclaimed work moves on after this plus a lease" />
                    <Row label="Startup grace" value={formatIsoDuration(data.workers.startupGrace)} />
                  </tbody>
                </table>
              </Panel>
              <Panel title="Retries">
                <table className="data-table data-table--compact">
                  <tbody>
                    <Row label="Base delay" value={formatIsoDuration(data.retries.baseDelay)} note="Full jitter, doubling per attempt" />
                    <Row label="Maximum delay" value={formatIsoDuration(data.retries.maxDelay)} />
                  </tbody>
                </table>
              </Panel>
              <Panel title="Chaos">
                <table className="data-table data-table--compact">
                  <tbody>
                    <Row label="Enabled" value={data.chaos.enabled ? 'yes' : 'no'} note="QUANTARUN_CHAOS_ENABLED" />
                    <Row label="Delivery window" value={formatIsoDuration(data.chaos.deliveryWindow)} />
                    <Row label="Pending per worker" value={String(data.chaos.maxPendingPerWorker)} />
                  </tbody>
                </table>
                {data.chaos.enabled && <Link to="/chaos">Open the chaos lab</Link>}
              </Panel>
            </div>
          )}
        </QueryView>
      ) : (
        <Panel>
          <Empty>The configuration is for the operator.</Empty>
        </Panel>
      )}
      <Panel title="This browser">
        <TraceViewerSetting />
      </Panel>
    </>
  )
}
