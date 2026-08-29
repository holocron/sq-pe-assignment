/**
 * The "this is still working" panel for a RUNNING analysis.
 *
 * The agent loop takes minutes, so the page always shows something moving:
 * a coverage progress rail, a ticking elapsed clock, the step the agent is on
 * right now, and whether updates arrive over SSE or through the polling
 * fallback. Motion is decorative only and is dropped for reduced-motion users.
 */
import { Radio, WifiOff } from 'lucide-react'
import type { TraceStep } from '../../api/types'
import { Badge } from '../../components/ui/Badge'
import { Card } from '../../components/ui/Card'
import { Spinner } from '../../components/ui/Spinner'
import { cn } from '../../lib/cn'
import { formatDuration } from '../../lib/format'
import { traceStepSummary } from './trace'

/** Uppercase micro-label, matching the verdict header. */
const CAPTION = 'text-2xs font-semibold tracking-caption text-muted uppercase'

const DOT_DELAYS = ['0ms', '240ms', '480ms']

/** Three staggered dots — the cheapest honest "still alive" signal. */
function ActivityDots() {
  return (
    <span aria-hidden="true" className="flex items-center gap-1">
      {DOT_DELAYS.map((delay) => (
        <span
          key={delay}
          style={{ animationDelay: delay }}
          className="size-1 rounded-full bg-accent motion-safe:animate-pulse"
        />
      ))}
    </span>
  )
}

export interface LiveRunPanelProps {
  elapsedMs: number | null
  stepCount: number
  maxSteps?: number
  connected: boolean
  streamError?: string | null
  /** Poll interval used while the stream is down, in milliseconds. */
  pollIntervalMs: number
  lastStep?: TraceStep | null
  /** 0..100 rule-coverage progress; falls back to an indeterminate rail. */
  progressPercent?: number | null
  /** Short caption for the progress figure, e.g. `4 / 18 rules`. */
  progressLabel?: string | null
  className?: string
}

export function LiveRunPanel({
  elapsedMs,
  stepCount,
  maxSteps,
  connected,
  streamError,
  pollIntervalMs,
  lastStep,
  progressPercent = null,
  progressLabel = null,
  className,
}: LiveRunPanelProps) {
  const determinate = typeof progressPercent === 'number' && Number.isFinite(progressPercent)

  return (
    <Card className={cn('overflow-hidden border-l-4 border-l-accent', className)}>
      <div className="h-1 w-full bg-surface-3" aria-hidden="true">
        {determinate ? (
          <div
            className="h-full bg-accent transition-[width] duration-700 ease-out"
            style={{ width: `${Math.max(2, Math.min(100, progressPercent ?? 0))}%` }}
          />
        ) : (
          <div className="h-full w-full bg-accent/40 motion-safe:animate-pulse" />
        )}
      </div>

      <div className="flex flex-wrap items-center justify-between gap-x-6 gap-y-3 px-4 py-3">
        <div className="flex min-w-0 items-start gap-3">
          <Spinner size="sm" label="Run in progress" className="mt-0.5 text-accent" />
          <div className="min-w-0">
            <p className="flex items-center gap-2 text-sm font-medium text-fg">
              Analysis running
              <ActivityDots />
            </p>
            <p role="status" aria-live="polite" className="truncate text-xs text-muted">
              Step {stepCount}
              {maxSteps ? ` of at most ${maxSteps}` : ''} — {traceStepSummary(lastStep)}
            </p>
          </div>
        </div>

        <div className="flex flex-wrap items-center gap-x-6 gap-y-2">
          {progressLabel ? (
            <div className="text-right">
              <p className={CAPTION}>Coverage</p>
              <p className="numeric text-sm font-medium text-fg">{progressLabel}</p>
            </div>
          ) : null}
          <div className="text-right">
            <p className={CAPTION}>Elapsed</p>
            <p className="numeric text-sm font-medium text-fg">
              {elapsedMs === null ? '—' : formatDuration(elapsedMs)}
            </p>
          </div>
          <Badge tone={connected ? 'info' : 'warning'} size="md">
            {connected ? (
              <>
                <Radio aria-hidden="true" className="size-3" />
                Live stream
              </>
            ) : (
              <>
                <WifiOff aria-hidden="true" className="size-3" />
                Polling every {Math.round(pollIntervalMs / 1000)}s
              </>
            )}
          </Badge>
        </div>
      </div>

      {!connected && streamError ? (
        <p className="border-t border-border px-4 py-2 text-xs leading-relaxed text-muted">
          {streamError} Results keep updating — the page falls back to reloading the analysis every{' '}
          {Math.round(pollIntervalMs / 1000)} seconds until the run finishes.
        </p>
      ) : null}
    </Card>
  )
}
