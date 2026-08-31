import { endOfDay, parseISO, startOfDay } from 'date-fns'
import { FilterX } from 'lucide-react'
import { useState } from 'react'
import { DEFAULT_PAGE_SIZE, useCustomerActivity } from '../../api/customers'
import {
  TRANSACTION_STATUSES,
  type ActivityQueryParams,
  type ActivitySummary,
  type Transaction,
} from '../../api/types'
import { Button } from '../../components/ui/Button'
import { Card, CardHeader, CardTitle } from '../../components/ui/Card'
import { Input } from '../../components/ui/Input'
import { Pagination } from '../../components/ui/Pagination'
import { Select } from '../../components/ui/Select'
import { Table, type SortState } from '../../components/ui/Table'
import { TabPanel, Tabs, type TabItem } from '../../components/ui/Tabs'
import { formatNumber } from '../../lib/format'
import { ACTIVITY_TABS, activityColumns, activityTabLabel, type ActivityTab } from './activityColumns'
import { TransactionDetailModal } from './TransactionDetailModal'
import { useDebouncedValue } from './useDebouncedValue'

export interface ActivityPanelProps {
  customerId: string
  /** Drives the per-tab counts; the panel works without it. */
  summary: ActivitySummary | undefined
}

const STATUS_OPTIONS = [
  { value: '', label: 'All statuses' },
  ...TRANSACTION_STATUSES.map((status) => ({ value: status, label: status })),
]

function tabCount(tab: ActivityTab, summary: ActivitySummary | undefined): number | null {
  if (!summary) return null
  if (tab === 'ALL') return summary.totalTransactions
  return summary.byActivityType.find((entry) => entry.activityType === tab)?.transactionCount ?? 0
}

/**
 * Tabbed activity explorer: one tab per activity type with the columns that
 * matter for it, server-side status/date filtering and server-side paging.
 */
export function ActivityPanel({ customerId, summary }: ActivityPanelProps) {
  const [tab, setTab] = useState<ActivityTab>('ALL')
  const [status, setStatus] = useState('')
  const [fromDate, setFromDate] = useState('')
  const [toDate, setToDate] = useState('')
  /* Amounts are debounced so a bound being typed out does not fire a request
     per keystroke; the dates above are discrete picker values, so they are not. */
  const [minAmountInput, setMinAmountInput] = useState('')
  const [maxAmountInput, setMaxAmountInput] = useState('')
  const minAmountText = useDebouncedValue(minAmountInput.trim(), 300)
  const maxAmountText = useDebouncedValue(maxAmountInput.trim(), 300)
  const [size, setSize] = useState(DEFAULT_PAGE_SIZE)
  const [selected, setSelected] = useState<Transaction | null>(null)
  /** Null keeps the endpoint default (createdAt,desc). */
  const [sort, setSort] = useState<SortState | null>(null)

  const rangeInvalid = Boolean(fromDate && toDate && fromDate > toDate)
  /* An unparseable draft is not a filter yet: it is ignored until it reads as
     a number, while a parsed range with min above max blocks both bounds. */
  const minAmount = minAmountText === '' ? null : Number(minAmountText)
  const maxAmount = maxAmountText === '' ? null : Number(maxAmountText)
  const amountInvalid =
    minAmount !== null &&
    maxAmount !== null &&
    Number.isFinite(minAmount) &&
    Number.isFinite(maxAmount) &&
    minAmount > maxAmount
  const filtersActive = Boolean(status || fromDate || toDate || minAmountText || maxAmountText)

  /* Changing the tab, a filter, the sort or the page size returns to the first
     page in the same render pass, so no request is issued for a stale page index. */
  const sortParam = sort ? `${sort.key},${sort.direction}` : null
  const pageKey = `${tab}|${status}|${fromDate}|${toDate}|${minAmountText}|${maxAmountText}|${size}|${sortParam ?? ''}`
  const [pageState, setPageState] = useState(() => ({ key: pageKey, page: 0 }))
  const page = pageState.key === pageKey ? pageState.page : 0
  if (pageState.key !== pageKey) setPageState({ key: pageKey, page: 0 })
  const setPage = (next: number) => setPageState({ key: pageKey, page: next })

  const params: ActivityQueryParams = {
    type: tab === 'ALL' ? undefined : tab,
    status: status || undefined,
    from: !rangeInvalid && fromDate ? startOfDay(parseISO(fromDate)).toISOString() : undefined,
    to: !rangeInvalid && toDate ? endOfDay(parseISO(toDate)).toISOString() : undefined,
    minAmount:
      !amountInvalid && minAmount !== null && Number.isFinite(minAmount) ? minAmount : undefined,
    maxAmount:
      !amountInvalid && maxAmount !== null && Number.isFinite(maxAmount) ? maxAmount : undefined,
    sort: sortParam,
    page,
    size,
  }
  const activityQuery = useCustomerActivity(customerId, params)
  const result = activityQuery.data

  const tabs: TabItem[] = ACTIVITY_TABS.map((item) => ({
    id: item,
    label: activityTabLabel(item),
    count: tabCount(item, summary),
  }))

  function clearFilters() {
    setStatus('')
    setFromDate('')
    setToDate('')
    setMinAmountInput('')
    setMaxAmountInput('')
  }

  return (
    <Card>
      <CardHeader
        actions={
          result && !activityQuery.isLoading ? (
            <span className="numeric text-xs text-muted">
              {formatNumber(result.totalElements)} transactions
            </span>
          ) : null
        }
      >
        <CardTitle>Activity</CardTitle>
        <p className="mt-0.5 text-xs text-muted">
          Select a row to open the full record, including its {tab === 'ALL' ? 'card, payment or crypto' : activityTabLabel(tab).toLowerCase()} detail.
        </p>
      </CardHeader>

      {/* One control bar: type tabs above, server-side filters below. */}
      <div className="border-b border-border bg-surface-2/40">
        <div className="px-4 pt-1">
          <Tabs
            tabs={tabs}
            value={tab}
            onChange={(id) => setTab(id as ActivityTab)}
            ariaLabel="Activity type"
          />
        </div>

        <div className="flex flex-wrap items-end gap-3 px-4 py-3">
          <Select
            label="Status"
            value={status}
            onChange={(event) => setStatus(event.target.value)}
            options={STATUS_OPTIONS}
            containerClassName="w-40"
          />
          <Input
            label="From"
            type="date"
            value={fromDate}
            max={toDate || undefined}
            onChange={(event) => setFromDate(event.target.value)}
            containerClassName="w-44"
          />
          <Input
            label="To"
            type="date"
            value={toDate}
            min={fromDate || undefined}
            onChange={(event) => setToDate(event.target.value)}
            containerClassName="w-44"
            error={rangeInvalid ? 'End date must be on or after the start date.' : null}
          />
          <Input
            label="Min amount"
            type="number"
            inputMode="decimal"
            min={0}
            step="any"
            value={minAmountInput}
            onChange={(event) => setMinAmountInput(event.target.value)}
            containerClassName="w-32"
          />
          <Input
            label="Max amount"
            type="number"
            inputMode="decimal"
            min={minAmount ?? 0}
            step="any"
            value={maxAmountInput}
            onChange={(event) => setMaxAmountInput(event.target.value)}
            containerClassName="w-32"
            error={amountInvalid ? 'Max amount must be at least the min amount.' : null}
          />
          {filtersActive ? (
            <Button
              variant="ghost"
              size="sm"
              onClick={clearFilters}
              iconLeft={<FilterX className="size-3.5" />}
              className="mb-0.5"
            >
              Clear filters
            </Button>
          ) : null}
        </div>
      </div>

      {ACTIVITY_TABS.map((item) => (
        <TabPanel key={item} id={item} active={item === tab}>
          <div
            aria-busy={activityQuery.isFetching || undefined}
            className={
              activityQuery.isFetching && !activityQuery.isLoading ? 'opacity-60' : undefined
            }
          >
            <Table
              columns={activityColumns(item)}
              rows={result?.content ?? []}
              rowKey={(transaction) => transaction.transactionId}
              loading={activityQuery.isLoading}
              error={activityQuery.error}
              onRetry={() => void activityQuery.refetch()}
              onRowClick={(transaction) => setSelected(transaction)}
              sort={sort}
              onSortChange={setSort}
              caption={`${activityTabLabel(item)} for this customer`}
              dense
              stickyHeader
              emptyTitle={
                item === 'ALL' ? 'No transactions found' : `No ${activityTabLabel(item).toLowerCase()} activity found`
              }
              emptyDescription={
                filtersActive
                  ? 'No records match the current status, date or amount filters.'
                  : 'This customer has no activity of this type on file.'
              }
            />
          </div>
        </TabPanel>
      ))}

      {result && result.totalElements > 0 ? (
        <Pagination
          page={result.page}
          size={result.size}
          totalPages={result.totalPages}
          totalElements={result.totalElements}
          onPageChange={setPage}
          onSizeChange={setSize}
          itemLabel="transactions"
          disabled={activityQuery.isFetching}
        />
      ) : null}

      <TransactionDetailModal
        transaction={selected}
        open={selected !== null}
        onClose={() => setSelected(null)}
      />
    </Card>
  )
}
