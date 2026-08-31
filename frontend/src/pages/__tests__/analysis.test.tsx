import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, within } from '@testing-library/react'
import type { ReactElement } from 'react'
import { describe, expect, it, vi } from 'vitest'
import type {
  AnalysisResult,
  CoverageFailedTraceStep,
  RuleEvaluation,
  SqlEvaluation,
  Transaction,
  TraceStep,
  UUID,
} from '../../api/types'
import { normalizeRuleEvaluation, normalizeTraceStep } from '../../api/analyses'
import { AnalysisResultView } from '../analysis/AnalysisResultView'
import { TraceViewer } from '../analysis/TraceViewer'
import { coverageStats } from '../analysis/coverage'
import { escalationOf } from '../analysis/escalation'
import { coverageFailedExplanation, mergeTraceSteps, traceStepMeta } from '../analysis/trace'

const TX_ONE: UUID = '11111111-aaaa-4000-8000-000000000001'
const TX_TWO: UUID = '22222222-bbbb-4000-8000-000000000002'

vi.mock('../../api/transactions', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/transactions')>()
  // Inlined ids: the factory is hoisted above the module-level constants.
  const transactions: Record<string, Transaction> = {
    '11111111-aaaa-4000-8000-000000000001': {
      transactionId: '11111111-aaaa-4000-8000-000000000001',
      customerId: 'cccccccc-0000-4000-8000-000000000001',
      activityType: 'PAYMENT',
      amount: 9800,
      currency: 'USD',
      status: 'Completed',
      createdAt: '2026-08-20T10:15:00Z',
      payment: null,
    },
    '22222222-bbbb-4000-8000-000000000002': {
      transactionId: '22222222-bbbb-4000-8000-000000000002',
      customerId: 'cccccccc-0000-4000-8000-000000000001',
      activityType: 'PAYMENT',
      amount: 9750,
      currency: 'USD',
      status: 'Completed',
      createdAt: '2026-08-21T11:20:00Z',
      payment: null,
    },
  }
  return {
    ...actual,
    fetchTransaction: vi.fn(async (transactionId: string) => {
      const found = transactions[transactionId]
      if (!found) throw new Error(`unexpected transaction ${transactionId}`)
      return found
    }),
  }
})

const RULE_IDS = {
  structuring: 'a0000000-0000-4000-8000-000000000001',
  sanctioned: 'a0000000-0000-4000-8000-000000000002',
  cryptoMixer: 'a0000000-0000-4000-8000-000000000003',
  declineBurst: 'a0000000-0000-4000-8000-000000000004',
  highValueCard: 'a0000000-0000-4000-8000-000000000005',
  dormantSpike: 'a0000000-0000-4000-8000-000000000006',
} as const

/**
 * The statement the agent wrote for the structuring rule.
 *
 * Written as the real thing — thresholds and window inside the SQL — because
 * that is the whole claim the page makes: the model chose this query, Postgres
 * did the counting, and `>= 8` cannot be misremembered as ten on the way past.
 */
const STRUCTURING_SQL = `SELECT t.transaction_id
FROM tx t
WHERE t.activity_type = 'PAYMENT'
  AND t.amount BETWEEN 9000 AND 9999
  AND (SELECT count(*) FROM tx w
       WHERE w.activity_type = 'PAYMENT'
         AND w.created_at > t.created_at - INTERVAL '24 hours'
         AND w.created_at <= t.created_at) >= 8`

const SANCTIONED_SQL = `SELECT t.transaction_id
FROM tx t JOIN payment p ON p.transaction_id = t.transaction_id
WHERE t.activity_type = 'PAYMENT' AND p.receiver_bank_country = 'RU' AND t.amount > 10000`

/** A query that returns nothing is a verdict too — the rule is cleared. */
const CLEARED_SQL = `SELECT t.transaction_id FROM tx t WHERE false`

/** How the backend scopes a fragment before running it. */
function wrapped(fragment: string): string {
  return `WITH tx AS (SELECT * FROM transactions WHERE customer_id = ?)\n${fragment}\nLIMIT 500`
}

/** `SqlRuleResult` for a query Postgres executed; the row count is the verdict. */
function answered(sql: string, matchedCount: number, ms = 14): SqlEvaluation {
  return {
    sql,
    effectiveSql: wrapped(sql),
    ok: true,
    matchedCount,
    capped: false,
    rejectionReason: null,
    errorMessage: null,
    ms,
  }
}

/** `SqlRuleResult` for a query validation refused. It never reached the database. */
function refused(sql: string, rejectionReason: string): SqlEvaluation {
  return {
    sql,
    effectiveSql: null,
    ok: false,
    matchedCount: null,
    capped: false,
    rejectionReason,
    errorMessage: null,
    ms: 2,
  }
}

/** `SqlRuleResult` for a query Postgres refused to execute. Also unjudged. */
function errored(sql: string, errorMessage: string): SqlEvaluation {
  return {
    sql,
    effectiveSql: wrapped(sql),
    ok: false,
    matchedCount: null,
    capped: false,
    rejectionReason: null,
    errorMessage,
    ms: 6,
  }
}

/**
 * `AnalysisDtos.RuleEvaluationView` as it really arrives — key names copied
 * from a live `GET /api/analyses/{id}`: the score is `score` and the evidence
 * is `matchedTransactionIds`. Hand-written fixtures in the frontend's own
 * invented vocabulary are what let a crashing screen ship with a green suite.
 *
 * Every verdict here is `SQL_DERIVED`: the agent wrote the query, Postgres
 * answered it, and the score is the rule's weight or nothing.
 */
const EVALUATIONS: RuleEvaluation[] = [
  {
    ruleId: RULE_IDS.structuring,
    ruleName: 'Structuring pattern under reporting threshold',
    appliesTo: 'PAYMENT',
    weight: 30,
    triggered: true,
    score: 30,
    matchedCount: 2,
    evaluatedTransactionCount: 14,
    rationale:
      'Selects payments between 9,000 and 9,999 that sit in a rolling 24-hour window holding eight or more of them.',
    matchedTransactionIds: [TX_ONE, TX_TWO],
    source: 'SQL_DERIVED',
    sql: answered(STRUCTURING_SQL, 2, 31),
  },
  {
    ruleId: RULE_IDS.sanctioned,
    ruleName: 'High-value wire to sanctioned jurisdiction',
    appliesTo: 'PAYMENT',
    weight: 40,
    triggered: true,
    score: 32.5,
    matchedCount: 1,
    evaluatedTransactionCount: 14,
    rationale: 'Selects payments above 10,000 whose beneficiary bank sits in a listed jurisdiction.',
    matchedTransactionIds: [TX_TWO],
    source: 'SQL_DERIVED',
    sql: answered(SANCTIONED_SQL, 1),
  },
  {
    ruleId: RULE_IDS.cryptoMixer,
    ruleName: 'Crypto exposure to privacy chain',
    appliesTo: 'CRYPTO',
    weight: 25,
    triggered: false,
    score: 0,
    matchedCount: 0,
    rationale: 'Selects transfers to a wallet with no exchange attribution on a privacy chain.',
    matchedTransactionIds: [],
    source: 'SQL_DERIVED',
    sql: answered(CLEARED_SQL, 0),
  },
  {
    ruleId: RULE_IDS.declineBurst,
    ruleName: 'Card decline burst followed by a large approval',
    appliesTo: 'CARD',
    weight: 20,
    triggered: false,
    score: 0,
    matchedCount: 0,
    rationale: 'Selects an approval above 1,000 preceded by five or more declines inside an hour.',
    matchedTransactionIds: [],
    source: 'SQL_DERIVED',
    sql: answered(CLEARED_SQL, 0),
  },
  {
    ruleId: RULE_IDS.highValueCard,
    ruleName: 'Card-not-present above 5,000',
    appliesTo: 'CARD',
    weight: 15,
    triggered: false,
    score: 0,
    matchedCount: 0,
    rationale: 'Selects card transactions above 5,000 where the card was not present.',
    matchedTransactionIds: [],
    source: 'SQL_DERIVED',
    sql: answered(CLEARED_SQL, 0),
  },
  {
    ruleId: RULE_IDS.dormantSpike,
    ruleName: 'Dormant account activity spike',
    appliesTo: 'ALL',
    weight: 10,
    triggered: false,
    score: 0,
    matchedCount: 0,
    rationale: 'Selects activity following a gap of 90 days with no transaction at all.',
    matchedTransactionIds: [],
    source: 'SQL_DERIVED',
    sql: answered(CLEARED_SQL, 0),
  },
]

/**
 * A run where the agent's first query for the structuring rule was refused and
 * the second one ran.
 *
 * Both attempts are in the transcript on purpose. A retry that reads like a
 * second successful evaluation would tell a reviewer a rule was measured twice
 * when it was measured once, so the two rows have to be distinguishable.
 */
const TRACE: TraceStep[] = [
  {
    type: 'tool_call',
    n: 1,
    tool: 'list_risk_rules',
    args: { activityTypes: ['PAYMENT', 'CARD'] },
    resultPreview: '6 applicable rules loaded',
    ms: 812,
  },
  {
    type: 'assistant',
    n: 2,
    text: 'The customer has repeated payments just under the 10,000 reporting threshold.',
    ms: 1500,
  },
  {
    type: 'tool_call',
    n: 3,
    tool: 'search_policy_knowledge',
    args: { query: 'structuring threshold' },
    resultPreview: 'AML Policy §3.2 — structuring is any deliberate split below 10,000.',
    ms: 640,
  },
  {
    type: 'tool_call',
    n: 4,
    tool: 'evaluate_rule',
    args: { rule_id: RULE_IDS.structuring },
    resultPreview: '{"accepted":false,"ruleName":"Structuring pattern under reporting threshold"}',
    ms: 90,
    sql: refused(
      'SELECT t.transaction_id FROM tx t; DROP TABLE transactions',
      'Only a single SELECT is allowed; the statement carries a second command.',
    ),
  },
  {
    type: 'tool_call',
    n: 5,
    tool: 'evaluate_rule',
    args: { rule_id: RULE_IDS.structuring },
    resultPreview:
      '{"accepted":true,"ruleName":"Structuring pattern under reporting threshold","triggered":true,"matchedTransactions":2}',
    ms: 240,
    sql: answered(STRUCTURING_SQL, 2, 31),
  },
  {
    type: 'coverage_reprompt',
    n: 6,
    missing: [RULE_IDS.sanctioned, RULE_IDS.dormantSpike],
  },
  { type: 'final', n: 7, riskLevel: 'HIGH', ms: 220 },
]

const COMPLETED: AnalysisResult = {
  assessmentId: 'ffffffff-0000-4000-8000-000000000001',
  customerId: 'cccccccc-0000-4000-8000-000000000001',
  status: 'COMPLETED',
  riskLevel: 'HIGH',
  totalScore: 62.5,
  rulesTotal: 6,
  rulesEvaluated: 6,
  coverageComplete: true,
  model: 'gpt-oss-120b',
  steps: 5,
  durationMs: 42_000,
  requestedBy: 'operator1',
  createdAt: '2026-08-29T09:00:00Z',
  completedAt: '2026-08-29T09:00:42Z',
  error: null,
  summary:
    'The customer shows a sustained structuring pattern on outbound payments.\n\nOne wire reached a bank in a sanctioned jurisdiction.',
  recommendations:
    '- Freeze outbound wires pending review.\n- File a suspicious activity report.',
  ruleEvaluations: EVALUATIONS,
  trace: TRACE,
}

/**
 * The toggle for one rule row of the coverage table.
 *
 * Scoped to the table on purpose: the trace viewer on the same page now names
 * the same rules on its own rows, so an unscoped query would be ambiguous.
 */
function ruleToggle(name: RegExp): HTMLElement {
  return within(screen.getByRole('table', { name: /Rule coverage/ })).getByRole('button', { name })
}

function renderWithQueryClient(ui: ReactElement) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(<QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>)
}

describe('AnalysisResultView — completed analysis', () => {
  it('renders the verdict, the model and the narrative', () => {
    renderWithQueryClient(<AnalysisResultView analysis={COMPLETED} />)

    expect(screen.getAllByText('HIGH').length).toBeGreaterThan(0)
    expect(screen.getByText('62.5')).toBeInTheDocument()
    expect(screen.getByText('gpt-oss-120b')).toBeInTheDocument()
    expect(screen.getByText('operator1')).toBeInTheDocument()
    expect(
      screen.getByText(/sustained structuring pattern on outbound payments/),
    ).toBeInTheDocument()
    expect(screen.getByText('File a suspicious activity report.')).toBeInTheDocument()
  })

  it('lists every applicable rule in the coverage table', () => {
    renderWithQueryClient(<AnalysisResultView analysis={COMPLETED} />)

    const table = screen.getByRole('table', { name: /Rule coverage/ })
    for (const evaluation of EVALUATIONS) {
      expect(within(table).getByText(evaluation.ruleName)).toBeInTheDocument()
    }
    // One header row plus one row per rule in the coverage set.
    expect(within(table).getAllByRole('row')).toHaveLength(EVALUATIONS.length + 1)
    expect(within(table).getAllByText('Triggered')).toHaveLength(2)
    expect(within(table).getAllByText('Not triggered')).toHaveLength(4)
  })

  it('shows a complete coverage indicator and names the database as the decider', () => {
    renderWithQueryClient(<AnalysisResultView analysis={COMPLETED} />)

    expect(screen.getAllByText('6 / 6 rules answered').length).toBeGreaterThan(0)
    expect(screen.getByText('Complete')).toBeInTheDocument()
    expect(screen.getByText('2 triggered')).toBeInTheDocument()
    expect(screen.getByText('0 unjudged')).toBeInTheDocument()
    expect(screen.getByText('6 decided by query')).toBeInTheDocument()
    expect(
      screen.getByText('The coverage gate confirmed every rule of the set reached a verdict.'),
    ).toBeInTheDocument()

    /* "Agent judged" was the old badge and it is now a lie: the agent chose the
       query, Postgres made the comparison. Every row has to say which. */
    const table = screen.getByRole('table', { name: /Rule coverage/ })
    expect(within(table).getAllByText('SQL verdict')).toHaveLength(6)
    expect(within(table).queryByText('Agent judged')).not.toBeInTheDocument()
    expect(within(table).queryByText(/[Dd]eterministic/)).not.toBeInTheDocument()
  })

  /* The old copy said a re-run "can reach different scores" and nothing more.
     That is still true but no longer the whole truth: the comparison is exact
     now, and only the query is the model's. Overclaiming either way is the
     failure mode this assertion guards. */
  it('states precisely what a re-run can and cannot change', () => {
    renderWithQueryClient(<AnalysisResultView analysis={COMPLETED} />)

    const explanation = screen.getByText(/Every applicable rule was answered by a query/)
    expect(explanation).toHaveTextContent('Triggered means the query returned rows')
    expect(explanation).toHaveTextContent('the arithmetic is exact')
    expect(explanation).toHaveTextContent(
      'a re-run can write a different query and reach a different score',
    )
    expect(explanation).not.toHaveTextContent('estimate')
  })

  /* `score` on the wire, not `scoreContribution`: reading the wrong key
     rendered '+—' in the Score column of every triggered rule. */
  it('shows each rule score contribution in the Score column', () => {
    renderWithQueryClient(<AnalysisResultView analysis={COMPLETED} />)

    const table = screen.getByRole('table', { name: /Rule coverage/ })
    // Triggered rows carry a leading '+'; a missing key would render '+—'.
    expect(within(table).getByText('+30.00')).toBeInTheDocument()
    expect(within(table).getByText('+32.50')).toBeInTheDocument()
    expect(within(table).getAllByText('0.00')).toHaveLength(4)
    expect(within(table).queryByText('—')).not.toBeInTheDocument()
    expect(within(table).queryByText('+—')).not.toBeInTheDocument()
  })

  it('ranks triggered rules by contribution, not alphabetically', () => {
    renderWithQueryClient(<AnalysisResultView analysis={COMPLETED} />)

    const names = within(screen.getByRole('table', { name: /Rule coverage/ }))
      .getAllByRole('row')
      .map((row) => row.textContent ?? '')
    const sanctioned = names.findIndex((text) => text.includes('sanctioned jurisdiction'))
    const structuring = names.findIndex((text) => text.includes('Structuring pattern'))
    expect(sanctioned).toBeGreaterThan(-1)
    expect(sanctioned).toBeLessThan(structuring)
  })

  /* A run stored before the change was decided by the model, and it has to keep
     reading that way: relabelling it 'SQL verdict' would hand a reviewer an
     assurance nobody ever gave. Score clamping only ever happened on that path,
     because a SQL verdict scores the weight or nothing. */
  it('keeps an older agent-judged run labelled as a model judgement', () => {
    const legacy: AnalysisResult = {
      ...COMPLETED,
      ruleEvaluations: COMPLETED.ruleEvaluations.map((evaluation) => ({
        ...evaluation,
        source: 'AGENT_JUDGED' as const,
        sql: null,
        ...(evaluation.ruleId === RULE_IDS.sanctioned ? { score: 40 } : {}),
      })),
    }

    renderWithQueryClient(<AnalysisResultView analysis={legacy} />)

    const table = screen.getByRole('table', { name: /Rule coverage/ })
    expect(within(table).getAllByText('Agent judged')).toHaveLength(6)
    expect(within(table).queryByText('SQL verdict')).not.toBeInTheDocument()
    expect(screen.queryByText('6 decided by query')).not.toBeInTheDocument()

    fireEvent.click(ruleToggle(/High-value wire to sanctioned/))
    expect(screen.getByText(/This verdict predates SQL evaluation/)).toBeInTheDocument()
    expect(screen.getByText(/the score is its estimate/)).toBeInTheDocument()
  })

  /* The whole point of dropping the engine: a gap in coverage is no longer a
     footnote on a finished review, it is the reason the run is FAILED. */
  it('reports an incomplete run as a failed review rather than a complete one', () => {
    const incomplete: AnalysisResult = {
      ...COMPLETED,
      status: 'FAILED',
      coverageComplete: false,
      rulesEvaluated: 4,
      ruleEvaluations: COMPLETED.ruleEvaluations.slice(0, 4),
      error: "Coverage incomplete: 2 rule(s) never judged - 'Card-not-present above 5,000'",
    }

    renderWithQueryClient(<AnalysisResultView analysis={incomplete} />)

    expect(screen.getAllByText('4 / 6 rules answered').length).toBeGreaterThan(0)
    expect(screen.getByText('Incomplete')).toBeInTheDocument()
    expect(screen.getByText('2 unjudged')).toBeInTheDocument()
    expect(screen.getByText('Incomplete review')).toBeInTheDocument()
    expect(
      screen.getByText(/an unanswered rule is never recorded as cleared/),
    ).toBeInTheDocument()
    expect(
      screen.queryByText('The coverage gate confirmed every rule of the set reached a verdict.'),
    ).not.toBeInTheDocument()
  })

  it('expands a triggered rule to reveal the transactions that matched it', async () => {
    renderWithQueryClient(<AnalysisResultView analysis={COMPLETED} />)

    const toggle = ruleToggle(/Structuring pattern/)
    expect(toggle).toHaveAttribute('aria-expanded', 'false')

    fireEvent.click(toggle)
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    // Once clamped in the row, once in full inside the expanded panel.
    expect(
      screen.getAllByText(
        'Selects payments between 9,000 and 9,999 that sit in a rolling 24-hour window holding eight or more of them.',
      ),
    ).toHaveLength(2)

    const matched = screen.getByRole('table', { name: /cited as evidence/ })
    expect(await within(matched).findByText('11111111')).toBeInTheDocument()
    expect(await within(matched).findByText('22222222')).toBeInTheDocument()
  })

  /* The audit story of the whole change: the verdict is only as good as the
     statement behind it, so the statement is on the page, character for
     character, and not a paraphrase of it. */
  it('reveals the query that decided a verdict, and what Postgres answered', () => {
    renderWithQueryClient(<AnalysisResultView analysis={COMPLETED} />)

    fireEvent.click(ruleToggle(/Structuring pattern/))

    const query = screen.getByLabelText(
      'SQL that decided Structuring pattern under reporting threshold',
    )
    expect(query.tagName).toBe('PRE')
    expect(query).toHaveTextContent("t.activity_type = 'PAYMENT'")
    // The threshold that was misread as ten by a model is in the SQL verbatim.
    expect(query).toHaveTextContent('>= 8')
    expect(query.querySelector('code')).not.toBeNull()

    expect(screen.getByText('Query that decided this verdict')).toBeInTheDocument()
    expect(
      screen.getByText(/Postgres returned 2 rows, so the rule is triggered\./),
    ).toBeInTheDocument()
    expect(screen.getByText('What the query looks for')).toBeInTheDocument()
    expect(screen.getByText(/rows means triggered/)).toBeInTheDocument()
    expect(
      screen.getByText(/contributes its full weight of 30\.00/),
    ).toBeInTheDocument()

    // The wrapped statement is what actually ran, so it is shown as well.
    expect(
      screen.getByLabelText(
        'Statement executed for Structuring pattern under reporting threshold',
      ),
    ).toHaveTextContent('WITH tx AS')
  })

  /* A cleared rule is the case where a false negative hides. The row must show
     that a query ran and came back empty, not merely that nothing happened. */
  it('shows the query behind a rule the database cleared', () => {
    renderWithQueryClient(<AnalysisResultView analysis={COMPLETED} />)

    fireEvent.click(ruleToggle(/Card-not-present above 5,000/))

    expect(
      screen.getByLabelText('SQL that decided Card-not-present above 5,000'),
    ).toHaveTextContent('WHERE false')
    expect(
      screen.getByText(/Postgres returned 0 rows, so the rule is not triggered\./),
    ).toBeInTheDocument()
    expect(
      screen.getByText(/The query returned no rows, so the rule contributes 0\.00\./),
    ).toBeInTheDocument()
  })

  /* A rule whose query never ran is unjudged. Rendering it as a quiet, cleared
     row is exactly the false assurance the coverage guarantee exists to stop. */
  it('renders a rule whose query errored as failed, never as cleared', () => {
    const broken: AnalysisResult = {
      ...COMPLETED,
      ruleEvaluations: COMPLETED.ruleEvaluations.map((evaluation) =>
        evaluation.ruleId === RULE_IDS.cryptoMixer
          ? {
              ...evaluation,
              sql: errored(
                'SELECT t.transaction_id FROM tx t WHERE t.chain = 1',
                'ERROR: column t.chain does not exist',
              ),
            }
          : evaluation,
      ),
    }

    renderWithQueryClient(<AnalysisResultView analysis={broken} />)

    expect(screen.getByText('1 query failed')).toBeInTheDocument()
    expect(screen.getByText('Query failed')).toBeInTheDocument()

    fireEvent.click(ruleToggle(/Crypto exposure to privacy chain/))
    expect(screen.getByText('ERROR: column t.chain does not exist')).toBeInTheDocument()
    expect(
      screen.getByText(/A rule whose query never ran is unjudged — it is not a cleared rule\./),
    ).toBeInTheDocument()
    expect(
      screen.getByText('The query never ran, so no transaction was returned.'),
    ).toBeInTheDocument()
  })

  /* The condition is a prompt now, so the reviewer must be able to read exactly
     the sentence the agent was judging - not a rendering of a parsed structure. */
  it('shows the rule condition verbatim when the rule set is joined in', () => {
    const condition =
      'Three or more payments, each between 8,000 and 9,999.99, inside any rolling 24-hour '
      + 'window, together totalling at least 20,000.'
    renderWithQueryClient(
      <AnalysisResultView
        analysis={COMPLETED}
        rules={[
          {
            ruleId: RULE_IDS.structuring,
            ruleName: 'Structuring pattern under reporting threshold',
            appliesTo: 'PAYMENT',
            weight: 30,
            thresholdLogic: condition,
          },
        ]}
      />,
    )

    fireEvent.click(ruleToggle(/Structuring pattern/))

    expect(screen.getByText('Rule condition')).toBeInTheDocument()
    expect(screen.getByText(condition)).toBeInTheDocument()
    expect(
      screen.getByText(
        'This is the text the agent was shown, word for word, and translated into the query below.',
      ),
    ).toBeInTheDocument()
  })

  /* A run that stopped before recording its evidence must not blank the page:
     the coverage table is the whole point of the screen. */
  it('renders a verdict whose matched transactions were never recorded', () => {
    const partial: AnalysisResult = {
      ...COMPLETED,
      ruleEvaluations: [
        normalizeRuleEvaluation({ ...EVALUATIONS[0], matchedTransactionIds: undefined }),
      ],
    }
    renderWithQueryClient(<AnalysisResultView analysis={partial} />)

    fireEvent.click(ruleToggle(/Structuring pattern/))
    expect(screen.getByText('The query returned no transactions.')).toBeInTheDocument()
  })

  it('reports an incomplete coverage set while the run is still going', () => {
    renderWithQueryClient(
      <AnalysisResultView
        analysis={{
          ...COMPLETED,
          status: 'RUNNING',
          riskLevel: null,
          totalScore: null,
          completedAt: null,
          durationMs: null,
          rulesEvaluated: 4,
          coverageComplete: false,
          ruleEvaluations: EVALUATIONS.slice(0, 4),
        }}
        live={{ connected: true, error: null, elapsedMs: 65_000, pollIntervalMs: 3000 }}
      />,
    )

    expect(screen.getAllByText('4 / 6 rules answered').length).toBeGreaterThan(0)
    expect(screen.getByText('In progress')).toBeInTheDocument()
    expect(screen.getByText(/2 rule\(s\) still to answer/)).toBeInTheDocument()
    expect(screen.getByText('Analysis running')).toBeInTheDocument()
    // Elapsed time is shown both in the live panel and as the run duration.
    expect(screen.getAllByText('1m 05s')).toHaveLength(2)
    expect(screen.getByText('Live stream')).toBeInTheDocument()
  })

  /* An escalation is a judgement laid on top of arithmetic. The reviewer has to
     meet both numbers and the reason for the gap before anything else, or the
     override reads as if the scores produced it. */
  it('shows both bands and the justification when the agent escalated', () => {
    renderWithQueryClient(
      <AnalysisResultView
        analysis={{
          ...COMPLETED,
          riskLevel: 'CRITICAL',
          mechanicalRiskLevel: 'HIGH',
          escalationJustification:
            'The beneficiary bank was added to the sanctions list two days after the wire settled.',
        }}
      />,
    )

    expect(screen.getByText('Escalated by the agent — raised one band')).toBeInTheDocument()
    expect(screen.getByText('Score band')).toBeInTheDocument()
    expect(screen.getByText('Recorded verdict')).toBeInTheDocument()
    expect(screen.getByText('Escalated from HIGH')).toBeInTheDocument()
    expect(
      screen.getByText(
        'The beneficiary bank was added to the sanctions list two days after the wire settled.',
      ),
    ).toBeInTheDocument()
    expect(
      screen.getByText(/The agent may raise the overall band when the context warrants it/),
    ).toBeInTheDocument()

    // Both bands are on the card, and the recorded one is the determination.
    expect(screen.getAllByText('CRITICAL').length).toBeGreaterThan(0)
    expect(screen.getAllByText('HIGH').length).toBeGreaterThan(0)
  })

  /* An override nobody explained is itself the finding; it must not read as an
     ordinary determination just because the justification is missing. */
  it('says so when an escalation carries no justification', () => {
    renderWithQueryClient(
      <AnalysisResultView
        analysis={{ ...COMPLETED, riskLevel: 'CRITICAL', mechanicalRiskLevel: 'HIGH' }}
      />,
    )

    expect(screen.getByText('Escalated by the agent — raised one band')).toBeInTheDocument()
    expect(
      screen.getByText(/No justification was recorded for this escalation/),
    ).toBeInTheDocument()
  })

  it('shows no escalation banner when the band is the one the score produces', () => {
    renderWithQueryClient(<AnalysisResultView analysis={COMPLETED} />)

    expect(screen.queryByText(/Escalated by the agent/)).not.toBeInTheDocument()
    expect(screen.queryByText(/Escalated from/)).not.toBeInTheDocument()
  })

  it('surfaces the error and a retry action for a failed run', () => {
    const onRerun = vi.fn()
    renderWithQueryClient(
      <AnalysisResultView
        analysis={{
          ...COMPLETED,
          status: 'FAILED',
          riskLevel: null,
          totalScore: null,
          error: 'The chat model timed out after 3 attempts.',
        }}
        onRerun={onRerun}
      />,
    )

    expect(screen.getByText('This analysis failed')).toBeInTheDocument()
    expect(screen.getByText('The chat model timed out after 3 attempts.')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: /Run the analysis again/ }))
    expect(onRerun).toHaveBeenCalledTimes(1)
  })
})

describe('TraceViewer', () => {
  const ruleNames = new Map<UUID, string>(
    EVALUATIONS.map((evaluation) => [evaluation.ruleId, evaluation.ruleName]),
  )

  it('renders every step with a recognisable label', () => {
    render(<TraceViewer steps={TRACE} ruleNames={ruleNames} />)

    for (const step of TRACE) {
      expect(screen.getByText(`#${step.n}`)).toBeInTheDocument()
    }
    expect(screen.getByText('Risk rules')).toBeInTheDocument()
    expect(screen.getByText('Policy knowledge search')).toBeInTheDocument()
    expect(screen.getByText('Agent reasoning')).toBeInTheDocument()
    expect(screen.getByText('Coverage gate')).toBeInTheDocument()
    expect(screen.getByText('Final assessment')).toBeInTheDocument()
    expect(
      screen.getByText('7 steps · 4 tool calls · 1 coverage reprompt · 1 failed query'),
    ).toBeInTheDocument()
    expect(
      screen.getByText(/The customer has repeated payments just under the 10,000/),
    ).toBeInTheDocument()
  })

  it('explains a coverage reprompt in plain language and names the missing rules', () => {
    render(<TraceViewer steps={TRACE} ruleNames={ruleNames} />)

    expect(
      screen.getByText(
        /The agent tried to conclude while 2 rules were still unjudged .* sent back to judge them/,
      ),
    ).toBeInTheDocument()
    expect(screen.getByText('High-value wire to sanctioned jurisdiction')).toBeInTheDocument()
    expect(screen.getByText('Dormant account activity spike')).toBeInTheDocument()
  })

  it('expands a tool call to show its arguments and result', () => {
    render(<TraceViewer steps={TRACE} />)

    const toolStep = screen.getByRole('button', { name: /Risk rules/ })
    expect(toolStep).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByText('6 applicable rules loaded')).not.toBeInTheDocument()

    fireEvent.click(toolStep)
    expect(toolStep).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByText('Arguments')).toBeInTheDocument()
    expect(screen.getByText(/activityTypes/)).toBeInTheDocument()
    expect(screen.getByText('6 applicable rules loaded')).toBeInTheDocument()
  })

  /* The collapsed row has to name the rule and how the database answered, and
     expanding it has to produce the statement — a verdict a reviewer cannot
     read the query for is a verdict taken on trust. */
  it('names the rule on an evaluate_rule row and reveals the SQL when expanded', () => {
    render(<TraceViewer steps={TRACE} ruleNames={ruleNames} />)

    const row = screen.getByRole('button', {
      /* The '·' separators are aria-hidden, so the accessible name is the label,
         the rule and the outcome run together — which is exactly the three
         things a collapsed row has to carry. */
      name: /Evaluate ruleStructuring pattern under reporting threshold.*triggered · 2 rows/,
    })
    expect(row).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByLabelText('SQL for step 5')).not.toBeInTheDocument()

    fireEvent.click(row)
    expect(row).toHaveAttribute('aria-expanded', 'true')

    const query = screen.getByLabelText('SQL for step 5')
    expect(query).toHaveTextContent('>= 8')
    expect(screen.getByText('Query the agent wrote')).toBeInTheDocument()
    expect(screen.getByLabelText('Statement executed at step 5')).toHaveTextContent('WITH tx AS')
  })

  /* A retry that looks like a success is the most misleading thing the trace
     could show: it would tell a reviewer the rule was measured when it never
     was. The refused attempt is a failure on its face, with its reason. */
  it('renders a refused query attempt as a failure, not as an evaluation', () => {
    render(<TraceViewer steps={TRACE} ruleNames={ruleNames} />)

    // Once as the chip on the collapsed row, once as the heading of the panel
    // that carries the reason — both without expanding anything.
    expect(screen.getAllByText('Query rejected')).toHaveLength(2)
    expect(
      screen.getByText('Only a single SELECT is allowed; the statement carries a second command.'),
    ).toBeInTheDocument()
    expect(
      screen.getByText(/Nothing was measured and the rule stayed undecided/),
    ).toBeInTheDocument()

    // The block counts the two attempts apart, so one answered rule stays one.
    expect(screen.getByText('1 rule answered')).toBeInTheDocument()
    expect(screen.getByText('1 attempt failed')).toBeInTheDocument()

    // The failed attempt still shows the statement that was refused.
    fireEvent.click(
      screen.getByRole('button', {
        name: /Evaluate ruleStructuring pattern under reporting thresholdQuery rejected/,
      }),
    )
    expect(screen.getByLabelText('SQL for step 4')).toHaveTextContent('DROP TABLE transactions')
  })

  it('hides a step type when its chip is switched off, and restores it', () => {
    render(<TraceViewer steps={TRACE} ruleNames={ruleNames} />)

    // The chips count the types present in the trace.
    const toolCalls = screen.getByRole('button', { name: /^Tool calls ?4$/ })
    expect(toolCalls).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: /^Reasoning ?1$/ })).toBeInTheDocument()
    expect(screen.getByText('Risk rules')).toBeInTheDocument()

    fireEvent.click(toolCalls)

    expect(toolCalls).toHaveAttribute('aria-pressed', 'false')
    expect(screen.queryByText('Risk rules')).not.toBeInTheDocument()
    expect(screen.queryByText('Policy knowledge search')).not.toBeInTheDocument()
    expect(screen.getByText('4 hidden')).toBeInTheDocument()
    // The header still counts the whole trace; only the timeline is filtered.
    expect(
      screen.getByText('7 steps · 4 tool calls · 1 coverage reprompt · 1 failed query'),
    ).toBeInTheDocument()

    fireEvent.click(toolCalls)
    expect(screen.getByText('Risk rules')).toBeInTheDocument()
  })

  it('says so when every step is filtered out', () => {
    render(<TraceViewer steps={TRACE} ruleNames={ruleNames} />)

    for (const name of [/Tool calls/, /Reasoning/, /Coverage reprompts/, /Final verdicts/]) {
      fireEvent.click(screen.getByRole('button', { name }))
    }

    expect(screen.getByText(/Every step is hidden by the type filters/)).toBeInTheDocument()
  })

  it('offers a jump to the latest step', () => {
    render(<TraceViewer steps={TRACE} ruleNames={ruleNames} />)

    const jump = screen.getByRole('button', { name: /Jump to latest/ })
    expect(jump).toBeEnabled()
    // jsdom has no scrollIntoView; the click must simply be a safe no-op there.
    fireEvent.click(jump)
  })

  it('waits visibly for the first step of a live run', () => {
    render(<TraceViewer steps={[]} running live={{ connected: true }} />)
    expect(screen.getByText(/first step/)).toBeInTheDocument()
    expect(screen.getByText('Live')).toBeInTheDocument()
  })

  it('falls back to an empty state when no trace was persisted', () => {
    render(<TraceViewer steps={[]} />)
    expect(screen.getByText('No reasoning trace recorded')).toBeInTheDocument()
  })
})

describe('trace and coverage helpers', () => {
  it('merges live SSE steps into the persisted trace without duplicates', () => {
    const persisted = TRACE.slice(0, 2)
    const live: TraceStep[] = [
      { type: 'assistant', n: 2, text: 'partial', ms: null },
      { type: 'tool_call', n: 3, tool: 'search_policy_knowledge', args: null, resultPreview: null },
    ]

    const merged = mergeTraceSteps(persisted, live)

    expect(merged.map((step) => step.n)).toEqual([1, 2, 3])
    // The persisted copy wins: it carries the timings and the result preview.
    expect(merged[1]).toEqual(persisted[1])
  })

  it('counts coverage and the verdicts behind it', () => {
    const stats = coverageStats(COMPLETED)

    expect(stats.total).toBe(6)
    expect(stats.evaluated).toBe(6)
    expect(stats.unjudged).toBe(0)
    expect(stats.complete).toBe(true)
    expect(stats.triggered).toBe(2)
    expect(stats.percent).toBe(100)
    expect(stats.scoreFromRules).toBe(62.5)
    expect(stats.sqlBacked).toBe(6)
    expect(stats.sqlFailed).toBe(0)
  })

  /* A rule whose query failed still has a row, so it would be counted as
     evaluated. It was not measured, and the panel has to be able to say so. */
  it('counts a failed query apart from an answered one', () => {
    const stats = coverageStats({
      ...COMPLETED,
      ruleEvaluations: COMPLETED.ruleEvaluations.map((evaluation, index) =>
        index === 0 ? { ...evaluation, sql: errored(STRUCTURING_SQL, 'ERROR: syntax') } : evaluation,
      ),
    })

    expect(stats.sqlBacked).toBe(6)
    expect(stats.sqlFailed).toBe(1)
  })

  /* The mechanical band is the floor. When the backend does not send it, the
     total score is it — an escalation raises the band, never the score. */
  it('derives the mechanical band from the score when the backend omits it', () => {
    expect(escalationOf({ riskLevel: 'CRITICAL', totalScore: 62.5 })).toMatchObject({
      mechanical: 'HIGH',
      final: 'CRITICAL',
      bands: 1,
      justification: null,
    })
    expect(escalationOf({ riskLevel: 'HIGH', totalScore: 62.5 })).toBeNull()
    // A band below the mechanical one is not an escalation and is never honoured.
    expect(escalationOf({ riskLevel: 'LOW', totalScore: 62.5 })).toBeNull()
  })

  /* `coverage_complete` is the column that gates COMPLETED, so it outranks the
     row count: a run the backend called incomplete must never render complete. */
  it('trusts the backend coverage flag over the counters', () => {
    const stats = coverageStats({
      rulesTotal: 6,
      rulesEvaluated: 6,
      coverageComplete: false,
      ruleEvaluations: EVALUATIONS,
    })

    expect(stats.complete).toBe(false)
  })

  it('names the unjudged rules as the reason a run failed, not as a backfill', () => {
    const step = normalizeTraceStep({
      n: 9,
      type: 'coverage_failed',
      missing: [RULE_IDS.highValueCard],
      text: 'Coverage failure: 1 of 6 applicable rule(s) never received a verdict',
      detail: {
        rules_total: 6,
        rules_unjudged: 1,
        unjudged_rule_names: ['Card-not-present above 5,000'],
      },
    })

    expect(step).toEqual({
      type: 'coverage_failed',
      n: 9,
      ms: null,
      missing: [RULE_IDS.highValueCard],
      unjudgedRuleNames: ['Card-not-present above 5,000'],
      rulesTotal: 6,
      text: 'Coverage failure: 1 of 6 applicable rule(s) never received a verdict',
    })
    expect(traceStepMeta(step).label).toBe('Coverage not met')
    expect(traceStepMeta(step).tone).toBe('danger')
    expect(coverageFailedExplanation(step as CoverageFailedTraceStep)).toContain(
      'nothing behind the agent to fill that in',
    )
  })
})
