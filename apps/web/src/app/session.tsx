import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { onUnauthorized, storeCredential, storedCredential } from '../api/client'
import { api, keys, type Session } from '../api/endpoints'
import { LiveEventsContext, MAX_LIVE_EVENTS, SessionContext } from './context'
import { openEventStream, type StreamState } from '../api/sse'
import type { StreamEvent } from '../api/types'

export function SessionProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient()
  const [session, setSession] = useState<Session | null>(null)
  const [checking, setChecking] = useState(() => storedCredential() !== null)

  const signOut = useCallback(() => {
    storeCredential(null)
    setSession(null)
    queryClient.clear()
  }, [queryClient])

  const signIn = useCallback(async (credential: string) => {
    storeCredential(credential)
    try {
      setSession(await api.session())
    } catch (error) {
      storeCredential(null)
      throw error
    }
  }, [])

  // A credential from earlier in this tab is checked once on load; a revoked key lands back on sign-in.
  useEffect(() => {
    if (!checking) {
      return
    }
    api
      .session()
      .then(setSession)
      .catch(() => {
        storeCredential(null)
      })
      .finally(() => {
        setChecking(false)
      })
  }, [checking])

  useEffect(() => onUnauthorized(signOut), [signOut])

  const value = useMemo(() => ({ session, signIn, signOut }), [session, signIn, signOut])
  if (checking) {
    return null
  }
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>
}

/** Queries touched by events are refreshed at most this often, however busy the stream. */
const INVALIDATE_EVERY_MS = 1_000

/**
 * One stream per tab. Events are kept for the feed, and they mark the affected queries stale (throttled), so views
 * refresh from the API when something changed instead of polling everything on a timer.
 */
export function LiveEventsProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient()
  const [state, setState] = useState<StreamState>({ kind: 'connecting' })
  const [events, setEvents] = useState<readonly StreamEvent[]>([])
  const pendingJobs = useRef(new Set<string>())
  const flushTimer = useRef<ReturnType<typeof setTimeout> | null>(null)

  useEffect(() => {
    const flush = () => {
      flushTimer.current = null
      const jobIds = [...pendingJobs.current]
      pendingJobs.current.clear()
      void queryClient.invalidateQueries({ queryKey: keys.overview })
      void queryClient.invalidateQueries({ queryKey: keys.jobsAll })
      void queryClient.invalidateQueries({ queryKey: keys.workers })
      jobIds.forEach((id) => void queryClient.invalidateQueries({ queryKey: keys.job(id) }))
    }
    const stop = openEventStream({
      url: '/api/v1/events/stream',
      credential: storedCredential,
      onState: setState,
      onMessage: (message) => {
        if (message.event === 'reset') {
          // Too much was missed to replay: everything on screen may be stale.
          void queryClient.invalidateQueries()
          return
        }
        if (message.event !== 'job') {
          return
        }
        let event: StreamEvent
        try {
          event = JSON.parse(message.data) as StreamEvent
        } catch {
          return
        }
        setEvents((current) => [event, ...current].slice(0, MAX_LIVE_EVENTS))
        pendingJobs.current.add(event.jobId)
        flushTimer.current ??= setTimeout(flush, INVALIDATE_EVERY_MS)
      },
    })
    return () => {
      stop()
      if (flushTimer.current !== null) {
        clearTimeout(flushTimer.current)
      }
    }
  }, [queryClient])

  const value = useMemo(() => ({ state, events }), [state, events])
  return <LiveEventsContext.Provider value={value}>{children}</LiveEventsContext.Provider>
}
