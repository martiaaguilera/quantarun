import type { ReactNode } from 'react'
import type { UseQueryResult } from '@tanstack/react-query'
import { ApiError } from '../api/client'
import { humanize, shortId } from '../format'
import { errorMessage } from './errors'

type Tone = 'neutral' | 'active' | 'warn' | 'good' | 'bad' | 'muted'

const TONES: Record<string, Tone> = {
  QUEUED: 'neutral',
  ASSIGNED: 'neutral',
  PENDING: 'neutral',
  SCHEDULED: 'active',
  RUNNING: 'active',
  DRAINING: 'warn',
  RETRY_WAIT: 'warn',
  LATE: 'warn',
  WAITING_FOR_CAPACITY: 'warn',
  WAITING_FOR_QUOTA: 'warn',
  SUCCEEDED: 'good',
  HEALTHY: 'good',
  ACTIVE: 'good',
  PLACED: 'good',
  DELIVERED: 'good',
  CHOSEN: 'good',
  FITS: 'neutral',
  FAILED: 'bad',
  LOST: 'bad',
  DEAD: 'bad',
  UNSCHEDULABLE: 'bad',
  EXCEEDS_CAPACITY: 'bad',
  MISSING_LABELS: 'bad',
  INSUFFICIENT_FREE_CAPACITY: 'warn',
  NOT_ACCEPTING_WORK: 'muted',
  CANCELLED: 'muted',
  EXPIRED: 'muted',
  OFFLINE: 'muted',
  DEREGISTERED: 'muted',
}

/** A state always shows its name; the tone only reinforces it, so no meaning rides on color alone. */
export function StatusChip({ value }: { value: string }) {
  const tone = TONES[value] ?? 'neutral'
  return <span className={`chip chip--${tone}`}>{humanize(value)}</span>
}

export function Id({ value, href }: { value: string | null | undefined; href?: string }) {
  if (!value) {
    return <span className="muted">–</span>
  }
  const text = <code title={value}>{shortId(value)}</code>
  return href ? <a href={href}>{text}</a> : text
}

export function Panel({
  title,
  actions,
  children,
  className,
}: {
  title?: ReactNode
  actions?: ReactNode
  children: ReactNode
  className?: string
}) {
  return (
    <section className={`panel ${className ?? ''}`}>
      {(title ?? actions) && (
        <header className="panel__header">
          {title && <h2>{title}</h2>}
          {actions && <div className="panel__actions">{actions}</div>}
        </header>
      )}
      {children}
    </section>
  )
}

export function ErrorNotice({ error, retry }: { error: unknown; retry?: () => void }) {
  const forbidden = error instanceof ApiError && error.status === 403
  return (
    <div className="notice notice--error" role="alert">
      <p>{forbidden ? 'This view needs the operator token. Sign in with it to see it.' : errorMessage(error)}</p>
      {retry && !forbidden && (
        <button type="button" className="button" onClick={retry}>
          Try again
        </button>
      )}
    </div>
  )
}

export function Loading({ label = 'Loading' }: { label?: string }) {
  return (
    <p className="loading" role="status">
      {label}…
    </p>
  )
}

export function Empty({ children }: { children: ReactNode }) {
  return <div className="empty">{children}</div>
}

/**
 * The three states every view needs, in one place: loading, failed (with a retry), and loaded. An empty result is
 * the caller's to describe, since only it knows what action fills it.
 */
export function QueryView<T>({
  query,
  children,
  loadingLabel,
}: {
  query: UseQueryResult<T>
  children: (data: T) => ReactNode
  loadingLabel?: string
}) {
  if (query.isPending) {
    return <Loading {...(loadingLabel ? { label: loadingLabel } : {})} />
  }
  if (query.isError) {
    return (
      <ErrorNotice
        error={query.error}
        retry={() => {
          void query.refetch()
        }}
      />
    )
  }
  return <>{children(query.data)}</>
}

/** A horizontal bar of used over total; the numbers sit beside it, so the bar is never the only reading. */
export function Meter({ label, used, total, unit }: { label: string; used: number; total: number; unit?: string }) {
  const ratio = total === 0 ? 0 : Math.min(1, used / total)
  return (
    <div className="meter">
      <span className="meter__label">{label}</span>
      <span className="meter__track" role="meter" aria-valuemin={0} aria-valuemax={total} aria-valuenow={used} aria-label={label}>
        <span className="meter__fill" style={{ width: `${String(ratio * 100)}%` }} />
      </span>
      <span className="meter__value">
        {used.toLocaleString()} / {total.toLocaleString()}
        {unit ? ` ${unit}` : ''}
      </span>
    </div>
  )
}
