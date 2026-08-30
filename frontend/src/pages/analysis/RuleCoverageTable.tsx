/**
 * Per-rule results with an explicit coverage indicator.
 *
 * Full rule coverage is the guarantee this table exists to evidence: every rule
 * in the coverage set is persisted, triggered or not, and a run may only be
 * COMPLETED when every one of them has a verdict.
 *
 * What each verdict *is* changed, and the table is the place that has to say so.
 * The agent writes a SQL query for the rule condition, Postgres runs it, and
 * triggered means the query returned rows; the score is then the rule's weight
 * or nothing. So every row names the database as the decider, and expanding a
 * row shows the statement that produced the verdict alongside the transactions
 * it returned — the audit trail is the query itself, not a paraphrase of it.
 */
import { Bot, ChevronRight, Database, ShieldAlert, ShieldCheck, TriangleAlert } from 'lucide-react'
import { Fragment, useId, useState, type ReactNode } from 'react'
import type { RiskRule, RuleEvaluation, UUID } from '../../api/types'
import { Badge } from '../../components/ui/Badge'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../../components/ui/Card'
import { EmptyState } from '../../components/ui/EmptyState'
import { ErrorState } from '../../components/ui/ErrorState'
import { Skeleton } from '../../components/ui/Skeleton'
import { cn } from '../../lib/cn'
import { formatDuration, formatNumber, shortId } from '../../lib/format'
import {
  coverageCountLabel,
  coverageExplanation,
  coverageStatusLabel,
  sortRuleEvaluations,
  type CoverageStats,
} from './coverage'
import { MatchedTransactions } from './MatchedTransactions'
import { SqlBlock } from './SqlBlock'
import { evidenceCapped, scoreDerivation, sqlFailure, verdictProvenance } from './sql'

const COLUMN_COUNT = 7

/** Uppercase micro-label shared by the panel captions and column headers. */
const CAPTION = 'text-2xs font-semibold tracking-caption text-muted uppercase'

const HEADER_CELL = cn('px-3 py-2 font-semibold', CAPTION)

/**
 * Who actually made the comparison, on every row.
 *
 * "Agent judged" would now be a lie for a current run: the agent chose the
 * query, the database decided the verdict. It is still the truth for a run
 * stored before the change, and those runs keep the old label rather than being
 * retro-badged into an assurance nobody gave.
 */
function SourceBadge({ evaluation }: { evaluation: RuleEvaluation }) {
  const provenance = verdictProvenance(evaluation)
  return (
    <Badge
      tone="neutral"
      icon={
        provenance.bySql ? (
          <Database aria-hidden="true" className="size-3" />
        ) : (
          <Bot aria-hidden="true" className="size-3" />
        )
      }
      title={provenance.title}
    >
      {provenance.label}
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

/**
 * What Postgres answered, in one line under the statement.
 *
 * The row count is the verdict, so it is stated rather than left to be inferred
 * from the evidence table — which may be a capped sample of it.
 */
function sqlNote(evaluation: RuleEvaluation): string | undefined {
  const sql = evaluation.sql
  if (!sql || !sql.ok) return undefined
  const rows = sql.matchedCount ?? evaluation.matchedCount ?? null
  const counted =
    rows === null
      ? evaluation.triggered
        ? 'Postgres returned rows, so the rule is triggered.'
        : 'Postgres returned no rows, so the rule is not triggered.'
      : `Postgres returned ${formatNumber(rows)} row${rows === 1 ? '' : 's'}, so the rule is ${
          evaluation.triggered ? 'triggered' : 'not triggered'
        }.`
  const timing = sql.ms === null ? '' : ` Took ${formatDuration(sql.ms)}.`
  const capped = evidenceCapped(evaluation)
    ? ' The evidence list beside it is a capped sample of them.'
    : ''
  return `${counted}${timing}${capped}`
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
  const weight = evaluation.weight ?? rule?.weight ?? null
  const appliesTo = evaluation.appliesTo ?? rule?.appliesTo ?? null
  /* `matchedTransactionIds` is the wire name; a run rebuilt from the assessment
     rows alone carries no evidence ids, so never dereference it blind. */
  const matchedIds = evaluation.matchedTransactionIds ?? []
  const sql = evaluation.sql ?? null
  const provenance = verdictProvenance(evaluation)
  const failure = sqlFailure(sql)
  const sampled = evidenceCapped(evaluation)

  return (
    <Fragment>
      <tr
        className={cn(
          'border-b border-border/60 transition-colors',
          triggered ? 'bg-warning-soft/25' : 'hover:bg-surface-2/60',
        )}
      >
        <td className={cn('px-3 py-2.5 align-top', triggered && 'border-l-2 border-l-warning')}>
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
          {formatNumber(evaluation.score, {
            minimumFractionDigits: 2,
            maximumFractionDigits: 2,
          })}
        </td>
        <td className="px-3 py-2.5 align-top">
          <div className="flex flex-wrap items-center gap-1.5">
            <SourceBadge evaluation={evaluation} />
            {failure ? (
              <Badge
                tone="danger"
                icon={<TriangleAlert aria-hidden="true" className="size-3" />}
                title={failure.reason}
              >
                {failure.label}
              </Badge>
            ) : null}
            {sampled ? (
              <Badge
                tone="warning"
                icon={<TriangleAlert aria-hidden="true" className="size-3" />}
                title="The query matched more transactions than the run stores ids for. The count is the true total; the evidence list below is a sample of it."
              >
                Evidence capped
              </Badge>
            ) : null}
          </div>
        </td>
        <td className="px-3 py-2.5 align-top text-muted">
          {evaluation.rationale ? (
            <p className="line-clamp-2 max-w-md text-xs leading-relaxed">{evaluation.rationale}</p>
          ) : (
            <span className="text-xs">No explanation recorded.</span>
          )}
        </td>
      </tr>

      {expanded ? (
        <tr id={panelId} className="border-b border-border/60 bg-surface-2/40">
          <td colSpan={COLUMN_COUNT} className="px-3 py-3">
            <div className="grid gap-4 lg:grid-cols-2">
              <div className="space-y-3">
                {rule ? (
                  <section>
                    <h4 className={CAPTION}>Rule condition</h4>
                    <p className="mt-1 text-xs leading-relaxed whitespace-pre-line text-fg">
                      {rule.thresholdLogic}
                    </p>
                    <p className="mt-1.5 text-2xs leading-relaxed text-subtle">
                      This is the text the agent was shown, word for word, and translated into the
                      query below.
                    </p>
                  </section>
                ) : null}

                {failure ? (
                  <div className="rounded-xs border border-danger/40 border-l-2 border-l-danger bg-danger-soft/50 p-3">
                    <p className={cn(CAPTION, 'text-danger-fg')}>{failure.label}</p>
                    <p className="mt-1.5 font-mono text-2xs leading-relaxed break-words text-fg">
                      {failure.reason}
                    </p>
                    <p className="mt-1.5 text-2xs leading-relaxed text-muted">
                      {failure.kind === 'rejected'
                        ? 'Validation refused to run this statement, so nothing was measured.'
                        : 'Postgres could not execute this statement, so nothing was measured.'}{' '}
                      A rule whose query never ran is unjudged — it is not a cleared rule.
                    </p>
                  </div>
                ) : null}

                {sql?.sql ? (
                  <SqlBlock
                    title="Query that decided this verdict"
                    label={`SQL that decided ${evaluation.ruleName}`}
                    sql={sql.sql}
                    note={sqlNote(evaluation)}
                  />
                ) : null}

                {sql?.effectiveSql && sql.effectiveSql !== sql.sql ? (
                  <SqlBlock
                    secondary
                    title="Statement executed"
                    label={`Statement executed for ${evaluation.ruleName}`}
                    sql={sql.effectiveSql}
                    note="The agent’s fragment after the backend scoped it to this customer. This is the text Postgres actually ran."
                  />
                ) : null}

                <section>
                  <h4 className={CAPTION}>
                    {provenance.bySql ? 'What the query looks for' : 'Rationale'}
                  </h4>
                  <p className="mt-1 text-sm leading-relaxed text-fg">
                    {evaluation.rationale ??
                      (provenance.bySql
                        ? 'The agent recorded no account of what this query looks for.'
                        : 'The verdict was recorded without a rationale.')}
                  </p>
                </section>

                <section>
                  <h4 className={CAPTION}>How this verdict was reached</h4>
                  <p className="mt-1 text-xs leading-relaxed text-muted">
                    {provenance.bySql ? (
                      <>
                        The agent wrote the query above; Postgres executed it and the verdict follows
                        from the result — rows means triggered. {scoreDerivation(evaluation)} The
                        model made no comparison and estimated no number here.
                      </>
                    ) : (
                      <>
                        This verdict predates SQL evaluation: the agent read the rule condition,
                        gathered the evidence with its tools and decided the comparison itself, so
                        the score is its estimate rather than the rule&rsquo;s weight applied to a
                        row count.
                      </>
                    )}
                  </p>
                </section>
              </div>

              <section>
                <h4 className={CAPTION}>
                  {provenance.bySql
                    ? 'Transactions the query returned'
                    : 'Transactions cited as evidence'}
                  {matchedIds.length > 0 ? (
                    <span className="numeric ml-1.5 font-normal text-subtle">
                      {sampled
                        ? `${matchedIds.length} of ${formatNumber(
                            evaluation.sql?.matchedCount ?? evaluation.matchedCount ?? 0,
                          )}`
                        : matchedIds.length}
                    </span>
                  ) : null}
                </h4>
                <div className="mt-1.5">
                  <MatchedTransactions
                    transactionIds={matchedIds}
                    emptyLabel={
                      provenance.bySql
                        ? failure
                          ? 'The query never ran, so no transaction was returned.'
                          : 'The query returned no transactions.'
                        : 'The agent cited no transaction for this rule.'
                    }
                  />
                </div>
              </section>
            </div>
          </td>
        </tr>
      ) : null}
    </Fragment>
  )
}

/** The headline "18 / 18 rules answered" block — the evidence a reviewer looks for first. */
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
          Rules answered
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
  /** Optional join on `GET /api/rules`, used to show the rule condition verbatim. */
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
          Every rule that applies to this customer, with the query that decided it — including the
          rules the database cleared. Expand a rule to read the statement that produced its verdict.
        </CardDescription>
      </CardHeader>

      <CardContent className="border-b border-border bg-surface-2/30">
        <div className="flex flex-wrap items-center justify-between gap-4">
          <CoverageMeter stats={stats} running={running} />
          <div className="flex flex-wrap items-center gap-1.5">
            <Badge tone="neutral" title="Rules whose query returned at least one transaction.">
              {stats.triggered} triggered
            </Badge>
            <Badge
              tone={stats.unjudged > 0 ? 'warning' : 'neutral'}
              title="Rules of the coverage set that never received a verdict. A run that ends this way is recorded FAILED."
            >
              {stats.unjudged} unjudged
            </Badge>
            {stats.sqlBacked > 0 ? (
              <Badge
                tone="neutral"
                icon={<Database aria-hidden="true" className="size-3" />}
                title="Verdicts that carry the statement Postgres answered them with. Expand a rule to read it."
              >
                {stats.sqlBacked} decided by query
              </Badge>
            ) : null}
            {stats.sqlFailed > 0 ? (
              <Badge
                tone="danger"
                icon={<TriangleAlert aria-hidden="true" className="size-3" />}
                title="Rules whose query was rejected or errored. Such a rule was never measured and must not be read as cleared."
              >
                {stats.sqlFailed} query failed
              </Badge>
            ) : null}
          </div>
        </div>

        <div
          role="progressbar"
          aria-valuenow={stats.evaluated}
          aria-valuemin={0}
          aria-valuemax={stats.total}
          aria-label="Rules answered"
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

        {stats.complete ? (
          <p className="mt-2 inline-flex items-center gap-1.5 text-xs text-fg">
            <ShieldCheck aria-hidden="true" className="size-3.5 text-accent" />
            The coverage gate confirmed every rule of the set reached a verdict.
          </p>
        ) : null}

        {!stats.complete && !running ? (
          <div className="mt-3 flex items-start gap-2.5 rounded-xs border border-warning/50 border-l-4 border-l-warning bg-warning-soft/60 p-3">
            <TriangleAlert aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-warning-fg" />
            <div className="min-w-0">
              <p className={cn(CAPTION, 'text-warning-fg')}>Incomplete review</p>
              <p className="mt-1.5 text-xs leading-relaxed text-fg">
                No verdict was ever recorded for{' '}
                {stats.unjudged > 0 ? stats.unjudged : stats.total - stats.evaluated} rule
                {stats.unjudged === 1 ? '' : 's'}. Nothing fills that gap in, and an unanswered rule
                is never recorded as cleared — the run is FAILED and the verdicts below are partial.
                Re-run the analysis before relying on this assessment.
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
                  Decided by
                </th>
                <th scope="col" className={cn(HEADER_CELL, 'text-left')}>
                  Explanation
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
                  label="Answered and cleared — no contribution"
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
