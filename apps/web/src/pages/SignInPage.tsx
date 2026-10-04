import { useState, type SyntheticEvent } from 'react'
import { useSession } from '../app/context'
import { errorMessage } from '../components/errors'

export function SignInPage() {
  const { signIn } = useSession()
  const [credential, setCredential] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const submit = (event: SyntheticEvent) => {
    event.preventDefault()
    setBusy(true)
    setError(null)
    signIn(credential.trim())
      .catch((failure: unknown) => {
        setError(errorMessage(failure))
      })
      .finally(() => {
        setBusy(false)
      })
  }

  return (
    <main className="sign-in">
      <form className="sign-in__form" onSubmit={submit}>
        <h1>QuantaRun</h1>
        <p className="muted">
          Sign in with the operator token to see every project and the fleet, or with a project API key to see that
          project's jobs. The key stays in this tab and is forgotten when you close it.
        </p>
        <label htmlFor="credential">Token or API key</label>
        <input
          id="credential"
          type="password"
          autoComplete="off"
          value={credential}
          onChange={(event) => {
            setCredential(event.target.value)
          }}
          required
        />
        {error && (
          <p className="form-error" role="alert">
            {error}
          </p>
        )}
        <button type="submit" className="button button--primary" disabled={busy || credential.trim() === ''}>
          {busy ? 'Signing in…' : 'Sign in'}
        </button>
      </form>
    </main>
  )
}
