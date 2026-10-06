// Response shapes of the control-plane API (/api/v1). Hand-written against the controllers' DTO records; the generated
// OpenAPI document is the contract, these types are what the console relies on.

export type JobStatus =
  | 'QUEUED'
  | 'SCHEDULED'
  | 'RUNNING'
  | 'RETRY_WAIT'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'DEAD'
  | 'CANCELLED'

export const JOB_STATUSES: readonly JobStatus[] = [
  'QUEUED',
  'RETRY_WAIT',
  'SCHEDULED',
  'RUNNING',
  'SUCCEEDED',
  'FAILED',
  'DEAD',
  'CANCELLED',
]

export const FINAL_STATUSES: readonly JobStatus[] = ['SUCCEEDED', 'FAILED', 'DEAD', 'CANCELLED']

export type WorkloadType = 'delay' | 'cpu-hash' | 'mock-inference' | 'fail' | 'memory' | 'staged' | 'http'

export const WORKLOAD_TYPES: readonly WorkloadType[] = [
  'delay',
  'cpu-hash',
  'mock-inference',
  'fail',
  'memory',
  'staged',
  'http',
]

export interface Resources {
  cpuMillis: number
  memoryMib: number
  accelerators: number
}

export interface Job {
  id: string
  projectId: string
  workloadType: WorkloadType
  payload: Record<string, unknown>
  status: JobStatus
  priority: number
  resources: Resources
  requiredLabels: string[]
  maxAttempts: number
  attemptCount: number
  attemptsInBudget: number
  reviveCount: number
  timeoutSeconds: number
  availableAt: string
  deadline: string | null
  idempotencyKey: string | null
  cancelRequestedAt: string | null
  schedulingOutcome: string | null
  schedulingReason: string | null
  createdAt: string
  updatedAt: string
  finishedAt: string | null
  traceId: string | null
}

export interface JobPage {
  items: Job[]
  nextBefore: string | null
}

export type JobEventType =
  | 'SUBMITTED'
  | 'SCHEDULED'
  | 'STARTED'
  | 'SUCCEEDED'
  | 'ATTEMPT_FAILED'
  | 'ATTEMPT_LOST'
  | 'RETRY_SCHEDULED'
  | 'FAILED'
  | 'DEAD'
  | 'CANCEL_REQUESTED'
  | 'CANCELLED'
  | 'CHECKPOINT_COMMITTED'
  | 'REVIVED'

export interface JobEvent {
  id: number
  attemptId: string | null
  type: JobEventType
  occurredAt: string
  details: Record<string, unknown>
}

export type AttemptStatus = 'ASSIGNED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'LOST' | 'CANCELLED'

export interface Attempt {
  id: string
  attemptNo: number
  workerId: string
  status: AttemptStatus
  assignedAt: string
  startedAt: string | null
  finishedAt: string | null
  leaseExpiresAt: string
  leaseRenewals: number
  failureClass: string | null
  failureMessage: string | null
  retryDecision: string | null
  result: unknown
  traceParent: string | null
}

export interface Checkpoint {
  stageIndex: number
  attemptId: string
  result: unknown
  committedAt: string
}

export type Verdict =
  | 'CHOSEN'
  | 'FITS'
  | 'INSUFFICIENT_FREE_CAPACITY'
  | 'NOT_ACCEPTING_WORK'
  | 'EXCEEDS_CAPACITY'
  | 'MISSING_LABELS'

export interface Candidate {
  workerId: string
  workerName: string
  verdict: Verdict
  detail: string
  score: number | null
}

export interface Decision {
  id: number
  jobId: string
  attemptId: string | null
  policy: string
  outcome: 'PLACED' | 'WAITING_FOR_CAPACITY' | 'WAITING_FOR_QUOTA' | 'UNSCHEDULABLE'
  chosenWorkerId: string | null
  reason: string
  candidates: Candidate[]
  queueWaitMs: number
  decidedAt: string
}

export interface WorkerResources {
  cpuMillis: number
  memoryMib: number
  accelerators: number
  slots: number
}

export type WorkerLifecycle = 'ACTIVE' | 'DRAINING' | 'OFFLINE' | 'DEREGISTERED'
export type WorkerHealth = 'HEALTHY' | 'LATE' | 'OFFLINE'

export interface Worker {
  id: string
  name: string
  version: string
  lifecycle: WorkerLifecycle
  health: WorkerHealth
  labels: string[]
  capacity: WorkerResources
  reserved: WorkerResources
  registeredAt: string
  lastSeenAt: string
}

/** Java Durations arrive as ISO-8601 strings ("PT0.5S"); formatDuration parses them. */
export type IsoDuration = string

export interface SchedulerStatus {
  enabled: boolean
  policy: string
  availablePolicies: string[]
  windowSize: number
  idleDelay: IsoDuration
  loops: number
}

export interface ProjectFairness {
  projectId: string
  name: string
  weight: number
  virtualTime: number
  maxQueuedJobs: number | null
  maxRunningJobs: number | null
  maxAccelerators: number | null
}

export interface FairnessView {
  systemVirtualTime: number
  projects: ProjectFairness[]
}

export interface Overview {
  jobs: {
    /** Unfinished jobs only; finished ones are counted over the last hour. */
    byStatus: Record<'QUEUED' | 'RETRY_WAIT' | 'SCHEDULED' | 'RUNNING', number>
    queued: number
    running: number
    succeededLastHour: number
    failedLastHour: number
    deadLastHour: number
    cancelledLastHour: number
    successRate: number | null
    retriesLastHour: number
    timeToStartP95Seconds: number | null
  }
  fleet: {
    healthyWorkers: number
    lateWorkers: number
    drainingWorkers: number
    capacity: WorkerResources
    reserved: WorkerResources
    utilization: Record<'cpuMillis' | 'memoryMib' | 'accelerators' | 'slots', number>
  } | null
  generatedAt: string
}

export interface Distribution {
  count: number
  mean: number
  p50: number
  p95: number
  p99: number
  max: number
}

export interface SimulationMetrics {
  succeeded: number
  failed: number
  dead: number
  neverFinished: number
  attempts: number
  makespanMs: number
  throughputPerMinute: number
  queueWaitMs: Distribution
  completionLatencyMs: Distribution
  deadlineMissRate: number | null
  starvationMs: number
  utilisation: { cpu: number; memory: number; accelerators: number | null; slots: number }
  fairness: number | null
  cycles: number
  projects: { project: string; weight: number; queueWaitMs: Distribution }[]
}

export interface PolicyResult {
  policy: string
  metrics: SimulationMetrics
  planning: { cycles: number; meanMicros: number; p99Micros: number }
  resultHash: string
}

export interface SimulationRun {
  id: string
  scenario: string
  seed: number
  jobCount: number
  createdAt: string
  results: { scenario: string; seed: number; jobCount: number; policies: PolicyResult[] }
}

export interface ScenarioInfo {
  scenario: string
  defaultJobCount: number
}

export type ChaosFault =
  | 'KILL_WORKER'
  | 'PAUSE_HEARTBEAT'
  | 'STOP_CLAIMING'
  | 'NETWORK_LATENCY'
  | 'STALL_ATTEMPTS'
  | 'PROVIDER_RATE_LIMITED'
  | 'PROVIDER_ERROR'
  | 'PROVIDER_MALFORMED'

export type FaultParameter = 'DELAY_MS' | 'DURATION_MS' | 'COUNT' | 'RETRY_AFTER_MS' | 'LATENCY_MS'

export interface FaultDefinition {
  fault: ChaosFault
  description: string
  parameters: { parameter: FaultParameter; min: number; max: number; defaultValue: number }[]
}

export interface ChaosCatalog {
  enabled: boolean
  faults: FaultDefinition[]
}

export interface TimelineEntry {
  at: string
  source: 'EXPERIMENT' | 'JOB' | 'WORKER'
  type: string
  jobId: string | null
  attemptId: string | null
  workerId: string | null
  detail: string
}

export interface ChaosExperiment {
  id: string
  fault: ChaosFault
  workerId: string
  jobId: string | null
  parameters: { delayMs: number; durationMs: number; count: number; retryAfterMs: number; latencyMs: number }
  status: 'PENDING' | 'DELIVERED' | 'EXPIRED' | 'CANCELLED'
  createdAt: string
  deliverBy: string
  deliveredAt: string | null
  endedAt: string | null
  timeline: {
    targetLifecycle: string | null
    entries: TimelineEntry[]
    jobs: {
      jobId: string
      status: string
      disruptedAt: string | null
      recoveredAt: string | null
      detectionMs: number | null
      recoveryMs: number | null
    }[]
    summary: { affectedJobs: number; disruptedJobs: number; recoveredJobs: number; maxRecoveryMs: number | null }
  } | null
}

export interface Settings {
  scheduler: { policy: string; loopEnabled: boolean; loops: number; windowSize: number; idleDelay: IsoDuration }
  workers: {
    heartbeatInterval: IsoDuration
    lateAfter: IsoDuration
    offlineAfter: IsoDuration
    leaseDuration: IsoDuration
    claimTimeout: IsoDuration
    startupGrace: IsoDuration
  }
  retries: { baseDelay: IsoDuration; maxDelay: IsoDuration }
  chaos: { enabled: boolean; deliveryWindow: IsoDuration; maxPendingPerWorker: number }
}

/** One event from /api/v1/events/stream. */
export interface StreamEvent {
  id: number
  jobId: string
  projectId: string
  attemptId: string | null
  type: JobEventType
  occurredAt: string
  details: Record<string, unknown>
}
