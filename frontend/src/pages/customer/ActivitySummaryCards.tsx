import { ArrowLeftRight, CalendarClock, Globe2, TriangleAlert, Wallet } from 'lucide-react'
import type { ActivitySummary, ActivityType } from '../../api/types'
import { Card, CardContent, CardHeader, CardTitle } from '../../components/ui/Card'
import { ErrorState } from '../../components/ui/ErrorState'
import { StatCard } from '../../components/ui/StatCard'
import { ACTIVITY_TYPE_LABELS } from '../../lib/activity'
import { cn } from '../../lib/cn'
import {
  EM_DASH,
  formatAmount,
  formatDate,
  formatMoney,
  formatNumber,
  formatPercent,
  formatRelativeTime,
} from '../../lib/format'

export interface ActivitySummaryCardsProps {
  summary: ActivitySummary | undefined
  loading: boolean
  error: unknown
  onRetry: () => void
}

/**
 * Amounts are summed server-side across whatever currencies the customer used,
 * so the currency code is only shown when there is exactly one.
 */
function money(amount: number | null | undefined, currencies: string[]): string {
  if (amount === null || amount === undefined) return EM_DASH
  return currencies.length === 1 ? formatMoney(amount, currencies[0]) : formatAmount(amount)
}

function currencyHint(currencies: string[]): string {
  if (currencies.length === 0) return 'No currency reported'
  if (currencies.length === 1) return `Currency ${currencies[0]}`
  return `Mixed currencies: ${currencies.join(', ')}`
}

function statusCount(summary: ActivitySummary, status: string): number {
  return summary.byStatus
    .filter((entry) => entry.status.toLowerCase() === status.toLowerCase())
    .reduce((total, entry) => total + entry.count, 0)
}

const TYPE_ACCENTS: Record<ActivityType, string> = {
  CARD: 'border-l-accent',
  PAYMENT: 'border-l-info',
  CRYPTO: 'border-l-border-strong',
}

/** Aggregate tiles for `GET /api/customers/{id}/summary`. */
export function ActivitySummaryCards({
  summary,
  loading,
  error,
  onRetry,
}: ActivitySummaryCardsProps) {
  if (error) {
    return (
      <Card>
        <ErrorState
          error={error}
          onRetry={onRetry}
          compact
          description="Activity aggregates could not be loaded."
        />
      </Card>
    )
  }

  const currencies = summary?.currencies ?? []
  const failedCount = summary ? statusCount(summary, 'Failed') : 0
  const reversedCount = summary ? statusCount(summary, 'Reversed') : 0
  const failedRatio = summary?.failedRatio ?? null
  const byType = new Map<ActivityType, ActivitySummary['byActivityType'][number]>(
    (summary?.byActivityType ?? []).map((entry) => [entry.activityType, entry]),
  )

  const velocity: Array<{ label: string; value: string }> = []
  if (summary) {
    if (summary.txCount24h !== null && summary.txCount24h !== undefined) {
      velocity.push({ label: 'Transactions, 24h', value: formatNumber(summary.txCount24h) })
    }
    if (summary.amountSum24h !== null && summary.amountSum24h !== undefined) {
      velocity.push({ label: 'Amount, 24h', value: money(summary.amountSum24h, currencies) })
    }
    if (summary.failedCount24h !== null && summary.failedCount24h !== undefined) {
      velocity.push({ label: 'Failed, 24h', value: formatNumber(summary.failedCount24h) })
    }
    if (summary.distinctCountries30d !== null && summary.distinctCountries30d !== undefined) {
      velocity.push({
        label: 'Distinct countries, 30d',
        value: formatNumber(summary.distinctCountries30d),
      })
    }
    if (summary.cryptoRatio30d !== null && summary.cryptoRatio30d !== undefined) {
      velocity.push({ label: 'Crypto share, 30d', value: formatPercent(summary.cryptoRatio30d) })
    }
    if (summary.maxAmount30d !== null && summary.maxAmount30d !== undefined) {
      velocity.push({ label: 'Largest single, 30d', value: money(summary.maxAmount30d, currencies) })
    }
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-3 xl:grid-cols-6">
        <StatCard
          label="Transactions"
          value={formatNumber(summary?.totalTransactions)}
          hint="All activity on file"
          numeric
          loading={loading}
          icon={<ArrowLeftRight className="size-4" />}
        />
        <StatCard
          label="Total amount"
          value={money(summary?.totalAmount, currencies)}
          hint={currencyHint(currencies)}
          numeric
          loading={loading}
          icon={<Wallet className="size-4" />}
        />
        <StatCard
          label="Failed / reversed"
          value={`${formatNumber(failedCount)} / ${formatNumber(reversedCount)}`}
          hint={
            failedRatio !== null
              ? `${formatPercent(failedRatio)} of activity failed`
              : 'Unsuccessful transactions'
          }
          numeric
          loading={loading}
          icon={<TriangleAlert className="size-4" />}
        />
        <StatCard
          label="Counterparty countries"
          value={formatNumber(summary?.countries.length)}
          hint={
            summary && summary.countries.length > 0
              ? summary.countries.slice(0, 6).join(', ') +
                (summary.countries.length > 6 ? ` +${summary.countries.length - 6}` : '')
              : 'No counterparty country reported'
          }
          numeric
          loading={loading}
          icon={<Globe2 className="size-4" />}
        />
        <StatCard
          label="First activity"
          value={formatDate(summary?.firstActivityAt)}
          hint={summary?.firstActivityAt ? formatRelativeTime(summary.firstActivityAt) : 'No activity'}
          numeric
          loading={loading}
          icon={<CalendarClock className="size-4" />}
        />
        <StatCard
          label="Last activity"
          value={formatDate(summary?.lastActivityAt)}
          hint={summary?.lastActivityAt ? formatRelativeTime(summary.lastActivityAt) : 'No activity'}
          numeric
          loading={loading}
          icon={<CalendarClock className="size-4" />}
        />
      </div>

      <div className="grid gap-3 sm:grid-cols-3">
        {(['CARD', 'PAYMENT', 'CRYPTO'] as ActivityType[]).map((type) => {
          const entry = byType.get(type)
          const avgAmount = entry?.avgAmount ?? null
          const maxAmount = entry?.maxAmount ?? null
          return (
            <div
              key={type}
              className={cn(
                'rounded-md border border-l-2 border-border bg-surface px-3.5 py-3 shadow-panel',
                TYPE_ACCENTS[type],
              )}
            >
              <div className="flex items-baseline justify-between gap-2">
                <span className="text-2xs font-semibold tracking-caption text-muted uppercase">
                  {ACTIVITY_TYPE_LABELS[type]}
                </span>
                <span className="numeric text-2xs text-subtle">
                  {formatNumber(entry?.count ?? 0)} tx
                </span>
              </div>
              <p className="numeric mt-1 text-lg leading-tight font-semibold text-fg">
                {money(entry?.totalAmount ?? 0, currencies)}
              </p>
              <dl className="mt-2 grid grid-cols-2 gap-x-3 gap-y-1 text-2xs text-subtle">
                <div className="flex justify-between gap-2">
                  <dt>Avg</dt>
                  <dd className="numeric text-right whitespace-nowrap text-muted">
                    {money(avgAmount, currencies)}
                  </dd>
                </div>
                <div className="flex justify-between gap-2">
                  <dt>Max</dt>
                  <dd className="numeric text-right whitespace-nowrap text-muted">
                    {money(maxAmount, currencies)}
                  </dd>
                </div>
              </dl>
            </div>
          )
        })}
      </div>

      {velocity.length > 0 ? (
        <Card>
          <CardHeader>
            <CardTitle>Velocity and exposure</CardTitle>
            <p className="mt-0.5 text-xs text-muted">
              The same aggregates the rule engine exposes as <code className="font-mono">agg.*</code>{' '}
              fields.
            </p>
          </CardHeader>
          <CardContent>
            {/* gap-px over a border-coloured backdrop gives hairline rules
                between the cells without a second border on every tile. */}
            <dl className="grid grid-cols-2 gap-px overflow-hidden rounded-xs border border-border bg-border sm:grid-cols-3 lg:grid-cols-6">
              {velocity.map((item) => (
                <div key={item.label} className="bg-surface px-3 py-2">
                  <dt className="text-2xs font-semibold tracking-caption text-subtle uppercase">{item.label}</dt>
                  <dd className="numeric mt-0.5 text-sm font-semibold whitespace-nowrap text-fg">
                    {item.value}
                  </dd>
                </div>
              ))}
            </dl>
          </CardContent>
        </Card>
      ) : null}

      {!loading && summary && summary.totalTransactions === 0 ? (
        <p className="text-xs text-muted">
          This customer has no transactions on file, so there is nothing to analyse yet.
        </p>
      ) : null}
    </div>
  )
}
