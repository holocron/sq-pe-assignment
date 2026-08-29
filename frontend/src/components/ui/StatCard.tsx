import type { ReactNode } from 'react'
import { cn } from '../../lib/cn'
import { Skeleton } from './Skeleton'

export interface StatCardProps {
  label: string
  value: ReactNode
  /** Small caption under the value, e.g. a currency or a time window. */
  hint?: ReactNode
  icon?: ReactNode
  loading?: boolean
  /** Locks the value to tabular figures so tiles in a row align — use it for money. */
  numeric?: boolean
  className?: string
}

/** Compact aggregate tile for dashboards and customer summaries. */
export function StatCard({
  label,
  value,
  hint,
  icon,
  loading = false,
  numeric = false,
  className,
}: StatCardProps) {
  return (
    <div
      className={cn(
        'flex flex-col gap-1 rounded-md border border-border bg-surface px-3.5 py-3 shadow-panel',
        className,
      )}
    >
      <div className="flex items-center justify-between gap-2">
        <span className="text-2xs font-semibold tracking-caption text-muted uppercase">{label}</span>
        {icon ? (
          <span aria-hidden="true" className="text-subtle">
            {icon}
          </span>
        ) : null}
      </div>
      {loading ? (
        <Skeleton className="h-6 w-24" />
      ) : (
        <span
          className={cn(
            'text-xl leading-tight font-semibold text-fg',
            numeric && 'numeric',
          )}
        >
          {value}
        </span>
      )}
      {hint ? <span className="text-2xs text-subtle">{hint}</span> : null}
    </div>
  )
}
