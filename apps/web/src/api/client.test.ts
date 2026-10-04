import { afterEach, describe, expect, it, vi } from 'vitest'
import { traceUrl } from '../features/settings/traceViewer'
import { formatMs, parseIsoDuration } from '../format'
import { ApiError, apiFetch, onUnauthorized, query, storeCredential } from './client'

describe('apiFetch', () => {
  afterEach(() => {
    storeCredential(null)
  })

  it('sends the stored credential and turns Problem Details into an ApiError with the server detail', async () => {
    storeCredential('qr_key')
    const fetchMock = vi.fn(() =>
      Promise.resolve(
        new Response(JSON.stringify({ title: 'Conflict', detail: 'Job is already finished.', code: 'JOB_NOT_CANCELLABLE' }), {
          status: 409,
          headers: { 'Content-Type': 'application/problem+json' },
        }),
      ),
    )
    vi.stubGlobal('fetch', fetchMock)

    const failure = await apiFetch('/api/v1/jobs/x/cancel', { method: 'POST' }).catch((error: unknown) => error)

    expect(failure).toBeInstanceOf(ApiError)
    expect(failure).toMatchObject({ status: 409, code: 'JOB_NOT_CANCELLABLE', message: 'Job is already finished.' })
    const init = (fetchMock.mock.calls[0] as unknown as [string, RequestInit])[1]
    expect((init.headers as Record<string, string>)['Authorization']).toBe('Bearer qr_key')
  })

  it('reports an unreachable control plane in plain words, and a 401 to the sign-in listener', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new TypeError('Failed to fetch'))))
    await expect(apiFetch('/api/v1/overview')).rejects.toMatchObject({ status: 0, code: 'UNREACHABLE' })

    const listener = vi.fn()
    const unsubscribe = onUnauthorized(listener)
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(new Response(null, { status: 401 }))))
    await expect(apiFetch('/api/v1/overview')).rejects.toMatchObject({ status: 401 })
    unsubscribe()

    expect(listener).toHaveBeenCalledTimes(1)
  })

  it('leaves unset filters out of the query string', () => {
    expect(query({ status: 'DEAD', priority: undefined, before: null, workloadType: '' })).toBe('?status=DEAD')
    expect(query({})).toBe('')
  })
})

describe('formatting', () => {
  it('reads Java durations', () => {
    expect(parseIsoDuration('PT0.5S')).toBe(500)
    expect(parseIsoDuration('PT2M30S')).toBe(150_000)
    expect(parseIsoDuration('PT1H')).toBe(3_600_000)
    expect(parseIsoDuration('P1D')).toBeNull()
  })

  it('formats milliseconds compactly', () => {
    expect(formatMs(850)).toBe('850 ms')
    expect(formatMs(12_400)).toBe('12.4 s')
    expect(formatMs(185_000)).toBe('3 min 05 s')
    expect(formatMs(null)).toBe('–')
  })
})

describe('trace links', () => {
  it('fills in the trace id and refuses anything but http(s)', () => {
    expect(traceUrl('abc', 'http://localhost:16686/trace/{traceId}')).toBe('http://localhost:16686/trace/abc')
    expect(traceUrl('abc', 'javascript:alert({traceId})')).toBeNull()
  })
})
