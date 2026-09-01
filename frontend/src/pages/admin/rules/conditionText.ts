/**
 * Helpers for the prose in `risk_rules.threshold_logic`.
 *
 * The condition is a prompt: the agent reads it, investigates with its tools and
 * writes one SQL query from it; Postgres answers the query and the answer is the
 * verdict. Nothing here parses it — these functions only measure, excerpt and
 * insert, so a rule that reads badly is caught by the author (or rewritten by the
 * Enhance wand) rather than by a run that quietly translates it into the wrong
 * question.
 */
import {
  RULE_CONDITION_MAX_LENGTH,
  RULE_CONDITION_MIN_LENGTH,
  type RuleScope,
} from '../../../api/types'

/** Collapses newlines and runs of spaces so a condition fits one table cell. */
function collapseWhitespace(text: string): string {
  return text.replace(/\s+/g, ' ').trim()
}

/** Single-line excerpt for the rules table, truncated on a word boundary. */
export function conditionExcerpt(text: string, maxLength = 180): string {
  const flat = collapseWhitespace(text)
  if (flat.length <= maxLength) return flat
  const cut = flat.slice(0, maxLength)
  const lastSpace = cut.lastIndexOf(' ')
  return `${(lastSpace > maxLength * 0.6 ? cut.slice(0, lastSpace) : cut).trimEnd()}…`
}

/**
 * A condition pasted from the old JSON DSL would be stored verbatim and handed
 * to the model as if it were prose, which is exactly the failure this editor
 * exists to prevent — so it is rejected with an explanation rather than saved.
 */
function looksLikeJson(text: string): boolean {
  const trimmed = text.trim()
  if (!trimmed.startsWith('{') && !trimmed.startsWith('[')) return false
  return /"(op|conditions|operator|field)"\s*:/.test(trimmed) || /^[[{][\s\S]*[\]}]$/.test(trimmed)
}

/** Blocking validation for the condition. Returns null when it may be saved. */
export function validateCondition(text: string): string | null {
  const trimmed = text.trim()
  if (trimmed.length === 0) return 'Write the condition the agent has to judge.'
  if (looksLikeJson(text)) {
    return 'Conditions are plain English now, not JSON. Describe when the rule is triggered.'
  }
  if (trimmed.length < RULE_CONDITION_MIN_LENGTH) {
    return `Too short to judge — write at least ${RULE_CONDITION_MIN_LENGTH} characters.`
  }
  if (text.length > RULE_CONDITION_MAX_LENGTH) {
    return `${text.length - RULE_CONDITION_MAX_LENGTH} character(s) over the ${RULE_CONDITION_MAX_LENGTH} limit.`
  }
  return null
}

/** Inserts `snippet` at the caret, keeping single spaces around it. */
export function insertAtCaret(
  current: string,
  snippet: string,
  selectionStart: number,
  selectionEnd: number,
): { text: string; caret: number } {
  const before = current.slice(0, selectionStart)
  const after = current.slice(selectionEnd)
  const needsLeadingSpace = before.length > 0 && !/\s$/.test(before)
  const needsTrailingSpace = after.length > 0 && !/^[\s.,;:)]/.test(after)
  const insert = `${needsLeadingSpace ? ' ' : ''}${snippet}${needsTrailingSpace ? ' ' : ''}`
  return { text: `${before}${insert}${after}`, caret: before.length + insert.length }
}

/** Appends a paragraph without destroying what the author already wrote. */
export function appendParagraph(current: string, paragraph: string): string {
  if (current.trim().length === 0) return paragraph
  return `${current.trimEnd()}\n\n${paragraph}`
}

export const RULE_SCOPE_HINTS: Record<RuleScope, string> = {
  ALL: 'Judged on every transaction the customer has.',
  CARD: 'Judged on card activity only.',
  PAYMENT: 'Judged on payment activity only.',
  CRYPTO: 'Judged on crypto activity only.',
}
