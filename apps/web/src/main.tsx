import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ApiError } from './api/client'
import { Console } from './app/Console'
import { SessionProvider } from './app/session'
import './styles.css'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // The event stream marks what changed as stale; a short staleTime keeps navigation from refetching for nothing.
      staleTime: 5_000,
      // Only an unreachable control plane is worth retrying; an answered error (403, 404, 409) will answer the same.
      retry: (failures, error) => failures < 2 && error instanceof ApiError && error.status === 0,
    },
  },
})

const rootElement = document.getElementById('root')
if (!rootElement) {
  throw new Error('index.html is missing the #root element')
}

createRoot(rootElement).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <SessionProvider>
        <Console />
      </SessionProvider>
    </QueryClientProvider>
  </StrictMode>,
)
