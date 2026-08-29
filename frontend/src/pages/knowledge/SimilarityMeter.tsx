import { cn } from '../../lib/cn'
import { normalizeSimilarity, similarityBand } from './similarity'

export interface SimilarityMeterProps {
  score: number | null | undefined
  className?: string
}

/**
 * Retrieval confidence as a readable chip: the band in words, a small meter and
 * a whole-number percentage — never the raw cosine float, and never the risk
 * ramp, because closeness to a query says nothing about risk.
 */
export function SimilarityMeter({ score, className }: SimilarityMeterProps) {
  const value = normalizeSimilarity(score)

  if (value === null) {
    return (
      <span
        className={cn(
          'inline-flex h-6 items-center rounded-full border border-border bg-surface-2 px-2.5 text-2xs text-subtle',
          className,
        )}
      >
        Similarity not reported
      </span>
    )
  }

  const percent = Math.round(value * 100)
  const band = similarityBand(value)

  return (
    <div
      className={cn(
        'inline-flex h-6 items-center gap-2 rounded-full border border-border bg-surface-2 pr-2 pl-2.5',
        className,
      )}
      title={`${band.label} — ${band.description}`}
    >
      <span className="text-2xs font-medium whitespace-nowrap text-muted">{band.label}</span>
      <span
        role="meter"
        aria-valuenow={percent}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-label="Similarity to the query"
        className="h-1 w-12 shrink-0 overflow-hidden rounded-full bg-surface-3"
      >
        <span
          aria-hidden="true"
          className="block h-full rounded-full bg-accent"
          style={{ width: `${percent}%` }}
        />
      </span>
      <span className="numeric w-8 text-right text-2xs font-semibold text-fg">{percent}%</span>
    </div>
  )
}
