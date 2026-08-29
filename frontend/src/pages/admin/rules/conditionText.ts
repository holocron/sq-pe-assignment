/**
 * Helpers for the prose in `risk_rules.threshold_logic`.
 *
 * The condition is a prompt: the agent reads it, decides which of its tools to
 * call and judges the verdict. Nothing here parses it — these functions only
 * measure, excerpt and coach, so a rule that reads badly is caught by the author
 * rather than by a run that quietly scores it wrong.
 */
import {
  RULE_CONDITION_MAX_LENGTH,
  RULE_CONDITION_MIN_LENGTH,
  type FieldCatalogEntry,
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

export interface ConditionCheck {
  id: 'threshold' | 'window' | 'fields'
  label: string
  hint: string
  met: boolean
}

const NUMBER_PATTERN = /\d/
const WINDOW_PATTERN =
  /\b(?:\d+\s*(?:h|hr|hrs|hour|hours|d|day|days|week|weeks|month|months|min|minute|minutes)|24h|30d|7d|rolling|within|same day|per day|overnight|consecutive)\b/i

/** Field paths and labels the text mentions, used by the guidance checklist. */
function referencedFields(
  text: string,
  catalog: readonly FieldCatalogEntry[],
): FieldCatalogEntry[] {
  const haystack = text.toLowerCase()
  if (haystack.trim().length === 0) return []
  return catalog.filter((entry) => {
    if (haystack.includes(entry.field.toLowerCase())) return true
    const label = entry.label.toLowerCase()
    return label.length >= 4 && haystack.includes(label)
  })
}

/**
 * Advisory quality checks. They never block saving — an author who knows what
 * they are doing can write a condition that fails all three — but a blank
 * checklist is the clearest possible signal that the agent will be guessing.
 */
export function conditionChecks(
  text: string,
  catalog: readonly FieldCatalogEntry[],
): ConditionCheck[] {
  return [
    {
      id: 'threshold',
      label: 'Names a concrete threshold',
      hint: 'e.g. “above 10 000”, “three or more payments”',
      met: NUMBER_PATTERN.test(text),
    },
    {
      id: 'window',
      label: 'States a time window',
      hint: 'e.g. “within any rolling 24 hours”, “over 30 days”',
      met: WINDOW_PATTERN.test(text),
    },
    {
      id: 'fields',
      label: 'Refers to data the agent can fetch',
      hint: 'Use a name from the available-data panel',
      met: referencedFields(text, catalog).length > 0,
    },
  ]
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
