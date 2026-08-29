/**
 * The application-wide crash guard.
 *
 * React 19 unmounts the whole root when a render throws with nothing above it
 * to catch, which turned any API/UI field-name drift into a blank white page.
 * These tests pin the two guarantees: a crashing screen is contained to the
 * routed outlet (the chrome survives and the operator can recover), and the
 * boundary really is mounted in `App.tsx` — not just available to import.
 */
import { fireEvent, render as rtlRender, screen, waitFor, within } from '@testing-library/react'
import type { ReactElement } from 'react'
import { MemoryRouter, Outlet, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { User } from '../../api/types'
import { writeStoredAuth } from '../../auth/storage'
import App, { queryClient } from '../../App'
import { ErrorBoundary, RouteErrorBoundary } from '../ErrorBoundary'

const { getJsonMock } = vi.hoisted(() => ({ getJsonMock: vi.fn() }))

vi.mock('../../api/client', async () => {
  const actual = await vi.importActual<typeof import('../../api/client')>('../../api/client')
  return { ...actual, getJson: getJsonMock }
})

/* The screen under test is the one the CRITICAL findings crashed on: a field
   the API never sends, dereferenced without a guard. */
vi.mock('../../pages/DashboardPage', () => ({
  DashboardPage: () => {
    throw new TypeError("Cannot read properties of undefined (reading 'length')")
  },
}))

const CRASH_MESSAGE = "Cannot read properties of undefined (reading 'length')"

let failing = true

function Flaky() {
  if (failing) throw new TypeError(CRASH_MESSAGE)
  return <p>Recovered screen</p>
}

/* When a boundary catches a throw that happened during a *concurrent* render,
   React re-renders the root synchronously and reports the original throw to the
   root's `onRecoverableError`. The default implementation calls the global
   `reportError`, which Vitest counts as an unhandled exception and turns into a
   non-zero exit code even though every test passed. The throws here are
   deliberate, so the root collects them instead — and `afterEach` asserts that
   every collected error really is the planted crash, so an unrelated React
   recoverable error still fails this file. */
const recoverableErrors: unknown[] = []

function render(ui: ReactElement) {
  return rtlRender(ui, { onRecoverableError: (error) => void recoverableErrors.push(error) })
}

function isPlantedCrash(error: unknown): boolean {
  if (error === null) return true // the deliberate `throw null` case
  if (typeof error !== 'object') return false
  const { message, cause } = error as { message?: unknown; cause?: unknown }
  if (typeof message === 'string' && message.includes(CRASH_MESSAGE)) return true
  return cause !== undefined && isPlantedCrash(cause)
}

beforeEach(() => {
  failing = true
  recoverableErrors.length = 0
  // React logs every caught error itself; keep the suite output readable.
  vi.spyOn(console, 'error').mockImplementation(() => {})
})

afterEach(() => {
  const unexpected = recoverableErrors.filter((error) => !isPlantedCrash(error))
  recoverableErrors.length = 0
  expect(unexpected).toEqual([])
})

describe('ErrorBoundary', () => {
  it('renders its children while nothing throws', () => {
    failing = false
    render(
      <ErrorBoundary>
        <Flaky />
      </ErrorBoundary>,
    )
    expect(screen.getByText('Recovered screen')).toBeInTheDocument()
  })

  it('renders the full-page panel with the thrown message instead of unmounting', () => {
    render(
      <ErrorBoundary>
        <Flaky />
      </ErrorBoundary>,
    )
    const alert = screen.getByRole('alert')
    expect(alert).toHaveTextContent('The application stopped unexpectedly')
    expect(alert).toHaveTextContent(CRASH_MESSAGE)
    expect(screen.getByRole('button', { name: /Reload the application/ })).toBeInTheDocument()
    expect(screen.queryByText('Recovered screen')).not.toBeInTheDocument()
  })

  it('reports the error to the caller and to the console', () => {
    const onError = vi.fn()
    render(
      <ErrorBoundary onError={onError}>
        <Flaky />
      </ErrorBoundary>,
    )
    expect(onError).toHaveBeenCalledTimes(1)
    expect(onError.mock.calls[0][0]).toBeInstanceOf(TypeError)
    expect(console.error).toHaveBeenCalled()
  })

  it('holds the fallback for a value that is falsy when thrown', () => {
    function ThrowsNull(): never {
      throw null
    }
    render(
      <ErrorBoundary>
        <ThrowsNull />
      </ErrorBoundary>,
    )
    expect(screen.getByRole('alert')).toHaveTextContent('The application stopped unexpectedly')
  })

  it('hands a custom fallback the error and a working reset', () => {
    render(
      <ErrorBoundary
        fallback={({ error, reset }) => (
          <div>
            <p>custom: {(error as Error).message}</p>
            <button onClick={reset}>Retry</button>
          </div>
        )}
      >
        <Flaky />
      </ErrorBoundary>,
    )
    expect(screen.getByText(`custom: ${CRASH_MESSAGE}`)).toBeInTheDocument()

    failing = false
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }))
    expect(screen.getByText('Recovered screen')).toBeInTheDocument()
  })

  it('clears a caught error when a reset key changes', () => {
    const { rerender } = render(
      <ErrorBoundary resetKeys={['/customers/1']}>
        <Flaky />
      </ErrorBoundary>,
    )
    expect(screen.getByRole('alert')).toBeInTheDocument()

    failing = false
    rerender(
      <ErrorBoundary resetKeys={['/dashboard']}>
        <Flaky />
      </ErrorBoundary>,
    )
    expect(screen.getByText('Recovered screen')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
})

/** Stand-in for `AppShell`: the chrome that must survive a screen crash. */
function Chrome() {
  return (
    <div>
      <nav aria-label="Main">
        <a href="/dashboard">Dashboard</a>
      </nav>
      <main>
        <Outlet />
      </main>
    </div>
  )
}

function renderRoutes(path: string, dashboard = <p>Dashboard screen</p>) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route element={<Chrome />}>
          <Route element={<RouteErrorBoundary />}>
            <Route path="customers/:customerId" element={<Flaky />} />
            <Route path="dashboard" element={dashboard} />
          </Route>
        </Route>
      </Routes>
    </MemoryRouter>,
  )
}

describe('RouteErrorBoundary', () => {
  it('contains a screen crash to the outlet and keeps the chrome usable', () => {
    renderRoutes('/customers/abc')

    const nav = screen.getByRole('navigation', { name: 'Main' })
    expect(within(nav).getByRole('link', { name: 'Dashboard' })).toBeInTheDocument()

    const alert = screen.getByRole('alert')
    expect(alert).toHaveTextContent('This screen could not be displayed')
    expect(alert).toHaveTextContent(CRASH_MESSAGE)
  })

  it('re-renders the screen when the operator retries', () => {
    renderRoutes('/customers/abc')
    failing = false
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(screen.getByText('Recovered screen')).toBeInTheDocument()
  })

  it('recovers when the crashed screen is the dashboard itself', () => {
    // The link target is the current path, so navigation alone changes no
    // reset key — the panel must clear the error on its way out regardless.
    renderRoutes('/dashboard', <Flaky />)
    expect(screen.getByRole('alert')).toBeInTheDocument()

    failing = false
    fireEvent.click(screen.getByRole('link', { name: 'Back to dashboard' }))
    expect(screen.getByText('Recovered screen')).toBeInTheDocument()
  })

  it('recovers on navigation without a page reload', async () => {
    renderRoutes('/customers/abc')
    fireEvent.click(screen.getByRole('link', { name: 'Back to dashboard' }))
    expect(await screen.findByText('Dashboard screen')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
})

describe('mounted in the application', () => {
  const OPERATOR: User = { username: 'operator1', fullName: 'Olive Operator', role: 'OPERATOR' }

  beforeEach(() => {
    queryClient.clear()
    getJsonMock.mockImplementation((url: string) =>
      url === '/auth/me' ? Promise.resolve(OPERATOR) : Promise.resolve({}),
    )
    writeStoredAuth({ token: 'test-token', expiresAt: null, user: OPERATOR })
  })

  it('keeps the sidebar and top bar alive when a routed screen throws', async () => {
    window.history.pushState({}, '', '/dashboard')
    render(<App />)

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('This screen could not be displayed')
    expect(alert).toHaveTextContent(CRASH_MESSAGE)

    const nav = await screen.findByRole('navigation', { name: 'Main' })
    expect(within(nav).getByRole('link', { name: 'Dashboard' })).toBeInTheDocument()
    await waitFor(() => expect(screen.getByText('Olive Operator')).toBeInTheDocument())
  })
})
