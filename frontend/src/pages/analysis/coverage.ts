/**
 * Rule-coverage arithmetic for the analysis page.
 *
 * BUILD_SPEC section 4 guarantees that one row lands in `risk_assessments` for
 * every rule in the coverage set — triggered or not, agent-evaluated or
 * deterministically backfilled. These helpers turn that guarantee into
 * something a reviewer can verify at a glance.
 */
import type { AnalysisResult, AnalysisSummary, RuleEvaluation } from '../../api/types'

export interface CoverageStats {
  /** Size of the coverage set (`analysis_runs.rules_total`). */
  total: number
  /** Rules that have a persisted verdict. */
  evaluated: number
  triggered: number
  /** Verdicts produced by the agent itself. */
  agentCount: number
  /** Verdicts produced by the deterministic backfill. */
  fallbackCount: number
  /** Rules where the agent and the DSL engine disagreed. */
  disagreements: number
  /** Every rule in the coverage set has a verdict. */
  complete: boolean
  /** The agent covered everything without needing the deterministic backfill. */
  agentComplete: boolean
  /** 0..100, for the coverage meter. */
  percent: number
  /** Sum of the persisted score contributions. */
  scoreFromRules: number
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
  const fallbackCount = evaluations.filter(
    (item) => item.source === 'DETERMINISTIC_FALLBACK',
  ).length
  const agentCount = evaluations.filter((item) => item.source === 'AGENT').length
  const disagreements = evaluations.filter((item) => item.disagreement === true).length
  const scoreFromRules = evaluations.reduce(
    (sum, item) => sum + (Number.isFinite(item.scoreContribution) ? item.scoreContribution : 0),
    0,
  )

  const complete = total > 0 && evaluated >= total
  const agentComplete =
    typeof analysis.coverageComplete === 'boolean'
      ? analysis.coverageComplete
      : complete && fallbackCount === 0

  return {
    total,
    evaluated,
    triggered,
    agentCount,
    fallbackCount,
    disagreements,
    complete,
    agentComplete,
    percent: total > 0 ? Math.min(100, Math.round((evaluated / total) * 100)) : 0,
    scoreFromRules,
  }
}

/** `18 / 18 rules evaluated` — rendered as a single text node so it reads cleanly. */
export function coverageCountLabel(stats: CoverageStats): string {
  return `${stats.evaluated} / ${stats.total} rules evaluated`
}

export function coverageStatusLabel(stats: CoverageStats, running = false): string {
  if (stats.complete) return 'Complete'
  return running ? 'In progress' : 'Incomplete'
}

/** The explanatory line under the coverage meter. */
export function coverageExplanation(stats: CoverageStats, running = false): string {
  if (stats.total === 0) {
    return running
      ? 'The coverage set is loaded at the start of the run; verdicts appear here as the agent submits them.'
      : 'No applicable rules were recorded for this analysis.'
  }
  if (!stats.complete) {
    return running
      ? `${stats.total - stats.evaluated} rule(s) still to evaluate. The loop cannot finish until every rule has a verdict.`
      : `${stats.total - stats.evaluated} rule(s) have no recorded verdict. This should not happen — the deterministic backfill is meant to close the gap.`
  }
  if (stats.fallbackCount > 0) {
    return `Every applicable rule has a verdict. ${stats.fallbackCount} of them were backfilled by the deterministic engine after the agent stopped short.`
  }
  return 'Every applicable rule was evaluated by the agent itself — no deterministic backfill was needed.'
}

/**
 * Triggered rules first (highest contribution on top), then the rest by name,
 * so the evidence is at the top while every rule stays on screen.
 */
export function sortRuleEvaluations(evaluations: readonly RuleEvaluation[]): RuleEvaluation[] {
  return [...evaluations].sort((a, b) => {
    if (a.triggered !== b.triggered) return a.triggered ? -1 : 1
    if (a.triggered && b.triggered) {
      const diff = (b.scoreContribution ?? 0) - (a.scoreContribution ?? 0)
      if (diff !== 0) return diff
    }
    return a.ruleName.localeCompare(b.ruleName)
  })
}
