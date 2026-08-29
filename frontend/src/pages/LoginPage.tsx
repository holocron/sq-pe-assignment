import { Eye, EyeOff, LogIn, TriangleAlert } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { Navigate, useLocation, useNavigate, useSearchParams } from 'react-router-dom'
import { isApiError } from '../api/errors'
import type { LoginRequest } from '../api/types'
import { useAuth } from '../auth/useAuth'
import { Button } from '../components/ui/Button'
import { Card } from '../components/ui/Card'
import { Input } from '../components/ui/Input'
import { ThemeToggle } from '../components/layout/ThemeToggle'

/** Accounts created by the backend seed — this is a demo/review environment. */
const DEMO_ACCOUNTS: ReadonlyArray<{ credentials: LoginRequest; role: string; note: string }> = [
  {
    credentials: { username: 'admin', password: 'admin123' },
    role: 'Admin',
    note: 'Rules, knowledge base and users',
  },
  {
    credentials: { username: 'operator1', password: 'operator123' },
    role: 'Operator',
    note: 'Customer activity and analyses',
  },
]

interface FieldErrors {
  username?: string
  password?: string
}

function validate(credentials: LoginRequest): FieldErrors {
  const errors: FieldErrors = {}
  if (!credentials.username.trim()) errors.username = 'Enter your username.'
  if (!credentials.password) errors.password = 'Enter your password.'
  return errors
}

/** Turns an API failure into something an operator can act on. */
function signInError(error: unknown): string {
  if (isApiError(error)) {
    if (error.status === 401 || error.status === 403) {
      return 'Incorrect username or password. Check your credentials and try again.'
    }
    if (error.isNetworkError) {
      return error.detail ?? 'The API did not respond. Check that the backend is running.'
    }
    return error.detail?.trim() || error.title
  }
  return 'Sign-in failed. Please try again.'
}

/**
 * Reads the route the operator was originally heading for. `ProtectedRoute`
 * puts it in `location.state.from`; the axios 401 fallback uses `?next=`.
 */
function fromLocationState(state: unknown): string | null {
  if (typeof state !== 'object' || state === null) return null
  const from = (state as { from?: unknown }).from
  if (typeof from === 'string') return from
  if (typeof from !== 'object' || from === null) return null
  const { pathname, search, hash } = from as {
    pathname?: unknown
    search?: unknown
    hash?: unknown
  }
  if (typeof pathname !== 'string' || pathname.length === 0) return null
  return `${pathname}${typeof search === 'string' ? search : ''}${typeof hash === 'string' ? hash : ''}`
}

/**
 * Text stand-in for the Swissquote wordmark. The licensed logo asset is not
 * bundled with this repository, so the brand is rendered as type in the sans
 * stack with the brand-orange mark beside it — see the README.
 */
function Wordmark() {
  return (
    <div className="flex flex-col items-center gap-2">
      <div className="flex items-center gap-2.5">
        <span aria-hidden="true" className="size-4 shrink-0 rounded-xxs bg-accent" />
        <span className="text-xl font-semibold tracking-tight-swiss text-sidebar-fg">
          Swissquote
        </span>
      </div>
      <p className="text-2xs font-semibold tracking-caption text-sidebar-muted uppercase">
        Customer Activity Analytics
      </p>
    </div>
  )
}

export function LoginPage() {
  const { login, isAuthenticated } = useAuth()
  const location = useLocation()
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()

  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  const nextParam = searchParams.get('next')
  const redirectTo = fromLocationState(location.state) ?? (nextParam && nextParam.startsWith('/') ? nextParam : null) ?? '/dashboard'

  async function signIn(credentials: LoginRequest) {
    const errors = validate(credentials)
    setFieldErrors(errors)
    setFormError(null)
    if (errors.username || errors.password) return

    setSubmitting(true)
    try {
      await login({ username: credentials.username.trim(), password: credentials.password })
      navigate(redirectTo, { replace: true })
    } catch (error) {
      setFormError(signInError(error))
    } finally {
      setSubmitting(false)
    }
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    void signIn({ username, password })
  }

  function signInWithDemoAccount(credentials: LoginRequest) {
    setUsername(credentials.username)
    setPassword(credentials.password)
    void signIn(credentials)
  }

  if (isAuthenticated) {
    return <Navigate to={redirectTo} replace />
  }

  return (
    <div className="relative flex min-h-svh flex-col bg-sidebar">
      {/* Brand rule across the top — the one piece of orange in the chrome. */}
      <div aria-hidden="true" className="h-1 w-full shrink-0 bg-accent" />

      <div className="flex justify-end px-4 py-4 sm:px-6">
        <ThemeToggle />
      </div>

      <main className="flex flex-1 items-start justify-center px-4 pb-10 sm:items-center sm:px-6">
        <div className="w-full max-w-md">
          <Wordmark />

          <Card className="mt-7 overflow-hidden shadow-popover">
            <div className="px-6 py-6 sm:px-7">
              <h2 className="text-base font-semibold tracking-tight-swiss text-fg">Sign in</h2>
              <p className="mt-1 text-xs text-muted">
                Use your Swissquote customer-care credentials to continue.
              </p>

              <form onSubmit={handleSubmit} noValidate className="mt-5 flex flex-col gap-4">
                {formError ? (
                  <div
                    role="alert"
                    className="flex items-start gap-2 rounded-xs border border-danger/30 bg-danger-soft px-3 py-2 text-xs text-danger-fg"
                  >
                    <TriangleAlert aria-hidden="true" className="mt-px size-3.5 shrink-0" />
                    <span>{formError}</span>
                  </div>
                ) : null}

                <Input
                  label="Username"
                  name="username"
                  autoComplete="username"
                  autoFocus
                  required
                  value={username}
                  error={fieldErrors.username ?? null}
                  onChange={(event) => {
                    setUsername(event.target.value)
                    if (fieldErrors.username) setFieldErrors((current) => ({ ...current, username: undefined }))
                  }}
                  placeholder="operator1"
                  className="h-9"
                />

                <Input
                  label="Password"
                  name="password"
                  type={showPassword ? 'text' : 'password'}
                  autoComplete="current-password"
                  required
                  value={password}
                  error={fieldErrors.password ?? null}
                  onChange={(event) => {
                    setPassword(event.target.value)
                    if (fieldErrors.password) setFieldErrors((current) => ({ ...current, password: undefined }))
                  }}
                  className="h-9"
                  iconRight={
                    <button
                      type="button"
                      onClick={() => setShowPassword((current) => !current)}
                      aria-label={showPassword ? 'Hide password' : 'Show password'}
                      className="rounded-xs p-0.5 text-subtle transition-colors hover:text-fg"
                    >
                      {showPassword ? (
                        <EyeOff aria-hidden="true" className="size-4" />
                      ) : (
                        <Eye aria-hidden="true" className="size-4" />
                      )}
                    </button>
                  }
                />

                <Button
                  type="submit"
                  variant="primary"
                  size="lg"
                  fullWidth
                  className="mt-1"
                  loading={submitting}
                  iconLeft={submitting ? undefined : <LogIn className="size-4" />}
                >
                  {submitting ? 'Signing in…' : 'Sign in'}
                </Button>
              </form>
            </div>

            {/* Demo credentials: a clearly-marked panel, not loose text. */}
            <section
              aria-labelledby="demo-credentials-heading"
              className="border-t border-border bg-surface-2/60 px-6 py-4 sm:px-7"
            >
              <div className="flex items-baseline justify-between gap-3">
                <h3
                  id="demo-credentials-heading"
                  className="text-2xs font-semibold tracking-caption text-muted uppercase"
                >
                  Demo credentials
                </h3>
                <span className="text-2xs text-subtle">Seeded review environment</span>
              </div>

              <ul className="mt-2.5 divide-y divide-border overflow-hidden rounded-xs border border-border bg-surface">
                {DEMO_ACCOUNTS.map((account) => (
                  <li
                    key={account.credentials.username}
                    className="flex items-center justify-between gap-3 px-3 py-2"
                  >
                    <span className="min-w-0">
                      <span className="block truncate font-mono text-xs text-fg">
                        {account.credentials.username}
                        <span className="px-1 text-subtle">/</span>
                        {account.credentials.password}
                      </span>
                      <span className="block truncate text-2xs text-subtle">
                        {account.role} — {account.note}
                      </span>
                    </span>
                    <Button
                      size="sm"
                      variant="secondary"
                      disabled={submitting}
                      onClick={() => signInWithDemoAccount(account.credentials)}
                    >
                      Use
                    </Button>
                  </li>
                ))}
              </ul>
            </section>
          </Card>

          <p className="mt-6 text-center text-2xs text-sidebar-muted">
            Internal Swissquote tool. Access is role-restricted and logged.
          </p>
        </div>
      </main>
    </div>
  )
}
