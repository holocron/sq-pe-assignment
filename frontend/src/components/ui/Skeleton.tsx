import { cn } from '../../lib/cn'

export interface SkeletonProps {
  className?: string
  /** Rendered as a rounded pill instead of a rectangle. */
  pill?: boolean
}

/** Neutral loading placeholder — never coloured, so it cannot imply risk. */
export function Skeleton({ className, pill = false }: SkeletonProps) {
  return (
    <span
      aria-hidden="true"
      className={cn(
        'block animate-pulse bg-surface-3',
        pill ? 'rounded-full' : 'rounded-xs',
        className ?? 'h-4 w-full',
      )}
    />
  )
}

export function SkeletonText({ lines = 3, className }: { lines?: number; className?: string }) {
  return (
    <div className={cn('space-y-2', className)}>
      {Array.from({ length: lines }, (_, index) => (
        <Skeleton
          key={index}
          className={cn('h-3.5', index === lines - 1 ? 'w-2/3' : 'w-full')}
        />
      ))}
    </div>
  )
}
