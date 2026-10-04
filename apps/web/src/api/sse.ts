// A Server-Sent Events client on fetch. EventSource cannot send an Authorization header, and putting the credential
// in the URL would leak it into proxy and server logs, so the stream is read and parsed here instead.

export interface SseMessage {
  id: string | null
  event: string
  data: string
}

/**
 * Incremental parser for the text/event-stream format: feed it chunks as they arrive, get complete messages back.
 * Comments (":keepalive") are skipped; a message without an "event:" field is a "message".
 */
export class SseParser {
  private buffer = ''

  push(chunk: string): SseMessage[] {
    this.buffer += chunk.replace(/\r\n?/g, '\n')
    const messages: SseMessage[] = []
    let boundary = this.buffer.indexOf('\n\n')
    while (boundary !== -1) {
      const block = this.buffer.slice(0, boundary)
      this.buffer = this.buffer.slice(boundary + 2)
      const message = parseBlock(block)
      if (message) {
        messages.push(message)
      }
      boundary = this.buffer.indexOf('\n\n')
    }
    return messages
  }
}

function parseBlock(block: string): SseMessage | null {
  let id: string | null = null
  let event = 'message'
  const data: string[] = []
  for (const line of block.split('\n')) {
    if (line.startsWith(':') || line.length === 0) {
      continue
    }
    const colon = line.indexOf(':')
    const field = colon === -1 ? line : line.slice(0, colon)
    let value = colon === -1 ? '' : line.slice(colon + 1)
    if (value.startsWith(' ')) {
      value = value.slice(1)
    }
    if (field === 'id') {
      id = value
    } else if (field === 'event') {
      event = value
    } else if (field === 'data') {
      data.push(value)
    }
  }
  return data.length === 0 ? null : { id, event, data: data.join('\n') }
}

export type StreamState =
  | { kind: 'connecting' }
  | { kind: 'open' }
  | { kind: 'retrying'; inMs: number; reason: string }
  | { kind: 'closed' }

export interface StreamOptions {
  url: string
  credential: () => string | null
  onMessage: (message: SseMessage) => void
  onState: (state: StreamState) => void
  /** Backoff after a failed or ended connection: full jitter up to a growing cap. */
  baseDelayMs?: number
  maxDelayMs?: number
  random?: () => number
}

/**
 * Keeps one stream open: reconnects after every end or error with backoff, and resumes from the last id it saw
 * (Last-Event-ID), so the server replays what was missed. Returns a function that stops it for good.
 */
export function openEventStream(options: StreamOptions): () => void {
  const base = options.baseDelayMs ?? 1_000
  const max = options.maxDelayMs ?? 30_000
  const random = options.random ?? Math.random
  let lastEventId: string | null = null
  let failures = 0
  let stopped = false
  let controller: AbortController | null = null
  let timer: ReturnType<typeof setTimeout> | null = null

  const scheduleRetry = (reason: string) => {
    if (stopped) {
      return
    }
    failures += 1
    const ceiling = Math.min(max, base * 2 ** Math.min(failures - 1, 10))
    const delay = Math.round(random() * ceiling)
    options.onState({ kind: 'retrying', inMs: delay, reason })
    timer = setTimeout(connect, delay)
  }

  const connect = () => {
    if (stopped) {
      return
    }
    options.onState({ kind: 'connecting' })
    controller = new AbortController()
    const headers: Record<string, string> = { Accept: 'text/event-stream' }
    const credential = options.credential()
    if (credential) {
      headers['Authorization'] = `Bearer ${credential}`
    }
    if (lastEventId !== null) {
      headers['Last-Event-ID'] = lastEventId
    }
    fetch(options.url, { headers, signal: controller.signal })
      .then(async (response) => {
        if (!response.ok || !response.body) {
          scheduleRetry(`HTTP ${String(response.status)}`)
          return
        }
        failures = 0
        options.onState({ kind: 'open' })
        const parser = new SseParser()
        const reader = response.body.pipeThrough(new TextDecoderStream()).getReader()
        for (;;) {
          const { value, done } = await reader.read()
          if (done) {
            break
          }
          for (const message of parser.push(value)) {
            if (message.id !== null) {
              lastEventId = message.id
            }
            options.onMessage(message)
          }
        }
        // The server ends a stream after its lifetime; that is a normal reconnect, not a failure to report loudly.
        scheduleRetry('stream ended')
      })
      .catch((error: unknown) => {
        if (stopped) {
          return
        }
        scheduleRetry(error instanceof Error ? error.message : 'connection failed')
      })
  }

  connect()
  return () => {
    stopped = true
    if (timer !== null) {
      clearTimeout(timer)
    }
    controller?.abort()
    options.onState({ kind: 'closed' })
  }
}
