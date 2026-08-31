import {
  BookOpen,
  Cpu,
  LayoutDashboard,
  Search,
  SlidersHorizontal,
  Sparkles,
  Users,
  type LucideIcon,
} from 'lucide-react'
import { NavLink } from 'react-router-dom'
import type { Role } from '../../api/types'
import { cn } from '../../lib/cn'

export interface NavItem {
  to: string
  label: string
  icon: LucideIcon
  /** Roles allowed to see the entry; undefined means everyone signed in. */
  roles?: Role[]
  /** Matches nested routes as well (e.g. /admin/rules/new). */
  end?: boolean
}

export interface NavSection {
  id: string
  /** Section heading; omitted for the primary group. */
  title?: string
  roles?: Role[]
  items: NavItem[]
}

/** Single source of truth for the sidebar — mirrors the router in App.tsx. */
export const NAV_SECTIONS: NavSection[] = [
  {
    id: 'main',
    items: [
      { to: '/dashboard', label: 'Dashboard', icon: LayoutDashboard },
      // Deliberately not `end`, so viewing one analysis keeps the section marked.
      { to: '/analyses', label: 'Analyses', icon: Sparkles },
      { to: '/knowledge-search', label: 'Knowledge Search', icon: Search },
    ],
  },
  {
    id: 'admin',
    title: 'Admin',
    roles: ['ADMIN'],
    items: [
      { to: '/admin/rules', label: 'Risk Rules', icon: SlidersHorizontal },
      { to: '/admin/knowledge', label: 'Knowledge Base', icon: BookOpen },
      { to: '/admin/users', label: 'Users', icon: Users },
      { to: '/admin/llm-settings', label: 'LLM Settings', icon: Cpu },
    ],
  },
]

export function visibleSections(role: Role | null): NavSection[] {
  return NAV_SECTIONS.filter((section) => !section.roles || (role && section.roles.includes(role)))
    .map((section) => ({
      ...section,
      items: section.items.filter((item) => !item.roles || (role && item.roles.includes(role))),
    }))
    .filter((section) => section.items.length > 0)
}

export interface NavProps {
  role: Role | null
  /** Called after a link is followed — closes the mobile drawer. */
  onNavigate?: () => void
  className?: string
}

/**
 * Primary navigation. It always sits on the Swissquote-black chrome, so it is
 * styled from the `sidebar-*` tokens rather than the content-surface ones.
 */
export function Nav({ role, onNavigate, className }: NavProps) {
  const sections = visibleSections(role)

  return (
    <nav aria-label="Main" className={cn('flex flex-col gap-5 px-2 py-4', className)}>
      {sections.map((section) => (
        <div key={section.id} className="flex flex-col gap-0.5">
          {section.title ? (
            <h2 className="px-3 pb-1.5 text-2xs font-semibold tracking-caption text-sidebar-subtle uppercase">
              {section.title}
            </h2>
          ) : null}
          {section.items.map((item) => {
            const Icon = item.icon
            return (
              <NavLink
                key={item.to}
                to={item.to}
                end={item.end}
                onClick={onNavigate}
                className={({ isActive }) =>
                  cn(
                    // The active entry carries a brand-orange left rail over a
                    // lighter panel — the transparent rail on idle entries keeps
                    // the labels on one vertical line.
                    'flex items-center gap-2.5 rounded-r-xs border-l-2 py-1.5 pr-2 pl-2.5',
                    'text-sm font-medium transition-colors',
                    'focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-1 focus-visible:ring-offset-sidebar focus-visible:outline-none',
                    isActive
                      ? 'border-l-accent bg-sidebar-active text-sidebar-fg'
                      : 'border-l-transparent text-sidebar-muted hover:bg-sidebar-hover hover:text-sidebar-fg',
                  )
                }
              >
                {({ isActive }) => (
                  <>
                    <Icon
                      aria-hidden="true"
                      className={cn('size-4 shrink-0', isActive ? 'opacity-100' : 'opacity-70')}
                    />
                    <span className="truncate">{item.label}</span>
                  </>
                )}
              </NavLink>
            )
          })}
        </div>
      ))}
    </nav>
  )
}
