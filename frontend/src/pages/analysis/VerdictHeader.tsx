/**
 * The verdict header — the formal risk determination for a run.
 *
 * Reads top-down like a decision record: the level as the canonical filled
 * RiskBadge, the total score in large tabular figures on the banded 0–100
 * scale, and then a quiet hairline strip of audit facts (model, steps,
 * duration, coverage, requester, timestamp).
 */
import { Clock, Cpu, Layers, ShieldCheck, UserRound } from 'lucide-react'
import type { ReactNode } from 'react'
import { RISK_LEVELS, type AnalysisResult, type AnalysisStatus } from '../../api/types'
import { Badge } from '../../components/ui/Badge'
import { Card } from '../../components/ui/Card'
import { RiskBadge } from '../../components/ui/RiskBadge'
import { Spinner } from '../../components/ui/Spinner'
import { cn } from '../../lib/cn'
import { EM_DASH, formatDateTime, formatDuration, formatNumber } from '../../lib/format'
import { RISK_BAND_BOUNDS, RISK_LEVEL_STYLES, riskLevelFromScore } from '../../lib/risk'
import { coverageCountLabel, type CoverageStats } from './coverage'

const STATUS_LABEL: Record<AnalysisStatus, string> = {
  RUNNING: 'Running',
  COMPLETED: 'Completed',
  FAILED: 'Failed',
}

/** Uppercase micro-label used for every field caption in the header. */
const CAPTION = 'text-2xs font-semibold tracking-caption text-muted uppercase'

function StatusChip({ status }: { status: AnalysisStatus }) {
  if (status === 'RUNNING') {
    return (
      <Badge tone="info" size="md">
        <Spinner size="xs" label="Running" />
        {STATUS_LABEL.RUNNING}
      </Badge>
    )
  }
  return (
    <Badge tone={status === 'FAILED' ? 'danger' : 'neutral'} size="md" dot>
      {STATUS_LABEL[status]}
    </Badge>
  )
}

/** The 0–100 scale with the four risk bands and a marker at the total score. */
function ScoreScale({ score }: { score: number | null }) {
  const value = typeof score === 'number' && Number.isFinite(score) ? score : null
  const percent = value === null ? null : Math.max(0, Math.min(100, value))
  const band = riskLevelFromScore(value)

  return (
    <div className="mt-3">
      <div className="relative pt-2">
        <div
          className="flex h-2 w-full overflow-hidden rounded-full bg-surface-3"
          aria-hidden="true"
        >
          {RISK_LEVELS.map((level) => (
            <span
              key={level}
              className={cn(
                'flex-1 border-r border-surface transition-opacity last:border-r-0',
                RISK_LEVEL_STYLES[level].bar,
                band === null ? 'opacity-25' : band === level ? 'opacity-100' : 'opacity-20',
              )}
            />
          ))}
        </div>
        {percent === null ? null : (
          <span
            aria-hidden="true"
            className="absolute top-0 flex -translate-x-1/2 flex-col items-center"
            style={{ left: `${percent}%` }}
          >
            <span className="size-1.5 rotate-45 rounded-xxs bg-fg" />
            <span className="-mt-0.5 h-4 w-px bg-fg" />
          </span>
        )}
      </div>
      <div className="numeric mt-1.5 flex justify-between text-2xs text-subtle">
        <span>0</span>
        <span>25</span>
        <span>50</span>
        <span>75</span>
        <span>100</span>
      </div>
    </div>
  )
}

function Fact({
  label,
  value,
  icon,
  mono = false,
}: {
  label: string
  value: ReactNode
  icon?: ReactNode
  mono?: boolean
}) {
  return (
    <div className="min-w-0 bg-surface px-4 py-2.5">
      <dt className={cn('flex items-center gap-1.5', CAPTION)}>
        {icon}
        {label}
      </dt>
      <dd className={cn('mt-1 truncate text-sm text-fg', mono && 'font-mono text-xs')}>{value}</dd>
    </div>
  )
}

export interface VerdictHeaderProps {
  analysis: AnalysisResult
  stats: CoverageStats
  /** Live elapsed time while the run is still going. */
  elapsedMs?: number | null
  className?: string
}

export function VerdictHeader({ analysis, stats, elapsedMs, className }: VerdictHeaderProps) {
  const running = analysis.status === 'RUNNING'
  const duration = analysis.durationMs ?? (running ? elapsedMs : null)
  const score =
    analysis.totalScore === null || analysis.totalScore === undefined ? null : analysis.totalScore
  const level = analysis.riskLevel ?? null
  const band = level ? RISK_BAND_BOUNDS[level] : null

  return (
    <Card
      className={cn(
        // Risk-coloured left rail, always paired with the labelled badge below
        // so the colour is never the only signal.
        'overflow-hidden border-l-4',
        level ? RISK_LEVEL_STYLES[level].accentBorder : 'border-l-border-strong',
        className,
      )}
    >
      <div className="grid gap-5 p-4 sm:p-5 lg:grid-cols-[minmax(0,1fr)_minmax(0,22rem)] lg:gap-8">
        <div className="min-w-0">
          <p className={CAPTION}>Risk determination</p>
          <div className="mt-2 flex flex-wrap items-center gap-2">
            <RiskBadge level={analysis.riskLevel} size="lg" />
            <StatusChip status={analysis.status} />
          </div>
          <p className="mt-2 max-w-md text-xs leading-relaxed text-muted">
            {running
              ? 'The agent is still working through the coverage set — the level is only final once every rule has a verdict.'
              : level
                ? `Determined from ${coverageCountLabel(stats)} and the weighted score below.`
                : 'This run never reached a determination.'}
          </p>
        </div>

        <div className="min-w-0">
          <div className="flex items-start justify-between gap-4">
            <div className="min-w-0">
              <p className={CAPTION}>Total risk score</p>
              <p className="mt-1 flex items-baseline gap-1.5">
                <span className="numeric text-4xl leading-none font-semibold tracking-tight-swiss text-fg">
                  {score === null ? EM_DASH : formatNumber(score, { maximumFractionDigits: 2 })}
                </span>
                <span className="numeric text-sm font-medium text-subtle">
                  {running && score === null ? 'pending' : '/ 100'}
                </span>
              </p>
            </div>
            {band && level ? (
              <p className="shrink-0 text-right">
                <span className={cn(CAPTION, 'block')}>Band</span>
                <span className="numeric mt-1 block text-xs font-medium text-fg">
                  {band.min}–{band.max}
                </span>
              </p>
            ) : null}
          </div>
          <ScoreScale score={score} />
        </div>
      </div>

      <dl className="grid grid-cols-2 gap-px border-t border-border bg-border sm:grid-cols-3 lg:grid-cols-6">
        <Fact
          label="Model"
          icon={<Cpu aria-hidden="true" className="size-3" />}
          value={analysis.model ?? EM_DASH}
          mono
        />
        <Fact
          label="Steps"
          icon={<Layers aria-hidden="true" className="size-3" />}
          value={
            <span className="numeric">
              {analysis.steps === null || analysis.steps === undefined
                ? EM_DASH
                : formatNumber(analysis.steps)}
            </span>
          }
        />
        <Fact
          label="Duration"
          icon={<Clock aria-hidden="true" className="size-3" />}
          value={<span className="numeric">{formatDuration(duration)}</span>}
        />
        <Fact
          label="Rule coverage"
          icon={<ShieldCheck aria-hidden="true" className="size-3" />}
          value={<span className="numeric">{coverageCountLabel(stats)}</span>}
        />
        <Fact
          label="Requested by"
          icon={<UserRound aria-hidden="true" className="size-3" />}
          value={analysis.requestedBy ?? EM_DASH}
        />
        <Fact
          label={running ? 'Started' : 'Completed'}
          value={
            <span className="numeric">
              {formatDateTime(running ? analysis.createdAt : analysis.completedAt)}
            </span>
          }
        />
      </dl>
    </Card>
  )
}
