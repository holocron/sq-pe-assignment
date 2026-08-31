import { ChevronRight, LogOut, Menu, X } from 'lucide-react'
import { useEffect, useState } from 'react'
import { Link, Outlet, useLocation } from 'react-router-dom'
import { useAuth } from '../../auth/useAuth'
import { cn } from '../../lib/cn'
import { initials } from '../../lib/format'
import { Button } from '../ui/Button'
import { RoleBadge } from '../ui/RoleBadge'
import { Nav, NAV_SECTIONS } from './Nav'
import { ThemeToggle } from './ThemeToggle'

/**
 * Text stand-in for the Swissquote wordmark — the licensed logo asset is not
 * bundled with this repository (see the README). "Swissquote" is set in the
 * brand sans stack with tight tracking beside a brand-orange mark, and the
 * product name sits under it as the lighter secondary line.
 *
 * `tone="sidebar"` renders it on the Swissquote-black chrome, `tone="bar"` on
 * the white top bar used below the `lg` breakpoint.
 */
function Brand({
  tone = 'sidebar',
  className,
  onClick,
}: {
  tone?: 'sidebar' | 'bar'
  className?: string
  onClick?: () => void
}) {
  const onDark = tone === 'sidebar'
  return (
    <Link
      to="/dashboard"
      onClick={onClick}
      className={cn(
        'flex items-center gap-2.5 rounded-xs px-1 py-1 focus-visible:ring-2 focus-visible:ring-ring',
        'focus-visible:ring-offset-2 focus-visible:outline-none',
        onDark ? 'focus-visible:ring-offset-sidebar' : 'focus-visible:ring-offset-surface',
        className,
      )}
    >
      <span aria-hidden="true" className="size-4 shrink-0 rounded-xxs bg-accent" />
      <span className="min-w-0 leading-tight">
        <span
          className={cn(
            'block truncate text-sm font-semibold tracking-tight-swiss',
            onDark ? 'text-sidebar-fg' : 'text-fg',
          )}
        >
          Swissquote
        </span>
        <span
          className={cn(
            'block truncate text-2xs',
            onDark ? 'text-sidebar-muted' : 'text-muted',
          )}
        >
          Customer Activity Analytics
        </span>
      </span>
    </Link>
  )
}

interface TrailCrumb {
  label: string
  /** Set on every crumb except the current page. */
  to?: string
}

/**
 * Detail routes that have no nav entry of their own. Everything else derives
 * from `NAV_SECTIONS`, which already mirrors the router.
 */
const DETAIL_TRAILS: ReadonlyArray<readonly [string, readonly TrailCrumb[]]> = [
  ['/customers', [{ label: 'Dashboard', to: '/dashboard' }, { label: 'Customer' }]],
  ['/analyses', [{ label: 'Analyses', to: '/analyses' }, { label: 'Analysis' }]],
]

/** Section trail for the top bar, e.g. `Admin › Risk Rules`. */
function sectionTrail(pathname: string): readonly TrailCrumb[] {
  for (const section of NAV_SECTIONS) {
    for (const item of section.items) {
      if (pathname === item.to) {
        return section.title
          ? [{ label: section.title }, { label: item.label }]
          : [{ label: item.label }]
      }
    }
  }
  /* A detail route under a nav entry (e.g. /analyses/<id>) is NOT that nav
     page — the trail must not present the list as the current page. */
  const detail = DETAIL_TRAILS.find(([prefix]) => pathname.startsWith(`${prefix}/`))
  if (detail) return detail[1]
  for (const section of NAV_SECTIONS) {
    for (const item of section.items) {
      if (pathname.startsWith(`${item.to}/`)) {
        return section.title
          ? [{ label: section.title }, { label: item.label }]
          : [{ label: item.label }]
      }
    }
  }
  return []
}

/**
 * Application chrome: fixed sidebar from `lg` up, a slide-over drawer below
 * that, and a header carrying the section trail, the signed-in operator, the
 * theme toggle and logout.
 */
export function AppShell() {
  const { user, role, logout } = useAuth()
  const { pathname } = useLocation()
  const trail = sectionTrail(pathname)
  const [drawerOpen, setDrawerOpen] = useState(false)

  useEffect(() => {
    if (!drawerOpen) return
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setDrawerOpen(false)
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [drawerOpen])

  return (
    <div className="min-h-svh bg-bg">
      <a
        href="#main-content"
        className="sr-only focus:not-sr-only focus:fixed focus:top-3 focus:left-3 focus:z-[70] focus:rounded-md focus:bg-surface focus:px-3 focus:py-2 focus:text-sm focus:shadow-popover"
      >
        Skip to content
      </a>

      {/* Fixed sidebar (lg and up) — Swissquote black chrome. */}
      <aside className="fixed inset-y-0 left-0 z-30 hidden w-60 flex-col border-r border-sidebar-border bg-sidebar lg:flex">
        <div className="flex h-14 items-center border-b border-sidebar-border px-3">
          <Brand />
        </div>
        <div className="flex-1 overflow-y-auto">
          <Nav role={role} />
        </div>
        <div className="border-t border-sidebar-border px-4 py-3 text-2xs leading-relaxed text-sidebar-subtle">
          Every risk verdict is auditable — rule coverage is recorded per analysis.
        </div>
      </aside>

      {/* Slide-over drawer (below lg) */}
      {drawerOpen ? (
        <div className="fixed inset-0 z-40 lg:hidden">
          <button
            type="button"
            aria-label="Close navigation"
            className="absolute inset-0 bg-overlay"
            onClick={() => setDrawerOpen(false)}
          />
          <div
            role="dialog"
            aria-modal="true"
            aria-label="Navigation"
            className="relative flex h-full w-64 flex-col border-r border-sidebar-border bg-sidebar shadow-popover animate-fade-in"
          >
            <div className="flex h-14 items-center justify-between border-b border-sidebar-border px-3">
              <Brand onClick={() => setDrawerOpen(false)} />
              {/* Styled from the sidebar tokens rather than `<Button />`, whose
                  variants are built for light content surfaces. */}
              <button
                type="button"
                aria-label="Close navigation"
                onClick={() => setDrawerOpen(false)}
                className="inline-flex size-8 shrink-0 items-center justify-center rounded-xs text-sidebar-muted transition-colors hover:bg-sidebar-hover hover:text-sidebar-fg focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-sidebar focus-visible:outline-none"
              >
                <X aria-hidden="true" className="size-4" />
              </button>
            </div>
            <div className="flex-1 overflow-y-auto">
              <Nav role={role} onNavigate={() => setDrawerOpen(false)} />
            </div>
          </div>
        </div>
      ) : null}

      <div className="lg:pl-60">
        <header className="sticky top-0 z-20 flex h-14 items-center gap-3 border-b border-border bg-surface/85 px-3 backdrop-blur lg:px-6">
          <Button
            variant="ghost"
            size="icon"
            className="lg:hidden"
            aria-label="Open navigation"
            aria-expanded={drawerOpen}
            onClick={() => setDrawerOpen(true)}
          >
            <Menu className="size-4" />
          </Button>

          <div className="lg:hidden">
            <Brand tone="bar" />
          </div>

          {/* Section trail — the top bar's left-hand context, per the design
              system. Crumbs that name another page are real links, so the
              trail is a way back, not just a label. The page's own <h1>
              stays the accessible heading. */}
          {trail.length > 0 ? (
            <nav
              aria-label="Breadcrumb"
              className="hidden min-w-0 items-center gap-1.5 text-xs text-muted lg:flex"
            >
              {trail.map((crumb, index) => (
                <span key={crumb.label} className="flex min-w-0 items-center gap-1.5">
                  {index > 0 ? (
                    <ChevronRight aria-hidden="true" className="size-3 shrink-0 text-subtle" />
                  ) : null}
                  {crumb.to ? (
                    <Link
                      to={crumb.to}
                      className="truncate rounded-xs underline-offset-4 hover:text-fg hover:underline focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
                    >
                      {crumb.label}
                    </Link>
                  ) : (
                    <span
                      className={cn(
                        'truncate',
                        index === trail.length - 1 && 'font-medium text-fg',
                      )}
                    >
                      {crumb.label}
                    </span>
                  )}
                </span>
              ))}
            </nav>
          ) : null}

          <div className="flex-1" />

          <ThemeToggle />

          <div className="hidden items-center gap-2.5 border-l border-border pl-3 sm:flex">
            <span
              aria-hidden="true"
              className="flex size-8 items-center justify-center rounded-full bg-surface-2 text-2xs font-semibold text-muted"
            >
              {initials(user?.fullName ?? user?.username)}
            </span>
            <span className="leading-tight">
              <span className="block max-w-40 truncate text-xs font-medium text-fg">
                {user?.fullName ?? user?.username ?? 'Signed in'}
              </span>
              <span className="block text-2xs text-subtle">{user?.username}</span>
            </span>
            <RoleBadge role={role} />
          </div>

          <Button
            variant="ghost"
            size="sm"
            onClick={logout}
            iconLeft={<LogOut className="size-3.5" />}
          >
            <span className="hidden sm:inline">Sign out</span>
            <span className="sr-only sm:hidden">Sign out</span>
          </Button>
        </header>

        <main id="main-content" className="mx-auto w-full max-w-[1600px] px-4 py-5 lg:px-6 lg:py-6">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
