import { useMutation, useQuery, type UseMutationResult, type UseQueryResult } from '@tanstack/react-query'
import { getJson, postJson } from './client'
import type { ApiError } from './errors'
import type { MutationOpts, QueryOpts } from './query'
import { queryKeys } from './queryKeys'
import type { AuthResponse, LoginRequest, User } from './types'

/** `POST /api/auth/login` */
export function login(body: LoginRequest): Promise<AuthResponse> {
  return postJson<AuthResponse, LoginRequest>('/auth/login', body)
}

/** `GET /api/auth/me` */
export function fetchCurrentUser(): Promise<User> {
  return getJson<User>('/auth/me')
}

/**
 * Raw login mutation. Prefer `useAuth().login()` from `src/auth` — it also
 * persists the session and updates the auth context.
 */
export function useLogin(
  options?: MutationOpts<AuthResponse, LoginRequest>,
): UseMutationResult<AuthResponse, ApiError, LoginRequest> {
  return useMutation<AuthResponse, ApiError, LoginRequest>({
    mutationFn: login,
    ...options,
  })
}

/** Revalidates the stored session against the backend. */
export function useCurrentUser(options?: QueryOpts<User>): UseQueryResult<User, ApiError> {
  return useQuery<User, ApiError, User, readonly unknown[]>({
    queryKey: queryKeys.auth.me(),
    queryFn: fetchCurrentUser,
    staleTime: 5 * 60_000,
    retry: false,
    ...options,
  })
}
