export type HealthStatus = 'UP' | 'DOWN' | 'OUT_OF_SERVICE' | 'UNKNOWN'

export interface ProbeResult {
  probe: 'liveness' | 'readiness'
  status: HealthStatus
}

const KNOWN_STATUSES: readonly HealthStatus[] = ['UP', 'DOWN', 'OUT_OF_SERVICE', 'UNKNOWN']

function isHealthStatus(value: unknown): value is HealthStatus {
  return typeof value === 'string' && (KNOWN_STATUSES as readonly string[]).includes(value)
}

/**
 * Actuator answers 503 with a JSON body when a probe is DOWN, so a non-2xx response is still a valid reading.
 * Only transport failures and unparseable bodies are errors.
 */
export async function fetchProbe(probe: ProbeResult['probe'], signal?: AbortSignal): Promise<ProbeResult> {
  const response = await fetch(`/actuator/health/${probe}`, {
    headers: { Accept: 'application/json' },
    ...(signal ? { signal } : {}),
  })
  if (response.status !== 200 && response.status !== 503) {
    throw new Error(`Health probe ${probe} returned HTTP ${String(response.status)}`)
  }
  const body: unknown = await response.json()
  const status = typeof body === 'object' && body !== null && 'status' in body ? body.status : undefined
  if (!isHealthStatus(status)) {
    throw new Error(`Health probe ${probe} returned an unrecognised body`)
  }
  return { probe, status }
}
