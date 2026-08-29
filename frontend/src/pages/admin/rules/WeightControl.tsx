/**
 * Weight control for one rule.
 *
 * A number on its own tells an author nothing, because what a weight *means* is
 * where it lands on the 0-100 risk scale. The control therefore draws the four
 * bands (LOW / MEDIUM / HIGH / CRITICAL) and marks the weight on them, so
 * "35" reads as "this rule alone can carry a customer into MEDIUM".
 */
import { useId } from 'react'
import { RULE_WEIGHT_MAX, RULE_WEIGHT_MIN, RISK_LEVELS } from '../../../api/types'
import { Input } from '../../../components/ui/Input'
import { cn } from '../../../lib/cn'
import { formatNumber } from '../../../lib/format'
import { RISK_BAND_BOUNDS, RISK_LEVEL_STYLES, riskLevelFromScore } from '../../../lib/risk'

export interface WeightControlProps {
  /** Raw draft string, so a half-typed number is never coerced to 0. */
  value: string
  onChange: (value: string) => void
  error?: string | null
  disabled?: boolean
  /** Combined weight of every other rule, used for the share caption. */
  otherRulesWeight?: number | null
}

const SCALE_MAX = 100

export function WeightControl({
  value,
  onChange,
  error,
  disabled = false,
  otherRulesWeight = null,
}: WeightControlProps) {
  const sliderId = useId()
  const weight = Number(value)
  const valid = Number.isFinite(weight) && weight >= 0
  const clamped = valid ? Math.min(weight, SCALE_MAX) : 0
  const level = valid ? riskLevelFromScore(weight) : null

  return (
    <fieldset className="flex flex-col gap-2 rounded-md border border-border bg-surface-2/40 px-3 py-2.5">
      <legend className="sr-only">Weight</legend>
      <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-1">
        <label
          htmlFor={sliderId}
          className="text-2xs font-semibold tracking-caption text-muted uppercase"
        >
          Weight
          <span className="ml-1.5 font-normal normal-case text-subtle">
            the most this rule can add to the score
          </span>
        </label>
        {valid ? (
          <p className="text-2xs text-muted">
            <span className="numeric font-semibold text-fg">
              {formatNumber(weight, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
            </span>{' '}
            of 100 points
            {level ? (
              <>
                {' '}
                — on its own that is{' '}
                <span className={cn('font-semibold', RISK_LEVEL_STYLES[level].text)}>{level}</span>
              </>
            ) : null}
          </p>
        ) : null}
      </div>

      <div className="flex items-center gap-3">
        <input
          id={sliderId}
          type="range"
          min={0}
          max={SCALE_MAX}
          step={0.5}
          disabled={disabled}
          value={clamped}
          onChange={(event) => onChange(event.target.value)}
          className="h-1.5 flex-1 accent-accent"
        />
        <Input
          label="Weight value"
          hideLabel
          type="number"
          min={RULE_WEIGHT_MIN}
          max={RULE_WEIGHT_MAX}
          step={0.5}
          containerClassName="w-24"
          className="numeric text-right"
          value={value}
          disabled={disabled}
          error={error}
          onChange={(event) => onChange(event.target.value)}
        />
      </div>

      {/* The banding is the only thing that makes a weight legible, so it is
          drawn rather than described: four fixed 25-point segments with the
          weight marked on them. */}
      <div aria-hidden="true" className="relative">
        <div className="flex h-2 overflow-hidden rounded-full">
          {RISK_LEVELS.map((band) => (
            <div
              key={band}
              className={cn('h-full opacity-35', RISK_LEVEL_STYLES[band].bar)}
              style={{
                width: `${RISK_BAND_BOUNDS[band].max - RISK_BAND_BOUNDS[band].min}%`,
              }}
            />
          ))}
        </div>
        <span
          className="absolute top-[-3px] h-[14px] w-0.5 rounded-full bg-fg"
          style={{ left: `calc(${clamped}% - 1px)` }}
        />
        <div className="mt-1 flex text-2xs text-subtle">
          {RISK_LEVELS.map((band) => (
            <span
              key={band}
              className="numeric"
              style={{ width: `${RISK_BAND_BOUNDS[band].max - RISK_BAND_BOUNDS[band].min}%` }}
            >
              {RISK_BAND_BOUNDS[band].min}
            </span>
          ))}
        </div>
      </div>

      <p className="text-2xs leading-relaxed text-subtle">
        The agent estimates this rule’s contribution and the backend caps it at the weight, so the
        score recorded for a triggered rule is between 0 and{' '}
        <span className="numeric">{valid ? formatNumber(weight, { maximumFractionDigits: 2 }) : '—'}</span>.
        {weight > SCALE_MAX ? ' A weight above 100 exceeds the whole risk scale on its own.' : ''}
        {otherRulesWeight !== null && valid
          ? ` Every rule together can reach ${formatNumber(otherRulesWeight + weight, { maximumFractionDigits: 2 })}.`
          : ''}
      </p>
    </fieldset>
  )
}
