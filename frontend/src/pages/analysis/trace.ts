/**
 * Presentation metadata for the ReAct trace.
 *
 * The wire format is deliberately terse — `{"n":1,"type":"tool_call",
 * "tool":"list_risk_rules","args":{},"result_preview":"...","ms":812}` — so
 * everything an operator needs to read it (label, icon, plain-language
 * explanation) lives here rather than in the backend payload.
 */
import {
  BookOpen,
  Brain,
  ChartColumn,
  CircleAlert,
  ClipboardCheck,
  Database,
  Flag,
  Gavel,
  ListOrdered,
  ReceiptText,
  ShieldAlert,
  SlidersHorizontal,
  TriangleAlert,
  UserRound,
  Wrench,
  type LucideIcon,
} from 'lucide-react'
import type { JsonValue, SqlEvaluation, ToolCallTraceStep, TraceStep } from '../../api/types'
import { formatAmount, humanizeToken, shortId } from '../../lib/format'
import { sqlFailure } from './sql'

/** What a tool call is for; drives the chip on the trace step. */
export type ToolKind = 'evidence' | 'policy' | 'submit' | 'custom'

export interface ToolMeta {
  /** Wire name, e.g. `list_risk_rules`. */
  name: string
  label: string
  /** One line an operator can read without knowing the tool API. */
  description: string
  icon: LucideIcon
  kind: ToolKind
}

export const TOOL_KIND_LABELS: Record<ToolKind, string> = {
  evidence: 'Evidence',
  policy: 'Policy',
  submit: 'Verdict',
  custom: 'Tool',
}

/**
 * The agent's tools. `evaluate_rule` is the one that settles a rule: the agent
 * writes SQL expressing the condition, Postgres runs it, and the verdict follows
 * from the row count rather than from anything the model concluded.
 */
export const TOOL_META: Record<string, ToolMeta> = {
  get_customer_profile: {
    name: 'get_customer_profile',
    label: 'Customer profile',
    description: "Read the customer's name, date of birth, age and country.",
    icon: UserRound,
    kind: 'evidence',
  },
  get_customer_activity_summary: {
    name: 'get_customer_activity_summary',
    label: 'Activity summary',
    description:
      'Read counts and sums per activity type, currencies, countries, velocity and failed ratio.',
    icon: ChartColumn,
    kind: 'evidence',
  },
  list_transactions: {
    name: 'list_transactions',
    label: 'List transactions',
    description: "Listed a page of the customer's transactions, optionally filtered.",
    icon: ListOrdered,
    kind: 'evidence',
  },
  get_transaction_details: {
    name: 'get_transaction_details',
    label: 'Transaction details',
    description: 'Opened one transaction with its card, payment or crypto specifics.',
    icon: ReceiptText,
    kind: 'evidence',
  },
  list_risk_rules: {
    name: 'list_risk_rules',
    label: 'Risk rules',
    description:
      'Loaded every rule that applies to this customer — the coverage set, each condition in plain English for the agent to express as SQL.',
    icon: SlidersHorizontal,
    kind: 'evidence',
  },
  search_policy_knowledge: {
    name: 'search_policy_knowledge',
    label: 'Policy knowledge search',
    description: 'Searched the policy knowledge base for supporting guidance (RAG).',
    icon: BookOpen,
    kind: 'policy',
  },
  evaluate_rule: {
    name: 'evaluate_rule',
    label: 'Evaluate rule',
    description:
      'Ran the agent’s SQL for one rule condition against this customer. Postgres decided it: rows returned means triggered, and the score is the rule’s weight.',
    icon: Database,
    kind: 'submit',
  },
  submit_rule_evaluation: {
    name: 'submit_rule_evaluation',
    label: 'Submit rule verdict',
    description:
      'Recorded the agent’s own verdict and estimated score for one rule. This is the older tool, used before rule conditions were answered by a query.',
    icon: ClipboardCheck,
    kind: 'submit',
  },
  submit_final_assessment: {
    name: 'submit_final_assessment',
    label: 'Submit final assessment',
    description: 'Submitted the overall risk level, summary and recommendations.',
    icon: Gavel,
    kind: 'submit',
  },
}

/** Falls back to a readable label for any tool the backend adds later. */
export function toolMeta(name: string | null | undefined): ToolMeta {
  const key = (name ?? '').trim()
  const known = TOOL_META[key]
  if (known) return known
  return {
    name: key || 'unknown_tool',
    label: key ? humanizeToken(key) : 'Unknown tool',
    description: 'Tool reported by the agent that this console does not know about.',
    icon: Wrench,
    kind: 'custom',
  }
}

export type TraceStepTone = 'neutral' | 'info' | 'accent' | 'warning' | 'danger'

/**
 * Per-step-type accent. Ordinary work stays grey, lookups that inform the
 * verdict use the secondary blue, and the brand orange is reserved for the
 * steps that actually decide something — never for routine evidence.
 */
const TOOL_KIND_TONES: Record<ToolKind, TraceStepTone> = {
  evidence: 'neutral',
  policy: 'info',
  submit: 'accent',
  custom: 'neutral',
}

export interface TraceStepMeta {
  label: string
  description: string
  icon: LucideIcon
  tone: TraceStepTone
}

export function traceStepMeta(step: TraceStep): TraceStepMeta {
  switch (step.type) {
    case 'tool_call': {
      const meta = toolMeta(step.tool)
      /* A query that was refused or errored decided nothing. It must not wear
         the same marker as one Postgres answered, or a retry after a failure
         reads as two successful evaluations of the same rule. */
      const failure = sqlFailure(step.sql)
      if (failure) {
        return {
          label: meta.label,
          description:
            'The query for this rule did not run, so nothing was measured and the rule was left undecided.',
          icon: TriangleAlert,
          tone: 'danger',
        }
      }
      return {
        label: meta.label,
        description: meta.description,
        icon: meta.icon,
        tone: TOOL_KIND_TONES[meta.kind],
      }
    }
    case 'assistant':
      return {
        label: 'Agent reasoning',
        description: 'The agent thinking out loud between tool calls.',
        icon: Brain,
        tone: 'neutral',
      }
    case 'coverage_reprompt':
      return {
        label: 'Coverage gate',
        description: 'The rule-coverage gate refused to let the loop finish.',
        icon: ShieldAlert,
        tone: 'warning',
      }
    case 'coverage_failed':
      return {
        label: 'Coverage not met',
        description:
          'The loop ran out of steps with rules still unjudged, so the run was recorded as FAILED rather than reported as complete.',
        icon: ShieldAlert,
        tone: 'danger',
      }
    case 'final':
      return {
        label: 'Final assessment',
        description: 'The agent closed the loop with an overall verdict.',
        icon: Flag,
        tone: 'accent',
      }
    case 'error':
      return {
        label: 'Agent error',
        description: 'The run stopped here.',
        icon: TriangleAlert,
        tone: 'danger',
      }
    default:
      return {
        label: humanizeToken(step.rawType),
        description: 'Step type this console does not know about; the raw payload is shown.',
        icon: CircleAlert,
        tone: 'neutral',
      }
  }
}

/**
 * Plain-language explanation of a `coverage_reprompt` step — the single most
 * important thing to make legible, because it is the proof that the loop
 * cannot finish with an unevaluated rule.
 */
export function coverageRepromptExplanation(missingCount: number): string {
  if (missingCount <= 0) {
    return 'The agent tried to conclude before every applicable rule had a verdict — it was sent back to finish the coverage set.'
  }
  const rules = missingCount === 1 ? 'rule was' : 'rules were'
  const them = missingCount === 1 ? 'it' : 'them'
  return `The agent tried to conclude while ${missingCount} ${rules} still unjudged — it was sent back to judge ${them} before it could finish.`
}

/**
 * Plain-language explanation of a `coverage_failed` step. Nothing closes a
 * coverage gap any more, so this step is the reason the run is FAILED.
 */
export function coverageFailedExplanation(step: {
  missing: readonly string[]
  unjudgedRuleNames: readonly string[]
  rulesTotal: number | null
}): string {
  const missing = step.missing.length || step.unjudgedRuleNames.length
  const of = step.rulesTotal ? ` of ${step.rulesTotal}` : ''
  const named = step.unjudgedRuleNames.length ? ` (${step.unjudgedRuleNames.join(', ')})` : ''
  return `The loop ended with ${missing}${of} applicable rule(s) never judged${named}. There is nothing behind the agent to fill that in, so the run is recorded as FAILED and the verdicts it did reach are kept as a partial review.`
}

/**
 * One-line "what is happening now" label for the live run panel.
 *
 * Named down to the rule where the step says which one: during the checklist
 * phase every step is a `submit_rule_evaluation`, so "Calling Submit rule
 * verdict" for two solid minutes tells an operator nothing.
 */
export function traceStepSummary(step: TraceStep | null | undefined): string {
  if (!step) return 'Waiting for the agent to take its first step.'
  switch (step.type) {
    case 'tool_call': {
      const subject = traceStepIdentity(step).subject
      return `Calling ${toolMeta(step.tool).label}${subject ? ` — ${subject}` : ''}`
    }
    case 'assistant':
      return 'Reasoning about the evidence so far'
    case 'coverage_reprompt':
      return `Coverage gate — ${step.missing.length} rule(s) still to judge`
    case 'coverage_failed':
      return `Coverage not met — ${step.missing.length} rule(s) never judged`
    case 'final':
      return 'Writing the final assessment'
    case 'error':
      return step.message
    default:
      return humanizeToken(step.rawType)
  }
}

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

/**
 * `coverage_reprompt.missing[]` carries rule ids, but a backend that sends
 * rule names instead must still read correctly — only ids get shortened.
 */
export function missingRuleLabel(value: string, ruleNames?: ReadonlyMap<string, string>): string {
  const name = ruleNames?.get(value)
  if (name) return name
  return UUID_PATTERN.test(value.trim()) ? value.slice(0, 8) : value
}

/** Stable identity of a step across the SSE stream and the persisted trace. */
export function traceStepKey(step: TraceStep): string {
  return `${step.n}:${step.type}`
}

/**
 * Merges the persisted trace with steps received over SSE.
 *
 * The persisted copy wins on conflict (it carries the result preview and
 * timings), live-only steps are appended, and the result is ordered by step
 * number so a reconnect cannot scramble the timeline.
 */
export function mergeTraceSteps(
  persisted: readonly TraceStep[] = [],
  live: readonly TraceStep[] = [],
): TraceStep[] {
  const byKey = new Map<string, TraceStep>()
  for (const step of persisted) byKey.set(traceStepKey(step), step)
  for (const step of live) {
    const key = traceStepKey(step)
    if (!byKey.has(key)) byKey.set(key, step)
  }
  return [...byKey.values()].sort((a, b) => a.n - b.n)
}

/** Pretty-prints tool arguments / raw payloads; tolerates JSON-in-a-string. */
export function formatJsonValue(value: JsonValue | null | undefined): string {
  if (value === null || value === undefined) return ''
  if (typeof value === 'string') {
    const trimmed = value.trim()
    if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
      try {
        return JSON.stringify(JSON.parse(trimmed), null, 2)
      } catch {
        return value
      }
    }
    return value
  }
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}

/** True when there is something worth showing in the arguments panel. */
export function hasJsonContent(value: JsonValue | null | undefined): boolean {
  if (value === null || value === undefined) return false
  if (typeof value === 'string') return value.trim().length > 0
  if (Array.isArray(value)) return value.length > 0
  if (typeof value === 'object') return Object.keys(value).length > 0
  return true
}

/* -------------------------------------------------------------------------- */
/* Step identity: what the step acted on, and how it came out                  */
/* -------------------------------------------------------------------------- */

/**
 * The collapsed row's identity.
 *
 * A run with twelve rules produces two dozen steps whose tool name is the same,
 * and the rule each one settled used to be visible only after expanding the
 * arguments. The rule now has to be on the face of the row, together with how
 * the query came out — including when it did not run at all, which is the one
 * outcome that must never be mistaken for a cleared rule.
 */
export interface TraceStepIdentity {
  /** What the step acted on: a rule name, a transaction, a policy query. */
  subject: string | null
  /** The one-line result, e.g. `triggered +30.00`, `12 rules in scope`. */
  outcome: string | null
  /**
   * Emphasis for the outcome chip. Deliberately only `neutral` and `accent`:
   * per the design system the risk ramp means a risk level, and a triggered
   * rule is not one (DESIGN_SYSTEM.md, "brand colour vs risk colour").
   */
  outcomeTone: 'neutral' | 'accent'
  /** `rule 3/12` on a verdict step, when the run said how far it had got. */
  progress: string | null
}

const EMPTY_IDENTITY: TraceStepIdentity = {
  subject: null,
  outcome: null,
  outcomeTone: 'neutral',
  progress: null,
}

/** Extra facts the viewer knows that one step does not carry on its own. */
export interface TraceStepIdentityContext {
  /** Resolves a rule id to its name, for a step whose result was not persisted. */
  ruleNames?: ReadonlyMap<string, string>
  /** 1-based position of this verdict among the run's verdicts. */
  ruleOrdinal?: number
  /** Size of the coverage set, when the step's own result does not say. */
  ruleCount?: number
}

/** `12 rules`, `1 rule` — plural agreement without a formatter. */
function countLabel(count: number, noun: string): string {
  return `${count} ${noun}${count === 1 ? '' : 's'}`
}

/**
 * Reads one top-level key out of a tool result preview.
 *
 * The preview is JSON that the backend truncated at 600 characters, so it
 * cannot be parsed; every field read here is near the front of its payload by
 * construction (record component order), and a miss simply leaves the row
 * without that part of its label.
 */
function previewString(preview: string | null | undefined, key: string): string | null {
  if (!preview) return null
  const match = new RegExp(`"${key}"\\s*:\\s*"((?:[^"\\\\]|\\\\.)*)"`).exec(preview)
  if (!match) return null
  try {
    return (JSON.parse(`"${match[1]}"`) as string).trim() || null
  } catch {
    return match[1].trim() || null
  }
}

function previewNumber(preview: string | null | undefined, key: string): number | null {
  if (!preview) return null
  const match = new RegExp(`"${key}"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)`).exec(preview)
  return match ? Number(match[1]) : null
}

/**
 * Whether the preview carries a key at all.
 *
 * A tool that refused a call answers `{"error":"…","hint":"…"}`, and the message
 * is long enough to be cut mid-string, so the row is recognised by the key
 * being there rather than by reading its value.
 */
function previewHas(preview: string | null | undefined, key: string): boolean {
  return !!preview && new RegExp(`"${key}"\\s*:`).test(preview)
}

function previewBoolean(preview: string | null | undefined, key: string): boolean | null {
  if (!preview) return null
  const match = new RegExp(`"${key}"\\s*:\\s*(true|false)`).exec(preview)
  return match ? match[1] === 'true' : null
}

/** The tool arguments as an object, tolerating the JSON-in-a-string form. */
function argsObject(value: JsonValue | null | undefined): Record<string, JsonValue> {
  if (typeof value === 'string') {
    try {
      return argsObject(JSON.parse(value) as JsonValue)
    } catch {
      return {}
    }
  }
  return value && typeof value === 'object' && !Array.isArray(value) ? value : {}
}

function argString(args: Record<string, JsonValue>, key: string): string | null {
  const value = args[key]
  return typeof value === 'string' && value.trim() ? value.trim() : null
}

function argNumber(args: Record<string, JsonValue>, key: string): number | null {
  const value = args[key]
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

function argBoolean(args: Record<string, JsonValue>, key: string): boolean | null {
  const value = args[key]
  return typeof value === 'boolean' ? value : null
}

/** The two label fields the backend records on the step, when they survived. */
function wireIdentity(step: TraceStep): { subject: string | null; outcome: string | null } {
  const wire = step as { subject?: unknown; outcome?: unknown }
  return {
    subject: typeof wire.subject === 'string' && wire.subject.trim() ? wire.subject.trim() : null,
    outcome: typeof wire.outcome === 'string' && wire.outcome.trim() ? wire.outcome.trim() : null,
  }
}

function verdictIdentity(
  step: ToolCallTraceStep,
  context: TraceStepIdentityContext,
): TraceStepIdentity {
  const preview = step.resultPreview
  const args = argsObject(step.args)
  const ruleId = previewString(preview, 'ruleId') ?? argString(args, 'rule_id')
  // The result names the rule; a step whose result was not persisted still has
  // the id it was called with, which the rule catalogue can name.
  const fromCatalogue = ruleId ? (context.ruleNames?.get(ruleId) ?? null) : null
  const fromId = ruleId ? `Rule ${shortId(ruleId)}` : null
  const subject = previewString(preview, 'ruleName') ?? fromCatalogue ?? fromId

  // The tool refuses a verdict that cites nothing, or an unknown rule; that row
  // must not read like a recorded verdict.
  if (previewHas(preview, 'error')) {
    return { subject, outcome: 'call rejected', outcomeTone: 'neutral', progress: null }
  }

  const triggered = previewBoolean(preview, 'recordedAsTriggered') ?? argBoolean(args, 'triggered')
  const score = previewNumber(preview, 'recordedScore') ?? argNumber(args, 'score')
  const submitted = previewNumber(preview, 'verdictsSubmitted') ?? context.ruleOrdinal ?? null
  const total = previewNumber(preview, 'rulesTotal') ?? context.ruleCount ?? null
  const progress = submitted && total ? `rule ${submitted}/${total}` : null

  if (triggered === null) {
    return { subject, outcome: null, outcomeTone: 'neutral', progress }
  }
  const outcome = triggered
    ? `triggered${score === null ? '' : ` +${formatAmount(score)}`}`
    : 'not triggered'
  return { subject, outcome, outcomeTone: triggered ? 'accent' : 'neutral', progress }
}

/**
 * The collapsed row of an `evaluate_rule` step.
 *
 * Which rule, and how the database answered. A failed or rejected attempt says
 * so in place of an outcome, because "not triggered" and "the query never ran"
 * are opposite facts and a row that blurs them would be the most misleading
 * thing on the page.
 */
function sqlVerdictIdentity(
  step: ToolCallTraceStep,
  context: TraceStepIdentityContext,
): TraceStepIdentity {
  const preview = step.resultPreview
  const args = argsObject(step.args)
  const ruleId = previewString(preview, 'ruleId') ?? argString(args, 'rule_id')
  const subject =
    previewString(preview, 'ruleName') ??
    (ruleId ? (context.ruleNames?.get(ruleId) ?? `Rule ${shortId(ruleId)}`) : null)

  const failure = sqlFailure(step.sql)
  if (failure) {
    return {
      subject,
      outcome: failure.kind === 'rejected' ? 'query rejected' : 'query failed',
      outcomeTone: 'neutral',
      progress: null,
    }
  }
  // The tool refuses a call it cannot act on at all — an unknown rule, no SQL —
  // and answers a query it would not run with accepted:false. Either way the
  // rule was left where it was, so neither may render as an evaluation.
  if (previewHas(preview, 'error') || previewBoolean(preview, 'accepted') === false) {
    return { subject, outcome: 'call rejected', outcomeTone: 'neutral', progress: null }
  }

  const submitted = previewNumber(preview, 'verdictsSubmitted') ?? context.ruleOrdinal ?? null
  const total = previewNumber(preview, 'rulesTotal') ?? context.ruleCount ?? null
  const progress = submitted && total ? `rule ${submitted}/${total}` : null

  const triggered = previewBoolean(preview, 'triggered') ?? argBoolean(args, 'triggered')
  if (triggered === null) {
    return { subject, outcome: null, outcomeTone: 'neutral', progress }
  }
  /* `matchedTransactions` is the field name on the tool acknowledgement; the
     persisted evaluation calls the same number `matchedCount`. */
  const matched =
    previewNumber(preview, 'matchedTransactions') ??
    previewNumber(preview, 'matchedCount') ??
    step.sql?.matchedCount ??
    null
  const rows = matched === null ? '' : ` · ${countLabel(matched, 'row')}`
  return {
    subject,
    outcome: triggered ? `triggered${rows}` : `not triggered${rows}`,
    outcomeTone: triggered ? 'accent' : 'neutral',
    progress,
  }
}

/** `PAYMENT 9,800.00 CHF on 2026-08-20` — enough to recognise the transaction. */
function transactionSubject(step: ToolCallTraceStep): string | null {
  const preview = step.resultPreview
  const type = previewString(preview, 'activityType')
  const amount = previewNumber(preview, 'amount')
  const currency = previewString(preview, 'currency')
  const day = previewString(preview, 'createdAt')?.slice(0, 10) ?? null
  const money = amount === null ? null : `${formatAmount(amount)}${currency ? ` ${currency}` : ''}`
  const head = [type, money].filter(Boolean).join(' ')
  // The same wording the backend records, so a row reads alike either way.
  const described = day ? `${head} on ${day}`.trim() : head
  if (described) return described
  const id = argString(argsObject(step.args), 'transaction_id')
  return id ? `Transaction ${shortId(id)}` : null
}

function toolCallIdentity(
  step: ToolCallTraceStep,
  context: TraceStepIdentityContext,
): TraceStepIdentity {
  const preview = step.resultPreview
  switch (step.tool) {
    case 'evaluate_rule':
      return sqlVerdictIdentity(step, context)
    case 'submit_rule_evaluation':
      return verdictIdentity(step, context)
    case 'list_risk_rules': {
      const total = previewNumber(preview, 'rulesTotal')
      return total === null
        ? EMPTY_IDENTITY
        : { ...EMPTY_IDENTITY, outcome: `${countLabel(total, 'rule')} in scope` }
    }
    case 'get_transaction_details':
      return {
        ...EMPTY_IDENTITY,
        subject: transactionSubject(step),
        outcome: previewString(preview, 'status'),
      }
    case 'list_transactions': {
      const returned = previewNumber(preview, 'returned')
      const matching = previewNumber(preview, 'matchingTransactions')
      return returned === null || matching === null
        ? EMPTY_IDENTITY
        : { ...EMPTY_IDENTITY, outcome: `${returned} of ${countLabel(matching, 'transaction')}` }
    }
    case 'search_policy_knowledge': {
      const returned = previewNumber(preview, 'returned')
      return {
        ...EMPTY_IDENTITY,
        subject: previewString(preview, 'query') ?? argString(argsObject(step.args), 'query'),
        outcome: returned === null ? null : countLabel(returned, 'passage'),
      }
    }
    case 'get_customer_profile':
      return { ...EMPTY_IDENTITY, subject: previewString(preview, 'fullName') }
    case 'get_customer_activity_summary': {
      const total = previewNumber(preview, 'totalTransactions')
      return total === null
        ? EMPTY_IDENTITY
        : { ...EMPTY_IDENTITY, outcome: countLabel(total, 'transaction') }
    }
    case 'submit_final_assessment': {
      const accepted = previewBoolean(preview, 'accepted')
      if (accepted === null) return EMPTY_IDENTITY
      const outstanding = previewNumber(preview, 'verdictsStillRequired') ?? 0
      return accepted
        ? { ...EMPTY_IDENTITY, outcome: 'assessment accepted', outcomeTone: 'accent' }
        : {
            ...EMPTY_IDENTITY,
            outcome: `rejected: ${countLabel(outstanding, 'rule')} unjudged`,
          }
    }
    default:
      return EMPTY_IDENTITY
  }
}

/**
 * What a step should say while collapsed.
 *
 * The backend records `subject` and `outcome` on the step itself; when they
 * reach the viewer they are used verbatim, because they were written where the
 * meaning was known. Otherwise — an older run, or a payload normalised without
 * them — they are recovered from the call's own arguments and result preview,
 * so the row is legible either way.
 */
export function traceStepIdentity(
  step: TraceStep,
  context: TraceStepIdentityContext = {},
): TraceStepIdentity {
  const derived =
    step.type === 'tool_call'
      ? toolCallIdentity(step, context)
      : step.type === 'coverage_reprompt'
        ? { ...EMPTY_IDENTITY, outcome: `${countLabel(step.missing.length, 'rule')} still unjudged` }
        : step.type === 'coverage_failed'
          ? {
              ...EMPTY_IDENTITY,
              outcome: step.rulesTotal
                ? `${step.missing.length} of ${step.rulesTotal} never judged`
                : `${countLabel(step.missing.length, 'rule')} never judged`,
            }
          : EMPTY_IDENTITY

  const wire = wireIdentity(step)
  if (!wire.subject && !wire.outcome) return derived
  return {
    ...derived,
    subject: wire.subject ?? derived.subject,
    outcome: wire.outcome ?? derived.outcome,
    // The recorded outcome already carries its own progress counter.
    progress: wire.outcome ? null : derived.progress,
  }
}

/**
 * The size of the coverage set, as the run itself reported it.
 *
 * Taken from the steps rather than from the rule catalogue on purpose: the
 * catalogue holds every rule, while the coverage set is only the rules that
 * apply to this customer, and "rule 3 of 12" must count the second.
 */
export function coverageSetSize(steps: readonly TraceStep[]): number | null {
  for (const step of steps) {
    if (step.type !== 'tool_call') continue
    const total = previewNumber(step.resultPreview, 'rulesTotal')
    if (total !== null && total > 0) return total
  }
  return null
}

/* -------------------------------------------------------------------------- */
/* Grouping                                                                    */
/* -------------------------------------------------------------------------- */

/** One timeline entry: a single step, or a run of consecutive rule verdicts. */
export type TraceBlock =
  | { kind: 'step'; step: TraceStep }
  | { kind: 'verdicts'; steps: TraceStep[] }

/** Tool calls that settle one rule, in either the SQL or the older form. */
const VERDICT_TOOLS = new Set(['evaluate_rule', 'submit_rule_evaluation'])

/** True when the step settles one rule of the coverage set. */
export function isVerdictStep(step: TraceStep): boolean {
  return step.type === 'tool_call' && VERDICT_TOOLS.has(step.tool)
}

/**
 * The query one step ran, if it ran one.
 *
 * Narrowing lives here rather than at each call site so the viewer can ask any
 * step for its SQL without first proving it is a tool call.
 */
export function traceStepSql(step: TraceStep): SqlEvaluation | null {
  return step.type === 'tool_call' ? (step.sql ?? null) : null
}

/**
 * Folds a run of consecutive verdict steps into one block.
 *
 * Twelve rules judged back to back are twelve rows either way, but as one block
 * they read as one phase of the run — "the agent worked through the checklist"
 * — instead of twelve unrelated markers down the timeline.
 */
export function groupTraceSteps(steps: readonly TraceStep[]): TraceBlock[] {
  const blocks: TraceBlock[] = []
  for (const step of steps) {
    const last = blocks[blocks.length - 1]
    if (isVerdictStep(step)) {
      if (last?.kind === 'verdicts') {
        last.steps.push(step)
        continue
      }
      blocks.push({ kind: 'verdicts', steps: [step] })
      continue
    }
    blocks.push({ kind: 'step', step })
  }
  // A lone verdict is not a phase; it stays an ordinary row.
  return blocks.map((block) =>
    block.kind === 'verdicts' && block.steps.length === 1
      ? { kind: 'step', step: block.steps[0] }
      : block,
  )
}
