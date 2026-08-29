/**
 * Parsing for `analysis_runs.summary` / `.recommendations`.
 *
 * Both columns are free TEXT written by the model, so the content arrives as
 * prose, as a markdown-ish bullet list, or occasionally as a JSON array. All
 * three are normalised into blocks here so the page never shows raw JSON.
 */
import { humanizeToken } from '../../lib/format'

export type NarrativeBlock =
  | { kind: 'heading'; text: string }
  | { kind: 'paragraph'; text: string }
  | { kind: 'list'; items: string[] }

const BULLET = /^\s*(?:[-*•‣]|\d+[.)])\s+/
const HEADING = /^\s*#{1,6}\s+/

/**
 * Drops the markdown emphasis markers the model sometimes emits, and collapses
 * runs of blank space.
 *
 * The collapse is not cosmetic. A degenerating model emits long runs of
 * NO-BREAK SPACE (U+00A0), which the browser refuses to wrap: one real run of
 * this system concluded with `1. File SARS ( S` followed by a hundred of them,
 * which stretched the "Recommended actions" panel across the page. The server
 * cleans new runs at the source, but rows written before that are still on
 * file, so the renderer does not trust its input either. `\s` in JavaScript
 * already covers U+00A0 and the other Unicode space separators; the
 * zero-width characters it does not cover are listed explicitly.
 */
const BLANK_RUN = /[\s\u200b\ufeff]+/g

function clean(text: string): string {
  return text
    .replace(/\*\*(.+?)\*\*/g, '$1')
    .replace(/__(.+?)__/g, '$1')
    .replace(/`([^`]+)`/g, '$1')
    .replace(BLANK_RUN, ' ')
    .trim()
}

function stringifyJsonItem(item: unknown): string {
  if (item === null || item === undefined) return ''
  if (typeof item === 'string') return clean(item)
  if (typeof item === 'number' || typeof item === 'boolean') return String(item)
  if (Array.isArray(item)) return item.map(stringifyJsonItem).filter(Boolean).join(' — ')
  const record = item as Record<string, unknown>
  for (const key of ['text', 'recommendation', 'action', 'summary', 'description', 'title']) {
    const value = record[key]
    if (typeof value === 'string' && value.trim()) return clean(value)
  }
  return Object.entries(record)
    .filter(([, value]) => value !== null && typeof value !== 'object')
    .map(([key, value]) => `${humanizeToken(key)}: ${String(value)}`)
    .join(' · ')
}

function parseJsonNarrative(raw: string): NarrativeBlock[] | null {
  const trimmed = raw.trim()
  if (!trimmed.startsWith('[') && !trimmed.startsWith('{')) return null
  let parsed: unknown
  try {
    parsed = JSON.parse(trimmed)
  } catch {
    return null
  }
  if (Array.isArray(parsed)) {
    const items = parsed.map(stringifyJsonItem).filter((item) => item.length > 0)
    return items.length > 0 ? [{ kind: 'list', items }] : null
  }
  if (typeof parsed === 'object' && parsed !== null) {
    const blocks: NarrativeBlock[] = []
    for (const [key, value] of Object.entries(parsed as Record<string, unknown>)) {
      const label = humanizeToken(key)
      if (Array.isArray(value)) {
        const items = value.map(stringifyJsonItem).filter((item) => item.length > 0)
        if (items.length === 0) continue
        blocks.push({ kind: 'heading', text: label })
        blocks.push({ kind: 'list', items })
      } else {
        const text = stringifyJsonItem(value)
        if (!text) continue
        blocks.push({ kind: 'heading', text: label })
        blocks.push({ kind: 'paragraph', text })
      }
    }
    return blocks.length > 0 ? blocks : null
  }
  return null
}

/** Splits free text into headings, paragraphs and bullet lists. */
export function parseNarrative(raw: string | null | undefined): NarrativeBlock[] {
  if (!raw) return []
  const text = raw.trim()
  if (!text) return []

  const fromJson = parseJsonNarrative(text)
  if (fromJson) return fromJson

  const blocks: NarrativeBlock[] = []
  let paragraph: string[] = []
  let list: string[] = []

  const flushParagraph = () => {
    if (paragraph.length === 0) return
    const value = clean(paragraph.join(' '))
    if (value) blocks.push({ kind: 'paragraph', text: value })
    paragraph = []
  }
  const flushList = () => {
    if (list.length === 0) return
    blocks.push({ kind: 'list', items: list })
    list = []
  }

  for (const line of text.split(/\r?\n/)) {
    if (!line.trim()) {
      flushList()
      flushParagraph()
      continue
    }
    if (HEADING.test(line)) {
      flushList()
      flushParagraph()
      const value = clean(line.replace(HEADING, ''))
      if (value) blocks.push({ kind: 'heading', text: value })
      continue
    }
    if (BULLET.test(line)) {
      flushParagraph()
      const value = clean(line.replace(BULLET, ''))
      if (value) list.push(value)
      continue
    }
    flushList()
    paragraph.push(line.trim())
  }
  flushList()
  flushParagraph()
  return blocks
}
