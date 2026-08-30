/**
 * Risk-rule administration API.
 *
 * `threshold_logic` is natural language: the agent reads the condition and
 * translates it into a SQL query, Postgres runs the query, and the rule fires
 * when rows come back. Nothing in this module parses the condition — it only
 * ever moves text.
 */
import {
  useMutation,
  useQuery,
  useQueryClient,
  type UseMutationResult,
  type UseQueryResult,
} from '@tanstack/react-query'
import { deleteJson, getJson, postJson, putJson } from './client'
import type { ApiError } from './errors'
import type { MutationOpts, QueryOpts } from './query'
import { queryKeys } from './queryKeys'
import {
  ACTIVITY_TYPES,
  FIELD_CATEGORIES,
  FIELD_TYPES,
  RULE_SCOPES,
  type ActivityType,
  type FieldCatalogEntry,
  type FieldCatalogEntryWire,
  type FieldCategory,
  type FieldType,
  type RiskRule,
  type RiskRuleInput,
  type RiskRuleWire,
  type RuleScope,
  type RuleTestMatch,
  type RuleTestMatchWire,
  type RuleTestRequest,
  type RuleTestResult,
  type RuleTestResultWire,
  type UUID,
} from './types'

function toFiniteNumber(value: number | string | null | undefined): number | null {
  if (value === null || value === undefined || value === '') return null
  const parsed = typeof value === 'number' ? value : Number(value)
  return Number.isFinite(parsed) ? parsed : null
}

function trimmedOrNull(value: string | null | undefined): string | null {
  const text = typeof value === 'string' ? value.trim() : ''
  return text.length > 0 ? text : null
}

/* -------------------------------------------------------------------------- */
/* Rules                                                                       */
/* -------------------------------------------------------------------------- */

export function normalizeRule(wire: RiskRuleWire): RiskRule {
  return {
    ruleId: wire.ruleId,
    ruleName: wire.ruleName,
    appliesTo: wire.appliesTo,
    thresholdLogic: wire.thresholdLogic ?? '',
    weight: toFiniteNumber(wire.weight) ?? 0,
  }
}

/** `GET /api/rules` */
export async function fetchRules(): Promise<RiskRule[]> {
  const wire = await getJson<RiskRuleWire[]>('/rules')
  return (wire ?? []).map(normalizeRule)
}

/** `POST /api/rules` (ADMIN) */
export async function createRule(input: RiskRuleInput): Promise<RiskRule> {
  return normalizeRule(await postJson<RiskRuleWire, RiskRuleInput>('/rules', input))
}

/** `PUT /api/rules/{ruleId}` (ADMIN) */
export async function updateRule(ruleId: UUID, input: RiskRuleInput): Promise<RiskRule> {
  return normalizeRule(await putJson<RiskRuleWire, RiskRuleInput>(`/rules/${ruleId}`, input))
}

/** `DELETE /api/rules/{ruleId}` (ADMIN) */
export function deleteRule(ruleId: UUID): Promise<void> {
  return deleteJson(`/rules/${ruleId}`)
}

/* -------------------------------------------------------------------------- */
/* Field catalog                                                               */
/* -------------------------------------------------------------------------- */

/**
 * `FieldType` is a plain Java enum with no `@JsonValue`, so the catalog arrives
 * with UPPER CASE type names. The reference panel keys off the lowercase union,
 * so the two vocabularies meet here and nowhere else.
 */
const FIELD_TYPE_ALIASES: Record<string, FieldType> = {
  number: 'number',
  integer: 'number',
  decimal: 'number',
  long: 'number',
  string: 'string',
  text: 'string',
  enum: 'enum',
  boolean: 'boolean',
  bool: 'boolean',
  datetime: 'datetime',
  timestamp: 'datetime',
  instant: 'datetime',
  date: 'date',
}

/** Unknown types degrade to `string`; the panel only uses this as a hint. */
function toFieldType(wire: string | null | undefined): FieldType {
  const known = FIELD_TYPE_ALIASES[String(wire ?? '').toLowerCase()]
  if (known) return known
  const lower = String(wire ?? '').toLowerCase()
  return (FIELD_TYPES as readonly string[]).includes(lower) ? (lower as FieldType) : 'string'
}

/** `agg.tx_count_24h` -> `aggregate`, `card.mcc_code` -> `card`, ... */
const PREFIX_CATEGORIES: Record<string, FieldCategory> = {
  agg: 'aggregate',
  aggregate: 'aggregate',
  aggregates: 'aggregate',
  card: 'card',
  payment: 'payment',
  crypto: 'crypto',
  customer: 'customer',
  transaction: 'transaction',
}

/**
 * `category` is a free-form string on the wire, so it needs a floor. Falling
 * back to the field path rather than to a fixed group keeps `agg.*` and
 * `payment.*` in the right section even if the backend ever omits it.
 */
function toCategory(wire: FieldCatalogEntryWire): FieldCategory {
  const declared = String(wire.category ?? '')
    .trim()
    .toLowerCase()
  if ((FIELD_CATEGORIES as readonly string[]).includes(declared)) return declared as FieldCategory
  const mappedDeclared = PREFIX_CATEGORIES[declared]
  if (mappedDeclared) return mappedDeclared

  const prefix = wire.field.includes('.') ? wire.field.split('.', 1)[0] : ''
  return PREFIX_CATEGORIES[prefix ?? ''] ?? 'transaction'
}

/** `payment.receiver_bank_country` -> `Receiver bank country`. */
function humanizeField(field: string): string {
  const leaf = field.includes('.') ? field.slice(field.lastIndexOf('.') + 1) : field
  const spaced = leaf.replace(/_+/g, ' ').trim()
  return spaced.charAt(0).toUpperCase() + spaced.slice(1)
}

function toScope(wire: RuleScope | null | undefined): RuleScope {
  return wire && (RULE_SCOPES as readonly string[]).includes(wire) ? wire : 'ALL'
}

/** Maps one wire catalog entry onto the shape the reference panel renders. */
export function normalizeFieldCatalogEntry(wire: FieldCatalogEntryWire): FieldCatalogEntry {
  const rawOptions = wire.options ?? []
  return {
    field: wire.field,
    label: trimmedOrNull(wire.label) ?? humanizeField(wire.field),
    type: toFieldType(wire.type),
    category: toCategory(wire),
    appliesTo: toScope(wire.appliesTo),
    description: trimmedOrNull(wire.description),
    options: Array.isArray(rawOptions) ? rawOptions.filter(Boolean) : [],
    nullable: wire.nullable === true,
    example: trimmedOrNull(wire.example),
  }
}

/** `GET /api/rules/field-catalog` (ADMIN) */
export async function fetchFieldCatalog(): Promise<FieldCatalogEntry[]> {
  const wire = await getJson<FieldCatalogEntryWire[]>('/rules/field-catalog')
  return (wire ?? [])
    .filter((entry) => typeof entry?.field === 'string' && entry.field.length > 0)
    .map(normalizeFieldCatalogEntry)
}

/* -------------------------------------------------------------------------- */
/* Rule test                                                                   */
/* -------------------------------------------------------------------------- */

/**
 * A rule test is a model call, not a query: the agent has to read the condition,
 * fetch the customer's activity and reason about it. The shared 30s axios
 * timeout would abort a healthy judgement, so this one request gets its own.
 *
 * Measured against the local gpt-oss-120b, a judgement of a twenty-transaction
 * scope takes two to three minutes. The backend gives up at 240s
 * (`caa.rules.judge.timeout-seconds`) and answers with a 504 that says so; this
 * budget is deliberately longer, so the server's honest error wins the race and
 * the admin is told what happened rather than watching the browser abandon a
 * request that was still going to succeed.
 */
export const RULE_TEST_TIMEOUT_MS = 300_000

const KNOWN_ACTIVITY_TYPES = new Set<string>(ACTIVITY_TYPES)

function normalizeMatch(wire: RuleTestMatchWire): RuleTestMatch {
  const activityType =
    wire.activityType && KNOWN_ACTIVITY_TYPES.has(wire.activityType)
      ? (wire.activityType as ActivityType)
      : null
  return {
    transactionId: wire.transactionId,
    activityType,
    amount: toFiniteNumber(wire.amount),
    currency: trimmedOrNull(wire.currency),
    status: trimmedOrNull(wire.status),
    createdAt: trimmedOrNull(wire.createdAt),
    reason: trimmedOrNull(wire.reason),
  }
}

/**
 * `matchedCount` is what the model cited; `matchedTransactions` is what the
 * backend chose to return. When the second is shorter the difference is real
 * evidence the panel is not showing, so it is flagged rather than smoothed over.
 */
export function normalizeRuleTestResult(wire: RuleTestResultWire): RuleTestResult {
  const matches: RuleTestMatch[] = (wire.matchedTransactions ?? [])
    .filter((match) => Boolean(match?.transactionId))
    .map(normalizeMatch)
  const matchedCount = toFiniteNumber(wire.matchedCount) ?? matches.length
  return {
    triggered: wire.triggered === true,
    score: toFiniteNumber(wire.score),
    weight: toFiniteNumber(wire.weight),
    rationale: trimmedOrNull(wire.rationale),
    matches,
    matchedCount,
    evidenceTruncated: matchedCount > matches.length,
    evaluatedCount: toFiniteNumber(wire.evaluatedTransactionCount),
    customerName: trimmedOrNull(wire.customerName),
    model: trimmedOrNull(wire.model),
    durationMs: toFiniteNumber(wire.durationMs),
    notes: Array.isArray(wire.notes) ? wire.notes.filter(Boolean) : [],
  }
}

/** `POST /api/rules/test` (ADMIN) — runs a real model judgement. */
export async function testRule(request: RuleTestRequest): Promise<RuleTestResult> {
  const wire = await postJson<RuleTestResultWire, RuleTestRequest>('/rules/test', request, {
    timeout: RULE_TEST_TIMEOUT_MS,
  })
  return normalizeRuleTestResult(wire ?? {})
}

/* -------------------------------------------------------------------------- */
/* Hooks                                                                       */
/* -------------------------------------------------------------------------- */

export function useRules(options?: QueryOpts<RiskRule[]>): UseQueryResult<RiskRule[], ApiError> {
  return useQuery<RiskRule[], ApiError, RiskRule[], readonly unknown[]>({
    queryKey: queryKeys.rules.list(),
    queryFn: fetchRules,
    staleTime: 60_000,
    ...options,
  })
}

export function useFieldCatalog(
  options?: QueryOpts<FieldCatalogEntry[]>,
): UseQueryResult<FieldCatalogEntry[], ApiError> {
  return useQuery<FieldCatalogEntry[], ApiError, FieldCatalogEntry[], readonly unknown[]>({
    queryKey: queryKeys.rules.fieldCatalog(),
    queryFn: fetchFieldCatalog,
    staleTime: 15 * 60_000,
    ...options,
  })
}

export function useCreateRule(
  options?: MutationOpts<RiskRule, RiskRuleInput>,
): UseMutationResult<RiskRule, ApiError, RiskRuleInput> {
  const queryClient = useQueryClient()
  return useMutation<RiskRule, ApiError, RiskRuleInput>({
    mutationFn: createRule,
    ...options,
    onSuccess: (data, variables, onMutateResult, context) => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.rules.root })
      options?.onSuccess?.(data, variables, onMutateResult, context)
    },
  })
}

export interface UpdateRuleVariables {
  ruleId: UUID
  input: RiskRuleInput
}

export function useUpdateRule(
  options?: MutationOpts<RiskRule, UpdateRuleVariables>,
): UseMutationResult<RiskRule, ApiError, UpdateRuleVariables> {
  const queryClient = useQueryClient()
  return useMutation<RiskRule, ApiError, UpdateRuleVariables>({
    mutationFn: ({ ruleId, input }) => updateRule(ruleId, input),
    ...options,
    onSuccess: (data, variables, onMutateResult, context) => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.rules.root })
      options?.onSuccess?.(data, variables, onMutateResult, context)
    },
  })
}

export function useDeleteRule(
  options?: MutationOpts<void, UUID>,
): UseMutationResult<void, ApiError, UUID> {
  const queryClient = useQueryClient()
  return useMutation<void, ApiError, UUID>({
    mutationFn: deleteRule,
    ...options,
    onSuccess: (data, variables, onMutateResult, context) => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.rules.root })
      options?.onSuccess?.(data, variables, onMutateResult, context)
    },
  })
}

/** "Run the agent's judgement" button on the rule editor. */
export function useTestRule(
  options?: MutationOpts<RuleTestResult, RuleTestRequest>,
): UseMutationResult<RuleTestResult, ApiError, RuleTestRequest> {
  return useMutation<RuleTestResult, ApiError, RuleTestRequest>({
    mutationFn: testRule,
    ...options,
  })
}
