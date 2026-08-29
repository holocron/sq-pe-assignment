import type { RiskLevel } from '../../api/types'
import { cn } from '../../lib/cn'
import { formatNumber } from '../../lib/format'
import { RISK_LEVEL_STYLES, UNKNOWN_RISK_STYLE } from '../../lib/risk'

export type RiskBadgeVariant = 'soft' | 'solid'
export type RiskBadgeSize = 'sm' | 'md' | 'lg'

export interface RiskBadgeProps {
  level: RiskLevel | null | undefined
  /** Numeric total score; rendered alongside the level when `showScore`. */
  score?: number | null
  showScore?: boolean
  variant?: RiskBadgeVariant
  size?: RiskBadgeSize
  className?: string
}

const SIZES: Record<RiskBadgeSize, string> = {
  sm: 'h-5 px-1.5 text-2xs gap-1',
  md: 'h-6 px-2 text-xs gap-1.5',
  lg: 'h-7 px-2.5 text-sm gap-2',
}

const ICON_SIZES: Record<RiskBadgeSize, string> = {
  sm: 'size-2.5',
  md: 'size-3',
  lg: 'size-3.5',
}

/**
 * THE canonical risk indicator. Every screen showing a risk level must use it
 * so the colour scale (emerald / amber / orange / rose) stays consistent.
 *
 * Rendered as a filled pill carrying a severity icon and the level text, so a
 * verdict is readable without colour vision and never gets mistaken for one of
 * the app's own controls.
 */
export function RiskBadge({
  level,
  score,
  showScore = false,
  variant = 'solid',
  size = 'md',
  className,
}: RiskBadgeProps) {
  const style = level ? RISK_LEVEL_STYLES[level] : null
  const tone = style
    ? variant === 'solid'
      ? style.solid
      : style.soft
    : variant === 'solid'
      ? UNKNOWN_RISK_STYLE.solid
      : UNKNOWN_RISK_STYLE.soft
  const label = level ?? 'NOT ASSESSED'
  const Icon = (style ?? UNKNOWN_RISK_STYLE).icon

  return (
    <span
      className={cn(
        'inline-flex items-center rounded-full border font-semibold tracking-caption uppercase',
        'leading-none',
        variant === 'solid' && 'border-transparent',
        tone,
        SIZES[size],
        className,
      )}
      title={
        showScore && score !== null && score !== undefined
          ? `Risk ${label} — score ${formatNumber(score, { maximumFractionDigits: 2 })}`
          : `Risk ${label}`
      }
    >
      <Icon aria-hidden="true" className={cn('shrink-0', ICON_SIZES[size])} />
      {label}
      {showScore && score !== null && score !== undefined ? (
        // Full opacity on purpose: dimming the figure dropped white-on-orange
        // below 4.5:1. The lighter weight is what separates it from the level.
        <span className="numeric font-normal">
          {formatNumber(score, { maximumFractionDigits: 1 })}
        </span>
      ) : null}
    </span>
  )
}

export interface RiskScoreBarProps {
  score: number | null | undefined
  level?: RiskLevel | null
  /** Score value that fills the bar completely. */
  max?: number
  className?: string
}

/** Horizontal score meter using the same risk colour scale. */
export function RiskScoreBar({ score, level, max = 100, className }: RiskScoreBarProps) {
  const value = typeof score === 'number' && Number.isFinite(score) ? score : 0
  const percent = Math.max(0, Math.min(100, (value / max) * 100))
  const fill = level ? RISK_LEVEL_STYLES[level].bar : UNKNOWN_RISK_STYLE.bar
  return (
    <div
      role="meter"
      aria-valuenow={value}
      aria-valuemin={0}
      aria-valuemax={max}
      aria-label="Total risk score"
      className={cn('h-1.5 w-full overflow-hidden rounded-full bg-surface-3', className)}
    >
      <div className={cn('h-full rounded-full transition-[width]', fill)} style={{ width: `${percent}%` }} />
    </div>
  )
}
