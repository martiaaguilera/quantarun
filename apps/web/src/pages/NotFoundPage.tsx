import { Link } from 'react-router'
import { Empty } from '../components/ui'

export function NotFoundPage() {
  return (
    <>
      <h1>Page not found</h1>
      <Empty>
        Nothing lives at this address. Go to the <Link to="/">overview</Link> or the <Link to="/jobs">job list</Link>.
      </Empty>
    </>
  )
}
