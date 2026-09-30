import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createBrowserRouter, RouterProvider } from 'react-router'
import { AppShell } from './layout/AppShell'
import { OverviewPage } from './pages/OverviewPage'
import './styles.css'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // Operational data goes stale quickly, but a failed poll should not flood a struggling control plane.
      staleTime: 2_000,
      retry: 2,
    },
  },
})

const router = createBrowserRouter([
  {
    element: <AppShell />,
    children: [{ index: true, element: <OverviewPage /> }],
  },
])

const rootElement = document.getElementById('root')
if (!rootElement) {
  throw new Error('index.html is missing the #root element')
}

createRoot(rootElement).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  </StrictMode>,
)
