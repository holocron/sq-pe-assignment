import { eachDayOfInterval, format, parseISO, startOfDay, subDays } from 'date-fns'
import { ChartNoAxesColumn, LineChart as LineChartIcon } from 'lucide-react'
import { useMemo } from 'react'
import {
  Area,
  AreaChart,
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import { useCustomerActivity } from '../../api/customers'
import type { ActivitySummary, ActivityType } from '../../api/types'
import { Card, CardContent, CardHeader, CardTitle } from '../../components/ui/Card'
import { EmptyState } from '../../components/ui/EmptyState'
import { ErrorState } from '../../components/ui/ErrorState'
import { Skeleton } from '../../components/ui/Skeleton'
import { ACTIVITY_TYPE_LABELS } from '../../lib/activity'
import { cn } from '../../lib/cn'
import {
  formatAmount,
  formatCompactNumber,
  formatDate,
  formatMoney,
  formatNumber,
} from '../../lib/format'

/** Rolling window rendered by the timeline, in days. */
export const TIMELINE_DAYS = 30
/** Upper bound on the rows pulled to build the timeline client-side. */
const TIMELINE_FETCH_SIZE = 250

/**
 * Activity types never use the risk ramp — those four colours must keep their
 * single meaning. The accent, the info hue and a neutral are enough to tell
 * three series apart.
 */
const TYPE_COLORS: Record<ActivityType, string> = {
  CARD: 'var(--color-accent)',
  PAYMENT: 'var(--color-info)',
  CRYPTO: 'var(--color-border-strong)',
}

/** Same three hues as the chart, as utilities, so the legend needs no style attribute. */
const TYPE_SWATCHES: Record<ActivityType, string> = {
  CARD: 'bg-accent',
  PAYMENT: 'bg-info',
  CRYPTO: 'bg-border-strong',
}

/* Grey axes and hairline gridlines: the data is the only thing with colour. */
const AXIS_TICK = { fill: 'var(--color-subtle)', fontSize: 11 }
const AXIS_LINE = { stroke: 'var(--color-border)' }
const GRID_STROKE = 'var(--color-border)'
/** Recharts injects a wrapper div we cannot style with a class. */
const TOOLTIP_WRAPPER = { outline: 'none' } as const

function moneyLabel(amount: number, currencies: string[]): string {
  return currencies.length === 1 ? formatMoney(amount, currencies[0]) : formatAmount(amount)
}

function ChartTooltip({ title, rows }: { title: string; rows: Array<[string, string]> }) {
  return (
    <div className="min-w-40 rounded-xs border border-border bg-surface px-2.5 py-2 shadow-popover">
      <p className="text-2xs font-semibold tracking-caption text-fg uppercase">{title}</p>
      <dl className="mt-1.5 flex flex-col gap-0.5">
        {rows.map(([label, value]) => (
          <div key={label} className="flex items-baseline justify-between gap-4">
            <dt className="text-2xs text-muted">{label}</dt>
            <dd className="numeric text-2xs font-semibold whitespace-nowrap text-fg">{value}</dd>
          </div>
        ))}
      </dl>
    </div>
  )
}

/* -------------------------------------------------------------------------- */
/* Daily timeline                                                              */
/* -------------------------------------------------------------------------- */

export interface ActivityTimelineCardProps {
  customerId: string
  /** Currencies reported by the summary; drives the money formatting. */
  currencies: string[]
}

interface TimelinePoint {
  day: string
  amount: number
  count: number
}

/** Daily transaction volume for the last 30 days, bucketed client-side. */
export function ActivityTimelineCard({ customerId, currencies }: ActivityTimelineCardProps) {
  const from = useMemo(() => startOfDay(subDays(new Date(), TIMELINE_DAYS - 1)), [])
  const query = useCustomerActivity(customerId, {
    from: from.toISOString(),
    page: 0,
    size: TIMELINE_FETCH_SIZE,
  })

  const transactions = query.data?.content
  const points = useMemo<TimelinePoint[]>(() => {
    const buckets = new Map<string, TimelinePoint>()
    for (const day of eachDayOfInterval({ start: from, end: new Date() })) {
      const key = format(day, 'yyyy-MM-dd')
      buckets.set(key, { day: key, amount: 0, count: 0 })
    }
    for (const transaction of transactions ?? []) {
      const created = parseISO(transaction.createdAt)
      if (Number.isNaN(created.getTime())) continue
      const bucket = buckets.get(format(created, 'yyyy-MM-dd'))
      if (!bucket) continue
      bucket.amount += transaction.amount
      bucket.count += 1
    }
    return [...buckets.values()]
  }, [transactions, from])

  const windowCount = points.reduce((total, point) => total + point.count, 0)
  const windowAmount = points.reduce((total, point) => total + point.amount, 0)
  const truncated = (query.data?.totalElements ?? 0) > TIMELINE_FETCH_SIZE

  return (
    <Card className="flex flex-col">
      <CardHeader
        actions={
          query.isLoading ? null : (
            <span className="numeric text-xs text-muted">
              {formatNumber(windowCount)} tx · {moneyLabel(windowAmount, currencies)}
            </span>
          )
        }
      >
        <CardTitle>Daily volume — last {TIMELINE_DAYS} days</CardTitle>
        <p className="mt-0.5 text-xs text-muted">
          Amount transacted per day
          {currencies.length > 1 ? `, summed across ${currencies.join(', ')}` : ''}.
          {truncated ? ` Based on the first ${TIMELINE_FETCH_SIZE} transactions in the window.` : ''}
        </p>
      </CardHeader>

      <CardContent className="flex-1">
        {query.error ? (
          <ErrorState
            error={query.error}
            compact
            onRetry={() => void query.refetch()}
            description="The activity timeline could not be loaded."
          />
        ) : query.isLoading ? (
          <Skeleton className="h-56 w-full" />
        ) : windowCount === 0 ? (
          <EmptyState
            compact
            icon={<LineChartIcon className="size-5" />}
            title={`No activity in the last ${TIMELINE_DAYS} days`}
            description="Older activity is still available in the tables below."
          />
        ) : (
          <div className="h-56 w-full">
            <ResponsiveContainer width="100%" height="100%">
              <AreaChart data={points} margin={{ top: 8, right: 8, bottom: 0, left: 0 }}>
                <defs>
                  <linearGradient id="activity-volume" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor="var(--color-accent)" stopOpacity={0.28} />
                    <stop offset="100%" stopColor="var(--color-accent)" stopOpacity={0.02} />
                  </linearGradient>
                </defs>
                <CartesianGrid vertical={false} stroke={GRID_STROKE} strokeDasharray="3 3" />
                <XAxis
                  dataKey="day"
                  tick={AXIS_TICK}
                  tickLine={false}
                  axisLine={AXIS_LINE}
                  minTickGap={24}
                  tickFormatter={(value: string) => format(parseISO(value), 'd MMM')}
                />
                <YAxis
                  tick={AXIS_TICK}
                  tickLine={false}
                  axisLine={false}
                  width={56}
                  tickFormatter={(value: number) => formatCompactNumber(value)}
                />
                <Tooltip
                  cursor={{ stroke: 'var(--color-border-strong)', strokeWidth: 1 }}
                  wrapperStyle={TOOLTIP_WRAPPER}
                  content={({ active, payload }) => {
                    const point = active ? (payload?.[0]?.payload as TimelinePoint | undefined) : undefined
                    if (!point) return null
                    return (
                      <ChartTooltip
                        title={formatDate(point.day)}
                        rows={[
                          ['Amount', moneyLabel(point.amount, currencies)],
                          ['Transactions', formatNumber(point.count)],
                        ]}
                      />
                    )
                  }}
                />
                <Area
                  type="monotone"
                  dataKey="amount"
                  stroke="var(--color-accent)"
                  strokeWidth={1.75}
                  fill="url(#activity-volume)"
                  activeDot={{
                    r: 3,
                    fill: 'var(--color-accent)',
                    stroke: 'var(--color-surface)',
                    strokeWidth: 2,
                  }}
                  isAnimationActive={false}
                />
              </AreaChart>
            </ResponsiveContainer>
          </div>
        )}
      </CardContent>
    </Card>
  )
}

/* -------------------------------------------------------------------------- */
/* Per-type breakdown                                                          */
/* -------------------------------------------------------------------------- */

export interface ActivityBreakdownCardProps {
  summary: ActivitySummary | undefined
  loading: boolean
  error: unknown
  onRetry: () => void
}

interface BreakdownPoint {
  type: ActivityType
  name: string
  amount: number
  count: number
}

/** Total amount per activity type, straight from the summary endpoint. */
export function ActivityBreakdownCard({
  summary,
  loading,
  error,
  onRetry,
}: ActivityBreakdownCardProps) {
  const currencies = summary?.currencies ?? []
  const data: BreakdownPoint[] = (['CARD', 'PAYMENT', 'CRYPTO'] as ActivityType[]).map((type) => {
    const entry = summary?.byActivityType.find((item) => item.activityType === type)
    return {
      type,
      name: ACTIVITY_TYPE_LABELS[type],
      amount: entry?.totalAmount ?? 0,
      count: entry?.count ?? 0,
    }
  })
  const hasData = data.some((point) => point.count > 0)

  return (
    <Card className="flex flex-col">
      <CardHeader>
        <CardTitle>Mix by activity type</CardTitle>
        <p className="mt-0.5 text-xs text-muted">Total amount and transaction count, all time.</p>
      </CardHeader>
      <CardContent className="flex-1">
        {error ? (
          <ErrorState error={error} compact onRetry={onRetry} />
        ) : loading ? (
          <Skeleton className="h-56 w-full" />
        ) : !hasData ? (
          <EmptyState
            compact
            icon={<ChartNoAxesColumn className="size-5" />}
            title="No activity recorded"
            description="This customer has no transactions on file."
          />
        ) : (
          <>
            <div className="h-40 w-full">
              <ResponsiveContainer width="100%" height="100%">
                <BarChart
                  data={data}
                  layout="vertical"
                  margin={{ top: 4, right: 8, bottom: 0, left: 0 }}
                >
                  <CartesianGrid horizontal={false} stroke={GRID_STROKE} strokeDasharray="3 3" />
                  <XAxis
                    type="number"
                    tick={AXIS_TICK}
                    tickLine={false}
                    axisLine={false}
                    tickFormatter={(value: number) => formatCompactNumber(value)}
                  />
                  <YAxis
                    type="category"
                    dataKey="name"
                    tick={AXIS_TICK}
                    tickLine={false}
                    axisLine={AXIS_LINE}
                    width={64}
                  />
                  <Tooltip
                    cursor={{ fill: 'var(--color-surface-2)' }}
                    wrapperStyle={TOOLTIP_WRAPPER}
                    content={({ active, payload }) => {
                      const point = active
                        ? (payload?.[0]?.payload as BreakdownPoint | undefined)
                        : undefined
                      if (!point) return null
                      return (
                        <ChartTooltip
                          title={point.name}
                          rows={[
                            ['Amount', moneyLabel(point.amount, currencies)],
                            ['Transactions', formatNumber(point.count)],
                          ]}
                        />
                      )
                    }}
                  />
                  <Bar dataKey="amount" radius={[0, 2, 2, 0]} isAnimationActive={false}>
                    {data.map((point) => (
                      <Cell key={point.type} fill={TYPE_COLORS[point.type]} />
                    ))}
                  </Bar>
                </BarChart>
              </ResponsiveContainer>
            </div>

            <ul className="mt-3 flex flex-wrap gap-x-5 gap-y-1.5 border-t border-border pt-2.5">
              {data.map((point) => (
                <li key={point.type} className="flex items-center gap-1.5 text-2xs text-muted">
                  <span
                    aria-hidden="true"
                    className={cn('size-2 rounded-xxs', TYPE_SWATCHES[point.type])}
                  />
                  {point.name}
                  <span className="numeric font-semibold text-fg">{formatNumber(point.count)}</span>
                </li>
              ))}
            </ul>
          </>
        )}
      </CardContent>
    </Card>
  )
}
