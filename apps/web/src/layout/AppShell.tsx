import { NavLink, Outlet } from 'react-router'
import { useLiveEvents, useSession } from '../app/context'
import { formatMs, shortId } from '../format'

// Grouped by what an operator is doing: watching the system, experimenting with it, or checking how it is configured.
const NAVIGATION = [
  {
    group: 'Operate',
    items: [
      { to: '/', label: 'Overview', end: true },
      { to: '/jobs', label: 'Jobs' },
      { to: '/dead', label: 'Dead jobs' },
      { to: '/workers', label: 'Workers' },
      { to: '/scheduler', label: 'Scheduler' },
      { to: '/events', label: 'Events' },
    ],
  },
  {
    group: 'Experiment',
    items: [
      { to: '/policy-lab', label: 'Policy lab' },
      { to: '/chaos', label: 'Chaos lab' },
    ],
  },
  { group: 'Configure', items: [{ to: '/settings', label: 'Settings' }] },
] as const

function StreamIndicator() {
  const { state } = useLiveEvents()
  let label: string
  let tone: string
  switch (state.kind) {
    case 'open':
      label = 'Live'
      tone = 'good'
      break
    case 'connecting':
      label = 'Connecting'
      tone = 'neutral'
      break
    case 'retrying':
      label = `Reconnecting in ${formatMs(state.inMs)}`
      tone = 'warn'
      break
    case 'closed':
      label = 'Offline'
      tone = 'muted'
      break
  }
  return (
    <span className={`stream stream--${tone}`} title={state.kind === 'retrying' ? state.reason : undefined}>
      {label}
    </span>
  )
}

export function AppShell() {
  const { session, signOut } = useSession()
  return (
    <div className="shell">
      <header className="shell__header">
        <span className="shell__product">QuantaRun</span>
        <StreamIndicator />
        <span className="shell__identity">
          {session?.role === 'OPERATOR' ? 'Operator' : `Project ${shortId(session?.projectId)}`}
        </span>
        <button type="button" className="link-button" onClick={signOut}>
          Sign out
        </button>
      </header>
      <nav className="shell__nav" aria-label="Primary">
        {NAVIGATION.map((section) => (
          <div key={section.group} className="nav-group">
            <p className="nav-group__title">{section.group}</p>
            <ul>
              {section.items.map((item) => (
                <li key={item.to}>
                  <NavLink to={item.to} end={'end' in item}>
                    {item.label}
                  </NavLink>
                </li>
              ))}
            </ul>
          </div>
        ))}
      </nav>
      <main className="shell__main">
        <Outlet />
      </main>
    </div>
  )
}
