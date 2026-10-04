import { render } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactElement } from 'react'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { vi } from 'vitest'
import type { Session } from '../api/endpoints'
import { LiveEventsContext, SessionContext } from '../app/context'

/** Answers API paths from a table; anything unlisted is a 404 Problem Detail, so a test notices a missed call. */
export function stubApi(routes: Record<string, { status?: number; body: unknown }>) {
  const fetchMock = vi.fn((input: string) => {
    const path = input.split('?')[0] ?? input
    const route = routes[path] ?? routes[input]
    if (!route) {
      return Promise.resolve(new Response(JSON.stringify({ detail: `No stub for ${input}` }), { status: 404 }))
    }
    return Promise.resolve(new Response(JSON.stringify(route.body), { status: route.status ?? 200 }))
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

export function renderPage(element: ReactElement, options: { session?: Session; path?: string; route?: string } = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const session = options.session ?? { role: 'OPERATOR', projectId: null }
  const router = createMemoryRouter([{ path: options.route ?? '*', element }], {
    initialEntries: [options.path ?? '/'],
  })
  return render(
    <QueryClientProvider client={client}>
      <SessionContext.Provider value={{ session, signIn: () => Promise.resolve(), signOut: () => undefined }}>
        <LiveEventsContext.Provider value={{ state: { kind: 'open' }, events: [] }}>
          <RouterProvider router={router} />
        </LiveEventsContext.Provider>
      </SessionContext.Provider>
    </QueryClientProvider>,
  )
}
