import { useQueryClient } from '@tanstack/react-query'
import {
  createContext,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { fetchCurrentUser, login as loginRequest } from '../api/auth'
import { registerUnauthorizedHandler } from '../api/client'
import type { LoginRequest, Role, User } from '../api/types'
import {
  clearStoredAuth,
  isSessionExpired,
  readStoredAuth,
  writeStoredAuth,
  type StoredAuth,
} from './storage'

export interface AuthContextValue {
  user: User | null
  token: string | null
  role: Role | null
  isAuthenticated: boolean
  isAdmin: boolean
  /** True while the stored session is being re-checked against `/auth/me`. */
  isRevalidating: boolean
  login: (credentials: LoginRequest) => Promise<User>
  logout: () => void
  hasRole: (...roles: Role[]) => boolean
}

export const AuthContext = createContext<AuthContextValue | null>(null)

function initialSession(): StoredAuth | null {
  const stored = readStoredAuth()
  if (!stored) return null
  if (isSessionExpired(stored)) {
    clearStoredAuth()
    return null
  }
  return stored
}

export function AuthProvider({ children }: { children: ReactNode }) {
  /* The persisted session is read synchronously here, so the very first render
     already knows whether anyone is signed in — there is no hydration gap and
     therefore no flash of the sign-in redirect. */
  const [session, setSession] = useState<StoredAuth | null>(initialSession)
  /* Revalidation starts immediately for a restored session, so the flag is
     seeded rather than switched on from inside the effect. */
  const [isRevalidating, setIsRevalidating] = useState(() => Boolean(session?.token))
  const navigate = useNavigate()
  const location = useLocation()
  const locationRef = useRef(location)
  /** The token to revalidate is the one present at mount, captured once. */
  const mountedToken = useRef(session?.token ?? null)
  const queryClient = useQueryClient()

  useEffect(() => {
    locationRef.current = location
  }, [location])

  const clearSession = useCallback(() => {
    clearStoredAuth()
    setSession(null)
    queryClient.clear()
  }, [queryClient])

  /* A 401 anywhere in the app ends the session and returns to /login while
     remembering where the operator was trying to go. */
  useEffect(() => {
    return registerUnauthorizedHandler(() => {
      clearSession()
      const from = locationRef.current
      if (from.pathname !== '/login') {
        navigate('/login', { replace: true, state: { from } })
      }
    })
  }, [clearSession, navigate])

  /* Re-check the persisted token once on load so a revoked or expired session
     does not linger behind a cached user object. Later session changes are
     driven by `login` / `logout`, so this deliberately never re-runs. */
  useEffect(() => {
    if (!mountedToken.current) return
    let cancelled = false
    fetchCurrentUser()
      .then((user) => {
        if (cancelled) return
        setSession((current) => {
          if (!current) return current
          const next: StoredAuth = { ...current, user }
          writeStoredAuth(next)
          return next
        })
      })
      .catch(() => {
        /* 401 is handled by the interceptor; other failures keep the session. */
      })
      .finally(() => {
        if (!cancelled) setIsRevalidating(false)
      })
    return () => {
      cancelled = true
    }
  }, [])

  const login = useCallback(
    async (credentials: LoginRequest): Promise<User> => {
      const response = await loginRequest(credentials)
      writeStoredAuth(response)
      setSession({
        token: response.token,
        expiresAt: response.expiresAt ?? null,
        user: response.user,
      })
      queryClient.clear()
      return response.user
    },
    [queryClient],
  )

  const logout = useCallback(() => {
    clearSession()
    navigate('/login', { replace: true })
  }, [clearSession, navigate])

  const hasRole = useCallback(
    (...roles: Role[]) => (session ? roles.includes(session.user.role) : false),
    [session],
  )

  const value = useMemo<AuthContextValue>(
    () => ({
      user: session?.user ?? null,
      token: session?.token ?? null,
      role: session?.user.role ?? null,
      isAuthenticated: Boolean(session),
      isAdmin: session?.user.role === 'ADMIN',
      isRevalidating,
      login,
      logout,
      hasRole,
    }),
    [session, isRevalidating, login, logout, hasRole],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
