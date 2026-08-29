import type { ActivityType, Transaction } from '../../api/types'
import { Badge } from '../../components/ui/Badge'
import { StatusBadge } from '../../components/ui/StatusBadge'
import type { Column } from '../../components/ui/Table'
import {
  ACTIVITY_TYPE_LABELS,
  cardDetail,
  cryptoDetail,
  paymentDetail,
} from '../../lib/activity'
import {
  EM_DASH,
  formatCountry,
  formatDateTime,
  formatDateTimeSeconds,
  formatMoney,
  maskPan,
  truncateMiddle,
} from '../../lib/format'

/** `ALL` shows every activity type with a shared, type-agnostic column set. */
export type ActivityTab = 'ALL' | ActivityType

export const ACTIVITY_TABS: ActivityTab[] = ['ALL', 'CARD', 'PAYMENT', 'CRYPTO']

export function activityTabLabel(tab: ActivityTab): string {
  return tab === 'ALL' ? 'All activity' : ACTIVITY_TYPE_LABELS[tab]
}

/** Opaque identifiers (wallets, hashes, IBANs) render truncated but copyable. */
function Mono({ value, head = 8, tail = 6 }: { value: string | null | undefined; head?: number; tail?: number }) {
  if (!value) return <span className="text-subtle">{EM_DASH}</span>
  return (
    <span className="font-mono text-2xs text-muted" title={value}>
      {truncateMiddle(value, head, tail)}
    </span>
  )
}

function CountryCell({ code }: { code: string | null | undefined }) {
  if (!code) return <span className="text-subtle">{EM_DASH}</span>
  return <span title={formatCountry(code)}>{code}</span>
}

const dateColumn: Column<Transaction> = {
  key: 'createdAt',
  header: 'Date',
  cell: (transaction) => (
    <span className="numeric whitespace-nowrap text-muted" title={formatDateTimeSeconds(transaction.createdAt)}>
      {formatDateTime(transaction.createdAt)}
    </span>
  ),
  className: 'w-44',
}

const amountColumn: Column<Transaction> = {
  key: 'amount',
  header: 'Amount',
  align: 'right',
  cell: (transaction) => (
    <span className="numeric font-medium whitespace-nowrap text-fg">
      {formatMoney(transaction.amount, transaction.currency)}
    </span>
  ),
  className: 'w-40',
}

const statusColumn: Column<Transaction> = {
  key: 'status',
  header: 'Status',
  cell: (transaction) => <StatusBadge status={transaction.status} />,
  className: 'w-28',
}

/** One-line description of whatever detail row the transaction carries. */
function describe(transaction: Transaction): string {
  switch (transaction.activityType) {
    case 'CARD': {
      const card = transaction.card
      if (!card) return EM_DASH
      return [card.merchantName, card.mccCode ? `MCC ${card.mccCode}` : null]
        .filter(Boolean)
        .join(' · ')
    }
    case 'PAYMENT': {
      const payment = transaction.payment
      if (!payment) return EM_DASH
      return [payment.paymentMethod, payment.receiverBankCountry ? `to ${payment.receiverBankCountry}` : null]
        .filter(Boolean)
        .join(' · ')
    }
    case 'CRYPTO': {
      const crypto = transaction.crypto
      if (!crypto) return EM_DASH
      return [crypto.blockchain, crypto.exchangeName ?? 'no exchange']
        .filter(Boolean)
        .join(' · ')
    }
  }
}

const allColumns: Column<Transaction>[] = [
  dateColumn,
  {
    key: 'activityType',
    header: 'Type',
    cell: (transaction) => (
      <Badge tone="neutral">{ACTIVITY_TYPE_LABELS[transaction.activityType]}</Badge>
    ),
    className: 'w-28',
  },
  {
    key: 'detail',
    header: 'Detail',
    cell: (transaction) => (
      <span className="block max-w-[28rem] truncate text-muted">{describe(transaction)}</span>
    ),
  },
  amountColumn,
  statusColumn,
]

const cardColumns: Column<Transaction>[] = [
  dateColumn,
  {
    key: 'pan',
    header: 'Card',
    cell: (transaction) => {
      const card = cardDetail(transaction)
      return (
        <span className="numeric whitespace-nowrap">{card ? maskPan(card.cardPan) : EM_DASH}</span>
      )
    },
    className: 'w-32',
  },
  {
    key: 'cardType',
    header: 'Type',
    cell: (transaction) => cardDetail(transaction)?.cardType ?? EM_DASH,
    className: 'w-24 hidden xl:table-cell',
  },
  {
    key: 'merchant',
    header: 'Merchant',
    cell: (transaction) => (
      <span className="block max-w-56 truncate">{cardDetail(transaction)?.merchantName ?? EM_DASH}</span>
    ),
  },
  {
    key: 'mcc',
    header: 'MCC',
    cell: (transaction) => (
      <span className="numeric font-mono text-2xs text-muted">
        {cardDetail(transaction)?.mccCode ?? EM_DASH}
      </span>
    ),
    className: 'w-20',
  },
  {
    key: 'cardPresent',
    header: 'Presence',
    cell: (transaction) => {
      const card = cardDetail(transaction)
      if (!card) return <span className="text-subtle">{EM_DASH}</span>
      return card.cardPresent ? (
        <Badge tone="neutral">Card present</Badge>
      ) : (
        <Badge tone="warning">Card not present</Badge>
      )
    },
    className: 'w-36 hidden lg:table-cell',
  },
  {
    key: 'decline',
    header: 'Decline reason',
    cell: (transaction) => {
      const reason = cardDetail(transaction)?.declineReason
      return reason ? (
        <span className="text-xs text-danger-fg">{reason}</span>
      ) : (
        <span className="text-subtle">{EM_DASH}</span>
      )
    },
    className: 'w-44 hidden lg:table-cell',
  },
  amountColumn,
  statusColumn,
]

const paymentColumns: Column<Transaction>[] = [
  dateColumn,
  {
    key: 'method',
    header: 'Method',
    cell: (transaction) => paymentDetail(transaction)?.paymentMethod ?? EM_DASH,
    className: 'w-28',
  },
  {
    key: 'sender',
    header: 'Sender account',
    cell: (transaction) => <Mono value={paymentDetail(transaction)?.senderAccount} head={10} />,
    className: 'w-44 hidden lg:table-cell',
  },
  {
    key: 'receiver',
    header: 'Receiver account',
    cell: (transaction) => <Mono value={paymentDetail(transaction)?.receiverAccount} head={10} />,
    className: 'w-44',
  },
  {
    key: 'beneficiaryCountry',
    header: 'Beneficiary country',
    cell: (transaction) => <CountryCell code={paymentDetail(transaction)?.receiverBankCountry} />,
    className: 'w-40',
  },
  amountColumn,
  statusColumn,
]

const cryptoColumns: Column<Transaction>[] = [
  dateColumn,
  {
    key: 'chain',
    header: 'Chain',
    cell: (transaction) => {
      const crypto = cryptoDetail(transaction)
      return crypto ? <Badge tone="outline">{crypto.blockchain}</Badge> : EM_DASH
    },
    className: 'w-24',
  },
  {
    key: 'walletFrom',
    header: 'From wallet',
    cell: (transaction) => <Mono value={cryptoDetail(transaction)?.walletAddressFrom} />,
    className: 'w-40 hidden xl:table-cell',
  },
  {
    key: 'walletTo',
    header: 'To wallet',
    cell: (transaction) => <Mono value={cryptoDetail(transaction)?.walletAddressTo} />,
    className: 'w-40',
  },
  {
    key: 'txHash',
    header: 'Tx hash',
    cell: (transaction) => <Mono value={cryptoDetail(transaction)?.txHash} head={10} tail={6} />,
    className: 'w-44 hidden lg:table-cell',
  },
  {
    key: 'exchange',
    header: 'Exchange',
    cell: (transaction) => {
      const exchange = cryptoDetail(transaction)?.exchangeName
      return exchange ? (
        <span>{exchange}</span>
      ) : (
        <span className="text-xs text-muted" title="No exchange attributed to this transfer">
          Unattributed
        </span>
      )
    },
    className: 'w-36',
  },
  amountColumn,
  statusColumn,
]

/** Column set for a tab — each type shows the fields that actually matter. */
export function activityColumns(tab: ActivityTab): Column<Transaction>[] {
  switch (tab) {
    case 'CARD':
      return cardColumns
    case 'PAYMENT':
      return paymentColumns
    case 'CRYPTO':
      return cryptoColumns
    default:
      return allColumns
  }
}
