import { useQuery, type UseQueryResult } from '@tanstack/react-query'
import { getJson } from './client'
import type { ApiError } from './errors'
import type { QueryOpts } from './query'
import { queryKeys } from './queryKeys'
import type { AppUser } from './types'

/** `GET /api/users` (ADMIN) */
export async function fetchUsers(): Promise<AppUser[]> {
  return (await getJson<AppUser[]>('/users')) ?? []
}

export function useUsers(options?: QueryOpts<AppUser[]>): UseQueryResult<AppUser[], ApiError> {
  return useQuery<AppUser[], ApiError, AppUser[], readonly unknown[]>({
    queryKey: queryKeys.users.list(),
    queryFn: fetchUsers,
    staleTime: 60_000,
    ...options,
  })
}
