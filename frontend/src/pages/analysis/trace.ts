/**
 * Presentation metadata for the ReAct trace (BUILD_SPEC section 4).
 *
 * The wire format is deliberately terse — `{"n":1,"type":"tool_call",
 * "tool":"list_risk_rules","args":{},"result_preview":"...","ms":812}` — so
 * everything an operator needs to read it (label, icon, plain-language
 * explanation) lives here rather than in the backend payload.
 */
import {
  BookOpen,
  Brain,
  Calculator,
  ChartColumn,
  CircleAlert,
  ClipboardCheck,
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
import type { JsonValue, TraceStep } from '../../api/types'
import { humanizeToken } from '../../lib/format'

/** What a tool call is for; drives the chip on the trace step. */
export type ToolKind = 'evidence' | 'policy' | 'evaluate' | 'submit' | 'custom'

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
  evaluate: 'Deterministic check',
  submit: 'Verdict',
  custom: 'Tool',
}

/** The nine agent tools from BUILD_SPEC section 4. */
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
    description: 'Loaded every rule that applies to this customer — the coverage set.',
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
  evaluate_rule_deterministically: {
    name: 'evaluate_rule_deterministically',
    label: 'Deterministic rule check',
    description:
      'Ran the rule DSL engine over the transactions, so the match set is exact rather than estimated.',
    icon: Calculator,
    kind: 'evaluate',
  },
  submit_rule_evaluation: {
    name: 'submit_rule_evaluation',
    label: 'Submit rule verdict',
    description: 'Recorded the verdict, score and rationale for one rule.',
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
  evaluate: 'info',
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
  return `The agent tried to conclude while ${missingCount} ${rules} still unevaluated — it was sent back to evaluate ${them} before it could finish.`
}

/** One-line "what is happening now" label for the live run panel. */
export function traceStepSummary(step: TraceStep | null | undefined): string {
  if (!step) return 'Waiting for the agent to take its first step.'
  switch (step.type) {
    case 'tool_call':
      return `Calling ${toolMeta(step.tool).label}`
    case 'assistant':
      return 'Reasoning about the evidence so far'
    case 'coverage_reprompt':
      return `Coverage gate — ${step.missing.length} rule(s) still to evaluate`
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
