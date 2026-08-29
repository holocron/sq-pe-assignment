import {
  useMutation,
  useQuery,
  useQueryClient,
  type UseMutationResult,
  type UseQueryResult,
} from '@tanstack/react-query'
import { emptyRuleLogic, parseRuleNode } from '../lib/rules'
import { deleteJson, getJson, postJson, putJson } from './client'
import type { ApiError } from './errors'
import type { MutationOpts, QueryOpts } from './query'
import { queryKeys } from './queryKeys'
import {
  RULE_OPERATORS,
  type FieldCatalogEntry,
  type FieldCatalogEntryWire,
  type FieldType,
  type RiskRule,
  type RiskRuleInput,
  type RiskRuleWire,
  type RuleOperator,
  type RuleTestRequest,
  type RuleTestResult,
  type UUID,
} from './types'

/** `threshold_logic` is a TEXT column, so it may arrive as a JSON string. */
export function normalizeRule(wire: RiskRuleWire): RiskRule {
  return {
    ruleId: wire.ruleId,
    ruleName: wire.ruleName,
    appliesTo: wire.appliesTo,
    thresholdLogic: parseRuleNode(wire.thresholdLogic) ?? emptyRuleLogic(),
    weight: typeof wire.weight === 'number' ? wire.weight : Number(wire.weight ?? 0),
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

/**
 * `rules/FieldType` is a plain Java enum with no `@JsonValue`, so the catalog
 * arrives with UPPER CASE type names. The editor's operator table, value
 * widgets and validators all key off the lowercase `FieldType` union, so the
 * name is folded here — the single place the two vocabularies meet.
 */
const FIELD_TYPE_ALIASES: Record<string, FieldType> = {
  number: 'number',
  integer: 'number',
  decimal: 'number',
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

/** Unknown types degrade to `string`, which supports every text operator. */
function toFieldType(wire: string | null | undefined): FieldType {
  return FIELD_TYPE_ALIASES[String(wire ?? '').toLowerCase()] ?? 'string'
}

function toOperators(wire: readonly string[] | null | undefined): RuleOperator[] | null {
  if (!Array.isArray(wire) || wire.length === 0) return null
  const known = wire.filter((operator): operator is RuleOperator =>
    (RULE_OPERATORS as readonly string[]).includes(operator),
  )
  return known.length > 0 ? known : null
}

/** Maps `RuleDtos.FieldCatalogEntry` onto the shape the visual editor reads. */
export function normalizeFieldCatalogEntry(wire: FieldCatalogEntryWire): FieldCatalogEntry {
  const options = Array.isArray(wire.options) ? wire.options.filter(Boolean) : []
  return {
    field: wire.field,
    type: toFieldType(wire.type),
    label: wire.label ?? null,
    notes: wire.description ?? null,
    values: options.length > 0 ? options : null,
    valuesClosed: wire.optionsClosed ?? null,
    operators: toOperators(wire.operators),
    appliesTo: wire.appliesTo ?? null,
    nullable: wire.nullable ?? null,
  }
}

/** `GET /api/rules/field-catalog` (ADMIN) */
export async function fetchFieldCatalog(): Promise<FieldCatalogEntry[]> {
  const wire = await getJson<FieldCatalogEntryWire[]>('/rules/field-catalog')
  return (wire ?? []).map(normalizeFieldCatalogEntry)
}

/** `POST /api/rules/test` (ADMIN) */
export async function testRule(request: RuleTestRequest): Promise<RuleTestResult> {
  const result = await postJson<RuleTestResult, RuleTestRequest>('/rules/test', request)
  return {
    ...result,
    sampleMatches: result.sampleMatches ?? [],
    degraded: Boolean(result.degraded),
    notes: Array.isArray(result.notes) ? result.notes.filter(Boolean) : [],
  }
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

/** "Test rule" button on the rule editor. */
export function useTestRule(
  options?: MutationOpts<RuleTestResult, RuleTestRequest>,
): UseMutationResult<RuleTestResult, ApiError, RuleTestRequest> {
  return useMutation<RuleTestResult, ApiError, RuleTestRequest>({
    mutationFn: testRule,
    ...options,
  })
}
