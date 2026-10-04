import { afterEach, describe, expect, it, vi } from 'vitest'
import { openEventStream, SseParser, type SseMessage, type StreamState } from './sse'

describe('SseParser', () => {
  it('assembles messages split across chunks, and skips comments', () => {
    const parser = new SseParser()

    expect(parser.push('id: 7\nevent: job\nda')).toEqual([])
    expect(parser.push('ta: {"a":1}\n\n:keepalive\n\nid: 8\n')).toEqual([{ id: '7', event: 'job', data: '{"a":1}' }])
    expect(parser.push('data: second\n\n')).toEqual([{ id: '8', event: 'message', data: 'second' }])
  })

  it('joins multi-line data and accepts CRLF line endings', () => {
    const parser = new SseParser()

    expect(parser.push('event: reset\r\ndata: line one\r\ndata: line two\r\n\r\n')).toEqual([
      { id: null, event: 'reset', data: 'line one\nline two' },
    ])
  })
})

function streamOf(chunks: string[], keepOpen = false): ReadableStream<Uint8Array> {
  const encoder = new TextEncoder()
  return new ReadableStream({
    start(controller) {
      chunks.forEach((chunk) => {
        controller.enqueue(encoder.encode(chunk))
      })
      if (!keepOpen) {
        controller.close()
      }
    },
  })
}

describe('openEventStream', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  it('reconnects after the stream ends and resumes from the last event id', async () => {
    vi.useFakeTimers()
    const requests: Record<string, string>[] = []
    const fetchMock = vi.fn((_url: string, init: RequestInit) => {
      requests.push(init.headers as Record<string, string>)
      const body =
        requests.length === 1
          ? streamOf(['id: 41\nevent: job\ndata: {}\n\nid: 42\nevent: job\ndata: {}\n\n'])
          : streamOf(['id: 43\nevent: job\ndata: {}\n\n'], true)
      return Promise.resolve(new Response(body, { status: 200 }))
    })
    vi.stubGlobal('fetch', fetchMock)
    const messages: SseMessage[] = []
    const states: StreamState[] = []

    const stop = openEventStream({
      url: '/api/v1/events/stream',
      credential: () => 'secret',
      onMessage: (message) => messages.push(message),
      onState: (state) => states.push(state),
      random: () => 1,
    })
    await vi.waitFor(() => {
      expect(states.some((state) => state.kind === 'retrying')).toBe(true)
    })
    await vi.advanceTimersByTimeAsync(1_000)
    await vi.waitFor(() => {
      expect(messages.map((message) => message.id)).toEqual(['41', '42', '43'])
    })
    stop()

    expect(requests[0]?.['Authorization']).toBe('Bearer secret')
    expect(requests[0]?.['Last-Event-ID']).toBeUndefined()
    expect(requests[1]?.['Last-Event-ID']).toBe('42')
    expect(states.at(-1)).toEqual({ kind: 'closed' })
  })

  it('backs off with a growing ceiling while the server keeps failing', async () => {
    vi.useFakeTimers()
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(new Response(null, { status: 503 }))))
    const delays: number[] = []

    const stop = openEventStream({
      url: '/stream',
      credential: () => null,
      onMessage: () => undefined,
      onState: (state) => {
        if (state.kind === 'retrying') {
          delays.push(state.inMs)
        }
      },
      baseDelayMs: 1_000,
      maxDelayMs: 4_000,
      random: () => 1,
    })
    for (let i = 0; i < 4; i++) {
      await vi.waitFor(() => {
        expect(delays.length).toBe(i + 1)
      })
      await vi.advanceTimersByTimeAsync(delays[i] ?? 0)
    }
    stop()

    expect(delays.slice(0, 4)).toEqual([1_000, 2_000, 4_000, 4_000])
  })
})
