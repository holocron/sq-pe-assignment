import {
  useMutation,
  useQueries,
  useQuery,
  useQueryClient,
  type Query,
  type UseMutationResult,
  type UseQueryResult,
} from '@tanstack/react-query'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { getAuthToken } from '../auth/storage'
import { API_BASE_URL, getJson, postJson } from './client'
import { useCustomers } from './customers'
import { toApiError, type ApiError } from './errors'
import type { MutationOpts, QueryOpts } from './query'
import { queryKeys } from './queryKeys'
import {
  ANALYSIS_STATUSES,
  RISK_LEVELS,
  type AnalysisResult,
  type AnalysisResultWire,
  type AnalysisRun,
  type AnalysisStatus,
  type AnalysisStreamEvent,
  type AnalysisSummary,
  type CustomerSummary,
  type JsonObject,
  type JsonValue,
  type RiskLevel,
  type RuleEvaluation,
  type RuleEvaluationWire,
  type TraceStep,
  type UUID,
} from './types'

/* -------------------------------------------------------------------------- */
/* Normalisation                                                               */
/* -------------------------------------------------------------------------- */

function asObject(value: unknown): JsonObject | null {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
    ? (value as JsonObject)
    : null
}

function str(value: JsonValue | undefined): string | null {
  return typeof value === 'string' ? value : null
}

function num(value: JsonValue | undefined): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

function isAnalysisStatus(value: unknown): value is AnalysisStatus {
  return typeof value === 'string' && (ANALYSIS_STATUSES as readonly string[]).includes(value)
}

function asRiskLevel(value: JsonValue | undefined): RiskLevel | null {
  return typeof value === 'string' && (RISK_LEVELS as readonly string[]).includes(value)
    ? (value as RiskLevel)
    : null
}

/**
 * The trace JSONB (BUILD_SPEC section 4) uses snake_case keys inside the step
 * objects; the rest of the API is camelCase. Accept both and emit the
 * camelCase `TraceStep` union. Unrecognised step types survive as `unknown`
 * so the timeline never breaks on a new backend step type.
 */
export function normalizeTraceStep(raw: unknown, fallbackIndex = 0): TraceStep {
  const step = asObject(raw)
  if (!step) {
    return { type: 'unknown', n: fallbackIndex, rawType: 'unparseable', raw: {} }
  }
  const n = num(step.n) ?? fallbackIndex
  const ms = num(step.ms)
  const type = str(step.type) ?? 'unknown'
  switch (type) {
    case 'tool_call':
      return {
        type: 'tool_call',
        n,
        ms,
        tool: str(step.tool) ?? 'unknown_tool',
        args: step.args ?? null,
        resultPreview: str(step.result_preview) ?? str(step.resultPreview),
      }
    case 'assistant':
      return { type: 'assistant', n, ms, text: str(step.text) ?? '' }
    case 'coverage_reprompt': {
      const missing = Array.isArray(step.missing)
        ? step.missing.map((item) => String(item))
        : []
      return { type: 'coverage_reprompt', n, ms, missing }
    }
    case 'final':
      return {
        type: 'final',
        n,
        ms,
        riskLevel: asRiskLevel(step.risk_level) ?? asRiskLevel(step.riskLevel),
      }
    case 'error':
      return {
        type: 'error',
        n,
        ms,
        message: str(step.message) ?? str(step.error) ?? 'Agent error',
      }
    default:
      return { type: 'unknown', n, ms, rawType: type, raw: step }
  }
}

function normalizeTrace(trace: AnalysisResultWire['trace']): TraceStep[] {
  if (!trace) return []
  if (typeof trace === 'string') {
    try {
      return normalizeTrace(JSON.parse(trace) as AnalysisResultWire['trace'])
    } catch {
      return []
    }
  }
  const steps = Array.isArray(trace) ? trace : (asObject(trace)?.steps ?? null)
  if (!Array.isArray(steps)) return []
  return steps.map((step, index) => normalizeTraceStep(step, index + 1))
}

/**
 * A rule verdict always carries its matched-transaction evidence, but a run
 * that failed mid-flight can persist a row before the ids are known. The
 * coverage table expands into that array, so it is defaulted here rather than
 * guarded at every call site.
 */
export function normalizeRuleEvaluation(wire: RuleEvaluationWire): RuleEvaluation {
  return {
    ...wire,
    score: typeof wire.score === 'number' ? wire.score : Number(wire.score ?? 0),
    matchedTransactionIds: Array.isArray(wire.matchedTransactionIds)
      ? wire.matchedTransactionIds
      : [],
    degradationNotes: Array.isArray(wire.degradationNotes) ? wire.degradationNotes : [],
  }
}

export function normalizeAnalysisResult(wire: AnalysisResultWire): AnalysisResult {
  return {
    ...wire,
    summary: wire.summary ?? null,
    recommendations: wire.recommendations ?? null,
    ruleEvaluations: (wire.ruleEvaluations ?? []).map(normalizeRuleEvaluation),
    trace: normalizeTrace(wire.trace),
  }
}

/* -------------------------------------------------------------------------- */
/* Endpoints                                                                   */
/* -------------------------------------------------------------------------- */

/** `POST /api/customers/{customerId}/analyses` -> 202 Accepted. */
export function startAnalysis(customerId: UUID): Promise<AnalysisRun> {
  return postJson<AnalysisRun>(`/customers/${customerId}/analyses`)
}

/** `GET /api/analyses/{assessmentId}` */
export async function fetchAnalysis(assessmentId: UUID): Promise<AnalysisResult> {
  return normalizeAnalysisResult(await getJson<AnalysisResultWire>(`/analyses/${assessmentId}`))
}

/** `GET /api/customers/{customerId}/analyses` — history, newest first. */
export function fetchCustomerAnalyses(customerId: UUID): Promise<AnalysisSummary[]> {
  return getJson<AnalysisSummary[]>(`/customers/${customerId}/analyses`)
}

/* -------------------------------------------------------------------------- */
/* Hooks                                                                       */
/* -------------------------------------------------------------------------- */

export interface UseAnalysisOptions extends QueryOpts<AnalysisResult> {
  /** Poll every `pollIntervalMs` while the run is RUNNING. Default: on. */
  pollWhileRunning?: boolean
  pollIntervalMs?: number
}

export function useAnalysis(
  assessmentId: UUID | undefined,
  options: UseAnalysisOptions = {},
): UseQueryResult<AnalysisResult, ApiError> {
  const { pollWhileRunning = true, pollIntervalMs = 4000, ...queryOptions } = options
  return useQuery<AnalysisResult, ApiError, AnalysisResult, readonly unknown[]>({
    queryKey: queryKeys.analyses.detail(assessmentId ?? ''),
    queryFn: () => fetchAnalysis(assessmentId as UUID),
    enabled: Boolean(assessmentId),
    refetchInterval: (query) =>
      pollWhileRunning && query.state.data?.status === 'RUNNING' ? pollIntervalMs : false,
    ...queryOptions,
  })
}

export function useCustomerAnalyses(
  customerId: UUID | undefined,
  options?: QueryOpts<AnalysisSummary[]>,
): UseQueryResult<AnalysisSummary[], ApiError> {
  return useQuery<AnalysisSummary[], ApiError, AnalysisSummary[], readonly unknown[]>({
    queryKey: queryKeys.customers.analyses(customerId ?? ''),
    queryFn: () => fetchCustomerAnalyses(customerId as UUID),
    enabled: Boolean(customerId),
    ...options,
  })
}

/* -------------------------------------------------------------------------- */
/* Analyses across customers                                                   */
/* -------------------------------------------------------------------------- */

/**
 * How many customers the cross-customer views scan.
 *
 * The REST contract (BUILD_SPEC section 5) has no global analyses endpoint, so
 * anything that needs "analyses across customers" has to fan out over
 * `GET /api/customers/{id}/analyses`. The fan-out is bounded here, in one
 * place: if the backend ever grows `GET /api/analyses`, replace the body of
 * `useAnalysesAcrossCustomers` and every caller keeps working unchanged.
 */
export const ANALYSES_FANOUT_CUSTOMER_LIMIT = 25

/** Newest-first comparator for analysis rows, tolerant of unparseable dates. */
export function compareAnalysesNewestFirst(a: AnalysisSummary, b: AnalysisSummary): number {
  const left = Date.parse(a.createdAt)
  const right = Date.parse(b.createdAt)
  return (Number.isNaN(right) ? 0 : right) - (Number.isNaN(left) ? 0 : left)
}

/** Sorts a history list newest first without mutating the caller's array. */
export function sortAnalysesNewestFirst(analyses: readonly AnalysisSummary[]): AnalysisSummary[] {
  return [...analyses].sort(compareAnalysesNewestFirst)
}

export interface AnalysesAcrossCustomersOptions {
  /** Customers to scan. Defaults to `ANALYSES_FANOUT_CUSTOMER_LIMIT`. */
  limit?: number
  enabled?: boolean
  /** Re-poll a customer's history this often while one of its runs is live. */
  pollWhileRunningMs?: number
}

export interface AnalysesAcrossCustomers {
  /** The customers that were scanned, in the order the backend returned them. */
  customers: CustomerSummary[]
  /** Total customers on file, which may exceed `customers.length`. */
  totalCustomers: number
  customerNames: Map<UUID, string>
  /** Every analysis found, newest first. */
  rows: AnalysisSummary[]
  /** Newest COMPLETED run per customer, falling back to the newest run at all. */
  latestByCustomer: Map<UUID, AnalysisSummary>
  runningCount: number
  isPending: boolean
  error: ApiError | null
  refetch: () => void
}

/**
 * Merges every scanned customer's analysis history into one newest-first list,
 * plus the per-customer "latest verdict" the dashboard and watchlist need.
 */
export function useAnalysesAcrossCustomers(
  options: AnalysesAcrossCustomersOptions = {},
): AnalysesAcrossCustomers {
  const {
    limit = ANALYSES_FANOUT_CUSTOMER_LIMIT,
    enabled = true,
    pollWhileRunningMs = 5000,
  } = options

  const customersQuery = useCustomers({ page: 0, size: limit }, { enabled })
  const customers = useMemo(
    () => (enabled ? (customersQuery.data?.content ?? []) : []),
    [enabled, customersQuery.data],
  )

  const historyQueries = useQueries({
    queries: customers.map((customer) => ({
      queryKey: queryKeys.customers.analyses(customer.customerId),
      queryFn: () => fetchCustomerAnalyses(customer.customerId),
      staleTime: 30_000,
      refetchInterval: (query: Query<AnalysisSummary[]>) =>
        query.state.data?.some((item) => item.status === 'RUNNING')
          ? pollWhileRunningMs
          : (false as const),
    })),
  })

  const rows: AnalysisSummary[] = []
  const latestByCustomer = new Map<UUID, AnalysisSummary>()
  let runningCount = 0

  historyQueries.forEach((result, index) => {
    const customer = customers[index]
    if (!customer || !result.data) return
    const history = sortAnalysesNewestFirst(result.data)
    for (const analysis of history) {
      rows.push(analysis)
      if (analysis.status === 'RUNNING') runningCount += 1
    }
    const latest = history.find((analysis) => analysis.status === 'COMPLETED') ?? history[0]
    if (latest) latestByCustomer.set(customer.customerId, latest)
  })
  rows.sort(compareAnalysesNewestFirst)

  // `useQueries` widens the error type to `Error`; every rejection from the
  // api client is already an `ApiError`, and `toApiError` is a no-op for those.
  const rawFanOutError = historyQueries.find((result) => result.error)?.error
  const fanOutError = rawFanOutError ? toApiError(rawFanOutError) : null

  return {
    customers,
    totalCustomers: customersQuery.data?.totalElements ?? customers.length,
    customerNames: new Map(
      customers.map((customer) => [
        customer.customerId,
        [customer.firstName, customer.lastName].filter(Boolean).join(' ').trim(),
      ]),
    ),
    rows,
    latestByCustomer,
    runningCount,
    isPending:
      enabled &&
      (customersQuery.isPending || historyQueries.some((result) => result.isPending)),
    error: customersQuery.error ?? fanOutError,
    refetch: () => {
      void customersQuery.refetch()
      for (const result of historyQueries) void result.refetch()
    },
  }
}

/** Kicks off a run; invalidates that customer's analysis history on success. */
export function useStartAnalysis(
  options?: MutationOpts<AnalysisRun, UUID>,
): UseMutationResult<AnalysisRun, ApiError, UUID> {
  const queryClient = useQueryClient()
  return useMutation<AnalysisRun, ApiError, UUID>({
    mutationFn: startAnalysis,
    ...options,
    onSuccess: (data, customerId, onMutateResult, context) => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.customers.analyses(customerId) })
      options?.onSuccess?.(data, customerId, onMutateResult, context)
    },
  })
}

/* -------------------------------------------------------------------------- */
/* Server-Sent Events — GET /api/analyses/{assessmentId}/stream                */
/* -------------------------------------------------------------------------- */

/**
 * `EventSource` cannot send an `Authorization` header, so the JWT travels as
 * the `token` query parameter. The backend must accept `?token=<jwt>` on this
 * endpoint (and only this one) in addition to the bearer header.
 */
export function analysisStreamUrl(assessmentId: UUID, token = getAuthToken()): string {
  const base = `${API_BASE_URL}/analyses/${assessmentId}/stream`
  return token ? `${base}?token=${encodeURIComponent(token)}` : base
}

export interface AnalysisStreamHandlers {
  onOpen?: () => void
  onStep?: (step: TraceStep) => void
  onStatus?: (status: AnalysisStatus) => void
  onEvent?: (event: AnalysisStreamEvent) => void
  /** Transport failure; the caller decides whether to fall back to polling. */
  onError?: (message: string) => void
}

const STREAM_EVENT_NAMES = ['step', 'status', 'trace', 'analysis', 'complete', 'done'] as const

function parseStreamPayload(data: string): AnalysisStreamEvent | null {
  const trimmed = data.trim()
  if (!trimmed || trimmed === '[DONE]') return null
  let parsed: unknown
  try {
    parsed = JSON.parse(trimmed)
  } catch {
    return null
  }
  const record = asObject(parsed)
  if (!record) return null

  // A bare trace step, e.g. {"n":3,"type":"assistant","text":"..."}
  if (typeof record.type === 'string' && !('step' in record)) {
    const event: AnalysisStreamEvent = { step: normalizeTraceStep(record) }
    if (record.type === 'final') {
      event.status = 'COMPLETED'
      event.riskLevel = asRiskLevel(record.risk_level) ?? asRiskLevel(record.riskLevel)
    }
    return event
  }

  const event: AnalysisStreamEvent = {}
  const assessmentId = str(record.assessmentId)
  if (assessmentId) event.assessmentId = assessmentId
  if (isAnalysisStatus(record.status)) event.status = record.status
  if ('step' in record) event.step = normalizeTraceStep(record.step)
  const riskLevel = asRiskLevel(record.riskLevel) ?? asRiskLevel(record.risk_level)
  if (riskLevel) event.riskLevel = riskLevel
  const totalScore = num(record.totalScore)
  if (totalScore !== null) event.totalScore = totalScore
  const error = str(record.error)
  if (error) event.error = error
  return Object.keys(event).length > 0 ? event : null
}

/**
 * Subscribes to the live ReAct trace. Returns an unsubscribe function.
 * Handles both a default `message` stream and named SSE events.
 */
export function openAnalysisStream(
  assessmentId: UUID,
  handlers: AnalysisStreamHandlers,
): () => void {
  if (typeof EventSource === 'undefined') {
    handlers.onError?.('Live streaming is not supported in this browser.')
    return () => undefined
  }

  const source = new EventSource(analysisStreamUrl(assessmentId))
  let closed = false

  const dispatch = (data: string) => {
    const event = parseStreamPayload(data)
    if (!event) return
    handlers.onEvent?.(event)
    if (event.step) handlers.onStep?.(event.step)
    if (event.status) handlers.onStatus?.(event.status)
  }

  const messageListener = (event: MessageEvent<string>) => dispatch(event.data)
  source.addEventListener('open', () => handlers.onOpen?.())
  source.addEventListener('message', messageListener)
  for (const name of STREAM_EVENT_NAMES) {
    source.addEventListener(name, messageListener as EventListener)
  }
  source.addEventListener('error', (event: Event) => {
    // A named `error` event from the server carries data; a transport failure does not.
    const data = (event as MessageEvent<string>).data
    if (typeof data === 'string' && data.length > 0) {
      dispatch(data)
      return
    }
    if (closed) return
    if (source.readyState === EventSource.CLOSED) {
      handlers.onError?.('Live updates disconnected.')
    }
  })

  return () => {
    closed = true
    source.close()
  }
}

export interface UseAnalysisStreamOptions {
  /** Defaults to true when an id is supplied. */
  enabled?: boolean
  onStatus?: (status: AnalysisStatus) => void
}

export interface UseAnalysisStreamResult {
  /** Steps received on this connection, in arrival order. */
  steps: TraceStep[]
  status: AnalysisStatus | null
  connected: boolean
  error: string | null
  reset: () => void
}

/**
 * Live trace for the analysis page. On COMPLETED/FAILED the cached
 * `analyses.detail` query is invalidated so the persisted result takes over.
 */
export function useAnalysisStream(
  assessmentId: UUID | undefined,
  options: UseAnalysisStreamOptions = {},
): UseAnalysisStreamResult {
  const { enabled = true, onStatus } = options
  const queryClient = useQueryClient()
  const [steps, setSteps] = useState<TraceStep[]>([])
  const [status, setStatus] = useState<AnalysisStatus | null>(null)
  const [connected, setConnected] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [streamId, setStreamId] = useState<UUID | undefined>(assessmentId)
  const onStatusRef = useRef(onStatus)

  useEffect(() => {
    onStatusRef.current = onStatus
  }, [onStatus])

  // Reset during render when the analysis changes, rather than in an effect.
  if (streamId !== assessmentId) {
    setStreamId(assessmentId)
    setSteps([])
    setStatus(null)
    setError(null)
  }

  const reset = useCallback(() => {
    setSteps([])
    setStatus(null)
    setError(null)
  }, [])

  useEffect(() => {
    if (!assessmentId || !enabled) return

    const close = openAnalysisStream(assessmentId, {
      onOpen: () => {
        setConnected(true)
        setError(null)
      },
      onStep: (step) => {
        setSteps((current) =>
          current.some((existing) => existing.n === step.n && existing.type === step.type)
            ? current
            : [...current, step],
        )
      },
      onStatus: (next) => {
        setStatus(next)
        onStatusRef.current?.(next)
        if (next !== 'RUNNING') {
          void queryClient.invalidateQueries({
            queryKey: queryKeys.analyses.detail(assessmentId),
          })
        }
      },
      onError: (message) => {
        setConnected(false)
        setError(message)
      },
    })

    return () => {
      close()
      setConnected(false)
    }
  }, [assessmentId, enabled, queryClient])

  return { steps, status, connected, error, reset }
}
