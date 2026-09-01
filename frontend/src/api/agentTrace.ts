/**
 * Verbose agent tracing (ADMIN). While enabled, the server writes the agent's
 * reasoning and full tool calls/results to a trace file that RESTARTS fresh
 * every time tracing is (re-)enabled. Content is read incrementally from a
 * byte offset — when the file shrank (a restart) the server answers from
 * offset 0 with the new file in full.
 */
import {
  useMutation,
  useQuery,
  useQueryClient,
  type UseMutationResult,
  type UseQueryResult,
} from '@tanstack/react-query'
import { api, getJson, postJson } from './client'
import type { ApiError } from './errors'
import type { MutationOpts, QueryOpts } from './query'
import { queryKeys } from './queryKeys'
import type {
  AgentTraceContent,
  AgentTraceContentWire,
  AgentTraceState,
  AgentTraceStateWire,
  AgentTraceToggleInput,
} from './types'

/** Poll cadence of the live trace viewer while tracing is enabled. */
export const AGENT_TRACE_POLL_INTERVAL_MS = 2_500

function toCount(value: number | string | null | undefined): number {
  if (value === null || value === undefined || value === '') return 0
  const parsed = typeof value === 'number' ? value : Number(value)
  return Number.isFinite(parsed) && parsed >= 0 ? parsed : 0
}

function trimmedOrNull(value: string | null | undefined): string | null {
  const text = typeof value === 'string' ? value.trim() : ''
  return text.length > 0 ? text : null
}

export function normalizeAgentTraceState(
  wire: AgentTraceStateWire | null | undefined,
): AgentTraceState {
  return {
    enabled: wire?.enabled === true,
    fileName: trimmedOrNull(wire?.fileName),
    startedAt: trimmedOrNull(wire?.startedAt),
    sizeBytes: toCount(wire?.sizeBytes),
  }
}

export function normalizeAgentTraceContent(
  wire: AgentTraceContentWire | null | undefined,
): AgentTraceContent {
  return {
    content: typeof wire?.content === 'string' ? wire.content : '',
    sizeBytes: toCount(wire?.sizeBytes),
    fromOffset: toCount(wire?.fromOffset),
  }
}

/* -------------------------------------------------------------------------- */
/* Calls                                                                       */
/* -------------------------------------------------------------------------- */

/** `GET /api/admin/agent-trace` (ADMIN) */
export async function fetchAgentTraceState(): Promise<AgentTraceState> {
  return normalizeAgentTraceState(await getJson<AgentTraceStateWire>('/admin/agent-trace'))
}

/** `POST /api/admin/agent-trace` (ADMIN) — enabling starts a fresh trace file. */
export async function setAgentTraceEnabled(
  input: AgentTraceToggleInput,
): Promise<AgentTraceState> {
  return normalizeAgentTraceState(
    await postJson<AgentTraceStateWire, AgentTraceToggleInput>('/admin/agent-trace', input),
  )
}

/** `GET /api/admin/agent-trace/content?offset=N` (ADMIN) — incremental read. */
export async function fetchAgentTraceContent(offset: number): Promise<AgentTraceContent> {
  return normalizeAgentTraceContent(
    await getJson<AgentTraceContentWire>('/admin/agent-trace/content', {
      params: { offset },
    }),
  )
}

/**
 * `GET /api/admin/agent-trace/download` (ADMIN). The plain-text body is read
 * through the authed axios client (an anchor href cannot carry the bearer
 * token) and handed back for the caller to save as a Blob.
 */
export async function downloadAgentTrace(): Promise<string> {
  const response = await api.get<string>('/admin/agent-trace/download', {
    responseType: 'text',
    transformResponse: [(data: unknown) => data],
  })
  return typeof response.data === 'string' ? response.data : String(response.data ?? '')
}

/* -------------------------------------------------------------------------- */
/* Hooks                                                                       */
/* -------------------------------------------------------------------------- */

export function useAgentTraceState(
  options?: QueryOpts<AgentTraceState>,
): UseQueryResult<AgentTraceState, ApiError> {
  return useQuery<AgentTraceState, ApiError, AgentTraceState, readonly unknown[]>({
    queryKey: queryKeys.agentTrace.state(),
    queryFn: fetchAgentTraceState,
    ...options,
  })
}

export function useSetAgentTraceEnabled(
  options?: MutationOpts<AgentTraceState, AgentTraceToggleInput>,
): UseMutationResult<AgentTraceState, ApiError, AgentTraceToggleInput> {
  const queryClient = useQueryClient()
  return useMutation<AgentTraceState, ApiError, AgentTraceToggleInput>({
    mutationFn: setAgentTraceEnabled,
    ...options,
    onSuccess: (data, variables, onMutateResult, context) => {
      /* Store the fresh state immediately — it carries the new fileName the
         viewer keys its restart detection on. */
      queryClient.setQueryData(queryKeys.agentTrace.state(), data)
      void queryClient.invalidateQueries({ queryKey: queryKeys.agentTrace.state() })
      options?.onSuccess?.(data, variables, onMutateResult, context)
    },
  })
}
