import { isAxiosError } from 'axios'
import type { ProblemDetail } from './types'

export interface ApiErrorInit {
  status: number
  title: string
  detail?: string
  type?: string
  instance?: string
  fieldErrors?: Record<string, string[]>
  problem?: ProblemDetail | null
  cause?: unknown
}

/**
 * Normalised error for every failed API call.
 *
 * Backend failures are RFC-7807 `application/problem+json`; transport failures
 * (offline, DNS, CORS) surface as `status === 0` with `isNetworkError` set.
 */
export class ApiError extends Error {
  readonly status: number
  readonly title: string
  readonly detail: string | undefined
  readonly type: string | undefined
  readonly instance: string | undefined
  /** Bean-validation errors keyed by field name, when the backend sends them. */
  readonly fieldErrors: Record<string, string[]>
  readonly problem: ProblemDetail | null

  constructor(init: ApiErrorInit) {
    super(init.detail?.trim() || init.title)
    this.name = 'ApiError'
    this.status = init.status
    this.title = init.title
    this.detail = init.detail
    this.type = init.type
    this.instance = init.instance
    this.fieldErrors = init.fieldErrors ?? {}
    this.problem = init.problem ?? null
    if (init.cause !== undefined) this.cause = init.cause
  }

  get isNetworkError(): boolean {
    return this.status === 0
  }

  get isUnauthorized(): boolean {
    return this.status === 401
  }

  get isForbidden(): boolean {
    return this.status === 403
  }

  get isNotFound(): boolean {
    return this.status === 404
  }
}

export function isApiError(error: unknown): error is ApiError {
  return error instanceof ApiError
}

const STATUS_TITLES: Record<number, string> = {
  400: 'Invalid request',
  401: 'Session expired',
  403: 'Not permitted',
  404: 'Not found',
  409: 'Conflict',
  413: 'File too large',
  415: 'Unsupported file type',
  422: 'Validation failed',
  429: 'Too many requests',
  500: 'Server error',
  502: 'Backend unavailable',
  503: 'Backend unavailable',
  504: 'Backend timed out',
}

function statusTitle(status: number): string {
  return STATUS_TITLES[status] ?? (status >= 500 ? 'Server error' : 'Request failed')
}

function asRecord(value: unknown): Record<string, unknown> | null {
  if (typeof value === 'string') {
    const trimmed = value.trim()
    if (!trimmed.startsWith('{')) return null
    try {
      const parsed: unknown = JSON.parse(trimmed)
      return typeof parsed === 'object' && parsed !== null && !Array.isArray(parsed)
        ? (parsed as Record<string, unknown>)
        : null
    } catch {
      return null
    }
  }
  return typeof value === 'object' && value !== null && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null
}

function readFieldErrors(record: Record<string, unknown>): Record<string, string[]> {
  const raw = record.errors
  const result: Record<string, string[]> = {}
  if (Array.isArray(raw)) {
    for (const item of raw) {
      const entry = asRecord(item)
      const field = typeof entry?.field === 'string' ? entry.field : null
      const message =
        typeof entry?.message === 'string'
          ? entry.message
          : typeof entry?.defaultMessage === 'string'
            ? entry.defaultMessage
            : null
      if (field && message) result[field] = [...(result[field] ?? []), message]
    }
    return result
  }
  const map = asRecord(raw)
  if (!map) return result
  for (const [field, value] of Object.entries(map)) {
    if (typeof value === 'string') result[field] = [value]
    else if (Array.isArray(value)) result[field] = value.map((item) => String(item))
  }
  return result
}

/** Converts anything thrown by axios (or elsewhere) into an `ApiError`. */
export function toApiError(error: unknown): ApiError {
  if (isApiError(error)) return error

  if (isAxiosError(error)) {
    const response = error.response
    if (!response) {
      const timedOut = error.code === 'ECONNABORTED' || error.code === 'ETIMEDOUT'
      return new ApiError({
        status: 0,
        title: timedOut ? 'Request timed out' : 'Cannot reach the server',
        detail: timedOut
          ? 'The backend did not respond in time. Please retry.'
          : 'The API did not respond. Check that the backend is running on port 8080.',
        cause: error,
      })
    }
    const data: Record<string, unknown> = asRecord(response.data) ?? {}
    const rawStatus = data.status
    const status = typeof rawStatus === 'number' ? rawStatus : response.status
    const rawTitle = data.title
    const title =
      typeof rawTitle === 'string' && rawTitle.trim().length > 0
        ? rawTitle
        : statusTitle(status)
    const rawDetail = data.detail
    const rawMessage = data.message
    const detail =
      typeof rawDetail === 'string' && rawDetail.trim().length > 0
        ? rawDetail
        : typeof rawMessage === 'string' && rawMessage.trim().length > 0
          ? rawMessage
          : undefined
    return new ApiError({
      status,
      title,
      detail,
      type: typeof data.type === 'string' ? data.type : undefined,
      instance: typeof data.instance === 'string' ? data.instance : undefined,
      fieldErrors: readFieldErrors(data),
      problem: data as unknown as ProblemDetail,
      cause: error,
    })
  }

  if (error instanceof Error) {
    return new ApiError({ status: 0, title: error.name || 'Unexpected error', detail: error.message, cause: error })
  }

  return new ApiError({ status: 0, title: 'Unexpected error', detail: String(error) })
}

/** Best-effort single-line message for any thrown value. */
export function errorMessage(error: unknown): string {
  if (error === null || error === undefined) return 'Unknown error'
  if (isApiError(error)) return error.detail?.trim() || error.title
  if (error instanceof Error) return error.message || error.name
  if (typeof error === 'string') return error
  return 'Unexpected error'
}

/** Short heading for an error surface. */
export function errorTitle(error: unknown): string {
  if (isApiError(error)) return error.title
  if (error instanceof Error && error.name && error.name !== 'Error') return error.name
  return 'Something went wrong'
}
