import axios, { type AxiosRequestConfig, type AxiosResponse } from 'axios'
import { clearStoredAuth, getAuthToken } from '../auth/storage'
import { toApiError } from './errors'
import type { PageResponse, SpringPage } from './types'

/** Same-origin base path; the Vite dev server proxies it to :8080. */
export const API_BASE_URL = '/api'

export const api = axios.create({
  baseURL: API_BASE_URL,
  timeout: 30_000,
  headers: { Accept: 'application/json' },
})

/* -------------------------------------------------------------------------- */
/* 401 handling                                                                */
/* -------------------------------------------------------------------------- */

type UnauthorizedHandler = () => void

let unauthorizedHandler: UnauthorizedHandler | null = null

/**
 * Lets `AuthProvider` take over the redirect so it happens inside the router
 * (preserving the intended destination). Without a handler the client falls
 * back to a hard navigation to /login.
 */
export function registerUnauthorizedHandler(handler: UnauthorizedHandler): () => void {
  unauthorizedHandler = handler
  return () => {
    if (unauthorizedHandler === handler) unauthorizedHandler = null
  }
}

function isLoginRequest(url: string | undefined): boolean {
  return typeof url === 'string' && url.includes('/auth/login')
}

function handleUnauthorized(): void {
  clearStoredAuth()
  if (unauthorizedHandler) {
    unauthorizedHandler()
    return
  }
  if (typeof window !== 'undefined' && !window.location.pathname.startsWith('/login')) {
    const next = `${window.location.pathname}${window.location.search}`
    window.location.assign(`/login?next=${encodeURIComponent(next)}`)
  }
}

/* -------------------------------------------------------------------------- */
/* Interceptors                                                                */
/* -------------------------------------------------------------------------- */

api.interceptors.request.use((config) => {
  const token = getAuthToken()
  if (token) config.headers.set('Authorization', `Bearer ${token}`)
  return config
})

api.interceptors.response.use(
  (response) => response,
  (error: unknown) => {
    const apiError = toApiError(error)
    const requestUrl =
      typeof error === 'object' && error !== null && 'config' in error
        ? (error as { config?: { url?: string } }).config?.url
        : undefined
    if (apiError.status === 401 && !isLoginRequest(requestUrl)) {
      handleUnauthorized()
    }
    return Promise.reject(apiError)
  },
)

/* -------------------------------------------------------------------------- */
/* Request helpers                                                             */
/* -------------------------------------------------------------------------- */

export type QueryParams = Record<
  string,
  string | number | boolean | null | undefined
>

/** Drops null/undefined/empty entries so the query string stays clean. */
export function cleanParams(params: QueryParams): Record<string, string | number | boolean> {
  const result: Record<string, string | number | boolean> = {}
  for (const [key, value] of Object.entries(params)) {
    if (value === null || value === undefined || value === '') continue
    result[key] = value
  }
  return result
}

export async function getJson<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
  const response: AxiosResponse<T> = await api.get<T>(url, config)
  return response.data
}

export async function postJson<T, B = unknown>(
  url: string,
  body?: B,
  config?: AxiosRequestConfig,
): Promise<T> {
  const response: AxiosResponse<T> = await api.post<T>(url, body, config)
  return response.data
}

export async function putJson<T, B = unknown>(
  url: string,
  body?: B,
  config?: AxiosRequestConfig,
): Promise<T> {
  const response: AxiosResponse<T> = await api.put<T>(url, body, config)
  return response.data
}

export async function deleteJson(url: string, config?: AxiosRequestConfig): Promise<void> {
  await api.delete(url, config)
}

/* -------------------------------------------------------------------------- */
/* Paging                                                                      */
/* -------------------------------------------------------------------------- */

/**
 * Normalises both Spring serialisations (flattened `Page` and Boot 4
 * `PagedModel` with a nested `page` object) into `PageResponse<T>`.
 */
export function toPage<T>(wire: SpringPage<T> | null | undefined): PageResponse<T> {
  const content = Array.isArray(wire?.content) ? wire.content : []
  const meta = wire?.page
  const size = meta?.size ?? wire?.size ?? content.length
  const number = meta?.number ?? wire?.number ?? 0
  const totalElements = meta?.totalElements ?? wire?.totalElements ?? content.length
  const totalPages =
    meta?.totalPages ?? wire?.totalPages ?? (size > 0 ? Math.ceil(totalElements / size) : 0)
  return {
    content,
    page: number,
    size,
    totalElements,
    totalPages,
    first: wire?.first ?? number === 0,
    last: wire?.last ?? (totalPages === 0 || number >= totalPages - 1),
    empty: wire?.empty ?? content.length === 0,
  }
}

export function emptyPage<T>(size = 20): PageResponse<T> {
  return {
    content: [],
    page: 0,
    size,
    totalElements: 0,
    totalPages: 0,
    first: true,
    last: true,
    empty: true,
  }
}
