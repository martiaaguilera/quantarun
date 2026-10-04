import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type SyntheticEvent } from 'react'
import { api, keys } from '../api/endpoints'
import type { SimulationRun } from '../api/types'
import { BarChart } from '../components/charts'
import { Empty, Panel, QueryView } from '../components/ui'
import { errorMessage } from '../components/errors'
import { formatAgo, formatMs, formatPercent, humanize } from '../format'

const POLICIES = ['FIFO', 'PRIORITY', 'LEAST_LOADED', 'BIN_PACKING', 'FAIR_SHARE', 'DEADLINE'] as const

/** What each scenario stresses, in one line, so the choice is informed before running it. */
const SCENARIO_NOTES: Record<string, string> = {
  STEADY: 'Steady arrivals the fleet can absorb: the baseline.',
  BURST: 'A wave far beyond capacity, then quiet: how the queue drains.',
  MIXED_RESOURCES: 'Small and large requests mixed: where packing matters.',
  ACCELERATOR_SCARCE: 'Accelerator jobs compete for a few accelerator slots.',
  NOISY_NEIGHBOR: 'One tenant floods the queue while two submit a little: fairness.',
  DEADLINE_HEAVY: 'Most jobs carry deadlines of varying slack.',
  WORKER_FAILURE: 'A worker dies mid-run; its leases expire and work moves.',
  RATE_LIMIT: 'A provider answers 429 with Retry-After; retries must wait.',
}

function Results({ run }: { run: SimulationRun }) {
  const policies = run.results.policies
  const bars = (pick: (metrics: (typeof policies)[number]['metrics']) => number | null) =>
    policies.map((result) => ({ label: result.policy.replace('_', ' '), value: pick(result.metrics) ?? 0 }))
  const hasDeadlines = policies.some((result) => result.metrics.deadlineMissRate !== null)
  const hasFairness = policies.some((result) => result.metrics.fairness !== null)

  return (
    <>
      <p className="muted small">
        {humanize(run.scenario)}, seed {run.seed}, {run.jobCount.toLocaleString()} jobs, run {formatAgo(run.createdAt)}.
        The same scenario, seed and job count always give the same result hashes.
      </p>
      <div className="small-multiples">
        <BarChart title="Queue wait, p95 (lower is better)" bars={bars((m) => m.queueWaitMs.p95)} format={formatMs} lowerIsBetter />
        <BarChart title="Throughput per minute" bars={bars((m) => m.throughputPerMinute)} format={(v) => v.toFixed(1)} />
        <BarChart title="Slot utilization" bars={bars((m) => m.utilisation.slots)} format={(v) => formatPercent(v)} />
        <BarChart title="Longest wait (lower is better)" bars={bars((m) => m.starvationMs)} format={formatMs} lowerIsBetter />
        {hasFairness && (
          <BarChart title="Fairness, Jain's index" bars={bars((m) => m.fairness)} format={(v) => v.toFixed(3)} />
        )}
        {hasDeadlines && (
          <BarChart
            title="Deadlines missed (lower is better)"
            bars={bars((m) => m.deadlineMissRate)}
            format={(v) => formatPercent(v, 1)}
            lowerIsBetter
          />
        )}
      </div>
      <table className="data-table data-table--compact">
        <thead>
          <tr>
            <th scope="col">Policy</th>
            <th scope="col" className="num">Succeeded</th>
            <th scope="col" className="num">Attempts</th>
            <th scope="col" className="num">Wait p50</th>
            <th scope="col" className="num">Wait p99</th>
            <th scope="col" className="num">Makespan</th>
            <th scope="col" className="num">Planning p99</th>
            <th scope="col">Result hash</th>
          </tr>
        </thead>
        <tbody>
          {policies.map((result) => (
            <tr key={result.policy}>
              <th scope="row">{result.policy}</th>
              <td className="num">{result.metrics.succeeded.toLocaleString()}</td>
              <td className="num">{result.metrics.attempts.toLocaleString()}</td>
              <td className="num">{formatMs(result.metrics.queueWaitMs.p50)}</td>
              <td className="num">{formatMs(result.metrics.queueWaitMs.p99)}</td>
              <td className="num">{formatMs(result.metrics.makespanMs)}</td>
              <td className="num">{result.planning.p99Micros.toFixed(0)} µs</td>
              <td>
                <code title={result.resultHash}>{result.resultHash.slice(0, 12)}</code>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {policies[0] && policies[0].metrics.projects.length > 1 && (
        <>
          <h3>Queue wait p95 by project</h3>
          <p className="muted small">Totals hide where a policy puts the waiting; this is where it went.</p>
          <table className="data-table data-table--compact">
            <thead>
              <tr>
                <th scope="col">Project (weight)</th>
                {policies.map((result) => (
                  <th key={result.policy} scope="col" className="num">
                    {result.policy}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {policies[0].metrics.projects.map((project, index) => (
                <tr key={project.project}>
                  <th scope="row">
                    {project.project} ({project.weight})
                  </th>
                  {policies.map((result) => (
                    <td key={result.policy} className="num">
                      {formatMs(result.metrics.projects[index]?.queueWaitMs.p95)}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
    </>
  )
}

export function PolicyLabPage() {
  const queryClient = useQueryClient()
  const scenarios = useQuery({ queryKey: keys.scenarios, queryFn: ({ signal }) => api.scenarios(signal) })
  const runs = useQuery({ queryKey: keys.simulations, queryFn: ({ signal }) => api.simulations(signal) })
  const [scenario, setScenario] = useState('NOISY_NEIGHBOR')
  const [seed, setSeed] = useState(42)
  const [jobCount, setJobCount] = useState(2000)
  const [policies, setPolicies] = useState<string[]>([...POLICIES])
  const [selected, setSelected] = useState<string | null>(null)
  const run = useMutation({
    mutationFn: api.runSimulation,
    onSuccess: (result) => {
      setSelected(result.id)
      void queryClient.invalidateQueries({ queryKey: keys.simulations })
    },
  })

  const submit = (event: SyntheticEvent) => {
    event.preventDefault()
    run.mutate({ scenario, seed, jobCount, policies })
  }
  const toggle = (policy: string) => {
    setPolicies((current) => (current.includes(policy) ? current.filter((p) => p !== policy) : [...current, policy]))
  }
  const shown = runs.data?.find((candidate) => candidate.id === selected) ?? (selected === null ? runs.data?.[0] : undefined)

  return (
    <>
      <h1>Policy lab</h1>
      <p className="lede">
        Replays a generated workload through the scheduler's own placement code, once per policy, in simulated time. Nothing
        runs on the real fleet. A run of a few thousand jobs takes seconds.
      </p>
      <Panel title="Run a comparison">
        <form className="form-grid" onSubmit={submit}>
          <label>
            Scenario
            <select value={scenario} onChange={(event) => { setScenario(event.target.value) }}>
              {(scenarios.data ?? []).map((option) => (
                <option key={option.scenario} value={option.scenario}>
                  {humanize(option.scenario)}
                </option>
              ))}
            </select>
            <span className="hint">{SCENARIO_NOTES[scenario] ?? ''}</span>
          </label>
          <label>
            Seed
            <input type="number" value={seed} onChange={(event) => { setSeed(Number(event.target.value)) }} />
          </label>
          <label>
            Jobs
            <input
              type="number"
              min={1}
              max={20000}
              value={jobCount}
              onChange={(event) => { setJobCount(Number(event.target.value)) }}
            />
          </label>
          <fieldset>
            <legend>Policies</legend>
            {POLICIES.map((policy) => (
              <label key={policy} className="check">
                <input type="checkbox" checked={policies.includes(policy)} onChange={() => { toggle(policy) }} />
                {policy}
              </label>
            ))}
          </fieldset>
          <div className="form-actions">
            <button type="submit" className="button button--primary" disabled={run.isPending || policies.length === 0}>
              {run.isPending ? 'Simulating…' : 'Run comparison'}
            </button>
            {run.isError && (
              <span className="form-error" role="alert">
                {errorMessage(run.error)}
              </span>
            )}
          </div>
        </form>
      </Panel>
      <Panel
        title="Results"
        actions={
          runs.data && runs.data.length > 1 ? (
            <label>
              Run{' '}
              <select value={shown?.id ?? ''} onChange={(event) => { setSelected(event.target.value) }}>
                {runs.data.map((candidate) => (
                  <option key={candidate.id} value={candidate.id}>
                    {humanize(candidate.scenario)}, seed {candidate.seed}, {formatAgo(candidate.createdAt)}
                  </option>
                ))}
              </select>
            </label>
          ) : null
        }
      >
        <QueryView query={runs}>
          {() => (shown ? <Results run={shown} /> : <Empty>No runs yet. Pick a scenario above and run a comparison.</Empty>)}
        </QueryView>
      </Panel>
    </>
  )
}
