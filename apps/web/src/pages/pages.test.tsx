import { fireEvent, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { Job } from '../api/types'
import { renderPage, stubApi } from '../test/render'
import { JobsPage } from './JobsPage'
import { OverviewPage } from './OverviewPage'
import { SignInPage } from './SignInPage'

const job: Job = {
  id: '01a106e1-fdc4-79e3-a1fb-13837d517060',
  projectId: 'p',
  workloadType: 'mock-inference',
  payload: {},
  status: 'RETRY_WAIT',
  priority: 4,
  resources: { cpuMillis: 500, memoryMib: 256, accelerators: 0 },
  requiredLabels: [],
  maxAttempts: 3,
  attemptCount: 1,
  attemptsInBudget: 1,
  reviveCount: 0,
  timeoutSeconds: 60,
  availableAt: new Date().toISOString(),
  deadline: null,
  idempotencyKey: null,
  cancelRequestedAt: null,
  schedulingOutcome: 'PLACED',
  schedulingReason: 'Placed on worker-cpu by FIFO',
  createdAt: new Date().toISOString(),
  updatedAt: new Date().toISOString(),
  finishedAt: null,
  traceId: null,
}

describe('JobsPage', () => {
  it('lists jobs with their state, attempts and latest verdict, linking to each job', async () => {
    stubApi({ '/api/v1/jobs': { body: { items: [job], nextBefore: null } }, '/api/v1/workers': { body: [] } })

    renderPage(<JobsPage />, { path: '/jobs' })

    // "Retry wait" is also a filter option, so wait for the row itself first.
    expect(await screen.findByRole('link', { name: '01a106e1' })).toHaveAttribute('href', `/jobs/${job.id}`)
    const row = screen.getByRole('link', { name: '01a106e1' }).closest('tr')
    expect(row).toHaveTextContent('Retry wait')
    expect(row).toHaveTextContent('1 / 3')
    expect(row).toHaveTextContent('Placed on worker-cpu by FIFO')
  })

  it('says how to get out of an empty filtered result', async () => {
    stubApi({ '/api/v1/jobs': { body: { items: [], nextBefore: null } }, '/api/v1/workers': { body: [] } })

    renderPage(<JobsPage />, { path: '/jobs?status=DEAD' })

    expect(await screen.findByText(/No job matches these filters/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Clear filters' })).toBeInTheDocument()
  })

  it('shows the server error with a way to retry', async () => {
    stubApi({ '/api/v1/jobs': { status: 503, body: { detail: 'Database unavailable.' } }, '/api/v1/workers': { body: [] } })

    renderPage(<JobsPage />, { path: '/jobs' })

    expect(await screen.findByRole('alert')).toHaveTextContent('Database unavailable.')
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument()
  })
})

describe('OverviewPage', () => {
  const overview = {
    jobs: {
      byStatus: { QUEUED: 3, RETRY_WAIT: 1, SCHEDULED: 2, RUNNING: 4 },
      queued: 4,
      running: 6,
      succeededLastHour: 9,
      failedLastHour: 1,
      deadLastHour: 0,
      cancelledLastHour: 3,
      successRate: 0.9,
      retriesLastHour: 2,
      timeToStartP95Seconds: 1.25,
    },
    fleet: null,
    generatedAt: new Date().toISOString(),
  }

  it('shows a project its numbers, and explains that the fleet is for the operator', async () => {
    stubApi({ '/api/v1/overview': { body: overview } })

    renderPage(<OverviewPage />, { session: { role: 'PROJECT', projectId: 'p' } })

    expect(await screen.findByText('90.0 %')).toBeInTheDocument()
    expect(screen.getByText('1.25 s')).toBeInTheDocument()
    expect(screen.getByText(/Finished in the last hour: 9 succeeded, 1 failed, 0 dead, 3 cancelled/)).toBeInTheDocument()
    expect(screen.getByText(/sign in with the operator token to see it/)).toBeInTheDocument()
  })
})

describe('SignInPage', () => {
  it('shows why a credential was refused', async () => {
    stubApi({ '/api/v1/session': { status: 401, body: { detail: 'Unknown API key.' } } })
    const { SessionProvider } = await import('../app/session')
    const { QueryClient, QueryClientProvider } = await import('@tanstack/react-query')
    const { render } = await import('@testing-library/react')

    render(
      <QueryClientProvider client={new QueryClient()}>
        <SessionProvider>
          <SignInPage />
        </SessionProvider>
      </QueryClientProvider>,
    )
    fireEvent.change(screen.getByLabelText('Token or API key'), { target: { value: 'qr_wrong' } })
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Unknown API key.')
  })
})
