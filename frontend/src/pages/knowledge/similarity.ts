/**
 * Similarity is a cosine score from pgvector. A bare float means nothing to a
 * customer-care operator, so the UI renders a labelled band plus a percentage —
 * never colour alone, and never the risk ramp, because this number says nothing
 * about risk.
 */

export interface SimilarityBand {
  label: string
  /** Tooltip text explaining what the band means. */
  description: string
}

/** Accepts 0..1 cosine scores and 0..100 percentages; clamps to 0..1. */
export function normalizeSimilarity(score: number | null | undefined): number | null {
  if (typeof score !== 'number' || !Number.isFinite(score)) return null
  const value = score > 1 ? score / 100 : score
  if (value < 0) return 0
  return value > 1 ? 1 : value
}

export function similarityBand(value: number): SimilarityBand {
  if (value >= 0.8) {
    return { label: 'Strong match', description: 'Very close to the query embedding.' }
  }
  if (value >= 0.65) {
    return { label: 'Good match', description: 'Clearly related to the query.' }
  }
  if (value >= 0.5) {
    return { label: 'Moderate match', description: 'Loosely related to the query.' }
  }
  return {
    label: 'Weak match',
    description: 'Distant from the query; treat as background context.',
  }
}
