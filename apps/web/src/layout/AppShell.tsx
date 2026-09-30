import { NavLink, Outlet } from 'react-router'

// Navigation lists only views that exist. Entries are added as each phase ships its view, so the console never
// shows a page without real data behind it.
const NAVIGATION = [{ to: '/', label: 'Overview' }] as const

export function AppShell() {
  return (
    <div className="shell">
      <header className="shell__header">
        <span className="shell__product">QuantaRun</span>
        <span className="shell__tagline">workload control plane</span>
      </header>
      <nav className="shell__nav" aria-label="Primary">
        <ul>
          {NAVIGATION.map((item) => (
            <li key={item.to}>
              <NavLink to={item.to} end>
                {item.label}
              </NavLink>
            </li>
          ))}
        </ul>
      </nav>
      <main className="shell__main">
        <Outlet />
      </main>
    </div>
  )
}
