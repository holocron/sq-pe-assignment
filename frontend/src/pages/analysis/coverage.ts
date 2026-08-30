/**
 * Rule-coverage arithmetic for the analysis page.
 *
 * The coverage guarantee is what these helpers make visible: one row lands in
 * `risk_assessments` for every rule that was answered, triggered or not, and a
 * run may only reach COMPLETED when every applicable rule has a verdict. A rule
 * whose query never executed is unjudged, not "not triggered" — so an incomplete
 * coverage set is not a footnote on a finished review, it is the reason the run
 * was stored as FAILED, and the wording here says exactly that.
 */
import type { AnalysisResult, AnalysisSummary, RuleEvaluation } from '../../api/types'

export interface CoverageStats {
  /** Size of the coverage set (`analysis_runs.rules_total`). */
  total: number
  /** Rules that have a persisted agent verdict. */
  evaluated: number
  triggered: number
  /** Rules in the coverage set that never received a verdict. */
  unjudged: number
  /** Every rule in the coverage set has a verdict. */
  complete: boolean
  /** 0..100, for the coverage meter. */
  percent: number
  /** Sum of the persisted score contributions. */
  scoreFromRules: number
  /** Verdicts that carry the query Postgres answered them with. */
  sqlBacked: number
  /**
   * Verdicts whose query is on record as rejected or errored. Such a rule was
   * never decided, so a run that persisted one is not the complete review its
   * counters claim — the panel says so instead of letting it read as cleared.
   */
  sqlFailed: number
}

export type CoverageInput = Pick<
  AnalysisSummary,
  'rulesTotal' | 'rulesEvaluated' | 'coverageComplete'
> &
  Partial<Pick<AnalysisResult, 'ruleEvaluations'>>

export function coverageStats(analysis: CoverageInput): CoverageStats {
  const evaluations = analysis.ruleEvaluations ?? []
  const persistedEvaluated =
    typeof analysis.rulesEvaluated === 'number' ? analysis.rulesEvaluated : 0
  const evaluated = Math.max(evaluations.length, persistedEvaluated)
  const total = Math.max(
    typeof analysis.rulesTotal === 'number' ? analysis.rulesTotal : 0,
    evaluated,
  )

  const triggered = evaluations.filter((item) => item.triggered).length
  const sqlBacked = evaluations.filter((item) => item.sql != null).length
  const sqlFailed = evaluations.filter((item) => item.sql != null && !item.sql.ok).length
  const scoreFromRules = evaluations.reduce(
    (sum, item) => sum + (Number.isFinite(item.score) ? item.score : 0),
    0,
  )

  /* `coverage_complete` is the backend's own verdict and it is the column that
     gates COMPLETED, so it wins whenever it is present; the count is only the
     fallback for a summary row that does not carry it. */
  const counted = total > 0 && evaluated >= total
  const complete =
    typeof analysis.coverageComplete === 'boolean' ? analysis.coverageComplete : counted

  return {
    total,
    evaluated,
    triggered,
    unjudged: Math.max(0, total - evaluated),
    complete,
    percent: total > 0 ? Math.min(100, Math.round((evaluated / total) * 100)) : 0,
    scoreFromRules,
    sqlBacked,
    sqlFailed,
  }
}

/** `18 / 18 rules answered` — rendered as a single text node so it reads cleanly. */
export function coverageCountLabel(stats: CoverageStats): string {
  return `${stats.evaluated} / ${stats.total} rules answered`
}

export function coverageStatusLabel(stats: CoverageStats, running = false): string {
  if (stats.complete) return 'Complete'
  return running ? 'In progress' : 'Incomplete'
}

/**
 * The explanatory line under the coverage meter.
 *
 * The completed wording is the one that matters most, and it has to be exact.
 * The verdicts are no longer estimates — Postgres made every comparison and a
 * triggered rule contributes its whole weight — but the run is still not
 * reproducible, because the agent writes the query afresh each time and a
 * different query can select differently. Claiming either less or more than that
 * would be a lie about what a reviewer is holding.
 */
export function coverageExplanation(stats: CoverageStats, running = false): string {
  if (stats.total === 0) {
    return running
      ? 'The coverage set is fixed at the start of the run; verdicts appear here as each rule’s query is answered.'
      : 'No applicable rules were recorded for this analysis.'
  }
  if (!stats.complete) {
    const missing = stats.unjudged > 0 ? stats.unjudged : stats.total - stats.evaluated
    return running
      ? `${missing} rule(s) still to answer. The loop cannot finish until every rule has a verdict.`
      : `${missing} rule(s) were never answered, so this run was recorded as FAILED rather than reported as a complete review. The verdicts it did reach are kept below; re-run the analysis for a complete one.`
  }
  return 'Every applicable rule was answered by a query the agent wrote and Postgres executed. Triggered means the query returned rows, and a triggered rule contributes its full weight, so the arithmetic is exact. The agent still chooses how to express each condition, so a re-run can write a different query and reach a different score.'
}

/**
 * Triggered rules first (highest contribution on top), then the rest by name,
 * so the evidence is at the top while every rule stays on screen.
 */
export function sortRuleEvaluations(evaluations: readonly RuleEvaluation[]): RuleEvaluation[] {
  return [...evaluations].sort((a, b) => {
    if (a.triggered !== b.triggered) return a.triggered ? -1 : 1
    if (a.triggered && b.triggered) {
      const diff = (b.score ?? 0) - (a.score ?? 0)
      if (diff !== 0) return diff
    }
    return a.ruleName.localeCompare(b.ruleName)
  })
}
