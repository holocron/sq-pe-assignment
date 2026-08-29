import type { ReactNode } from 'react'
import { cn } from '../../lib/cn'

/**
 * Tones deliberately exclude the risk ramp — risk always renders through
 * `<RiskBadge />` so its colours keep a single meaning.
 *
 * These chips are *tinted* pills (soft fill, hairline border). The solid,
 * fully saturated fill is reserved for `<RiskBadge />`, which keeps a verdict
 * visually unique in a dense table.
 */
export type BadgeTone =
  | 'neutral'
  | 'accent'
  | 'info'
  | 'warning'
  | 'danger'
  | 'success'
  | 'outline'

export type BadgeSize = 'sm' | 'md'

export interface BadgeProps {
  tone?: BadgeTone
  size?: BadgeSize
  /** Leading glyph. Pair it with the label so colour is never the only signal. */
  icon?: ReactNode
  /** Adds a leading status dot (still paired with the label text). */
  dot?: boolean
  className?: string
  title?: string
  children: ReactNode
}

const TONES: Record<BadgeTone, string> = {
  neutral: 'border-border bg-surface-2 text-muted',
  accent: 'border-accent/35 bg-accent-soft text-accent-soft-fg',
  info: 'border-info/35 bg-info-soft text-info-fg',
  warning: 'border-warning/40 bg-warning-soft text-warning-fg',
  danger: 'border-danger/35 bg-danger-soft text-danger-fg',
  success: 'border-success/35 bg-success-soft text-success-fg',
  outline: 'border-border-strong bg-transparent text-muted',
}

const DOTS: Record<BadgeTone, string> = {
  neutral: 'bg-subtle',
  accent: 'bg-accent',
  info: 'bg-info',
  warning: 'bg-warning',
  danger: 'bg-danger',
  success: 'bg-success',
  outline: 'bg-border-strong',
}

const SIZES: Record<BadgeSize, string> = {
  sm: 'h-5 px-1.5 text-2xs gap-1',
  md: 'h-6 px-2 text-xs gap-1.5',
}

export function Badge({
  tone = 'neutral',
  size = 'sm',
  icon,
  dot = false,
  className,
  title,
  children,
}: BadgeProps) {
  return (
    <span
      title={title}
      className={cn(
        'inline-flex items-center rounded-full border font-semibold whitespace-nowrap',
        TONES[tone],
        SIZES[size],
        className,
      )}
    >
      {icon ? (
        <span aria-hidden="true" className="flex shrink-0 items-center">
          {icon}
        </span>
      ) : null}
      {dot && !icon ? (
        <span aria-hidden="true" className={cn('size-1.5 shrink-0 rounded-full', DOTS[tone])} />
      ) : null}
      {children}
    </span>
  )
}
