import { keepPreviousData, useQuery, type UseQueryResult } from '@tanstack/react-query'
import { cleanParams, getJson, toPage } from './client'
import type { ApiError } from './errors'
import type { QueryOpts } from './query'
import { queryKeys } from './queryKeys'
import { normalizeTransaction } from './transactions'
import type {
  ActivityQueryParams,
  ActivitySummary,
  ActivitySummaryWire,
  Customer,
  CustomerSearchParams,
  CustomerSummary,
  PageResponse,
  SpringPage,
  Transaction,
  TransactionWire,
  UUID,
} from './types'

export const DEFAULT_PAGE_SIZE = 20

/**
 * The summary endpoint reports currencies as `byCurrency` rollup objects. The
 * UI only ever needs the codes — to decide whether one currency symbol may be
 * used on the aggregate amounts — so they are derived here rather than each
 * component reaching into the rollup. Every other field keeps its wire name.
 */
export function normalizeActivitySummary(wire: ActivitySummaryWire): ActivitySummary {
  const byCurrency = wire.byCurrency ?? []
  return {
    ...wire,
    byActivityType: wire.byActivityType ?? [],
    byStatus: wire.byStatus ?? [],
    byCurrency,
    counterpartyCountries: wire.counterpartyCountries ?? [],
    dailyTimeline: wire.dailyTimeline ?? [],
    currencies: byCurrency
      .map((entry) => entry.currency)
      .filter((code): code is string => Boolean(code)),
  }
}

/** `GET /api/customers?query=&page=&size=` */
export async function searchCustomers(
  params: CustomerSearchParams = {},
): Promise<PageResponse<CustomerSummary>> {
  const wire = await getJson<SpringPage<CustomerSummary>>('/customers', {
    params: cleanParams({
      query: params.query,
      page: params.page ?? 0,
      size: params.size ?? DEFAULT_PAGE_SIZE,
    }),
  })
  return toPage(wire)
}

/** `GET /api/customers/{customerId}` */
export function fetchCustomer(customerId: UUID): Promise<Customer> {
  return getJson<Customer>(`/customers/${customerId}`)
}

/** `GET /api/customers/{customerId}/summary` */
export async function fetchCustomerSummary(customerId: UUID): Promise<ActivitySummary> {
  return normalizeActivitySummary(
    await getJson<ActivitySummaryWire>(`/customers/${customerId}/summary`),
  )
}

/** `GET /api/customers/{customerId}/activity?type=&status=&from=&to=&page=&size=` */
export async function fetchCustomerActivity(
  customerId: UUID,
  params: ActivityQueryParams = {},
): Promise<PageResponse<Transaction>> {
  const wire = await getJson<SpringPage<TransactionWire>>(`/customers/${customerId}/activity`, {
    params: cleanParams({
      type: params.type,
      status: params.status,
      from: params.from,
      to: params.to,
      page: params.page ?? 0,
      size: params.size ?? DEFAULT_PAGE_SIZE,
    }),
  })
  const page = toPage(wire)
  return { ...page, content: page.content.map(normalizeTransaction) }
}

/* -------------------------------------------------------------------------- */
/* Hooks                                                                       */
/* -------------------------------------------------------------------------- */

/** Paged customer search. Keeps the previous page visible while fetching. */
export function useCustomers(
  params: CustomerSearchParams = {},
  options?: QueryOpts<PageResponse<CustomerSummary>>,
): UseQueryResult<PageResponse<CustomerSummary>, ApiError> {
  return useQuery<PageResponse<CustomerSummary>, ApiError, PageResponse<CustomerSummary>, readonly unknown[]>({
    queryKey: queryKeys.customers.list(params),
    queryFn: () => searchCustomers(params),
    placeholderData: keepPreviousData,
    staleTime: 30_000,
    ...options,
  })
}

export function useCustomer(
  customerId: UUID | undefined,
  options?: QueryOpts<Customer>,
): UseQueryResult<Customer, ApiError> {
  return useQuery<Customer, ApiError, Customer, readonly unknown[]>({
    queryKey: queryKeys.customers.detail(customerId ?? ''),
    queryFn: () => fetchCustomer(customerId as UUID),
    enabled: Boolean(customerId),
    ...options,
  })
}

export function useCustomerSummary(
  customerId: UUID | undefined,
  options?: QueryOpts<ActivitySummary>,
): UseQueryResult<ActivitySummary, ApiError> {
  return useQuery<ActivitySummary, ApiError, ActivitySummary, readonly unknown[]>({
    queryKey: queryKeys.customers.summary(customerId ?? ''),
    queryFn: () => fetchCustomerSummary(customerId as UUID),
    enabled: Boolean(customerId),
    ...options,
  })
}

export function useCustomerActivity(
  customerId: UUID | undefined,
  params: ActivityQueryParams = {},
  options?: QueryOpts<PageResponse<Transaction>>,
): UseQueryResult<PageResponse<Transaction>, ApiError> {
  return useQuery<PageResponse<Transaction>, ApiError, PageResponse<Transaction>, readonly unknown[]>({
    queryKey: queryKeys.customers.activity(customerId ?? '', params),
    queryFn: () => fetchCustomerActivity(customerId as UUID, params),
    enabled: Boolean(customerId),
    placeholderData: keepPreviousData,
    ...options,
  })
}
