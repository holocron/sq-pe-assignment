/**
 * The trace has to say which rule each step was about.
 *
 * A reviewer opened a real run of twelve rules and found roughly two dozen
 * consecutive steps whose visible label was identical — "Submit rule verdict",
 * over and over — with the rule's identity buried in the collapsed arguments.
 * The tests that existed passed happily on exactly that, because they asserted
 * on labels that were the same for every row.
 *
 * So these tests render a trace with TWO different rule verdicts and insist the
 * rows can be told apart by what a person actually sees, before anything is
 * expanded. That is the assertion the defect would have failed.
 */
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { normalizeTraceStep } from '../../../api/analyses'
import type { TraceStep, UUID } from '../../../api/types'
import { TraceViewer } from '../TraceViewer'
import {
  coverageSetSize,
  groupTraceSteps,
  traceStepIdentity,
  traceStepSummary,
} from '../trace'

const STRUCTURING: UUID = 'aaaaaaaa-0000-4000-8000-000000000001'
const DORMANT: UUID = 'bbbbbbbb-0000-4000-8000-000000000002'
const STRUCTURING_NAME = 'Structuring - repeated payments just below the threshold'
const DORMANT_NAME = 'Dormant account activity spike'

const RULE_NAMES = new Map<UUID, string>([
  [STRUCTURING, STRUCTURING_NAME],
  [DORMANT, DORMANT_NAME],
])

/** `submit_rule_evaluation` answers with a VerdictAck; the trace keeps 600 characters of it. */
function verdictPreview(options: {
  ruleId: UUID
  ruleName: string
  triggered: boolean
  score: number
  submitted: number
}): string {
  return (
    `{"accepted":true,"ruleId":"${options.ruleId}","ruleName":"${options.ruleName}",` +
    `"recordedAsTriggered":${options.triggered},"recordedScore":${options.score.toFixed(2)},` +
    `"weightCap":30.00,"scoreClamped":false,"matchedTransactionsRecorded":2,"rulesTotal":12,` +
    `"verdictsSubmitted":${options.submitted},"verdictsStillRequired":${12 - options.submitted},` +
    `"rulesStillMissingAVerdict":[{"ruleId":"cccccccc-0000-4000-8000-000000000003","ruleN` +
    `... [412 more characters]`
  )
}

const CHECKLIST = normalizeTraceStep({
  n: 1,
  type: 'tool_call',
  tool: 'list_risk_rules',
  args: {},
  result_preview: '{"rulesTotal":12,"verdictsSubmitted":0,"verdictsStillRequired":12,"rules":[{"rul',
  ms: 812,
})

const STRUCTURING_VERDICT = normalizeTraceStep({
  n: 2,
  type: 'tool_call',
  tool: 'submit_rule_evaluation',
  args: { rule_id: STRUCTURING, triggered: true, score: 30, rationale: 'Eleven payments.' },
  result_preview: verdictPreview({
    ruleId: STRUCTURING,
    ruleName: STRUCTURING_NAME,
    triggered: true,
    score: 30,
    submitted: 3,
  }),
  ms: 1400,
})

const DORMANT_VERDICT = normalizeTraceStep({
  n: 3,
  type: 'tool_call',
  tool: 'submit_rule_evaluation',
  args: { rule_id: DORMANT, triggered: false, rationale: 'Continuously active for 90 days.' },
  result_preview: verdictPreview({
    ruleId: DORMANT,
    ruleName: DORMANT_NAME,
    triggered: false,
    score: 0,
    submitted: 4,
  }),
  ms: 900,
})

const TRACE: TraceStep[] = [CHECKLIST, STRUCTURING_VERDICT, DORMANT_VERDICT]

/** Every row of the timeline as a person reads it, before anything is expanded. */
function verdictRows(): string[] {
  return screen
    .getAllByRole('button', { name: /Submit rule verdict/ })
    .map((row) => row.textContent ?? '')
}

describe('TraceViewer — a collapsed row names what the step did', () => {
  it('tells two rule verdicts apart by their visible text alone', () => {
    render(<TraceViewer steps={TRACE} ruleNames={RULE_NAMES} />)

    const [structuring, dormant] = verdictRows()

    // The defect: with only the tool name on the row, these two were identical.
    expect(structuring).not.toBe(dormant)

    expect(structuring).toContain(STRUCTURING_NAME)
    expect(structuring).toContain('triggered +30.00')
    expect(structuring).toContain('rule 3/12')

    expect(dormant).toContain(DORMANT_NAME)
    expect(dormant).toContain('not triggered')
    expect(dormant).toContain('rule 4/12')

    // Neither row leaks the other's rule, which is what made the old trace unreadable.
    expect(structuring).not.toContain(DORMANT_NAME)
    expect(dormant).not.toContain(STRUCTURING_NAME)
  })

  it('names the rule without expanding the step, and still hides the raw result', () => {
    render(<TraceViewer steps={TRACE} ruleNames={RULE_NAMES} />)

    for (const row of screen.getAllByRole('button', { name: /Submit rule verdict/ })) {
      expect(row).toHaveAttribute('aria-expanded', 'false')
    }
    expect(screen.getByText(STRUCTURING_NAME)).toBeInTheDocument()
    expect(screen.getByText(DORMANT_NAME)).toBeInTheDocument()
    // The identity is on the row; the payload still waits behind the disclosure.
    expect(screen.queryByText(/weightCap/)).not.toBeInTheDocument()
    expect(screen.queryByText('Arguments')).not.toBeInTheDocument()
  })

  it('says how many rules the checklist holds', () => {
    render(<TraceViewer steps={TRACE} ruleNames={RULE_NAMES} />)
    expect(screen.getByText('12 rules in scope')).toBeInTheDocument()
  })

  it('keeps the outcome chip off the risk palette', () => {
    render(<TraceViewer steps={TRACE} ruleNames={RULE_NAMES} />)

    const triggered = screen.getByText('triggered +30.00')
    // A triggered rule is not a risk level; the risk ramp keeps its one meaning.
    expect(triggered.className).not.toMatch(/risk-/)
    expect(triggered.className).toMatch(/accent/)
    expect(screen.getByText('not triggered').className).not.toMatch(/risk-/)
  })

  it('reads a rule name out of the rule catalogue when no result was persisted', () => {
    const unpersisted = normalizeTraceStep({
      n: 2,
      type: 'tool_call',
      tool: 'submit_rule_evaluation',
      args: { rule_id: DORMANT, triggered: true, score: 12.5, rationale: 'A spike.' },
      ms: 700,
    })

    render(<TraceViewer steps={[CHECKLIST, unpersisted]} ruleNames={RULE_NAMES} />)

    const [row] = verdictRows()
    expect(row).toContain(DORMANT_NAME)
    expect(row).toContain('triggered +12.50')
    // Counted off the trace itself: first verdict of the twelve-rule coverage set.
    expect(row).toContain('rule 1/12')
  })

  it('names the transaction opened and the policy question asked', () => {
    const opened = normalizeTraceStep({
      n: 2,
      type: 'tool_call',
      tool: 'get_transaction_details',
      args: { transaction_id: '11111111-aaaa-4000-8000-000000000001' },
      result_preview:
        '{"transactionId":"11111111-aaaa-4000-8000-000000000001","customerId":"cccccccc-0000-4000-8000-000000000001","customerName":"Dana Kovac","activityType":"PAYMENT","amount":980.00,"currency":"USD","status":"Completed","createdAt":"2026-08-20T10:15:00Z"',
      ms: 120,
    })
    const searched = normalizeTraceStep({
      n: 3,
      type: 'tool_call',
      tool: 'search_policy_knowledge',
      args: { query: 'reporting threshold for structured payments', top_k: 3 },
      result_preview:
        '{"query":"reporting threshold for structured payments","returned":3,"passages":[{"citation"',
      ms: 640,
    })

    render(<TraceViewer steps={[opened, searched]} />)

    expect(screen.getByText('PAYMENT 980.00 USD on 2026-08-20')).toBeInTheDocument()
    expect(screen.getByText('Completed')).toBeInTheDocument()
    expect(screen.getByText('reporting threshold for structured payments')).toBeInTheDocument()
    expect(screen.getByText('3 passages')).toBeInTheDocument()
  })

  it('folds a run of verdicts into one block without hiding any of them', () => {
    render(<TraceViewer steps={TRACE} ruleNames={RULE_NAMES} />)

    expect(screen.getByText('Rule verdicts')).toBeInTheDocument()
    expect(screen.getByText('2 rules judged')).toBeInTheDocument()
    expect(verdictRows()).toHaveLength(2)
    expect(screen.getByText('#2')).toBeInTheDocument()
    expect(screen.getByText('#3')).toBeInTheDocument()
  })

  it('renders a trace persisted before the two fields existed', () => {
    // Exactly the old shape: no subject, no outcome, no result preview.
    const old = [
      normalizeTraceStep({ n: 1, type: 'tool_call', tool: 'list_risk_rules', args: {}, ms: 812 }),
      normalizeTraceStep({ n: 2, type: 'assistant', text: 'Looking at the payments.', ms: 400 }),
      normalizeTraceStep({ n: 3, type: 'final', risk_level: 'HIGH', ms: 220 }),
    ]

    render(<TraceViewer steps={old} />)

    expect(screen.getByText('Risk rules')).toBeInTheDocument()
    expect(screen.getByText('Agent reasoning')).toBeInTheDocument()
    expect(screen.getByText('Final assessment')).toBeInTheDocument()
    expect(screen.getByText('list_risk_rules')).toBeInTheDocument()
    expect(screen.getByText('3 steps · 1 tool calls')).toBeInTheDocument()
  })
})

describe('traceStepIdentity', () => {
  it('uses the label the backend recorded on the step, when it reaches the viewer', () => {
    // `subject` and `outcome` are written where the meaning is known — the tool
    // that ran — so they win over anything derived from the truncated payload.
    const step = {
      type: 'tool_call',
      n: 6,
      tool: 'submit_rule_evaluation',
      args: null,
      resultPreview: null,
      subject: DORMANT_NAME,
      outcome: 'triggered +12.50 (rule 4 of 12)',
    } as unknown as TraceStep

    expect(traceStepIdentity(step)).toMatchObject({
      subject: DORMANT_NAME,
      outcome: 'triggered +12.50 (rule 4 of 12)',
      // The recorded outcome carries its own counter; the viewer must not add a second.
      progress: null,
    })
  })

  it('says how far the coverage gate got, and what the run failed on', () => {
    const reprompt = normalizeTraceStep({
      n: 7,
      type: 'coverage_reprompt',
      missing: [STRUCTURING, DORMANT],
    })
    const failed = normalizeTraceStep({
      n: 9,
      type: 'coverage_failed',
      missing: [STRUCTURING],
      detail: { rules_total: 12, rules_unjudged: 1, unjudged_rule_names: [STRUCTURING_NAME] },
      text: 'Coverage failure.',
    })

    expect(traceStepIdentity(reprompt).outcome).toBe('2 rules still unjudged')
    expect(traceStepIdentity(failed).outcome).toBe('1 of 12 never judged')
  })

  it('marks a refused verdict as refused rather than as a recorded one', () => {
    const refused = normalizeTraceStep({
      n: 4,
      type: 'tool_call',
      tool: 'submit_rule_evaluation',
      args: { rule_id: DORMANT, triggered: true, rationale: 'Trust me.' },
      result_preview:
        '{"error":"transaction_ids is required when triggered=true, and none were given for rule',
      ms: 40,
    })

    const identity = traceStepIdentity(refused, { ruleNames: RULE_NAMES })
    expect(identity.subject).toBe(DORMANT_NAME)
    expect(identity.outcome).toBe('call rejected')
  })

  it('names the rule in the live "what is happening now" line', () => {
    // Through the checklist phase every live step is a verdict; the panel has to
    // say which rule, or it reads as the same sentence for two minutes.
    expect(traceStepSummary(STRUCTURING_VERDICT)).toBe(
      `Calling Submit rule verdict — ${STRUCTURING_NAME}`,
    )
    expect(traceStepSummary(CHECKLIST)).toBe('Calling Risk rules')
  })

  it('leaves a step it cannot describe unlabelled instead of guessing', () => {
    const bare = normalizeTraceStep({ n: 2, type: 'tool_call', tool: 'list_transactions', args: {} })
    expect(traceStepIdentity(bare)).toMatchObject({ subject: null, outcome: null, progress: null })
  })
})

describe('groupTraceSteps', () => {
  it('folds only consecutive verdicts, and leaves a lone one alone', () => {
    expect(groupTraceSteps(TRACE)).toEqual([
      { kind: 'step', step: CHECKLIST },
      { kind: 'verdicts', steps: [STRUCTURING_VERDICT, DORMANT_VERDICT] },
    ])
    expect(groupTraceSteps([CHECKLIST, STRUCTURING_VERDICT])).toEqual([
      { kind: 'step', step: CHECKLIST },
      { kind: 'step', step: STRUCTURING_VERDICT },
    ])
  })

  it('reads the size of the coverage set off the run itself', () => {
    expect(coverageSetSize(TRACE)).toBe(12)
    expect(coverageSetSize([])).toBeNull()
  })
})
