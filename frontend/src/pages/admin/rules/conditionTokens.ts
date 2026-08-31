/**
 * Lightweight highlighting for a rule condition.
 *
 * `threshold_logic` is prose, so nothing here parses it: the text is scanned
 * for the field names and labels of the field catalog and for threshold-like
 * numbers, and what is found is rendered as chips above the textarea. It is a
 * reading aid — "these are the fields and thresholds this condition names" —
 * never a validation, and it never rewrites the text.
 */
import type { FieldCatalogEntry } from '../../../api/types'

export interface ConditionToken {
  /** Stable key for rendering. */
  id: string
  kind: 'field' | 'number'
  /** What the chip shows — the field label or the number as written. */
  label: string
  /** The matched text as it appears in the condition (for the chip title). */
  matched: string
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

/**
 * A number worth pointing at: integer or decimal, optionally with space or
 * comma thousands separators (`10 000`, `9 999.99`, `0.5`, `8`). Single digits
 * inside prose like "rule 3 of 12" are still numbers — they are included, the
 * chips are a reading aid and over-matching is harmless.
 */
const NUMBER_PATTERN = /\d(?:[\d\s.,]*\d)?/g

/**
 * The catalog fields and thresholds named in `text`, in first-appearance
 * order. A field matches on its path (`payment.receiver_bank_country`), its
 * leaf (`receiver_bank_country`) or its label (`Receiver bank country`), all
 * case-insensitive on word boundaries.
 */
export function detectConditionTokens(
  text: string,
  catalog: readonly FieldCatalogEntry[],
): ConditionToken[] {
  const tokens: ConditionToken[] = []
  if (text.trim().length === 0) return tokens

  for (const entry of catalog) {
    const leaf = entry.field.includes('.')
      ? entry.field.slice(entry.field.lastIndexOf('.') + 1)
      : entry.field
    const candidates = [entry.field, leaf, entry.label]
    let matched: string | null = null
    for (const candidate of candidates) {
      if (!candidate || candidate.length < 3) continue
      const pattern = new RegExp(`(?<![\\w.])${escapeRegExp(candidate)}(?![\\w])`, 'i')
      const hit = pattern.exec(text)
      if (hit) {
        matched = hit[0]
        break
      }
    }
    if (matched !== null) {
      tokens.push({ id: `field:${entry.field}`, kind: 'field', label: entry.label, matched })
    }
  }

  const seenNumbers = new Set<string>()
  for (const hit of text.matchAll(NUMBER_PATTERN)) {
    const raw = hit[0]
    const normalized = raw.replace(/[\s,]/g, '')
    if (seenNumbers.has(normalized)) continue
    seenNumbers.add(normalized)
    tokens.push({ id: `number:${normalized}`, kind: 'number', label: raw, matched: raw })
  }

  return tokens
}
