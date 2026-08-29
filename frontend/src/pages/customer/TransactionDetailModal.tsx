import { TriangleAlert } from 'lucide-react'
import type { ReactNode } from 'react'
import { useTransaction } from '../../api/transactions'
import type { Transaction } from '../../api/types'
import { Badge } from '../../components/ui/Badge'
import { Button } from '../../components/ui/Button'
import { Modal } from '../../components/ui/Modal'
import { Spinner } from '../../components/ui/Spinner'
import { StatusBadge } from '../../components/ui/StatusBadge'
import { ACTIVITY_TYPE_LABELS } from '../../lib/activity'
import { errorMessage } from '../../api/errors'
import {
  EM_DASH,
  formatCountry,
  formatDateTimeSeconds,
  formatMoney,
  maskPan,
} from '../../lib/format'
import { CopyButton } from './CopyButton'

export interface TransactionDetailModalProps {
  /** Row the operator clicked; used as the fallback while the detail loads. */
  transaction: Transaction | null
  open: boolean
  onClose: () => void
}

function Field({
  label,
  children,
  mono = false,
  wide = false,
}: {
  label: string
  children: ReactNode
  mono?: boolean
  wide?: boolean
}) {
  return (
    <div className={wide ? 'sm:col-span-2' : undefined}>
      <dt className="text-2xs font-semibold tracking-caption text-subtle uppercase">{label}</dt>
      <dd
        className={
          mono
            ? 'mt-0.5 font-mono text-xs break-all text-fg'
            : 'mt-0.5 text-sm text-fg'
        }
      >
        {children}
      </dd>
    </div>
  )
}

function CopyableField({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-2xs font-semibold tracking-caption text-subtle uppercase">{label}</dt>
      <dd className="mt-0.5 flex items-center gap-1">
        <span className="font-mono text-xs break-all text-fg">{value}</span>
        <CopyButton value={value} label={label} />
      </dd>
    </div>
  )
}

function TypeDetail({ transaction }: { transaction: Transaction }) {
  switch (transaction.activityType) {
    case 'CARD': {
      const card = transaction.card
      if (!card) return <p className="text-xs text-muted">No card detail row is attached to this transaction.</p>
      return (
        <dl className="grid grid-cols-1 gap-x-6 gap-y-3 sm:grid-cols-2">
          <Field label="Card number">
            <span className="numeric">{maskPan(card.cardPan)}</span>
          </Field>
          <Field label="Card type">{card.cardType || EM_DASH}</Field>
          <Field label="Merchant">{card.merchantName || EM_DASH}</Field>
          <Field label="MCC" mono>
            {card.mccCode || EM_DASH}
          </Field>
          <Field label="Presence">
            {card.cardPresent ? (
              <Badge tone="neutral">Card present</Badge>
            ) : (
              <Badge tone="warning">Card not present</Badge>
            )}
          </Field>
          <Field label="Authorization code" mono>
            {card.authorizationCode || EM_DASH}
          </Field>
          <Field label="Decline reason" wide>
            {card.declineReason ? (
              <span className="text-danger-fg">{card.declineReason}</span>
            ) : (
              <span className="text-muted">Not declined</span>
            )}
          </Field>
        </dl>
      )
    }
    case 'PAYMENT': {
      const payment = transaction.payment
      if (!payment) return <p className="text-xs text-muted">No payment detail row is attached to this transaction.</p>
      return (
        <dl className="grid grid-cols-1 gap-x-6 gap-y-3 sm:grid-cols-2">
          <Field label="Payment method">{payment.paymentMethod || EM_DASH}</Field>
          <Field label="Beneficiary bank country">
            {payment.receiverBankCountry ? (
              <span>
                {payment.receiverBankCountry}
                <span className="ml-1.5 text-xs text-subtle">
                  {formatCountry(payment.receiverBankCountry)}
                </span>
              </span>
            ) : (
              EM_DASH
            )}
          </Field>
          <Field label="Sender account" mono>
            {payment.senderAccount || EM_DASH}
          </Field>
          <Field label="Receiver account" mono>
            {payment.receiverAccount || EM_DASH}
          </Field>
        </dl>
      )
    }
    case 'CRYPTO': {
      const crypto = transaction.crypto
      if (!crypto) return <p className="text-xs text-muted">No crypto detail row is attached to this transaction.</p>
      return (
        <dl className="grid grid-cols-1 gap-x-6 gap-y-3 sm:grid-cols-2">
          <Field label="Blockchain">{crypto.blockchain || EM_DASH}</Field>
          <Field label="Exchange">
            {crypto.exchangeName ?? (
              <span className="text-muted">Unattributed — no exchange reported</span>
            )}
          </Field>
          <Field label="From wallet" mono wide>
            {crypto.walletAddressFrom || EM_DASH}
          </Field>
          <Field label="To wallet" mono wide>
            {crypto.walletAddressTo || EM_DASH}
          </Field>
          <Field label="Transaction hash" mono wide>
            {crypto.txHash || EM_DASH}
          </Field>
        </dl>
      )
    }
  }
}

/** Full record for one transaction, refreshed from `/api/transactions/{id}`. */
export function TransactionDetailModal({
  transaction,
  open,
  onClose,
}: TransactionDetailModalProps) {
  const query = useTransaction(open && transaction ? transaction.transactionId : undefined)
  const record = query.data ?? transaction

  return (
    <Modal
      open={open && record !== null}
      onClose={onClose}
      size="lg"
      title="Transaction detail"
      description={
        record
          ? `${ACTIVITY_TYPE_LABELS[record.activityType]} · ${formatDateTimeSeconds(record.createdAt)}`
          : undefined
      }
      footer={
        <Button variant="secondary" onClick={onClose}>
          Close
        </Button>
      }
    >
      {record ? (
        <div className="flex flex-col gap-5">
          <div className="flex flex-wrap items-center justify-between gap-3 rounded-xs border border-border bg-surface-2/60 px-3 py-2.5">
            <div className="flex items-center gap-2.5">
              <Badge tone="neutral">{ACTIVITY_TYPE_LABELS[record.activityType]}</Badge>
              <StatusBadge status={record.status} size="md" />
              {query.isFetching ? <Spinner size="xs" label="Refreshing record" /> : null}
            </div>
            <span className="flex flex-col items-end leading-tight">
              <span className="text-2xs font-semibold tracking-caption text-subtle uppercase">
                Amount
              </span>
              <span className="numeric text-base font-semibold whitespace-nowrap text-fg">
                {formatMoney(record.amount, record.currency)}
              </span>
            </span>
          </div>

          {query.error ? (
            <p
              role="alert"
              className="flex items-start gap-2 rounded-xs border border-warning/30 bg-warning-soft px-3 py-2 text-xs text-warning-fg"
            >
              <TriangleAlert aria-hidden="true" className="mt-px size-3.5 shrink-0" />
              <span>
                Showing the record from the activity list — the full detail could not be reloaded:{' '}
                {errorMessage(query.error)}
              </span>
            </p>
          ) : null}

          <section>
            <h3 className="mb-2.5 text-2xs font-semibold tracking-caption text-muted uppercase">
              Transaction
            </h3>
            <dl className="grid grid-cols-1 gap-x-6 gap-y-3 sm:grid-cols-2">
              <CopyableField label="Transaction ID" value={record.transactionId} />
              <CopyableField label="Customer ID" value={record.customerId} />
              <Field label="Created">
                <span className="numeric">{formatDateTimeSeconds(record.createdAt)}</span>
              </Field>
              <Field label="Amount">
                <span className="numeric whitespace-nowrap">
                  {formatMoney(record.amount, record.currency)}
                </span>
              </Field>
            </dl>
          </section>

          <section>
            <h3 className="mb-2.5 text-2xs font-semibold tracking-caption text-muted uppercase">
              {ACTIVITY_TYPE_LABELS[record.activityType]} detail
            </h3>
            <TypeDetail transaction={record} />
          </section>
        </div>
      ) : null}
    </Modal>
  )
}
