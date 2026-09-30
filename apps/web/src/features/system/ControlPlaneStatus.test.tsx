import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { vi } from 'vitest'
import { ControlPlaneStatus } from './ControlPlaneStatus'

function renderWithFreshClient() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <ControlPlaneStatus />
    </QueryClientProvider>,
  )
}

function stubProbes(responses: Record<string, { status: number; body: unknown }>) {
  vi.stubGlobal(
    'fetch',
    vi.fn((input: string) => {
      const probe = input.split('/').at(-1) ?? ''
      const response = responses[probe]
      if (!response) {
        return Promise.reject(new TypeError('network down'))
      }
      return Promise.resolve(new Response(JSON.stringify(response.body), { status: response.status }))
    }),
  )
}

describe('ControlPlaneStatus', () => {
  it('shows each probe status as reported by actuator', async () => {
    stubProbes({
      liveness: { status: 200, body: { status: 'UP' } },
      readiness: { status: 503, body: { status: 'DOWN' } },
    })

    renderWithFreshClient()

    expect(await screen.findByText('UP')).toBeInTheDocument()
    expect(await screen.findByText('DOWN')).toBeInTheDocument()
  })

  it('reports an unreachable control plane instead of a stale status', async () => {
    stubProbes({})

    renderWithFreshClient()

    await waitFor(() => {
      expect(screen.getAllByText('unreachable')).toHaveLength(2)
    })
  })

  it('treats an unexpected body as an error rather than guessing a status', async () => {
    stubProbes({
      liveness: { status: 200, body: { unexpected: true } },
      readiness: { status: 200, body: { status: 'UP' } },
    })

    renderWithFreshClient()

    expect(await screen.findByText('unreachable')).toBeInTheDocument()
    expect(screen.getByText(/unrecognised body/)).toBeInTheDocument()
  })
})
