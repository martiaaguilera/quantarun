import { apiFetch, query } from './client'
import type {
  Attempt,
  ChaosCatalog,
  ChaosExperiment,
  ChaosFault,
  Checkpoint,
  Decision,
  FairnessView,
  Job,
  JobEvent,
  JobPage,
  JobStatus,
  Overview,
  ScenarioInfo,
  SchedulerStatus,
  Settings,
  SimulationRun,
  Worker,
} from './types'

export interface Session {
  role: 'OPERATOR' | 'PROJECT'
  projectId: string | null
}

export interface JobFilter {
  status?: JobStatus | undefined
  workloadType?: string | undefined
  priority?: number | undefined
  workerId?: string | undefined
  projectId?: string | undefined
  createdFrom?: string | undefined
  createdTo?: string | undefined
}

// Query keys in one place: the event stream invalidates by these prefixes.
export const keys = {
  session: ['session'] as const,
  overview: ['overview'] as const,
  jobs: (filter: JobFilter) => ['jobs', filter] as const,
  jobsAll: ['jobs'] as const,
  job: (id: string) => ['job', id] as const,
  workers: ['workers'] as const,
  scheduler: ['scheduler'] as const,
  decisions: ['scheduler', 'decisions'] as const,
  fairness: ['scheduler', 'fairness'] as const,
  simulations: ['simulations'] as const,
  scenarios: ['simulations', 'scenarios'] as const,
  chaosCatalog: ['chaos', 'faults'] as const,
  experiments: ['chaos', 'experiments'] as const,
  experiment: (id: string) => ['chaos', 'experiments', id] as const,
  settings: ['settings'] as const,
}

export const api = {
  session: (signal?: AbortSignal) => apiFetch<Session>('/api/v1/session', { signal }),
  overview: (signal?: AbortSignal) => apiFetch<Overview>('/api/v1/overview', { signal }),

  jobs: (filter: JobFilter, before: string | null, limit: number, signal?: AbortSignal) =>
    apiFetch<JobPage>(`/api/v1/jobs${query({ ...filter, before, limit })}`, { signal }),
  job: (id: string, signal?: AbortSignal) => apiFetch<Job>(`/api/v1/jobs/${id}`, { signal }),
  jobEvents: (id: string, signal?: AbortSignal) => apiFetch<JobEvent[]>(`/api/v1/jobs/${id}/events`, { signal }),
  jobAttempts: (id: string, signal?: AbortSignal) => apiFetch<Attempt[]>(`/api/v1/jobs/${id}/attempts`, { signal }),
  jobDecisions: (id: string, signal?: AbortSignal) =>
    apiFetch<Decision[]>(`/api/v1/jobs/${id}/decisions`, { signal }),
  jobCheckpoints: (id: string, signal?: AbortSignal) =>
    apiFetch<Checkpoint[]>(`/api/v1/jobs/${id}/checkpoints`, { signal }),
  cancelJob: (id: string) => apiFetch<unknown>(`/api/v1/jobs/${id}/cancel`, { method: 'POST' }),
  reviveJob: (id: string) => apiFetch<Job>(`/api/v1/jobs/${id}/revive`, { method: 'POST' }),

  workers: (signal?: AbortSignal) => apiFetch<Worker[]>('/api/v1/workers', { signal }),
  drainWorker: (id: string) => apiFetch<Worker>(`/api/v1/workers/${id}/drain`, { method: 'POST' }),

  scheduler: (signal?: AbortSignal) => apiFetch<SchedulerStatus>('/api/v1/scheduler', { signal }),
  decisions: (limit: number, signal?: AbortSignal) =>
    apiFetch<Decision[]>(`/api/v1/scheduler/decisions${query({ limit })}`, { signal }),
  fairness: (signal?: AbortSignal) => apiFetch<FairnessView>('/api/v1/scheduler/fairness', { signal }),

  scenarios: (signal?: AbortSignal) => apiFetch<ScenarioInfo[]>('/api/v1/simulations/scenarios', { signal }),
  simulations: (signal?: AbortSignal) => apiFetch<SimulationRun[]>('/api/v1/simulations?limit=20', { signal }),
  runSimulation: (request: { scenario: string; seed: number; jobCount: number; policies: string[] }) =>
    apiFetch<SimulationRun>('/api/v1/simulations', { method: 'POST', body: request }),

  chaosCatalog: (signal?: AbortSignal) => apiFetch<ChaosCatalog>('/api/v1/chaos/faults', { signal }),
  experiments: (signal?: AbortSignal) => apiFetch<ChaosExperiment[]>('/api/v1/chaos/experiments', { signal }),
  experiment: (id: string, signal?: AbortSignal) =>
    apiFetch<ChaosExperiment>(`/api/v1/chaos/experiments/${id}`, { signal }),
  createExperiment: (request: { fault: ChaosFault; workerId?: string; jobId?: string } & Record<string, unknown>) =>
    apiFetch<ChaosExperiment>('/api/v1/chaos/experiments', { method: 'POST', body: request }),
  cancelExperiment: (id: string) =>
    apiFetch<ChaosExperiment>(`/api/v1/chaos/experiments/${id}/cancel`, { method: 'POST' }),

  settings: (signal?: AbortSignal) => apiFetch<Settings>('/api/v1/settings', { signal }),
}
