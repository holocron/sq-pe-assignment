import type { ReactNode } from 'react'
import { cn } from '../../lib/cn'
import { EmptyState } from './EmptyState'
import { ErrorState } from './ErrorState'
import { Skeleton } from './Skeleton'

export type ColumnAlign = 'left' | 'right' | 'center'

export interface Column<T> {
  /** Stable identifier, also used as the React key. */
  key: string
  header: ReactNode
  /** Cell renderer. Keep money right-aligned and use `numeric` for figures. */
  cell: (row: T, rowIndex: number) => ReactNode
  align?: ColumnAlign
  /** Any width utility, e.g. `w-40`. */
  className?: string
  headerClassName?: string
  /** Renders the header for screen readers only (icon/action columns). */
  hideHeader?: boolean
}

export interface TableProps<T> {
  columns: Column<T>[]
  rows: T[]
  rowKey: (row: T, index: number) => string
  /** Renders skeleton rows instead of content. */
  loading?: boolean
  /** Anything truthy renders `<ErrorState />` in place of the body. */
  error?: unknown
  onRetry?: () => void
  /** Custom empty state; defaults to a neutral "No records" message. */
  empty?: ReactNode
  emptyTitle?: string
  emptyDescription?: ReactNode
  onRowClick?: (row: T, index: number) => void
  rowClassName?: (row: T, index: number) => string | undefined
  /** Visually hidden table caption; strongly recommended for screen readers. */
  caption?: string
  dense?: boolean
  stickyHeader?: boolean
  skeletonRows?: number
  className?: string
}

const ALIGN: Record<ColumnAlign, string> = {
  left: 'text-left',
  right: 'text-right',
  center: 'text-center',
}

/**
 * Generic data table covering the loading / error / empty / populated states.
 * Rows are clickable only when `onRowClick` is provided, and then they are
 * keyboard-activatable too.
 */
export function Table<T>({
  columns,
  rows,
  rowKey,
  loading = false,
  error,
  onRetry,
  empty,
  emptyTitle = 'No records',
  emptyDescription,
  onRowClick,
  rowClassName,
  caption,
  dense = false,
  stickyHeader = false,
  skeletonRows = 6,
  className,
}: TableProps<T>) {
  const cellPadding = dense ? 'px-3 py-1.5' : 'px-3 py-2.5'

  if (error) {
    return <ErrorState error={error} onRetry={onRetry} />
  }

  if (!loading && rows.length === 0) {
    return <>{empty ?? <EmptyState title={emptyTitle} description={emptyDescription} />}</>
  }

  return (
    <div className={cn('w-full overflow-x-auto', className)}>
      <table className="w-full border-collapse text-sm">
        {caption ? <caption className="sr-only">{caption}</caption> : null}
        <thead
          className={cn(
            'bg-surface-2/60',
            stickyHeader && 'sticky top-0 z-10 backdrop-blur-sm',
          )}
        >
          <tr>
            {columns.map((column) => (
              <th
                key={column.key}
                scope="col"
                className={cn(
                  'border-b border-border text-2xs font-semibold tracking-caption text-muted uppercase',
                  cellPadding,
                  ALIGN[column.align ?? 'left'],
                  column.className,
                  column.headerClassName,
                )}
              >
                {column.hideHeader ? <span className="sr-only">{column.header}</span> : column.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {loading
            ? Array.from({ length: skeletonRows }, (_, rowIndex) => (
                <tr key={`skeleton-${rowIndex}`} className="border-b border-border/60">
                  {columns.map((column) => (
                    <td key={column.key} className={cn(cellPadding, column.className)}>
                      <Skeleton className="h-3.5 w-full max-w-40" />
                    </td>
                  ))}
                </tr>
              ))
            : rows.map((row, rowIndex) => (
                <tr
                  key={rowKey(row, rowIndex)}
                  onClick={onRowClick ? () => onRowClick(row, rowIndex) : undefined}
                  onKeyDown={
                    onRowClick
                      ? (event) => {
                          if (event.key === 'Enter' || event.key === ' ') {
                            event.preventDefault()
                            onRowClick(row, rowIndex)
                          }
                        }
                      : undefined
                  }
                  tabIndex={onRowClick ? 0 : undefined}
                  role={onRowClick ? 'button' : undefined}
                  className={cn(
                    'border-b border-border/60 transition-colors last:border-b-0',
                    onRowClick &&
                      'cursor-pointer hover:bg-surface-2 focus-visible:bg-surface-2 focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-ring',
                    rowClassName?.(row, rowIndex),
                  )}
                >
                  {columns.map((column) => (
                    <td
                      key={column.key}
                      className={cn(
                        'align-middle text-fg',
                        cellPadding,
                        ALIGN[column.align ?? 'left'],
                        column.className,
                      )}
                    >
                      {column.cell(row, rowIndex)}
                    </td>
                  ))}
                </tr>
              ))}
        </tbody>
      </table>
    </div>
  )
}
