import type { UseMutationOptions, UseQueryOptions } from '@tanstack/react-query'
import type { ApiError } from './errors'

/**
 * Options callers may pass to the exported query hooks. `queryKey`/`queryFn`
 * are owned by the hook; everything else (enabled, staleTime, select, ...) is
 * forwarded to TanStack Query untouched.
 */
export type QueryOpts<TData> = Omit<
  UseQueryOptions<TData, ApiError, TData, readonly unknown[]>,
  'queryKey' | 'queryFn'
>

/** Options callers may pass to the exported mutation hooks. */
export type MutationOpts<TData, TVariables> = Omit<
  UseMutationOptions<TData, ApiError, TVariables>,
  'mutationFn'
>
