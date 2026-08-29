/**
 * Per-rule results with an explicit coverage indicator.
 *
 * BUILD_SPEC section 4 makes full rule coverage a graded requirement: every
 * rule in the coverage set is persisted, triggered or not, and each verdict
 * records whether it came from the agent or from the deterministic backfill.
 * This table is where a reviewer verifies that nothing was skipped, so the
 * coverage count leads the panel, triggered rules are grouped away from the
 * quiet ones, and any agent/deterministic disagreement is impossible to miss.
 */
import { Bot, ChevronRight, Scale, ShieldAlert, ShieldCheck, TriangleAlert } from 'lucide-react'
import { Fragment, useId, useState, type ReactNode } from 'react'
import type { EvaluationSource, RiskRule, RuleEvaluation, UUID } from '../../api/types'
import { Badge } from '../../components/ui/Badge'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../../components/ui/Card'
import { EmptyState } from '../../components/ui/EmptyState'
import { ErrorState } from '../../components/ui/ErrorState'
import { Skeleton } from '../../components/ui/Skeleton'
import { cn } from '../../lib/cn'
import { formatNumber, shortId } from '../../lib/format'
import { describeRuleNode, ruleLogicToJson } from '../../lib/rules'
import {
  coverageCountLabel,
  coverageExplanation,
  coverageStatusLabel,
  sortRuleEvaluations,
  type CoverageStats,
} from './coverage'
import { MatchedTransactions } from './MatchedTransactions'

const COLUMN_COUNT = 7

/** Uppercase micro-label shared by the panel captions and column headers. */
const CAPTION = 'text-2xs font-semibold tracking-caption text-muted uppercase'

const HEADER_CELL = cn('px-3 py-2 font-semibold', CAPTION)

function SourceBadge({ source }: { source: EvaluationSource }) {
  if (source === 'DETERMINISTIC_FALLBACK') {
    return (
      <Badge
        tone="outline"
        className="border-dashed"
        icon={<Scale aria-hidden="true" className="size-3" />}
        title="The agent never submitted a verdict for this rule, so the deterministic DSL engine evaluated it during backfill."
      >
        Deterministic fallback
      </Badge>
    )
  }
  return (
    <Badge
      tone="neutral"
      icon={<Bot aria-hidden="true" className="size-3" />}
      title="The agent submitted this verdict itself."
    >
      Agent
    </Badge>
  )
}

/**
 * Decorative divider between the triggered and the non-triggered block.
 * Hidden from assistive tech on purpose: every row already carries its own
 * "Triggered" / "Not triggered" verdict badge, so this is pure signposting.
 */
function GroupRow({
  label,
  count,
  triggered,
}: {
  label: string
  count: number
  triggered: boolean
}) {
  return (
    <tr aria-hidden="true">
      <td colSpan={COLUMN_COUNT} className="border-y border-border bg-surface-2/70 px-3 py-1.5">
        <span className={cn('flex items-center gap-2', CAPTION)}>
          <span
            className={cn(
              'size-1.5 rounded-full',
              triggered ? 'bg-warning' : 'bg-border-strong',
            )}
          />
          {label}
          <span className="numeric font-normal text-subtle">{count}</span>
        </span>
      </td>
    </tr>
  )
}

interface CoverageRowProps {
  evaluation: RuleEvaluation
  rule?: RiskRule
  expanded: boolean
  onToggle: () => void
}

function CoverageRow({ evaluation, rule, expanded, onToggle }: CoverageRowProps) {
  const reactId = useId()
  const panelId = `${reactId}-rule-panel`
  const triggered = evaluation.triggered
  const disputed = evaluation.disagreement === true
  const weight = evaluation.weight ?? rule?.weight ?? null
  const appliesTo = evaluation.appliesTo ?? rule?.appliesTo ?? null

  return (
    <Fragment>
      <tr
        className={cn(
          'border-b border-border/60 transition-colors',
          disputed
            ? 'bg-warning-soft/60'
            : triggered
              ? 'bg-warning-soft/25'
              : 'hover:bg-surface-2/60',
        )}
      >
        <td
          className={cn(
            'px-3 py-2.5 align-top',
            disputed
              ? 'border-l-4 border-l-warning'
              : triggered && 'border-l-2 border-l-warning',
          )}
        >
          <button
            type="button"
            onClick={onToggle}
            aria-expanded={expanded}
            aria-controls={panelId}
            className="group flex w-full items-start gap-1.5 rounded-xs px-1 py-0.5 text-left transition-colors hover:bg-surface-2 focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
          >
            <span
              aria-hidden="true"
              className="mt-0.5 flex size-4 shrink-0 items-center justify-center rounded-xxs border border-border bg-surface text-subtle transition-colors group-hover:border-border-strong group-hover:text-fg"
            >
              <ChevronRight className={cn('size-3 transition-transform', expanded && 'rotate-90')} />
            </span>
            <span className="min-w-0">
              <span className={cn('block text-sm text-fg', triggered && 'font-medium')}>
                {evaluation.ruleName}
              </span>
              <span className="block font-mono text-2xs text-subtle" title={evaluation.ruleId}>
                {shortId(evaluation.ruleId)}
              </span>
            </span>
          </button>
        </td>
        <td className="px-3 py-2.5 align-top">
          {appliesTo ? (
            <Badge tone="neutral">{appliesTo}</Badge>
          ) : (
            <span className="text-muted">—</span>
          )}
        </td>
        <td className="numeric px-3 py-2.5 text-right align-top text-muted">
          {weight === null
            ? '—'
            : formatNumber(weight, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
        </td>
        <td className="px-3 py-2.5 align-top">
          <Badge tone={triggered ? 'warning' : 'neutral'} dot>
            {triggered ? 'Triggered' : 'Not triggered'}
          </Badge>
        </td>
        <td
          className={cn(
            'numeric px-3 py-2.5 text-right align-top',
            triggered ? 'font-semibold text-fg' : 'text-subtle',
          )}
        >
          {triggered ? '+' : ''}
          {formatNumber(evaluation.scoreContribution, {
            minimumFractionDigits: 2,
            maximumFractionDigits: 2,
          })}
        </td>
        <td className="px-3 py-2.5 align-top">
          <div className="flex flex-wrap items-center gap-1.5">
            <SourceBadge source={evaluation.source} />
            {disputed ? (
              <Badge
                tone="warning"
                icon={<TriangleAlert aria-hidden="true" className="size-3" />}
                title="The agent and the deterministic engine disagreed. The deterministic verdict was used for scoring."
              >
                Disagreement
              </Badge>
            ) : null}
          </div>
        </td>
        <td className="px-3 py-2.5 align-top text-muted">
          {evaluation.rationale ? (
            <p className="line-clamp-2 max-w-md text-xs leading-relaxed">{evaluation.rationale}</p>
          ) : (
            <span className="text-xs">No rationale recorded.</span>
          )}
        </td>
      </tr>

      {expanded ? (
        <tr id={panelId} className="border-b border-border/60 bg-surface-2/40">
          <td colSpan={COLUMN_COUNT} className="px-3 py-3">
            <div className="grid gap-4 lg:grid-cols-2">
              <div className="space-y-3">
                <section>
                  <h4 className={CAPTION}>Rationale</h4>
                  <p className="mt-1 text-sm leading-relaxed text-fg">
                    {evaluation.rationale ?? 'The verdict was recorded without a rationale.'}
                  </p>
                </section>

                <section>
                  <h4 className={CAPTION}>Verdict source</h4>
                  <p className="mt-1 text-xs leading-relaxed text-muted">
                    {evaluation.source === 'DETERMINISTIC_FALLBACK'
                      ? 'The agent finished without submitting a verdict for this rule, so the deterministic DSL engine evaluated it during backfill. Coverage stays complete either way.'
                      : 'The agent submitted this verdict through submit_rule_evaluation.'}
                    {evaluation.disagreement
                      ? ' The agent and the deterministic engine disagreed here; the deterministic result was used for scoring.'
                      : ''}
                  </p>
                </section>

                {rule ? (
                  <section>
                    <h4 className={CAPTION}>Threshold logic</h4>
                    <p className="mt-1 font-mono text-2xs leading-relaxed break-words text-fg">
                      {describeRuleNode(rule.thresholdLogic)}
                    </p>
                    <pre className="mt-1.5 max-h-40 overflow-auto rounded-xs border border-border bg-surface px-2.5 py-2 font-mono text-2xs leading-relaxed text-muted">
                      {ruleLogicToJson(rule.thresholdLogic)}
                    </pre>
                  </section>
                ) : null}
              </div>

              <section>
                <h4 className={CAPTION}>
                  Matched transactions
                  {evaluation.transactionIds.length > 0 ? (
                    <span className="numeric ml-1.5 font-normal text-subtle">
                      {evaluation.transactionIds.length}
                    </span>
                  ) : null}
                </h4>
                <div className="mt-1.5">
                  <MatchedTransactions transactionIds={evaluation.transactionIds} />
                </div>
              </section>
            </div>
          </td>
        </tr>
      ) : null}
    </Fragment>
  )
}

/** The headline "18 / 18 rules evaluated" block — the evidence a reviewer looks for first. */
function CoverageMeter({
  stats,
  running,
}: {
  stats: CoverageStats
  running: boolean
}) {
  const Icon = stats.complete ? ShieldCheck : ShieldAlert
  return (
    <div className="flex min-w-0 items-center gap-3.5">
      <span
        aria-hidden="true"
        className={cn(
          'flex size-11 shrink-0 items-center justify-center rounded-md border',
          stats.complete
            ? 'border-accent/35 bg-accent-soft text-accent-soft-fg'
            : 'border-warning/40 bg-warning-soft text-warning-fg',
        )}
      >
        <Icon className="size-5" />
      </span>
      <div className="min-w-0">
        <p
          aria-hidden="true"
          className="numeric text-3xl leading-none font-semibold tracking-tight-swiss text-fg"
        >
          {stats.evaluated}
          <span className="px-1 text-subtle">/</span>
          {stats.total}
        </p>
        <p aria-hidden="true" className={cn('mt-1.5', CAPTION)}>
          Rules evaluated
        </p>
        <span className="sr-only">{coverageCountLabel(stats)}</span>
      </div>
      <Badge tone={stats.complete ? 'accent' : 'warning'} size="md" dot>
        {coverageStatusLabel(stats, running)}
      </Badge>
    </div>
  )
}

export interface RuleCoverageTableProps {
  evaluations: RuleEvaluation[]
  stats: CoverageStats
  /** Optional join on `GET /api/rules`, used to show the threshold logic. */
  rules?: RiskRule[]
  running?: boolean
  loading?: boolean
  error?: unknown
  onRetry?: () => void
  className?: string
}

export function RuleCoverageTable({
  evaluations,
  stats,
  rules,
  running = false,
  loading = false,
  error,
  onRetry,
  className,
}: RuleCoverageTableProps) {
  const [expandedIds, setExpandedIds] = useState<ReadonlySet<UUID>>(() => new Set<UUID>())
  const ruleIndex = new Map<UUID, RiskRule>((rules ?? []).map((rule) => [rule.ruleId, rule]))
  const rows = sortRuleEvaluations(evaluations)
  const triggeredRows = rows.filter((row) => row.triggered)
  const quietRows = rows.filter((row) => !row.triggered)

  const toggle = (ruleId: UUID) => {
    setExpandedIds((current) => {
      const next = new Set(current)
      if (next.has(ruleId)) next.delete(ruleId)
      else next.add(ruleId)
      return next
    })
  }

  const renderRows = (list: RuleEvaluation[]): ReactNode =>
    list.map((evaluation) => (
      <CoverageRow
        key={evaluation.ruleId}
        evaluation={evaluation}
        rule={ruleIndex.get(evaluation.ruleId)}
        expanded={expandedIds.has(evaluation.ruleId)}
        onToggle={() => toggle(evaluation.ruleId)}
      />
    ))

  return (
    <Card className={className}>
      <CardHeader>
        <CardTitle>Rule coverage</CardTitle>
        <CardDescription>
          Every rule that applies to this customer, with its verdict — including the rules that did
          not trigger.
        </CardDescription>
      </CardHeader>

      <CardContent className="border-b border-border bg-surface-2/30">
        <div className="flex flex-wrap items-center justify-between gap-4">
          <CoverageMeter stats={stats} running={running} />
          <div className="flex flex-wrap items-center gap-1.5">
            <Badge tone="neutral" title="Rules whose conditions matched at least one transaction.">
              {stats.triggered} triggered
            </Badge>
            <Badge tone="neutral" title="Verdicts submitted by the agent itself.">
              {stats.agentCount} by agent
            </Badge>
            <Badge
              tone={stats.fallbackCount > 0 ? 'warning' : 'neutral'}
              title="Verdicts produced by the deterministic backfill after the agent stopped short."
            >
              {stats.fallbackCount} deterministic
            </Badge>
            {stats.disagreements > 0 ? (
              <Badge
                tone="warning"
                icon={<TriangleAlert aria-hidden="true" className="size-3" />}
                title="Rules where the agent and the deterministic engine reached different verdicts."
              >
                {stats.disagreements} disputed
              </Badge>
            ) : null}
          </div>
        </div>

        <div
          role="progressbar"
          aria-valuenow={stats.evaluated}
          aria-valuemin={0}
          aria-valuemax={stats.total}
          aria-label="Rules evaluated"
          className="mt-3 h-2 w-full overflow-hidden rounded-full bg-surface-3"
        >
          <div
            className={cn(
              'h-full rounded-full transition-[width] duration-500 ease-out',
              stats.complete ? 'bg-accent' : 'bg-warning',
              running && !stats.complete && 'motion-safe:animate-pulse',
            )}
            style={{ width: `${stats.percent}%` }}
          />
        </div>

        <p className="mt-2 text-xs leading-relaxed text-muted">
          {coverageExplanation(stats, running)}
        </p>

        {stats.complete && stats.agentComplete && stats.fallbackCount === 0 ? (
          <p className="mt-2 inline-flex items-center gap-1.5 text-xs text-fg">
            <ShieldCheck aria-hidden="true" className="size-3.5 text-accent" />
            The coverage gate confirmed the agent evaluated the full rule set.
          </p>
        ) : null}

        {stats.disagreements > 0 ? (
          <div className="mt-3 flex items-start gap-2.5 rounded-xs border border-warning/50 border-l-4 border-l-warning bg-warning-soft/60 p-3">
            <TriangleAlert aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-warning-fg" />
            <div className="min-w-0">
              <p className={cn(CAPTION, 'text-warning-fg')}>
                Agent / deterministic disagreement
              </p>
              <p className="mt-1.5 text-xs leading-relaxed text-fg">
                The agent and the deterministic engine disagreed on {stats.disagreements} rule
                {stats.disagreements === 1 ? '' : 's'}. The deterministic verdict was used for
                scoring — this is the false-negative safety net, and the affected rows are flagged
                below.
              </p>
            </div>
          </div>
        ) : null}
      </CardContent>

      {error ? (
        <ErrorState error={error} onRetry={onRetry} />
      ) : loading && rows.length === 0 ? (
        <div className="space-y-2 px-4 py-4">
          {Array.from({ length: 6 }, (_, index) => (
            <Skeleton key={index} className="h-8 w-full" />
          ))}
        </div>
      ) : rows.length === 0 ? (
        <EmptyState
          compact
          title={running ? 'No verdicts yet' : 'No rule evaluations recorded'}
          description={
            running
              ? 'Rules appear here one by one as the agent submits each verdict.'
              : 'This run did not persist any per-rule results.'
          }
        />
      ) : (
        <div className="w-full overflow-x-auto">
          <table className="w-full border-collapse text-sm">
            <caption className="sr-only">Rule coverage — {coverageCountLabel(stats)}</caption>
            <thead className="bg-surface-2/60">
              <tr>
                <th scope="col" className={cn(HEADER_CELL, 'text-left')}>
                  Rule
                </th>
                <th scope="col" className={cn(HEADER_CELL, 'text-left')}>
                  Applies to
                </th>
                <th scope="col" className={cn(HEADER_CELL, 'text-right')}>
                  Weight
                </th>
                <th scope="col" className={cn(HEADER_CELL, 'text-left')}>
                  Verdict
                </th>
                <th scope="col" className={cn(HEADER_CELL, 'text-right')}>
                  Score
                </th>
                <th scope="col" className={cn(HEADER_CELL, 'text-left')}>
                  Source
                </th>
                <th scope="col" className={cn(HEADER_CELL, 'text-left')}>
                  Rationale
                </th>
              </tr>
            </thead>
            {triggeredRows.length > 0 ? (
              <tbody>
                <GroupRow
                  triggered
                  label="Triggered — contributing to the score"
                  count={triggeredRows.length}
                />
                {renderRows(triggeredRows)}
              </tbody>
            ) : null}
            {quietRows.length > 0 ? (
              <tbody>
                <GroupRow
                  triggered={false}
                  label="Evaluated — no contribution"
                  count={quietRows.length}
                />
                {renderRows(quietRows)}
              </tbody>
            ) : null}
          </table>
        </div>
      )}
    </Card>
  )
}
