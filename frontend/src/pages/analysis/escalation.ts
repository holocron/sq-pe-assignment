/**
 * Detecting and describing an escalation of the overall risk band.
 *
 * The band a run reaches on its own is arithmetic: the weights of the rules
 * whose query returned rows, summed and banded. The agent may raise that band —
 * because the context around the numbers warrants it — but it may never lower it
 * and it may never clear a rule the database said fired. So an escalation is a
 * human-style override of a computed number, and the verdict header has to show
 * it as one: both bands and the reason, not just the number that won.
 */
import type { AnalysisResult, AnalysisSummary, RiskLevel } from '../../api/types'
import { RISK_LEVEL_STYLES, riskLevelFromScore } from '../../lib/risk'

export type EscalationInput = Pick<AnalysisSummary, 'riskLevel' | 'totalScore'> &
  Partial<Pick<AnalysisResult, 'mechanicalRiskLevel' | 'escalationJustification'>>

export interface Escalation {
  /** The band the summed weights alone produce. */
  mechanical: RiskLevel
  /** The band that stands on the record. */
  final: RiskLevel
  /** How many bands the agent raised it by. */
  bands: number
  /** The reason the agent recorded, or null when it recorded none. */
  justification: string | null
}

/**
 * The mechanical band of a run.
 *
 * The backend's own value wins when it sends one. Otherwise it is derived from
 * `totalScore`, which is the sum of the triggered rules' weights and so is the
 * mechanical figure by construction — an escalation raises the band, never the
 * score.
 */
export function mechanicalBand(analysis: EscalationInput): RiskLevel | null {
  return analysis.mechanicalRiskLevel ?? riskLevelFromScore(analysis.totalScore)
}

/** Null unless the final band actually sits above the mechanical one. */
export function escalationOf(analysis: EscalationInput): Escalation | null {
  const mechanical = mechanicalBand(analysis)
  const final = analysis.riskLevel ?? null
  if (!mechanical || !final) return null

  const bands = RISK_LEVEL_STYLES[final].order - RISK_LEVEL_STYLES[mechanical].order
  if (bands <= 0) return null

  return {
    mechanical,
    final,
    bands,
    justification: analysis.escalationJustification?.trim() || null,
  }
}

/** `raised one band` / `raised two bands` — reads inside a sentence. */
export function escalationExtentLabel(escalation: Escalation): string {
  return escalation.bands === 1 ? 'raised one band' : `raised ${escalation.bands} bands`
}
