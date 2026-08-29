import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, within } from '@testing-library/react'
import type { ReactElement } from 'react'
import { describe, expect, it, vi } from 'vitest'
import type {
  AnalysisResult,
  RuleEvaluation,
  Transaction,
  TraceStep,
  UUID,
} from '../../api/types'
import { normalizeRuleEvaluation } from '../../api/analyses'
import { AnalysisResultView } from '../analysis/AnalysisResultView'
import { TraceViewer } from '../analysis/TraceViewer'
import { coverageStats } from '../analysis/coverage'
import { mergeTraceSteps } from '../analysis/trace'

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
 * `AnalysisDtos.RuleEvaluationView` as it really arrives — key names copied
 * from a live `GET /api/analyses/{id}`: the score is `score` and the evidence
 * is `matchedTransactionIds`. Hand-written fixtures in the frontend's own
 * invented vocabulary are what let a crashing screen ship with a green suite.
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
    rationale: 'Eleven payments between 9,500 and 9,999 USD across nine days.',
    explanation:
      "Rule 'Structuring pattern under reporting threshold' triggered on 2 of 14 PAYMENT transaction(s).",
    matchedTransactionIds: [TX_ONE, TX_TWO],
    source: 'AGENT',
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
    rationale: 'One SWIFT wire of 48,000 USD to a bank in a listed jurisdiction.',
    matchedTransactionIds: [TX_TWO],
    source: 'DETERMINISTIC_FALLBACK',
    disagreement: true,
    degraded: true,
    degradationNotes: ["'payment.receiver_bank_country' has no value on at least one transaction"],
  },
  {
    ruleId: RULE_IDS.cryptoMixer,
    ruleName: 'Crypto exposure to privacy chain',
    appliesTo: 'CRYPTO',
    weight: 25,
    triggered: false,
    score: 0,
    matchedCount: 0,
    rationale: 'No crypto activity in the review window.',
    matchedTransactionIds: [],
    source: 'AGENT',
  },
  {
    ruleId: RULE_IDS.declineBurst,
    ruleName: 'Card decline burst followed by a large approval',
    appliesTo: 'CARD',
    weight: 20,
    triggered: false,
    score: 0,
    matchedCount: 0,
    rationale: 'Only two declines, below the threshold of five.',
    matchedTransactionIds: [],
    source: 'AGENT',
  },
  {
    ruleId: RULE_IDS.highValueCard,
    ruleName: 'Card-not-present above 5,000',
    appliesTo: 'CARD',
    weight: 15,
    triggered: false,
    score: 0,
    matchedCount: 0,
    rationale: 'Highest card-not-present amount was 1,240 USD.',
    matchedTransactionIds: [],
    source: 'AGENT',
  },
  {
    ruleId: RULE_IDS.dormantSpike,
    ruleName: 'Dormant account activity spike',
    appliesTo: 'ALL',
    weight: 10,
    triggered: false,
    score: 0,
    matchedCount: 0,
    rationale: 'Account has been continuously active for 90 days.',
    matchedTransactionIds: [],
    source: 'AGENT',
  },
]

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
    type: 'coverage_reprompt',
    n: 4,
    missing: [RULE_IDS.sanctioned, RULE_IDS.dormantSpike],
  },
  { type: 'final', n: 5, riskLevel: 'HIGH', ms: 220 },
]

const COMPLETED: AnalysisResult = {
  assessmentId: 'ffffffff-0000-4000-8000-000000000001',
  customerId: 'cccccccc-0000-4000-8000-000000000001',
  status: 'COMPLETED',
  riskLevel: 'HIGH',
  totalScore: 62.5,
  rulesTotal: 6,
  rulesEvaluated: 6,
  coverageComplete: false,
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

  it('shows a complete coverage indicator and the verdict source of every rule', () => {
    renderWithQueryClient(<AnalysisResultView analysis={COMPLETED} />)

    expect(screen.getAllByText('6 / 6 rules evaluated').length).toBeGreaterThan(0)
    expect(screen.getByText('Complete')).toBeInTheDocument()
    expect(screen.getByText('2 triggered')).toBeInTheDocument()
    expect(screen.getByText('5 by agent')).toBeInTheDocument()
    expect(screen.getByText('1 deterministic')).toBeInTheDocument()

    const table = screen.getByRole('table', { name: /Rule coverage/ })
    expect(within(table).getAllByText('Agent')).toHaveLength(5)
    expect(within(table).getByText('Deterministic fallback')).toBeInTheDocument()
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

  it('flags a disagreement between the agent and the deterministic engine', () => {
    renderWithQueryClient(<AnalysisResultView analysis={COMPLETED} />)

    expect(screen.getByText('Disagreement')).toBeInTheDocument()
    expect(
      screen.getByText(/disagreed on 1 rule\. The deterministic verdict was used for scoring/),
    ).toBeInTheDocument()
  })

  it('expands a triggered rule to reveal the transactions that matched it', async () => {
    renderWithQueryClient(<AnalysisResultView analysis={COMPLETED} />)

    const toggle = screen.getByRole('button', { name: /Structuring pattern/ })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')

    fireEvent.click(toggle)
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    // Once clamped in the row, once in full inside the expanded panel.
    expect(
      screen.getAllByText('Eleven payments between 9,500 and 9,999 USD across nine days.'),
    ).toHaveLength(2)

    const matched = screen.getByRole('table', { name: /matched this rule/ })
    expect(await within(matched).findByText('11111111')).toBeInTheDocument()
    expect(await within(matched).findByText('22222222')).toBeInTheDocument()

    // The deterministic engine's own account of the verdict.
    expect(screen.getByText(/triggered on 2 of 14 PAYMENT transaction/)).toBeInTheDocument()
  })

  it('surfaces the degradation notes of a degraded rule when it is expanded', () => {
    renderWithQueryClient(<AnalysisResultView analysis={COMPLETED} />)

    fireEvent.click(screen.getByRole('button', { name: /High-value wire to sanctioned/ }))

    expect(screen.getByText('Degraded conditions')).toBeInTheDocument()
    expect(
      screen.getByText(
        "'payment.receiver_bank_country' has no value on at least one transaction",
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

    fireEvent.click(screen.getByRole('button', { name: /Structuring pattern/ }))
    expect(screen.getByText('No transaction matched this rule.')).toBeInTheDocument()
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
          ruleEvaluations: EVALUATIONS.slice(0, 4),
        }}
        live={{ connected: true, error: null, elapsedMs: 65_000, pollIntervalMs: 3000 }}
      />,
    )

    expect(screen.getAllByText('4 / 6 rules evaluated').length).toBeGreaterThan(0)
    expect(screen.getByText('In progress')).toBeInTheDocument()
    expect(screen.getByText(/2 rule\(s\) still to evaluate/)).toBeInTheDocument()
    expect(screen.getByText('Analysis running')).toBeInTheDocument()
    // Elapsed time is shown both in the live panel and as the run duration.
    expect(screen.getAllByText('1m 05s')).toHaveLength(2)
    expect(screen.getByText('Live stream')).toBeInTheDocument()
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
    expect(screen.getByText('5 steps · 2 tool calls · 1 coverage reprompt')).toBeInTheDocument()
    expect(
      screen.getByText(/The customer has repeated payments just under the 10,000/),
    ).toBeInTheDocument()
  })

  it('explains a coverage reprompt in plain language and names the missing rules', () => {
    render(<TraceViewer steps={TRACE} ruleNames={ruleNames} />)

    expect(
      screen.getByText(
        /The agent tried to conclude while 2 rules were still unevaluated .* sent back to evaluate them/,
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

  it('counts coverage, sources and disagreements', () => {
    const stats = coverageStats(COMPLETED)

    expect(stats.total).toBe(6)
    expect(stats.evaluated).toBe(6)
    expect(stats.complete).toBe(true)
    expect(stats.agentComplete).toBe(false)
    expect(stats.triggered).toBe(2)
    expect(stats.agentCount).toBe(5)
    expect(stats.fallbackCount).toBe(1)
    expect(stats.disagreements).toBe(1)
    expect(stats.percent).toBe(100)
    expect(stats.scoreFromRules).toBe(62.5)
  })
})
