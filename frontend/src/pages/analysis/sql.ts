/**
 * The query behind a rule verdict, as the analysis page presents it.
 *
 * A rule condition is still prose, but the agent no longer answers it from
 * memory: it writes SQL, Postgres runs it, and "triggered" means the query
 * returned rows. That makes the statement the primary evidence for a verdict,
 * so these helpers exist to classify one attempt (answered / failed) and to name
 * the failure — never to reformat the statement. An audit trail shows the query
 * that ran, character for character, so the only tidying done here is stripping
 * the indentation a heredoc left in front of every line.
 */
import type { EvaluationSource, RuleEvaluation, SqlEvaluation } from '../../api/types'

/** How one query attempt ended. */
export type SqlAttemptState =
  /** Postgres executed it and the row count decided the verdict. */
  | 'answered'
  /** Validation refused it, or Postgres errored. Nothing was decided. */
  | 'failed'
  /** No query on record — a run stored before rules were answered in SQL. */
  | 'absent'

export function sqlAttemptState(sql: SqlEvaluation | null | undefined): SqlAttemptState {
  if (!sql) return 'absent'
  return sql.ok ? 'answered' : 'failed'
}

/**
 * Why an attempt failed, and whether the query was refused before it ever ran.
 *
 * The two are worth telling apart on screen: a rejection is the validator
 * protecting the database from a statement it would not allow, while an error is
 * Postgres saying the query was wrong. Both leave the rule undecided.
 */
export interface SqlFailure {
  kind: 'rejected' | 'error'
  label: string
  reason: string
}

export function sqlFailure(sql: SqlEvaluation | null | undefined): SqlFailure | null {
  if (!sql || sql.ok) return null
  if (sql.rejectionReason) {
    return { kind: 'rejected', label: 'Query rejected', reason: sql.rejectionReason }
  }
  if (sql.errorMessage) {
    return { kind: 'error', label: 'Query failed', reason: sql.errorMessage }
  }
  return {
    kind: 'error',
    label: 'Query failed',
    reason: 'The query did not complete and the run recorded no reason for it.',
  }
}

/**
 * Strips the common leading indentation, so a statement built inside an indented
 * Java text block reads flush left. Nothing else about the text is touched.
 */
export function dedentSql(sql: string): string {
  const lines = sql.replace(/\r\n?/g, '\n').replace(/\s+$/, '').split('\n')
  while (lines.length > 0 && lines[0].trim() === '') lines.shift()
  const indents = lines
    .filter((line) => line.trim().length > 0)
    .map((line) => line.length - line.trimStart().length)
  const common = indents.length > 0 ? Math.min(...indents) : 0
  return lines.map((line) => line.slice(common)).join('\n')
}

/* -------------------------------------------------------------------------- */
/* Verdict provenance                                                          */
/* -------------------------------------------------------------------------- */

export interface VerdictProvenance {
  /** Chip text on the coverage row. */
  label: string
  /** The hover explanation; the same claim the expanded panel makes at length. */
  title: string
  /** True when a query, not a model, made the comparison. */
  bySql: boolean
}

const BY_SQL: VerdictProvenance = {
  label: 'SQL verdict',
  title:
    'The agent wrote a query for this rule condition and Postgres answered it. Triggered means the query returned rows — the model chose the query, it did not make the comparison.',
  bySql: true,
}

const BY_AGENT: VerdictProvenance = {
  label: 'Agent judged',
  title:
    'This verdict predates SQL evaluation: the agent read the rule condition, gathered the evidence and decided the comparison itself. The arithmetic behind it is the model’s.',
  bySql: false,
}

/**
 * Where this verdict actually came from.
 *
 * The recorded query outranks the `source` enum, because it is the evidence
 * rather than a claim about it: a row that carries the statement Postgres ran was
 * decided by Postgres whatever the enum says. Only when there is no query does
 * the enum decide, which is what keeps a genuinely older run reading honestly as
 * a model judgement instead of being relabelled.
 */
export function verdictProvenance(
  evaluation: Pick<RuleEvaluation, 'source'> & { sql?: SqlEvaluation | null },
): VerdictProvenance {
  if (evaluation.sql) return BY_SQL
  return isSqlSource(evaluation.source) ? BY_SQL : BY_AGENT
}

function isSqlSource(source: EvaluationSource | undefined): boolean {
  return source === 'SQL_DERIVED'
}

/**
 * True when the evidence list on screen is shorter than the number of rows the
 * query actually matched.
 *
 * The evaluator caps the ids it returns and the run caps the ids it stores, so a
 * rule can legitimately show eight transactions for a query that matched two
 * hundred. `matchedCount` is the true total either way, and the row has to say
 * that what is listed is a sample — a reviewer counting the table would
 * otherwise under-read the finding.
 */
export function evidenceCapped(
  evaluation: Pick<RuleEvaluation, 'matchedCount' | 'matchedTransactionIds'> & {
    sql?: SqlEvaluation | null
  },
): boolean {
  if (evaluation.sql?.capped) return true
  const total = evaluation.sql?.matchedCount ?? evaluation.matchedCount ?? null
  const listed = evaluation.matchedTransactionIds?.length ?? 0
  return total !== null && listed > 0 && total > listed
}

/**
 * The one-line account of how a score was arrived at.
 *
 * Scores stopped being estimates: a triggered rule contributes its whole weight
 * and a cleared one contributes nothing, so there is no longer a number for the
 * model to get wrong. The figure quoted is the score that was actually recorded,
 * not the weight it should equal — a sentence that explains a number has to be
 * about the number on the row.
 */
export function scoreDerivation(evaluation: Pick<RuleEvaluation, 'triggered' | 'score'>): string {
  const score = Number.isFinite(evaluation.score) ? evaluation.score.toFixed(2) : null
  if (evaluation.triggered) {
    return score === null
      ? 'The query returned rows, so the rule contributes its full weight.'
      : `The query returned rows, so the rule contributes its full weight of ${score}.`
  }
  return `The query returned no rows, so the rule contributes ${score ?? '0.00'}.`
}
