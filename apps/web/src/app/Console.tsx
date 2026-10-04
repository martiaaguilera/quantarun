import { createBrowserRouter, RouterProvider } from 'react-router'
import { AppShell } from '../layout/AppShell'
import { ChaosLabPage, ExperimentPage } from '../pages/ChaosLabPage'
import { DeadJobsPage } from '../pages/DeadJobsPage'
import { EventsPage } from '../pages/EventsPage'
import { JobDetailPage } from '../pages/JobDetailPage'
import { JobsPage } from '../pages/JobsPage'
import { NotFoundPage } from '../pages/NotFoundPage'
import { OverviewPage } from '../pages/OverviewPage'
import { PolicyLabPage } from '../pages/PolicyLabPage'
import { SchedulerPage } from '../pages/SchedulerPage'
import { SettingsPage } from '../pages/SettingsPage'
import { SignInPage } from '../pages/SignInPage'
import { WorkersPage } from '../pages/WorkersPage'
import { useSession } from './context'
import { LiveEventsProvider } from './session'

const router = createBrowserRouter([
  {
    element: <AppShell />,
    children: [
      { index: true, element: <OverviewPage /> },
      { path: 'jobs', element: <JobsPage /> },
      { path: 'jobs/:jobId', element: <JobDetailPage /> },
      { path: 'dead', element: <DeadJobsPage /> },
      { path: 'workers', element: <WorkersPage /> },
      { path: 'scheduler', element: <SchedulerPage /> },
      { path: 'events', element: <EventsPage /> },
      { path: 'policy-lab', element: <PolicyLabPage /> },
      { path: 'chaos', element: <ChaosLabPage /> },
      { path: 'chaos/:experimentId', element: <ExperimentPage /> },
      { path: 'settings', element: <SettingsPage /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
])

export function Console() {
  const { session } = useSession()
  if (!session) {
    return <SignInPage />
  }
  return (
    <LiveEventsProvider>
      <RouterProvider router={router} />
    </LiveEventsProvider>
  )
}
