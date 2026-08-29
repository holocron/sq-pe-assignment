import { useRef, type ReactNode } from 'react'
import { cn } from '../../lib/cn'

export interface TabItem {
  id: string
  label: ReactNode
  icon?: ReactNode
  /** Optional trailing count, e.g. the number of rows behind the tab. */
  count?: number | null
  disabled?: boolean
}

export interface TabsProps {
  tabs: TabItem[]
  value: string
  onChange: (id: string) => void
  /** Required for screen readers when the tablist has no visible heading. */
  ariaLabel: string
  className?: string
}

/** Accessible tablist with roving arrow-key navigation. */
export function Tabs({ tabs, value, onChange, ariaLabel, className }: TabsProps) {
  const listRef = useRef<HTMLDivElement | null>(null)

  const move = (direction: 1 | -1) => {
    const enabled = tabs.filter((tab) => !tab.disabled)
    if (enabled.length === 0) return
    const currentIndex = enabled.findIndex((tab) => tab.id === value)
    const nextIndex = (currentIndex + direction + enabled.length) % enabled.length
    const next = enabled[nextIndex]
    if (!next) return
    onChange(next.id)
    listRef.current
      ?.querySelector<HTMLButtonElement>(`[data-tab-id="${next.id}"]`)
      ?.focus()
  }

  return (
    <div
      ref={listRef}
      role="tablist"
      aria-label={ariaLabel}
      className={cn('flex items-center gap-1 border-b border-border', className)}
      onKeyDown={(event) => {
        if (event.key === 'ArrowRight') {
          event.preventDefault()
          move(1)
        } else if (event.key === 'ArrowLeft') {
          event.preventDefault()
          move(-1)
        }
      }}
    >
      {tabs.map((tab) => {
        const selected = tab.id === value
        return (
          <button
            key={tab.id}
            type="button"
            role="tab"
            data-tab-id={tab.id}
            id={`tab-${tab.id}`}
            aria-selected={selected}
            aria-controls={`tabpanel-${tab.id}`}
            tabIndex={selected ? 0 : -1}
            disabled={tab.disabled}
            onClick={() => onChange(tab.id)}
            className={cn(
              'inline-flex items-center gap-1.5 -mb-px border-b-2 px-3 py-1.5 text-sm font-medium transition-colors',
              'outline-none focus-visible:bg-surface-2 focus-visible:ring-2 focus-visible:ring-ring',
              'disabled:cursor-not-allowed disabled:opacity-50',
              // The selected rule is brand orange — chrome, never risk.
              selected
                ? 'border-accent font-semibold text-fg'
                : 'border-transparent text-muted hover:border-border-strong hover:text-fg',
            )}
          >
            {tab.icon}
            {tab.label}
            {tab.count !== null && tab.count !== undefined ? (
              <span className="numeric rounded-full border border-border bg-surface-2 px-1.5 py-px text-2xs text-muted">
                {tab.count}
              </span>
            ) : null}
          </button>
        )
      })}
    </div>
  )
}

export interface TabPanelProps {
  /** Must match the `TabItem.id` it belongs to. */
  id: string
  active: boolean
  children: ReactNode
  className?: string
}

export function TabPanel({ id, active, children, className }: TabPanelProps) {
  if (!active) return null
  return (
    <div
      role="tabpanel"
      id={`tabpanel-${id}`}
      aria-labelledby={`tab-${id}`}
      tabIndex={0}
      className={cn('outline-none', className)}
    >
      {children}
    </div>
  )
}
