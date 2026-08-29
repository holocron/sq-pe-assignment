/**
 * The ReAct trace viewer — the centrepiece of the analysis page.
 *
 * Renders every step of `analysis_runs.trace` (BUILD_SPEC section 4) as an
 * audit log: one vertical timeline, a monospace column for tool arguments and
 * results, restrained per-step accents (grey for routine work, the secondary
 * blue for lookups, the brand orange only where the agent decides something),
 * and `coverage_reprompt` steps called out with a plain-language explanation
 * of why the loop was sent back.
 *
 * A collapsed row has to stand on its own. Twelve rules produce two dozen tool
 * calls with the same name, so each row carries what the step acted on and how
 * it came out — `Submit rule verdict · Structuring… · triggered +30.00 ·
 * rule 3/12` — and consecutive verdicts fold into one block, so the checklist
 * reads as one phase of the run rather than a wall of identical markers. The
 * outcome chip stays on the neutral/accent tokens: per DESIGN_SYSTEM.md the
 * risk ramp means a risk level, and a triggered rule is not one.
 */
import { ChevronRight, ClipboardCheck, Radio, WifiOff } from 'lucide-react'
import { useEffect, useId, useMemo, useRef, useState, type ReactNode } from 'react'
import type { TraceStep, UUID } from '../../api/types'
import { Badge, type BadgeTone } from '../../components/ui/Badge'
import { Button } from '../../components/ui/Button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../../components/ui/Card'
import { EmptyState } from '../../components/ui/EmptyState'
import { RiskBadge } from '../../components/ui/RiskBadge'
import { Skeleton } from '../../components/ui/Skeleton'
import { Spinner } from '../../components/ui/Spinner'
import { cn } from '../../lib/cn'
import { formatDuration } from '../../lib/format'
import {
  TOOL_KIND_LABELS,
  coverageRepromptExplanation,
  coverageSetSize,
  formatJsonValue,
  groupTraceSteps,
  hasJsonContent,
  isVerdictStep,
  missingRuleLabel,
  toolMeta,
  traceStepIdentity,
  traceStepKey,
  traceStepMeta,
  type TraceStepIdentityContext,
  type TraceStepTone,
} from './trace'

/** Timeline marker per step tone. Hairline discs, never a heavy fill. */
const TONE_MARKER: Record<TraceStepTone, string> = {
  neutral: 'border-border bg-surface-2 text-muted',
  info: 'border-info/40 bg-info-soft text-info-fg',
  accent: 'border-accent/45 bg-accent-soft text-accent-soft-fg',
  warning: 'border-warning/45 bg-warning-soft text-warning-fg',
  danger: 'border-danger/45 bg-danger-soft text-danger-fg',
}

/** The step tone names line up 1:1 with the badge tones. */
const TONE_BADGE: Record<TraceStepTone, BadgeTone> = {
  neutral: 'neutral',
  info: 'info',
  accent: 'accent',
  warning: 'warning',
  danger: 'danger',
}

/** Uppercase micro-label shared by every sub-section of a step. */
const CAPTION = 'text-2xs font-semibold tracking-caption text-muted uppercase'

/** Longer reasoning is clamped so the timeline stays scannable. */
const LONG_TEXT = 360

function StepSection({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section>
      <h5 className={CAPTION}>{title}</h5>
      <div className="mt-1.5">{children}</div>
    </section>
  )
}

function CodeBlock({ text, wrap = false }: { text: string; wrap?: boolean }) {
  return (
    <pre
      className={cn(
        'max-h-56 overflow-auto rounded-xs border border-border bg-surface-2 px-2.5 py-2 font-mono text-2xs leading-relaxed text-fg',
        wrap && 'whitespace-pre-wrap break-words',
      )}
    >
      {text}
    </pre>
  )
}

interface TraceStepItemProps {
  step: TraceStep
  last: boolean
  /** The newest step of a live run; its marker keeps ticking. */
  live?: boolean
  expanded: boolean
  onToggle: () => void
  ruleNames?: ReadonlyMap<UUID, string>
  /** What the run itself said about the coverage set, for `rule 3/12`. */
  identityContext?: TraceStepIdentityContext
  /** Rendered inside a verdict block: the block owns the marker and the rail. */
  nested?: boolean
}

function TraceStepItem({
  step,
  last,
  live = false,
  expanded,
  onToggle,
  ruleNames,
  identityContext,
  nested = false,
}: TraceStepItemProps) {
  const meta = traceStepMeta(step)
  const identity = traceStepIdentity(step, { ruleNames, ...identityContext })
  const reactId = useId()
  const panelId = `${reactId}-panel`
  const Icon = meta.icon

  const expandable =
    step.type === 'tool_call' ||
    step.type === 'unknown' ||
    (step.type === 'assistant' && step.text.length > LONG_TEXT)

  const header = (
    <div className="flex min-w-0 flex-1 items-center gap-2">
      {expandable ? (
        <span
          aria-hidden="true"
          className="flex size-4 shrink-0 items-center justify-center rounded-xxs border border-border bg-surface text-subtle transition-colors group-hover:border-border-strong group-hover:text-fg"
        >
          <ChevronRight
            className={cn('size-3 transition-transform', expanded && 'rotate-90')}
          />
        </span>
      ) : (
        <span aria-hidden="true" className="w-4 shrink-0" />
      )}
      <span className={cn('truncate text-sm font-medium text-fg', identity.subject && 'shrink-0')}>
        {meta.label}
      </span>
      {/* The subject is the whole point of the collapsed row: which rule, which
          transaction, which query. It outranks the wire tool name, which is
          dropped when there is something human to show instead. */}
      {identity.subject ? (
        <>
          <span aria-hidden="true" className="shrink-0 text-subtle">
            ·
          </span>
          <span className="min-w-0 flex-1 truncate text-sm text-fg" title={identity.subject}>
            {identity.subject}
          </span>
        </>
      ) : step.type === 'tool_call' ? (
        <code className="hidden truncate rounded-xxs border border-border bg-surface-2 px-1.5 py-0.5 font-mono text-2xs text-muted sm:inline">
          {step.tool}
        </code>
      ) : null}
      {step.type === 'tool_call' ? (
        <Badge tone={TONE_BADGE[meta.tone]}>{TOOL_KIND_LABELS[toolMeta(step.tool).kind]}</Badge>
      ) : null}
      {identity.outcome ? (
        <Badge tone={identity.outcomeTone} className="shrink-0">
          {identity.outcome}
        </Badge>
      ) : null}
      {identity.progress ? (
        <span className="numeric hidden shrink-0 text-2xs text-subtle sm:inline">
          {identity.progress}
        </span>
      ) : null}
      <span className="numeric ml-auto flex shrink-0 items-center gap-3 text-2xs text-subtle">
        <span className="w-14 text-right">
          {step.ms !== null && step.ms !== undefined ? formatDuration(step.ms) : ''}
        </span>
        <span className="w-8 text-right font-mono">#{step.n}</span>
      </span>
    </div>
  )

  const body = (
    <>
      {expandable ? (
        <button
          type="button"
          onClick={onToggle}
          aria-expanded={expanded}
          aria-controls={panelId}
          className="group flex w-full items-center rounded-xs px-1.5 py-1 text-left transition-colors hover:bg-surface-2 focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
        >
          {header}
        </button>
      ) : (
        <div className="flex items-center px-1.5 py-1">{header}</div>
      )}

      {/* Inside a verdict block the description belongs to the block, not to
          each of its twelve rows. */}
      {step.type === 'tool_call' && !nested ? (
        <p className="px-1.5 pl-7 text-xs leading-relaxed text-muted">{meta.description}</p>
      ) : null}

      <div className="px-1.5">
        {step.type === 'tool_call' && expanded ? (
          <div
            id={panelId}
            className="mt-2 ml-5 space-y-3 border-l border-border pl-3.5 animate-fade-in"
          >
            <StepSection title="Arguments">
              {hasJsonContent(step.args) ? (
                <CodeBlock text={formatJsonValue(step.args)} />
              ) : (
                <p className="text-xs text-muted">Called without arguments.</p>
              )}
            </StepSection>
            <StepSection title="Result">
              {step.resultPreview ? (
                <CodeBlock text={step.resultPreview} wrap />
              ) : (
                <p className="text-xs text-muted">
                  The backend did not persist a preview of this result.
                </p>
              )}
            </StepSection>
          </div>
        ) : null}

        {step.type === 'assistant' ? (
          <p
            id={panelId}
            className={cn(
              'mt-1 border-l-2 border-border pl-3 text-sm leading-relaxed whitespace-pre-wrap text-fg',
              step.text.length > LONG_TEXT && !expanded && 'line-clamp-5',
            )}
          >
            {step.text || 'The model produced no text for this step.'}
          </p>
        ) : null}

        {step.type === 'coverage_reprompt' ? (
          <div className="mt-1.5 rounded-xs border border-warning/40 border-l-2 border-l-warning bg-warning-soft/50 p-3">
            <p className={cn('flex items-center gap-1.5', CAPTION, 'text-warning-fg')}>
              Loop sent back for full coverage
            </p>
            <p className="mt-1.5 text-xs leading-relaxed text-fg">
              {coverageRepromptExplanation(step.missing.length)}
            </p>
            {step.missing.length > 0 ? (
              <>
                <p className={cn('mt-2.5', CAPTION)}>Rules still missing a verdict</p>
                <ul className="mt-1.5 flex flex-wrap gap-1.5">
                  {step.missing.map((ruleId) => {
                    const named = ruleNames?.has(ruleId) ?? false
                    const label = missingRuleLabel(ruleId, ruleNames)
                    return (
                      <li key={ruleId}>
                        <Badge
                          tone="warning"
                          title={ruleId}
                          className={named ? undefined : 'font-mono'}
                        >
                          {label}
                        </Badge>
                      </li>
                    )
                  })}
                </ul>
              </>
            ) : null}
          </div>
        ) : null}

        {step.type === 'final' ? (
          <div className="mt-1.5 flex flex-wrap items-center gap-2 rounded-xs border border-accent/35 bg-accent-soft/40 px-2.5 py-2">
            <span className="text-xs text-muted">Verdict submitted by the agent:</span>
            <RiskBadge level={step.riskLevel} size="sm" />
          </div>
        ) : null}

        {step.type === 'error' ? (
          <p className="mt-1.5 rounded-xs border border-danger/40 border-l-2 border-l-danger bg-danger-soft/50 p-3 text-xs leading-relaxed text-fg">
            {step.message}
          </p>
        ) : null}

        {step.type === 'unknown' && expanded ? (
          <div
            id={panelId}
            className="mt-2 ml-5 border-l border-border pl-3.5 animate-fade-in"
          >
            <StepSection title="Raw step">
              <CodeBlock text={formatJsonValue(step.raw)} />
            </StepSection>
          </div>
        ) : null}
      </div>
    </>
  )

  if (nested) {
    return <li className={cn('min-w-0', last ? 'pb-0' : 'pb-1')}>{body}</li>
  }

  return (
    <li className="flex gap-3">
      <div className="flex flex-col items-center">
        <span
          aria-hidden="true"
          className={cn(
            'flex size-7 shrink-0 items-center justify-center rounded-full border',
            TONE_MARKER[meta.tone],
            live && 'motion-safe:animate-pulse',
          )}
        >
          <Icon className="size-3.5" />
        </span>
        {last ? null : <span aria-hidden="true" className="mt-1 w-px grow bg-border" />}
      </div>

      <div className={cn('min-w-0 flex-1', last ? 'pb-0' : 'pb-4')}>{body}</div>
    </li>
  )
}

interface VerdictBlockProps {
  steps: TraceStep[]
  last: boolean
  live?: boolean
  isExpanded: (key: string) => boolean
  onToggle: (key: string) => void
  ruleNames?: ReadonlyMap<UUID, string>
  /** Where each verdict sits in the checklist, for its `rule 3/12` counter. */
  identityContextFor: (step: TraceStep) => TraceStepIdentityContext
}

/**
 * A run of consecutive rule verdicts, under one marker.
 *
 * The agent judging its checklist is one phase of the run; twelve markers down
 * the timeline said the opposite. Every verdict keeps its own row — and its own
 * rule name, score and expandable detail — inside the block.
 */
function VerdictBlock({
  steps,
  last,
  live = false,
  isExpanded,
  onToggle,
  ruleNames,
  identityContextFor,
}: VerdictBlockProps) {
  return (
    <li className="flex gap-3">
      <div className="flex flex-col items-center">
        <span
          aria-hidden="true"
          className={cn(
            'flex size-7 shrink-0 items-center justify-center rounded-full border',
            TONE_MARKER.accent,
            live && 'motion-safe:animate-pulse',
          )}
        >
          <ClipboardCheck className="size-3.5" />
        </span>
        {last ? null : <span aria-hidden="true" className="mt-1 w-px grow bg-border" />}
      </div>

      <div className={cn('min-w-0 flex-1', last ? 'pb-0' : 'pb-4')}>
        <div className="flex items-center gap-2 px-1.5 py-1">
          <span className="text-sm font-medium text-fg">Rule verdicts</span>
          <Badge tone="neutral">
            {steps.length} {steps.length === 1 ? 'rule' : 'rules'} judged
          </Badge>
        </div>
        <p className="px-1.5 text-xs leading-relaxed text-muted">
          The agent worked through its checklist here. Each row is its judgement of one rule and the
          score it estimated, capped at that rule&rsquo;s weight.
        </p>
        <ol className="mt-1.5 ml-1 border-l border-border pl-2.5">
          {steps.map((step, index) => {
            const key = traceStepKey(step)
            return (
              <TraceStepItem
                key={key}
                nested
                step={step}
                last={index === steps.length - 1}
                expanded={isExpanded(key)}
                onToggle={() => onToggle(key)}
                ruleNames={ruleNames}
                identityContext={identityContextFor(step)}
              />
            )
          })}
        </ol>
      </div>
    </li>
  )
}

export interface TraceViewerProps {
  steps: TraceStep[]
  /** Streams new steps in and caps the height so the newest stays in view. */
  running?: boolean
  loading?: boolean
  /** Resolves the rule ids in a `coverage_reprompt` step to rule names. */
  ruleNames?: ReadonlyMap<UUID, string>
  /** Live-transport indicator; omitted when the run is finished. */
  live?: { connected: boolean }
  className?: string
}

export function TraceViewer({
  steps,
  running = false,
  loading = false,
  ruleNames,
  live,
  className,
}: TraceViewerProps) {
  const [expandAll, setExpandAll] = useState(false)
  const [overrides, setOverrides] = useState<Record<string, boolean>>({})
  const bottomRef = useRef<HTMLDivElement | null>(null)

  const stepCount = steps.length
  useEffect(() => {
    if (!running || stepCount === 0) return
    const node = bottomRef.current
    if (node && typeof node.scrollIntoView === 'function') {
      node.scrollIntoView({ block: 'nearest' })
    }
  }, [running, stepCount])

  const toolCallCount = useMemo(
    () => steps.filter((step) => step.type === 'tool_call').length,
    [steps],
  )
  const repromptCount = useMemo(
    () => steps.filter((step) => step.type === 'coverage_reprompt').length,
    [steps],
  )

  const blocks = useMemo(() => groupTraceSteps(steps), [steps])

  /* Where a verdict is in the checklist, counted off the trace itself, so a
     step whose result preview was not persisted still reads "rule 3/12". */
  const ordinals = useMemo(() => {
    const map = new Map<string, number>()
    let judged = 0
    for (const step of steps) {
      if (isVerdictStep(step)) map.set(traceStepKey(step), ++judged)
    }
    return map
  }, [steps])
  const ruleCount = useMemo(() => coverageSetSize(steps) ?? undefined, [steps])

  const isExpanded = (key: string) => overrides[key] ?? expandAll
  const toggle = (key: string) => {
    const next = !isExpanded(key)
    setOverrides((current) => ({ ...current, [key]: next }))
  }
  const identityContextFor = (step: TraceStep): TraceStepIdentityContext => ({
    ruleOrdinal: ordinals.get(traceStepKey(step)),
    ruleCount,
  })

  return (
    <Card className={className}>
      <CardHeader
        actions={
          <div className="flex items-center gap-2">
            {live ? (
              <Badge tone={live.connected ? 'info' : 'warning'}>
                {live.connected ? (
                  <>
                    <Radio aria-hidden="true" className="size-3" />
                    Live
                  </>
                ) : (
                  <>
                    <WifiOff aria-hidden="true" className="size-3" />
                    Polling
                  </>
                )}
              </Badge>
            ) : null}
            <Button
              size="sm"
              variant="ghost"
              onClick={() => {
                setExpandAll((current) => !current)
                setOverrides({})
              }}
              disabled={steps.length === 0}
            >
              {expandAll ? 'Collapse all' : 'Expand all'}
            </Button>
          </div>
        }
      >
        <CardTitle>Agent reasoning trace</CardTitle>
        <CardDescription>
          {stepCount > 0
            ? `${stepCount} steps · ${toolCallCount} tool calls${
                repromptCount > 0
                  ? ` · ${repromptCount} coverage reprompt${repromptCount === 1 ? '' : 's'}`
                  : ''
              }`
            : 'Every step of the ReAct loop, in the order the agent took it.'}
        </CardDescription>
      </CardHeader>

      <CardContent
        className={cn('py-4', running && 'max-h-[34rem] overflow-y-auto')}
        aria-busy={running || loading || undefined}
      >
        {loading && stepCount === 0 ? (
          <div className="space-y-4">
            {Array.from({ length: 4 }, (_, index) => (
              <div key={index} className="flex gap-3">
                <Skeleton className="size-7 shrink-0" pill />
                <div className="flex-1 space-y-2">
                  <Skeleton className="h-3.5 w-48" />
                  <Skeleton className="h-3 w-72" />
                </div>
              </div>
            ))}
          </div>
        ) : stepCount === 0 ? (
          running ? (
            <div className="flex items-center gap-2.5 px-1 py-4 text-sm text-muted">
              <Spinner size="sm" label="Waiting for the agent" className="text-accent" />
              Waiting for the agent&rsquo;s first step&hellip;
            </div>
          ) : (
            <EmptyState
              compact
              title="No reasoning trace recorded"
              description="This run did not persist a trace. Nothing was hidden — the transcript simply was not stored."
            />
          )
        ) : (
          <ol className="space-y-0">
            {blocks.map((block, index) => {
              const last = index === blocks.length - 1
              if (block.kind === 'verdicts') {
                const first = block.steps[0]
                return (
                  <VerdictBlock
                    key={`verdicts:${traceStepKey(first)}`}
                    steps={block.steps}
                    last={last && !running}
                    live={last && running}
                    isExpanded={isExpanded}
                    onToggle={toggle}
                    ruleNames={ruleNames}
                    identityContextFor={identityContextFor}
                  />
                )
              }
              const key = traceStepKey(block.step)
              return (
                <TraceStepItem
                  key={key}
                  step={block.step}
                  last={last && !running}
                  live={last && running}
                  expanded={isExpanded(key)}
                  onToggle={() => toggle(key)}
                  ruleNames={ruleNames}
                  identityContext={identityContextFor(block.step)}
                />
              )
            })}
            {running ? (
              <li className="flex gap-3" aria-hidden="true">
                <div className="flex w-7 justify-center">
                  <span className="mt-1 size-2 rounded-full bg-accent motion-safe:animate-pulse" />
                </div>
                <p className="px-1.5 text-xs text-subtle">The agent is taking its next step…</p>
              </li>
            ) : null}
          </ol>
        )}
        <div ref={bottomRef} />
      </CardContent>
    </Card>
  )
}
