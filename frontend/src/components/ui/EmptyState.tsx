import type { ReactNode } from 'react'
import { cn } from '../../lib/cn'

export interface EmptyStateProps {
  /** Usually a lucide icon element, e.g. `<Inbox className="size-5" />`. */
  icon?: ReactNode
  title: string
  description?: ReactNode
  /** Primary call to action. */
  action?: ReactNode
  compact?: boolean
  className?: string
}

/** The "no results" state for every list and detail view. */
export function EmptyState({
  icon,
  title,
  description,
  action,
  compact = false,
  className,
}: EmptyStateProps) {
  return (
    <div
      className={cn(
        'flex flex-col items-center justify-center gap-2 text-center',
        compact ? 'px-4 py-6' : 'px-6 py-12',
        className,
      )}
    >
      {icon ? (
        <span
          aria-hidden="true"
          className="flex size-9 items-center justify-center rounded-full bg-surface-2 text-subtle"
        >
          {icon}
        </span>
      ) : null}
      <p className="text-sm font-medium text-fg">{title}</p>
      {description ? (
        <p className="max-w-md text-xs text-muted">{description}</p>
      ) : null}
      {action ? <div className="mt-2">{action}</div> : null}
    </div>
  )
}
