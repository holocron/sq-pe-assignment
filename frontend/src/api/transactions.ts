import { useQuery, type UseQueryResult } from '@tanstack/react-query'
import { getJson } from './client'
import type { ApiError } from './errors'
import type { QueryOpts } from './query'
import { queryKeys } from './queryKeys'
import type { Transaction, TransactionWire, UUID } from './types'

/**
 * Collapses the two shapes the backend may use for the per-type detail object
 * (named key `card`/`payment`/`crypto`, or a generic `detail`) into the
 * discriminated `Transaction` union.
 */
export function normalizeTransaction(wire: TransactionWire): Transaction {
  const base = {
    transactionId: wire.transactionId,
    customerId: wire.customerId,
    amount: typeof wire.amount === 'number' ? wire.amount : Number(wire.amount ?? 0),
    currency: wire.currency,
    status: wire.status,
    createdAt: wire.createdAt,
  }
  switch (wire.activityType) {
    case 'CARD':
      return {
        ...base,
        activityType: 'CARD',
        card: wire.card ?? (wire.detail && 'cardPan' in wire.detail ? wire.detail : null),
      }
    case 'PAYMENT':
      return {
        ...base,
        activityType: 'PAYMENT',
        payment:
          wire.payment ?? (wire.detail && 'paymentMethod' in wire.detail ? wire.detail : null),
      }
    case 'CRYPTO':
      return {
        ...base,
        activityType: 'CRYPTO',
        crypto: wire.crypto ?? (wire.detail && 'blockchain' in wire.detail ? wire.detail : null),
      }
  }
}

/** `GET /api/transactions/{transactionId}` */
export async function fetchTransaction(transactionId: UUID): Promise<Transaction> {
  return normalizeTransaction(await getJson<TransactionWire>(`/transactions/${transactionId}`))
}

export function useTransaction(
  transactionId: UUID | undefined,
  options?: QueryOpts<Transaction>,
): UseQueryResult<Transaction, ApiError> {
  return useQuery<Transaction, ApiError, Transaction, readonly unknown[]>({
    queryKey: queryKeys.transactions.detail(transactionId ?? ''),
    queryFn: () => fetchTransaction(transactionId as UUID),
    enabled: Boolean(transactionId),
    ...options,
  })
}
