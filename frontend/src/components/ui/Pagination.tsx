import { ChevronLeft, ChevronRight } from 'lucide-react'
import { cn } from '../../lib/cn'
import { formatNumber } from '../../lib/format'
import { Button } from './Button'
import { Select } from './Select'

export interface PaginationProps {
  /** Zero-based page index, matching Spring's `Page.number`. */
  page: number
  totalPages: number
  totalElements: number
  size: number
  onPageChange: (page: number) => void
  onSizeChange?: (size: number) => void
  pageSizeOptions?: number[]
  /** Noun used in the summary line, e.g. "transactions". */
  itemLabel?: string
  disabled?: boolean
  className?: string
}

export function Pagination({
  page,
  totalPages,
  totalElements,
  size,
  onPageChange,
  onSizeChange,
  pageSizeOptions = [10, 20, 50, 100],
  itemLabel = 'results',
  disabled = false,
  className,
}: PaginationProps) {
  const safeTotalPages = Math.max(totalPages, 1)
  const firstItem = totalElements === 0 ? 0 : page * size + 1
  const lastItem = Math.min(totalElements, (page + 1) * size)

  return (
    <nav
      aria-label="Pagination"
      className={cn(
        'flex flex-wrap items-center justify-between gap-3 border-t border-border px-3 py-2',
        className,
      )}
    >
      <p className="text-xs text-muted">
        <span className="numeric">{formatNumber(firstItem)}</span>
        {'–'}
        <span className="numeric">{formatNumber(lastItem)}</span> of{' '}
        <span className="numeric font-medium text-fg">{formatNumber(totalElements)}</span>{' '}
        {itemLabel}
      </p>

      <div className="flex items-center gap-2">
        {onSizeChange ? (
          <Select
            label="Rows per page"
            hideLabel
            value={String(size)}
            disabled={disabled}
            containerClassName="w-28"
            className="h-8 py-1 text-xs"
            aria-label="Rows per page"
            onChange={(event) => onSizeChange(Number(event.target.value))}
            options={pageSizeOptions.map((option) => ({
              value: String(option),
              label: `${option} / page`,
            }))}
          />
        ) : null}
        <span className="text-xs text-muted">
          Page <span className="numeric font-medium text-fg">{page + 1}</span> of{' '}
          <span className="numeric">{safeTotalPages}</span>
        </span>
        <Button
          size="sm"
          variant="secondary"
          disabled={disabled || page <= 0}
          onClick={() => onPageChange(page - 1)}
          iconLeft={<ChevronLeft className="size-3.5" />}
          aria-label="Previous page"
        >
          Previous
        </Button>
        <Button
          size="sm"
          variant="secondary"
          disabled={disabled || page >= safeTotalPages - 1}
          onClick={() => onPageChange(page + 1)}
          iconRight={<ChevronRight className="size-3.5" />}
          aria-label="Next page"
        >
          Next
        </Button>
      </div>
    </nav>
  )
}
