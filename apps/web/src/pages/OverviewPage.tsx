import { ControlPlaneStatus } from '../features/system/ControlPlaneStatus'

export function OverviewPage() {
  return (
    <>
      <h1>Overview</h1>
      <ControlPlaneStatus />
    </>
  )
}
