// A console-side preference, not server configuration: where this browser opens a trace id. Kept per browser, since the
// trace viewer's address depends on where the person runs it.

const KEY = 'quantarun.traceViewer'
export const DEFAULT_TRACE_VIEWER = 'http://localhost:16686/trace/{traceId}'

export function traceViewerTemplate(): string {
  try {
    return window.localStorage.getItem(KEY) ?? DEFAULT_TRACE_VIEWER
  } catch {
    return DEFAULT_TRACE_VIEWER
  }
}

export function saveTraceViewerTemplate(template: string): void {
  try {
    if (template.trim() === '' || template === DEFAULT_TRACE_VIEWER) {
      window.localStorage.removeItem(KEY)
    } else {
      window.localStorage.setItem(KEY, template.trim())
    }
  } catch {
    // Unavailable storage just means the default stays in use.
  }
}

/** Null when the template is not an http(s) URL, so a stored value can never become a script link. */
export function traceUrl(traceId: string, template: string = traceViewerTemplate()): string | null {
  const url = template.replaceAll('{traceId}', encodeURIComponent(traceId))
  return /^https?:\/\//i.test(url) ? url : null
}
