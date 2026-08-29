/**
 * Query-term highlighting for retrieved chunks.
 *
 * Retrieval is semantic, so a returned passage does not necessarily contain the
 * literal query words. Highlighting the ones it does contain makes it obvious
 * which part of the chunk the reviewer should read first, without implying the
 * match was lexical.
 */

export interface HighlightSegment {
  text: string
  /** True when the segment is a literal occurrence of a query term. */
  match: boolean
}

/** Words carrying no retrieval signal — never worth highlighting. */
const STOPWORDS = new Set([
  'a', 'all', 'an', 'and', 'any', 'are', 'as', 'at', 'be', 'but', 'by',
  'can', 'do', 'does', 'for', 'from', 'has', 'have', 'how', 'in', 'into', 'is',
  'it', 'its', 'may', 'must', 'not', 'of', 'on', 'or', 'our', 'that', 'the',
  'their', 'them', 'there', 'these', 'they', 'this', 'to', 'was', 'we', 'were',
  'what', 'when', 'where', 'which', 'who', 'why', 'will', 'with', 'you', 'your',
])

const MAX_TERMS = 12

export function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

/**
 * Splits a query into the distinct words worth highlighting, longest first so
 * a longer term wins over a shorter one that is a prefix of it.
 */
export function extractQueryTerms(query: string, limit = MAX_TERMS): string[] {
  const seen = new Set<string>()
  for (const raw of query.toLowerCase().split(/[^\p{L}\p{N}]+/u)) {
    if (raw.length < 2 || STOPWORDS.has(raw)) continue
    seen.add(raw)
  }
  return [...seen].sort((a, b) => b.length - a.length).slice(0, limit)
}

function buildPattern(terms: string[]): RegExp | null {
  const usable = terms.filter((term) => term.trim().length > 0)
  if (usable.length === 0) return null
  const alternatives = usable.map(escapeRegExp).join('|')
  // Word-boundary lookarounds keep short terms (country codes such as `IR`)
  // from matching inside unrelated words.
  return new RegExp(`(?<![\\p{L}\\p{N}])(?:${alternatives})(?![\\p{L}\\p{N}])`, 'giu')
}

/**
 * Splits `text` into alternating plain and matched segments. Always returns at
 * least one segment for non-empty text, so callers can render it directly.
 */
export function highlightSegments(text: string, terms: string[]): HighlightSegment[] {
  if (!text) return []
  const pattern = buildPattern(terms)
  if (!pattern) return [{ text, match: false }]

  const segments: HighlightSegment[] = []
  let cursor = 0
  for (const match of text.matchAll(pattern)) {
    const index = match.index ?? 0
    const value = match[0]
    if (value.length === 0) continue
    if (index > cursor) segments.push({ text: text.slice(cursor, index), match: false })
    segments.push({ text: value, match: true })
    cursor = index + value.length
  }
  if (cursor < text.length) segments.push({ text: text.slice(cursor), match: false })
  return segments
}
