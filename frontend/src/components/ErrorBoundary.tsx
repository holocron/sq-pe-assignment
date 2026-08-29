import { RotateCw, TriangleAlert } from 'lucide-react'
import { Component, type ErrorInfo, type ReactNode } from 'react'
import { Outlet, useLocation } from 'react-router-dom'
import { errorMessage } from '../api/errors'
import { cn } from '../lib/cn'
import { Button } from './ui/Button'
import { Card } from './ui/Card'
import { LinkButton } from './ui/LinkButton'
import { buttonClasses } from './ui/buttonStyles'

export interface ErrorFallbackProps {
  /** Whatever was thrown during render — usually, but not necessarily, an `Error`. */
  error: unknown
  /** React's component stack for the throwing subtree, when it supplied one. */
  componentStack: string | null
  /** Clears the caught error and re-renders the subtree. */
  reset: () => void
}

export type ErrorBoundaryFallback = ReactNode | ((props: ErrorFallbackProps) => ReactNode)

export interface ErrorBoundaryProps {
  children?: ReactNode
  /** Defaults to the full-page `<AppErrorPanel />`. */
  fallback?: ErrorBoundaryFallback
  /** Any change clears a caught error — pass the pathname to recover on navigation. */
  resetKeys?: readonly unknown[]
  onError?: (error: unknown, info: ErrorInfo) => void
}

interface ErrorBoundaryState {
  hasError: boolean
  error: unknown
  componentStack: string | null
}

const NO_ERROR: ErrorBoundaryState = { hasError: false, error: null, componentStack: null }

function keysChanged(previous: readonly unknown[] = [], next: readonly unknown[] = []): boolean {
  return previous.length !== next.length || previous.some((key, i) => !Object.is(key, next[i]))
}

/**
 * Catches render-time exceptions so a single broken component cannot unmount
 * the application.
 *
 * React 19 unmounts the whole root when a render throws with nothing above it
 * to catch, which turns any field-name drift between the API and a component
 * into a blank white page. Two boundaries are mounted (see `App.tsx` and
 * `main.tsx`): one around the routed content, so a crashing screen keeps the
 * sidebar and top bar alive, and one around the entire tree as the last resort.
 *
 * `hasError` is tracked separately from `error` because `throw null` and
 * `throw undefined` are legal and must not be mistaken for "no error".
 */
export class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  state: ErrorBoundaryState = NO_ERROR

  static getDerivedStateFromError(error: unknown): Partial<ErrorBoundaryState> {
    return { hasError: true, error }
  }

  componentDidCatch(error: unknown, info: ErrorInfo): void {
    this.setState({ componentStack: info.componentStack ?? null })
    // An operator cannot open a debugger; the console is the only breadcrumb.
    console.error('[ErrorBoundary] render failed', error, info.componentStack)
    this.props.onError?.(error, info)
  }

  componentDidUpdate(previous: ErrorBoundaryProps): void {
    if (this.state.hasError && keysChanged(previous.resetKeys, this.props.resetKeys)) {
      this.reset()
    }
  }

  reset = (): void => {
    this.setState(NO_ERROR)
  }

  render(): ReactNode {
    const { hasError, error, componentStack } = this.state
    if (!hasError) return this.props.children

    const { fallback } = this.props
    const props: ErrorFallbackProps = { error, componentStack, reset: this.reset }
    if (typeof fallback === 'function') return fallback(props)
    if (fallback !== undefined) return fallback
    return <AppErrorPanel {...props} />
  }
}

/** The thrown message plus a collapsed stack — enough for a bug report. */
function ErrorDetails({ error, componentStack }: Omit<ErrorFallbackProps, 'reset'>) {
  const stack = error instanceof Error && error.stack ? error.stack : null
  const trace = [stack, componentStack].filter(Boolean).join('\n')
  return (
    <div className="min-w-0">
      <p className="rounded-xs border border-border bg-surface-2/50 px-3 py-2 font-mono text-xs break-words text-fg">
        {errorMessage(error)}
      </p>
      {trace ? (
        <details className="mt-2">
          <summary className="cursor-pointer text-xs text-muted hover:text-fg">
            Technical details
          </summary>
          <pre className="mt-2 max-h-56 overflow-auto rounded-xs bg-surface-2/50 px-3 py-2 font-mono text-2xs leading-relaxed whitespace-pre-wrap text-subtle">
            {trace}
          </pre>
        </details>
      ) : null}
    </div>
  )
}

function PanelHeading({ title, description }: { title: string; description: string }) {
  return (
    <div className="flex items-start gap-3">
      <span
        aria-hidden="true"
        className="mt-0.5 flex size-9 shrink-0 items-center justify-center rounded-full bg-danger-soft text-danger-fg"
      >
        <TriangleAlert className="size-4" />
      </span>
      <div className="min-w-0">
        <h2 className="text-sm font-semibold tracking-tight-swiss text-fg">{title}</h2>
        <p className="mt-0.5 text-xs text-muted">{description}</p>
      </div>
    </div>
  )
}

/**
 * Fallback for a crash inside a routed screen. Rendered into the `AppShell`
 * outlet, so the sidebar, top bar and navigation all survive.
 */
export function RouteErrorPanel({ error, componentStack, reset }: ErrorFallbackProps) {
  return (
    <Card role="alert" className="mx-auto max-w-2xl">
      <div className="border-b border-border px-4 py-3">
        <PanelHeading
          title="This screen could not be displayed"
          description="The rest of the application is still running. Retry the screen, or go back to the dashboard and continue from there."
        />
      </div>
      <div className="px-4 py-3.5">
        <ErrorDetails error={error} componentStack={componentStack} />
      </div>
      <div className="flex flex-wrap items-center justify-end gap-2 border-t border-border bg-surface-2/40 px-4 py-2.5">
        {/* `reset` as well as the navigation: the boundary resets on a
            pathname change, but the operator may already be on /dashboard. */}
        <LinkButton to="/dashboard" size="sm" onClick={reset}>
          Back to dashboard
        </LinkButton>
        <Button
          variant="primary"
          size="sm"
          onClick={reset}
          iconLeft={<RotateCw className="size-3.5" />}
        >
          Try again
        </Button>
      </div>
    </Card>
  )
}

/**
 * Last-resort fallback for a crash outside the routed content — the shell, a
 * provider or the root itself. It uses no router primitives, so it renders
 * correctly even when the crash happened above `<BrowserRouter>`.
 */
export function AppErrorPanel({ error, componentStack, reset }: ErrorFallbackProps) {
  return (
    <div className="flex min-h-svh items-center justify-center bg-bg p-6">
      <Card role="alert" className="w-full max-w-2xl">
        <div className="flex items-center gap-2.5 border-b border-border px-4 py-3">
          <span aria-hidden="true" className="size-4 shrink-0 rounded-xxs bg-accent" />
          <span className="leading-tight">
            <span className="block text-sm font-semibold tracking-tight-swiss text-fg">
              Swissquote
            </span>
            <span className="block text-2xs text-muted">Customer Activity Analytics</span>
          </span>
        </div>
        <div className="px-4 py-3.5">
          <PanelHeading
            title="The application stopped unexpectedly"
            description="An unrecoverable error occurred outside the current screen. Reloading restores the application; no data was changed."
          />
          <div className="mt-3.5 pl-12">
            <ErrorDetails error={error} componentStack={componentStack} />
          </div>
        </div>
        <div className="flex flex-wrap items-center justify-between gap-2 border-t border-border bg-surface-2/40 px-4 py-2.5">
          <Button variant="link" size="sm" onClick={reset}>
            Try again without reloading
          </Button>
          <div className="flex items-center gap-2">
            <a href="/dashboard" className={cn(buttonClasses({ size: 'sm' }))}>
              Back to dashboard
            </a>
            <Button
              variant="primary"
              size="sm"
              onClick={() => window.location.reload()}
              iconLeft={<RotateCw className="size-3.5" />}
            >
              Reload the application
            </Button>
          </div>
        </div>
      </Card>
    </div>
  )
}

/**
 * Pathless layout route that wraps every routed screen in a boundary.
 *
 * The boundary sits *inside* `AppShell`, so a screen-level crash is contained
 * to the outlet, and it resets on navigation: following "Back to dashboard"
 * clears the error without a full page reload.
 */
export function RouteErrorBoundary() {
  const { pathname } = useLocation()
  return (
    <ErrorBoundary resetKeys={[pathname]} fallback={(props) => <RouteErrorPanel {...props} />}>
      <Outlet />
    </ErrorBoundary>
  )
}
