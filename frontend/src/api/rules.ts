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
import type {
  FieldCatalogEntry,
  RiskRule,
  RiskRuleInput,
  RiskRuleWire,
  RuleTestRequest,
  RuleTestResult,
  UUID,
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

/** `GET /api/rules/field-catalog` (ADMIN) */
export async function fetchFieldCatalog(): Promise<FieldCatalogEntry[]> {
  return (await getJson<FieldCatalogEntry[]>('/rules/field-catalog')) ?? []
}

/** `POST /api/rules/test` (ADMIN) */
export async function testRule(request: RuleTestRequest): Promise<RuleTestResult> {
  const result = await postJson<RuleTestResult, RuleTestRequest>('/rules/test', request)
  return {
    ...result,
    sampleMatches: result.sampleMatches ?? [],
    degraded: Boolean(result.degraded),
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
