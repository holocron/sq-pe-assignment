import type {
  ActivityDetail,
  ActivityType,
  CardActivity,
  CryptoActivity,
  PaymentActivity,
  Transaction,
  TransactionStatus,
} from '../api/types'

export const ACTIVITY_TYPE_LABELS: Record<ActivityType, string> = {
  CARD: 'Card',
  PAYMENT: 'Payment',
  CRYPTO: 'Crypto',
}

export function activityTypeLabel(type: ActivityType | null | undefined): string {
  return type ? ACTIVITY_TYPE_LABELS[type] : '—'
}

/** Returns the inlined detail object for a transaction, whatever its type. */
export function activityDetail(transaction: Transaction): ActivityDetail | null {
  switch (transaction.activityType) {
    case 'CARD':
      return transaction.card
    case 'PAYMENT':
      return transaction.payment
    case 'CRYPTO':
      return transaction.crypto
  }
}

export function cardDetail(transaction: Transaction): CardActivity | null {
  return transaction.activityType === 'CARD' ? transaction.card : null
}

export function paymentDetail(transaction: Transaction): PaymentActivity | null {
  return transaction.activityType === 'PAYMENT' ? transaction.payment : null
}

export function cryptoDetail(transaction: Transaction): CryptoActivity | null {
  return transaction.activityType === 'CRYPTO' ? transaction.crypto : null
}

/**
 * Transaction status presentation. Deliberately does not reuse the risk ramp:
 * only genuine failures get the danger colour, everything else stays neutral so
 * a coloured cell in an activity table still means something specific.
 */
export type StatusTone = 'neutral' | 'info' | 'warning' | 'danger'

const STATUS_TONES: Record<string, StatusTone> = {
  completed: 'neutral',
  pending: 'info',
  failed: 'danger',
  reversed: 'warning',
  declined: 'danger',
}

export function statusTone(status: TransactionStatus | string | null | undefined): StatusTone {
  if (!status) return 'neutral'
  return STATUS_TONES[status.trim().toLowerCase()] ?? 'neutral'
}

export function statusLabel(status: TransactionStatus | string | null | undefined): string {
  if (!status) return '—'
  const trimmed = status.trim()
  return trimmed.charAt(0).toUpperCase() + trimmed.slice(1).toLowerCase()
}
