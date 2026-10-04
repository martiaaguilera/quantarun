import { createContext, useContext } from 'react'
import type { Session } from '../api/endpoints'
import type { StreamState } from '../api/sse'
import type { StreamEvent } from '../api/types'

export interface SessionContextValue {
  session: Session | null
  signIn: (credential: string) => Promise<void>
  signOut: () => void
}

export const SessionContext = createContext<SessionContextValue | null>(null)

export function useSession(): SessionContextValue {
  const value = useContext(SessionContext)
  if (!value) {
    throw new Error('useSession outside SessionProvider')
  }
  return value
}

export interface LiveEventsValue {
  state: StreamState
  events: readonly StreamEvent[]
}

export const LiveEventsContext = createContext<LiveEventsValue>({ state: { kind: 'closed' }, events: [] })

export function useLiveEvents(): LiveEventsValue {
  return useContext(LiveEventsContext)
}

/** How many recent events the console keeps in memory for the feed. */
export const MAX_LIVE_EVENTS = 300
