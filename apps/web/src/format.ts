// Formatting for an operations console: exact where it matters (ids, timestamps), compact everywhere else.

/** Parses Java's ISO-8601 Duration text ("PT0.5S", "PT2M30S", "PT1H") into milliseconds; null if unrecognised. */
export function parseIsoDuration(text: string): number | null {
  const match = /^PT(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?$/.exec(text)
  if (!match || text === 'PT') {
    return null
  }
  const [, hours, minutes, seconds] = match
  return (Number(hours ?? 0) * 3600 + Number(minutes ?? 0) * 60 + Number(seconds ?? 0)) * 1000
}

/** 850 ms, 12.4 s, 3 min 05 s, 2 h 10 min. */
export function formatMs(ms: number | null | undefined): string {
  if (ms === null || ms === undefined || !Number.isFinite(ms)) {
    return '–'
  }
  const abs = Math.abs(ms)
  const sign = ms < 0 ? '−' : ''
  if (abs < 1_000) {
    return `${sign}${String(Math.round(abs))} ms`
  }
  if (abs < 60_000) {
    return `${sign}${(abs / 1_000).toFixed(abs < 10_000 ? 2 : 1)} s`
  }
  if (abs < 3_600_000) {
    const minutes = Math.floor(abs / 60_000)
    const seconds = Math.round((abs % 60_000) / 1_000)
    return `${sign}${String(minutes)} min ${String(seconds).padStart(2, '0')} s`
  }
  const hours = Math.floor(abs / 3_600_000)
  const minutes = Math.round((abs % 3_600_000) / 60_000)
  return `${sign}${String(hours)} h ${String(minutes)} min`
}

export function formatIsoDuration(text: string): string {
  const ms = parseIsoDuration(text)
  return ms === null ? text : formatMs(ms)
}

export function between(from: string | null | undefined, to: string | null | undefined): number | null {
  if (!from || !to) {
    return null
  }
  return Date.parse(to) - Date.parse(from)
}

/** Wall-clock time with milliseconds, in the viewer's zone: timelines are read at that resolution. */
export function formatTime(iso: string | null | undefined): string {
  if (!iso) {
    return '–'
  }
  const date = new Date(iso)
  return `${date.toLocaleTimeString(undefined, { hour12: false })}.${String(date.getMilliseconds()).padStart(3, '0')}`
}

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) {
    return '–'
  }
  const date = new Date(iso)
  return `${date.toLocaleDateString(undefined, { month: 'short', day: 'numeric' })} ${formatTime(iso)}`
}

export function formatAgo(iso: string | null | undefined, now: number = Date.now()): string {
  if (!iso) {
    return '–'
  }
  const ms = now - Date.parse(iso)
  if (ms < 1_000) {
    return 'just now'
  }
  return `${formatMs(ms)} ago`
}

export function formatPercent(ratio: number | null | undefined, digits = 0): string {
  if (ratio === null || ratio === undefined || !Number.isFinite(ratio)) {
    return '–'
  }
  return `${(ratio * 100).toFixed(digits)} %`
}

export function formatCount(value: number): string {
  return value.toLocaleString()
}

/**
 * The last eight characters of a UUID, with the full id in the title. The ids are UUIDv7, whose leading characters are
 * a timestamp: jobs or workers created in the same second share them, so only the random tail tells rows apart.
 */
export function shortId(id: string | null | undefined): string {
  return id ? id.slice(-8) : '–'
}

export function humanize(constant: string): string {
  const lower = constant.toLowerCase().replaceAll('_', ' ')
  return lower.charAt(0).toUpperCase() + lower.slice(1)
}
