import type { AuthResponse, IsoDateTime, User } from '../api/types'

const STORAGE_KEY = 'caa.auth'

export interface StoredAuth {
  token: string
  expiresAt: IsoDateTime | null
  user: User
}

/**
 * In-memory mirror of the persisted session. The axios request interceptor
 * reads this on every call, so it must not touch localStorage each time.
 */
let cached: StoredAuth | null | undefined

function isUser(value: unknown): value is User {
  if (typeof value !== 'object' || value === null) return false
  const record = value as Record<string, unknown>
  return (
    typeof record.username === 'string' &&
    typeof record.fullName === 'string' &&
    (record.role === 'ADMIN' || record.role === 'OPERATOR')
  )
}

function parse(raw: string | null): StoredAuth | null {
  if (!raw) return null
  try {
    const parsed: unknown = JSON.parse(raw)
    if (typeof parsed !== 'object' || parsed === null) return null
    const record = parsed as Record<string, unknown>
    if (typeof record.token !== 'string' || record.token.length === 0) return null
    if (!isUser(record.user)) return null
    return {
      token: record.token,
      expiresAt: typeof record.expiresAt === 'string' ? record.expiresAt : null,
      user: record.user,
    }
  } catch {
    return null
  }
}

export function readStoredAuth(): StoredAuth | null {
  if (cached !== undefined) return cached
  if (typeof window === 'undefined') {
    cached = null
    return cached
  }
  try {
    cached = parse(window.localStorage.getItem(STORAGE_KEY))
  } catch {
    cached = null
  }
  return cached
}

export function writeStoredAuth(auth: AuthResponse | StoredAuth): void {
  const next: StoredAuth = {
    token: auth.token,
    expiresAt: auth.expiresAt ?? null,
    user: auth.user,
  }
  cached = next
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(next))
  } catch {
    /* storage unavailable — the session still works until reload */
  }
}

export function clearStoredAuth(): void {
  cached = null
  try {
    window.localStorage.removeItem(STORAGE_KEY)
  } catch {
    /* nothing to clean up */
  }
}

export function getAuthToken(): string | null {
  return readStoredAuth()?.token ?? null
}

/** True when the stored token is known to have expired (with a 5s skew). */
export function isSessionExpired(auth: StoredAuth | null = readStoredAuth()): boolean {
  if (!auth) return true
  if (!auth.expiresAt) return false
  const expiry = Date.parse(auth.expiresAt)
  if (Number.isNaN(expiry)) return false
  return expiry - 5000 <= Date.now()
}

/** Test seam: drops the in-memory mirror so the next read hits storage. */
export function resetAuthCache(): void {
  cached = undefined
}
