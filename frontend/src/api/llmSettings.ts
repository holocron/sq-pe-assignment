/**
 * Runtime LLM configuration (ADMIN): the OpenAI-compatible endpoint and the
 * chat/embedding models the agent and the knowledge base use. Changing the
 * embedding model invalidates every stored vector, so the save can answer 409
 * until the admin confirms the re-embed, and a started rebuild is tracked
 * through `reembed-status`.
 */
import {
  useMutation,
  useQuery,
  useQueryClient,
  type UseMutationResult,
  type UseQueryResult,
} from '@tanstack/react-query'
import { cleanParams, getJson, postJson, putJson } from './client'
import type { ApiError } from './errors'
import type { MutationOpts, QueryOpts } from './query'
import { queryKeys } from './queryKeys'
import {
  LLM_SETTINGS_SOURCES,
  type LlmConnectionTest,
  type LlmConnectionTestWire,
  type LlmModelListWire,
  type LlmSettings,
  type LlmSettingsInput,
  type LlmSettingsSaveResult,
  type LlmSettingsSaveWire,
  type LlmSettingsSource,
  type LlmSettingsWire,
  type ReembedStatus,
  type ReembedStatusWire,
} from './types'

/** Poll cadence while a re-embed rebuild is running. */
export const REEMBED_POLL_INTERVAL_MS = 2_500

function trimmedOrNull(value: string | null | undefined): string | null {
  const text = typeof value === 'string' ? value.trim() : ''
  return text.length > 0 ? text : null
}

function toFiniteNumber(value: number | string | null | undefined): number | null {
  if (value === null || value === undefined || value === '') return null
  const parsed = typeof value === 'number' ? value : Number(value)
  return Number.isFinite(parsed) ? parsed : null
}

function toCount(value: number | string | null | undefined): number {
  return toFiniteNumber(value) ?? 0
}

function toSource(wire: string | null | undefined): LlmSettingsSource {
  const declared = String(wire ?? '').toLowerCase()
  return (LLM_SETTINGS_SOURCES as readonly string[]).includes(declared)
    ? (declared as LlmSettingsSource)
    : 'environment'
}

export function normalizeLlmSettings(wire: LlmSettingsWire | null | undefined): LlmSettings {
  const chatModel = typeof wire?.chatModel === 'string' ? wire.chatModel : ''
  return {
    baseUrl: typeof wire?.baseUrl === 'string' ? wire.baseUrl : '',
    chatModel,
    /* A backend predating the tooling role sends no toolModel — subagents then
       run on the chat model, so that is the honest fallback. */
    toolModel: typeof wire?.toolModel === 'string' && wire.toolModel ? wire.toolModel : chatModel,
    embedModel: typeof wire?.embedModel === 'string' ? wire.embedModel : '',
    embedDimension: toFiniteNumber(wire?.embedDimension),
    chatApiKeySet: wire?.chatApiKeySet === true,
    embedApiKeySet: wire?.embedApiKeySet === true,
    source: toSource(wire?.source),
    updatedAt: trimmedOrNull(wire?.updatedAt),
    updatedBy: trimmedOrNull(wire?.updatedBy),
  }
}

export function normalizeReembedStatus(wire: ReembedStatusWire | null | undefined): ReembedStatus {
  return {
    running: wire?.running === true,
    totalDocuments: toCount(wire?.totalDocuments),
    completedDocuments: toCount(wire?.completedDocuments),
    failedDocuments: toCount(wire?.failedDocuments),
    lastError: trimmedOrNull(wire?.lastError),
  }
}

/* -------------------------------------------------------------------------- */
/* Calls                                                                       */
/* -------------------------------------------------------------------------- */

/** `GET /api/admin/llm-settings` (ADMIN) */
export async function fetchLlmSettings(): Promise<LlmSettings> {
  return normalizeLlmSettings(await getJson<LlmSettingsWire>('/admin/llm-settings'))
}

/** Shared PUT/test body builder. Per-model keys follow the contract: field
    omitted = keep the stored key; empty string = explicitly no key; non-empty
    = set. Callers only pass the fields the admin actually touched. */
function llmSettingsBody(input: LlmSettingsInput): LlmSettingsInput {
  const body: LlmSettingsInput = {
    baseUrl: input.baseUrl.trim(),
    chatModel: input.chatModel.trim(),
    toolModel: input.toolModel.trim(),
    embedModel: input.embedModel.trim(),
  }
  if (input.chatApiKey !== undefined) body.chatApiKey = input.chatApiKey.trim()
  if (input.embedApiKey !== undefined) body.embedApiKey = input.embedApiKey.trim()
  return body
}

/** `PUT /api/admin/llm-settings` (ADMIN) — may answer 409 on an unconfirmed embedding-model change. */
export async function updateLlmSettings(input: LlmSettingsInput): Promise<LlmSettingsSaveResult> {
  const body = llmSettingsBody(input)
  if (input.confirmReembed === true) body.confirmReembed = true
  const wire = await putJson<LlmSettingsSaveWire, LlmSettingsInput>('/admin/llm-settings', body)
  return { settings: normalizeLlmSettings(wire), reembedStarted: wire?.reembedStarted === true }
}

export interface FetchLlmModelsParams {
  baseUrl: string
  /** Only sent while the admin has typed a replacement key. */
  apiKey?: string
}

/**
 * `GET /api/admin/llm-settings/models?baseUrl=…&apiKey=…` (ADMIN). A dead
 * endpoint answers 502 problem+json; callers show that error and fall back to
 * free-text model fields rather than leaving the form stuck on empty selects.
 */
export async function fetchLlmModels(params: FetchLlmModelsParams): Promise<string[]> {
  const wire = await getJson<LlmModelListWire>('/admin/llm-settings/models', {
    params: cleanParams({ baseUrl: params.baseUrl.trim(), apiKey: params.apiKey?.trim() }),
  })
  return (wire?.models ?? []).filter((model) => typeof model === 'string' && model.length > 0)
}

/** `POST /api/admin/llm-settings/test` (ADMIN) — probes chat and embeddings. */
export async function testLlmConnection(input: LlmSettingsInput): Promise<LlmConnectionTest> {
  const wire = await postJson<LlmConnectionTestWire, LlmSettingsInput>(
    '/admin/llm-settings/test',
    llmSettingsBody(input),
  )
  return {
    chat: {
      ok: wire?.chat?.ok === true,
      detail: trimmedOrNull(wire?.chat?.detail),
    },
    /* The tooling probe appeared with the tool-model role; an older backend
       answers without it, and then it simply was not probed. */
    tool: {
      ok: wire?.tool?.ok === true,
      detail: trimmedOrNull(wire?.tool?.detail),
    },
    embed: {
      ok: wire?.embed?.ok === true,
      detail: trimmedOrNull(wire?.embed?.detail),
      dimension: toFiniteNumber(wire?.embed?.dimension),
    },
  }
}

/** `GET /api/admin/llm-settings/reembed-status` (ADMIN) */
export async function fetchReembedStatus(): Promise<ReembedStatus> {
  return normalizeReembedStatus(
    await getJson<ReembedStatusWire>('/admin/llm-settings/reembed-status'),
  )
}

/* -------------------------------------------------------------------------- */
/* Hooks                                                                       */
/* -------------------------------------------------------------------------- */

export function useLlmSettings(
  options?: QueryOpts<LlmSettings>,
): UseQueryResult<LlmSettings, ApiError> {
  return useQuery<LlmSettings, ApiError, LlmSettings, readonly unknown[]>({
    queryKey: queryKeys.llmSettings.settings(),
    queryFn: fetchLlmSettings,
    ...options,
  })
}

export function useUpdateLlmSettings(
  options?: MutationOpts<LlmSettingsSaveResult, LlmSettingsInput>,
): UseMutationResult<LlmSettingsSaveResult, ApiError, LlmSettingsInput> {
  const queryClient = useQueryClient()
  return useMutation<LlmSettingsSaveResult, ApiError, LlmSettingsInput>({
    mutationFn: updateLlmSettings,
    ...options,
    onSuccess: (data, variables, onMutateResult, context) => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.llmSettings.settings() })
      /* A save may have kicked off a re-embed; refreshing the status query is
         what starts the page's polling when `running` comes back true. */
      void queryClient.invalidateQueries({ queryKey: queryKeys.llmSettings.reembedStatus() })
      options?.onSuccess?.(data, variables, onMutateResult, context)
    },
  })
}

/** "Fetch models" affordance — a manual probe, not a cached query. */
export function useLlmModels(
  options?: MutationOpts<string[], FetchLlmModelsParams>,
): UseMutationResult<string[], ApiError, FetchLlmModelsParams> {
  return useMutation<string[], ApiError, FetchLlmModelsParams>({
    mutationFn: fetchLlmModels,
    ...options,
  })
}

export function useTestLlmConnection(
  options?: MutationOpts<LlmConnectionTest, LlmSettingsInput>,
): UseMutationResult<LlmConnectionTest, ApiError, LlmSettingsInput> {
  return useMutation<LlmConnectionTest, ApiError, LlmSettingsInput>({
    mutationFn: testLlmConnection,
    ...options,
  })
}

/**
 * Re-embed progress. The page passes `refetchInterval` keyed off
 * `data.running` so polling stops by itself when the rebuild finishes.
 */
export function useReembedStatus(
  options?: QueryOpts<ReembedStatus>,
): UseQueryResult<ReembedStatus, ApiError> {
  return useQuery<ReembedStatus, ApiError, ReembedStatus, readonly unknown[]>({
    queryKey: queryKeys.llmSettings.reembedStatus(),
    queryFn: fetchReembedStatus,
    staleTime: 0,
    ...options,
  })
}
