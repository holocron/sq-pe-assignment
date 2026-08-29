/**
 * The transactions behind a triggered rule.
 *
 * `risk_assessments` stores one row per (transaction, rule) pair, so a rule
 * verdict carries the exact transaction ids that matched. Expanding a rule
 * resolves them through `GET /api/transactions/{id}` — no id is ever shown
 * without giving the operator a way to see what it actually was.
 */
import { useQueries } from '@tanstack/react-query'
import { useState } from 'react'
import { queryKeys } from '../../api/queryKeys'
import { fetchTransaction } from '../../api/transactions'
import type { UUID } from '../../api/types'
import { Button } from '../../components/ui/Button'
import { Skeleton } from '../../components/ui/Skeleton'
import { StatusBadge } from '../../components/ui/StatusBadge'
import { ACTIVITY_TYPE_LABELS } from '../../lib/activity'
import { cn } from '../../lib/cn'
import { formatDateTime, formatMoney, shortId } from '../../lib/format'

const DEFAULT_LIMIT = 8

export interface MatchedTransactionsProps {
  /** `RuleEvaluationView.matchedTransactionIds`; tolerates a missing array. */
  transactionIds: UUID[] | null | undefined
  className?: string
}

export function MatchedTransactions({ transactionIds, className }: MatchedTransactionsProps) {
  const [showAll, setShowAll] = useState(false)
  const ids = Array.isArray(transactionIds) ? transactionIds : []
  const visibleIds = showAll ? ids : ids.slice(0, DEFAULT_LIMIT)

  const results = useQueries({
    queries: visibleIds.map((transactionId) => ({
      queryKey: queryKeys.transactions.detail(transactionId),
      queryFn: () => fetchTransaction(transactionId),
      staleTime: 60_000,
    })),
  })

  if (ids.length === 0) {
    return (
      <p className={cn('text-xs text-muted', className)}>
        No transaction matched this rule.
      </p>
    )
  }

  const pending = results.filter((result) => result.isPending).length
  const failed = results.filter((result) => result.isError).length
  const rows = results.flatMap((result, index) =>
    result.data ? [{ id: visibleIds[index] as UUID, transaction: result.data }] : [],
  )
  const hidden = ids.length - visibleIds.length

  return (
    <div className={cn('space-y-2', className)}>
      <div className="overflow-x-auto rounded-xs border border-border">
        <table className="w-full border-collapse text-xs">
          <caption className="sr-only">Transactions that matched this rule</caption>
          <thead className="bg-surface-2">
            <tr>
              <th scope="col" className="px-2.5 py-1.5 text-left font-semibold text-2xs tracking-caption text-muted uppercase">
                Transaction
              </th>
              <th scope="col" className="px-2.5 py-1.5 text-left font-semibold text-2xs tracking-caption text-muted uppercase">
                Type
              </th>
              <th scope="col" className="px-2.5 py-1.5 text-left font-semibold text-2xs tracking-caption text-muted uppercase">
                Date
              </th>
              <th scope="col" className="px-2.5 py-1.5 text-right font-semibold text-2xs tracking-caption text-muted uppercase">
                Amount
              </th>
              <th scope="col" className="px-2.5 py-1.5 text-left font-semibold text-2xs tracking-caption text-muted uppercase">
                Status
              </th>
            </tr>
          </thead>
          <tbody>
            {rows.map(({ id, transaction }) => (
              <tr key={id} className="border-t border-border/60">
                <td className="px-2.5 py-1.5">
                  <span className="font-mono text-2xs text-muted" title={id}>
                    {shortId(id)}
                  </span>
                </td>
                <td className="px-2.5 py-1.5 text-fg">
                  {ACTIVITY_TYPE_LABELS[transaction.activityType]}
                </td>
                <td className="numeric px-2.5 py-1.5 text-muted">
                  {formatDateTime(transaction.createdAt)}
                </td>
                <td className="numeric px-2.5 py-1.5 text-right font-medium text-fg">
                  {formatMoney(transaction.amount, transaction.currency)}
                </td>
                <td className="px-2.5 py-1.5">
                  <StatusBadge status={transaction.status} />
                </td>
              </tr>
            ))}
            {pending > 0
              ? Array.from({ length: pending }, (_, index) => (
                  <tr key={`pending-${index}`} className="border-t border-border/60">
                    {Array.from({ length: 5 }, (__, cellIndex) => (
                      <td key={cellIndex} className="px-2.5 py-1.5">
                        <Skeleton className="h-3 w-full max-w-24" />
                      </td>
                    ))}
                  </tr>
                ))
              : null}
          </tbody>
        </table>
      </div>

      {failed > 0 ? (
        <p role="status" className="text-2xs text-warning-fg">
          {failed} of {visibleIds.length} transactions could not be loaded. Their ids are still
          recorded against this rule.
        </p>
      ) : null}

      {hidden > 0 ? (
        <Button size="sm" variant="ghost" onClick={() => setShowAll(true)}>
          Show {hidden} more matched transaction{hidden === 1 ? '' : 's'}
        </Button>
      ) : null}
    </div>
  )
}
