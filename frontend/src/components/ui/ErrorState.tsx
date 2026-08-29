import { RotateCw, TriangleAlert } from 'lucide-react'
import type { ReactNode } from 'react'
import { errorMessage, errorTitle, isApiError } from '../../api/errors'
import { cn } from '../../lib/cn'
import { Button } from './Button'

export interface ErrorStateProps {
  /** Anything thrown by the API layer; `ApiError` renders title + detail. */
  error: unknown
  title?: string
  description?: ReactNode
  onRetry?: () => void
  retryLabel?: string
  compact?: boolean
  className?: string
}

/** The error state for every list and detail view. */
export function ErrorState({
  error,
  title,
  description,
  onRetry,
  retryLabel = 'Try again',
  compact = false,
  className,
}: ErrorStateProps) {
  const heading = title ?? errorTitle(error)
  const detail = description ?? errorMessage(error)
  const status = isApiError(error) && error.status > 0 ? error.status : null

  return (
    <div
      role="alert"
      className={cn(
        'flex flex-col items-center justify-center gap-2 text-center',
        compact ? 'px-4 py-6' : 'px-6 py-12',
        className,
      )}
    >
      <span
        aria-hidden="true"
        className="flex size-9 items-center justify-center rounded-full bg-danger-soft text-danger-fg"
      >
        <TriangleAlert className="size-4" />
      </span>
      <p className="text-sm font-medium text-fg">
        {heading}
        {status ? <span className="ml-1.5 text-xs font-normal text-subtle">({status})</span> : null}
      </p>
      {detail ? <p className="max-w-md text-xs text-muted">{detail}</p> : null}
      {onRetry ? (
        <Button
          className="mt-2"
          size="sm"
          variant="secondary"
          onClick={onRetry}
          iconLeft={<RotateCw className="size-3.5" />}
        >
          {retryLabel}
        </Button>
      ) : null}
    </div>
  )
}
