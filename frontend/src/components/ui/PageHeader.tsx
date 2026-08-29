import type { ReactNode } from 'react'
import { cn } from '../../lib/cn'

export interface PageHeaderProps {
  title: ReactNode
  description?: ReactNode
  /** Breadcrumb or back link rendered above the title. */
  eyebrow?: ReactNode
  actions?: ReactNode
  className?: string
}

/** Consistent page title block for every route. */
export function PageHeader({
  title,
  description,
  eyebrow,
  actions,
  className,
}: PageHeaderProps) {
  return (
    <header className={cn('flex flex-wrap items-start justify-between gap-3', className)}>
      <div className="min-w-0">
        {eyebrow ? <div className="mb-1 text-xs text-muted">{eyebrow}</div> : null}
        <h1 className="truncate text-xl font-semibold tracking-tight-swiss text-fg">{title}</h1>
        {description ? (
          <p className="mt-1 max-w-2xl text-sm text-muted">{description}</p>
        ) : null}
      </div>
      {actions ? <div className="flex shrink-0 items-center gap-2">{actions}</div> : null}
    </header>
  )
}
