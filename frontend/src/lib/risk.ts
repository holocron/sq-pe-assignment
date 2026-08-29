import {
  CircleAlert,
  CircleCheck,
  CircleHelp,
  OctagonX,
  TriangleAlert,
  type LucideIcon,
} from 'lucide-react'
import { RISK_LEVELS, type RiskLevel } from '../api/types'

/**
 * The single source of truth for risk colour in the product, taken from
 * `docs/DESIGN_SYSTEM.md`: LOW = emerald, MEDIUM = amber, HIGH = orange,
 * CRITICAL = rose.
 *
 * Every surface that shows a risk level must use these classes (normally via
 * `<RiskBadge />`) so the colour keeps its meaning. Colour is never the only
 * signal — the level text and the severity icon always ship with it.
 */
export interface RiskLevelStyle {
  level: RiskLevel
  /** Sentence-case label for prose; use `level` itself for chips. */
  label: string
  /** 0..3, ascending severity — handy for sorting. */
  order: number
  /** Filled chip: strong background with a foreground that clears 4.5:1 in both themes. */
  solid: string
  /** Tinted chip: soft background, tinted text, hairline border. */
  soft: string
  /** Foreground only. */
  text: string
  /** Small status dot / legend swatch. */
  dot: string
  /** Progress or score bar fill. */
  bar: string
  /** Border only, e.g. a highlighted table row. */
  border: string
  /** Left accent rule for cards and rows. */
  accentBorder: string
  /** Raw CSS variable reference for charts (recharts wants a colour string). */
  cssVar: string
  /**
   * Severity glyph. Rendered alongside the level text so the badge carries
   * three independent signals — colour, shape and words.
   */
  icon: LucideIcon
}

export const RISK_LEVEL_STYLES: Record<RiskLevel, RiskLevelStyle> = {
  LOW: {
    level: 'LOW',
    label: 'Low',
    order: 0,
    solid: 'bg-risk-low text-risk-low-on',
    soft: 'bg-risk-low-soft text-risk-low-fg border-risk-low/30',
    text: 'text-risk-low-fg',
    dot: 'bg-risk-low',
    bar: 'bg-risk-low',
    border: 'border-risk-low/40',
    accentBorder: 'border-l-risk-low',
    cssVar: 'var(--color-risk-low)',
    icon: CircleCheck,
  },
  MEDIUM: {
    level: 'MEDIUM',
    label: 'Medium',
    order: 1,
    solid: 'bg-risk-medium text-risk-medium-on',
    soft: 'bg-risk-medium-soft text-risk-medium-fg border-risk-medium/30',
    text: 'text-risk-medium-fg',
    dot: 'bg-risk-medium',
    bar: 'bg-risk-medium',
    border: 'border-risk-medium/40',
    accentBorder: 'border-l-risk-medium',
    cssVar: 'var(--color-risk-medium)',
    icon: CircleAlert,
  },
  HIGH: {
    level: 'HIGH',
    label: 'High',
    order: 2,
    solid: 'bg-risk-high text-risk-high-on',
    soft: 'bg-risk-high-soft text-risk-high-fg border-risk-high/30',
    text: 'text-risk-high-fg',
    dot: 'bg-risk-high',
    bar: 'bg-risk-high',
    border: 'border-risk-high/40',
    accentBorder: 'border-l-risk-high',
    cssVar: 'var(--color-risk-high)',
    icon: TriangleAlert,
  },
  CRITICAL: {
    level: 'CRITICAL',
    label: 'Critical',
    order: 3,
    solid: 'bg-risk-critical text-risk-critical-on',
    soft: 'bg-risk-critical-soft text-risk-critical-fg border-risk-critical/30',
    text: 'text-risk-critical-fg',
    dot: 'bg-risk-critical',
    bar: 'bg-risk-critical',
    border: 'border-risk-critical/40',
    accentBorder: 'border-l-risk-critical',
    cssVar: 'var(--color-risk-critical)',
    icon: OctagonX,
  },
}

/** Style bundle for an unknown / not-yet-assigned risk level. */
export const UNKNOWN_RISK_STYLE = {
  label: 'Not assessed',
  solid: 'bg-surface-3 text-muted',
  soft: 'bg-surface-2 text-muted border-border',
  text: 'text-muted',
  dot: 'bg-border-strong',
  bar: 'bg-border-strong',
  border: 'border-border',
  accentBorder: 'border-l-border-strong',
  cssVar: 'var(--color-border-strong)',
  icon: CircleHelp,
} as const

export function riskStyle(level: RiskLevel): RiskLevelStyle {
  return RISK_LEVEL_STYLES[level]
}

/** Banding from BUILD_SPEC section 4: LOW < 25 <= MEDIUM < 50 <= HIGH < 75 <= CRITICAL. */
export function riskLevelFromScore(score: number | null | undefined): RiskLevel | null {
  if (score === null || score === undefined || !Number.isFinite(score)) return null
  if (score < 25) return 'LOW'
  if (score < 50) return 'MEDIUM'
  if (score < 75) return 'HIGH'
  return 'CRITICAL'
}

/** Lower bound of a band, used to draw the score scale. */
export const RISK_BAND_BOUNDS: Record<RiskLevel, { min: number; max: number }> = {
  LOW: { min: 0, max: 25 },
  MEDIUM: { min: 25, max: 50 },
  HIGH: { min: 50, max: 75 },
  CRITICAL: { min: 75, max: 100 },
}

export function isRiskLevel(value: unknown): value is RiskLevel {
  return typeof value === 'string' && (RISK_LEVELS as readonly string[]).includes(value)
}

/** Accepts any casing / whitespace and returns a canonical level or null. */
export function parseRiskLevel(value: unknown): RiskLevel | null {
  if (typeof value !== 'string') return null
  const normalized = value.trim().toUpperCase()
  return isRiskLevel(normalized) ? normalized : null
}

/** Descending severity comparator for tables. */
export function compareRiskLevel(
  a: RiskLevel | null | undefined,
  b: RiskLevel | null | undefined,
): number {
  const orderA = a ? RISK_LEVEL_STYLES[a].order : -1
  const orderB = b ? RISK_LEVEL_STYLES[b].order : -1
  return orderB - orderA
}
