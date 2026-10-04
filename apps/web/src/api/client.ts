// One way to talk to the control plane: the session's credential on every request, and RFC 9457 Problem Details
// turned into ApiError so every view can say what went wrong in the server's own words.

const TOKEN_KEY = 'quantarun.credential'

/** sessionStorage, not localStorage: closing the tab signs out, so a shared machine keeps no long-lived key. */
export function storedCredential(): string | null {
  try {
    return window.sessionStorage.getItem(TOKEN_KEY)
  } catch {
    return null
  }
}

export function storeCredential(credential: string | null): void {
  try {
    if (credential === null) {
      window.sessionStorage.removeItem(TOKEN_KEY)
    } else {
      window.sessionStorage.setItem(TOKEN_KEY, credential)
    }
  } catch {
    // Storage can be unavailable (private mode, policy); the session then lasts until the page reloads.
  }
}

export class ApiError extends Error {
  readonly status: number
  readonly code: string | null

  constructor(status: number, code: string | null, message: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }
}

const listeners = new Set<() => void>()

/** Called when the server rejects the credential, so the app can return to sign-in. */
export function onUnauthorized(listener: () => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

function problemOf(body: unknown, status: number): ApiError {
  if (typeof body === 'object' && body !== null) {
    const record = body as Record<string, unknown>
    const detail = typeof record['detail'] === 'string' ? record['detail'] : null
    const title = typeof record['title'] === 'string' ? record['title'] : null
    const code = typeof record['code'] === 'string' ? record['code'] : null
    return new ApiError(status, code, detail ?? title ?? `The control plane answered HTTP ${String(status)}.`)
  }
  return new ApiError(status, null, `The control plane answered HTTP ${String(status)}.`)
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE'
  body?: unknown
  signal?: AbortSignal | undefined
}

export async function apiFetch<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const credential = storedCredential()
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (credential) {
    headers['Authorization'] = `Bearer ${credential}`
  }
  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json'
  }
  let response: Response
  try {
    response = await fetch(path, {
      method: options.method ?? 'GET',
      headers,
      ...(options.body === undefined ? {} : { body: JSON.stringify(options.body) }),
      ...(options.signal ? { signal: options.signal } : {}),
    })
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') {
      throw error
    }
    throw new ApiError(0, 'UNREACHABLE', 'The control plane did not answer. Check that it is running.')
  }
  if (response.status === 401) {
    listeners.forEach((listener) => {
      listener()
    })
  }
  const text = await response.text()
  let body: unknown = null
  if (text.length > 0) {
    try {
      body = JSON.parse(text)
    } catch {
      body = null
    }
  }
  if (!response.ok) {
    throw problemOf(body, response.status)
  }
  return body as T
}

/** Builds a query string from the defined values only, so an unset filter never reaches the server as "undefined". */
export function query(params: Record<string, string | number | null | undefined>): string {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value !== null && value !== undefined && value !== '') {
      search.set(key, String(value))
    }
  }
  const encoded = search.toString()
  return encoded.length === 0 ? '' : `?${encoded}`
}
