import {
  Activity,
  ChevronRight,
  CircleAlert,
  Clock3,
  Search,
  Users,
  X,
} from 'lucide-react'
import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useAnalysesAcrossCustomers } from '../api/analyses'
import { DEFAULT_PAGE_SIZE, useCustomers } from '../api/customers'
import type { AnalysisSummary, CustomerSummary } from '../api/types'
import {
  Button,
  Card,
  CardContent,
  CardHeader,
  CardTitle,
  EmptyState,
  ErrorState,
  Input,
  PageHeader,
  Pagination,
  RiskBadge,
  Skeleton,
  StatCard,
  Table,
  type Column,
} from '../components'
import {
  EM_DASH,
  formatAmount,
  formatCountry,
  formatDate,
  formatDateTime,
  formatNumber,
  formatRelativeTime,
  fullName,
  shortId,
} from '../lib/format'
import { compareRiskLevel } from '../lib/risk'
import { useDebouncedValue } from './customer/useDebouncedValue'

/** How many of the most recent analyses the side panel lists. */
const RECENT_ANALYSES_SHOWN = 8

/* -------------------------------------------------------------------------- */
/* Recent analyses panel                                                       */
/* -------------------------------------------------------------------------- */

interface RecentAnalysesPanelProps {
  rows: AnalysisSummary[]
  customerNames: Map<string, string>
  loading: boolean
  error: unknown
  onRetry: () => void
}

function RecentAnalysesPanel({
  rows,
  customerNames,
  loading,
  error,
  onRetry,
}: RecentAnalysesPanelProps) {
  return (
    <Card className="flex flex-col">
      <CardHeader
        actions={
          rows.length > 0 ? (
            <Link
              to="/analyses"
              className="rounded-xs text-xs font-medium text-accent-strong underline-offset-4 hover:underline"
            >
              Full history
            </Link>
          ) : null
        }
      >
        <CardTitle>Recent AI analyses</CardTitle>
        <p className="mt-0.5 text-xs text-muted">Across the customers scanned on this dashboard.</p>
      </CardHeader>

      {error ? (
        <ErrorState
          error={error}
          compact
          onRetry={onRetry}
          description="Analysis history could not be loaded."
        />
      ) : loading ? (
        <div className="flex flex-col gap-2.5 px-4 py-3">
          {Array.from({ length: 5 }, (_, index) => (
            <div key={index} className="flex items-center gap-3">
              <Skeleton className="h-3.5 w-32" />
              <Skeleton className="ml-auto h-5 w-20" pill />
            </div>
          ))}
        </div>
      ) : rows.length === 0 ? (
        <EmptyState
          compact
          icon={<Activity className="size-5" />}
          title="No analyses yet"
          description="Open a customer and run an AI risk analysis — the result appears here and in the customer's history."
        />
      ) : (
        <ul className="divide-y divide-border">
          {rows.map((analysis) => (
            <li key={analysis.assessmentId}>
              <Link
                to={`/analyses/${analysis.assessmentId}`}
                className="flex items-center gap-3 px-4 py-2 transition-colors hover:bg-surface-2 focus-visible:bg-surface-2"
              >
                <span className="min-w-0 flex-1">
                  <span className="block truncate text-xs font-medium text-fg">
                    {customerNames.get(analysis.customerId) || 'Unknown customer'}
                  </span>
                  <span className="block truncate text-2xs text-subtle">
                    <span title={formatDateTime(analysis.createdAt)}>
                      {formatRelativeTime(analysis.createdAt)}
                    </span>
                    {' · '}
                    {analysis.status === 'RUNNING'
                      ? 'Running now'
                      : analysis.status === 'FAILED'
                        ? 'Failed'
                        : `${formatNumber(analysis.rulesEvaluated ?? 0)}/${formatNumber(
                            analysis.rulesTotal ?? 0,
                          )} rules evaluated`}
                  </span>
                </span>
                <RiskBadge
                  level={analysis.riskLevel}
                  score={analysis.totalScore}
                  showScore={analysis.totalScore !== null}
                  size="sm"
                />
                <ChevronRight aria-hidden="true" className="size-3.5 shrink-0 text-subtle" />
              </Link>
            </li>
          ))}
        </ul>
      )}
    </Card>
  )
}

/* -------------------------------------------------------------------------- */
/* Dashboard                                                                   */
/* -------------------------------------------------------------------------- */

export function DashboardPage() {
  const navigate = useNavigate()
  const [queryInput, setQueryInput] = useState('')
  const [size, setSize] = useState(DEFAULT_PAGE_SIZE)
  const query = useDebouncedValue(queryInput.trim(), 300)

  /* Paging is scoped to the current query/page-size: changing either resets to
     the first page during the same render, so the request never goes out with
     a page index that no longer exists. */
  const pageKey = `${query}|${size}`
  const [pageState, setPageState] = useState(() => ({ key: pageKey, page: 0 }))
  const page = pageState.key === pageKey ? pageState.page : 0
  if (pageState.key !== pageKey) setPageState({ key: pageKey, page: 0 })
  const setPage = (next: number) => setPageState({ key: pageKey, page: next })

  const searchParams = { query: query || undefined, page, size }
  const customersQuery = useCustomers(searchParams)
  const results = customersQuery.data

  /* Recent analyses, the watchlist and the "latest risk" column all come from
     one bounded fan-out over the customer list, unaffected by the search box so
     the panels do not churn while the operator types. */
  const overview = useAnalysesAcrossCustomers()
  const latestRiskByCustomer = overview.latestByCustomer

  const elevatedCustomers = overview.customers.filter((customer) => {
    const level = latestRiskByCustomer.get(customer.customerId)?.riskLevel
    return level === 'HIGH' || level === 'CRITICAL'
  })

  const columns: Column<CustomerSummary>[] = [
    {
      key: 'customer',
      header: 'Customer',
      cell: (customer) => (
        <span className="flex flex-col leading-tight">
          <span className="font-medium text-fg">
            {fullName(customer.firstName, customer.lastName)}
          </span>
          <span className="font-mono text-2xs text-subtle" title={customer.customerId}>
            {shortId(customer.customerId)}
          </span>
        </span>
      ),
    },
    {
      key: 'country',
      header: 'Country',
      cell: (customer) => (
        <span title={formatCountry(customer.country)}>{customer.country || EM_DASH}</span>
      ),
      className: 'w-20',
    },
    {
      key: 'dob',
      header: 'Date of birth',
      cell: (customer) => (
        <span className="numeric whitespace-nowrap text-muted">
          {formatDate(customer.dob)}
          {typeof customer.age === 'number' ? (
            <span className="ml-1.5 text-2xs text-subtle">({customer.age})</span>
          ) : null}
        </span>
      ),
      className: 'w-40 hidden md:table-cell',
      headerClassName: 'hidden md:table-cell',
    },
    {
      key: 'transactions',
      header: 'Activity',
      align: 'right',
      cell: (customer) => (
        <span className="numeric text-muted">{formatNumber(customer.transactionCount)}</span>
      ),
      className: 'w-20',
    },
    {
      key: 'totalAmount',
      // The customer list carries no currency, so the column says so rather
      // than implying a single one.
      header: (
        <span className="flex flex-col items-end leading-tight">
          <span>Total amount</span>
          <span className="text-2xs font-normal normal-case text-subtle">all currencies</span>
        </span>
      ),
      align: 'right',
      cell: (customer) => (
        <span
          className="numeric whitespace-nowrap font-medium text-fg"
          title="Sum across all currencies on file"
        >
          {formatAmount(customer.totalAmount)}
        </span>
      ),
      className: 'w-36 hidden lg:table-cell',
      headerClassName: 'hidden lg:table-cell',
    },
    {
      key: 'lastActivity',
      header: 'Last activity',
      cell: (customer) => (
        <span className="whitespace-nowrap text-muted" title={formatDateTime(customer.lastActivityAt)}>
          {customer.lastActivityAt ? formatRelativeTime(customer.lastActivityAt) : EM_DASH}
        </span>
      ),
      className: 'w-32 hidden lg:table-cell',
      headerClassName: 'hidden lg:table-cell',
    },
    {
      key: 'risk',
      header: 'Latest risk',
      cell: (customer) => {
        const level =
          customer.lastRiskLevel ?? latestRiskByCustomer.get(customer.customerId)?.riskLevel ?? null
        return level ? (
          <RiskBadge level={level} size="sm" />
        ) : (
          <span className="text-2xs text-subtle">Not assessed</span>
        )
      },
      className: 'w-32',
    },
    {
      key: 'open',
      header: 'Open customer',
      hideHeader: true,
      align: 'right',
      cell: () => <ChevronRight aria-hidden="true" className="size-4 text-subtle" />,
      className: 'w-10',
    },
  ]

  return (
    <div className="flex flex-col gap-5">
      <PageHeader
        title="Customer activity"
        description="Find a customer by ID or name, review their card, payment and crypto activity, and run an AI risk analysis."
      />

      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <StatCard
          label="Customers"
          value={formatNumber(overview.totalCustomers)}
          hint="On file"
          numeric
          icon={<Users className="size-4" />}
          loading={overview.isPending}
        />
        <StatCard
          label="Analyses"
          value={formatNumber(overview.rows.length)}
          hint="Across scanned customers"
          numeric
          icon={<Activity className="size-4" />}
          loading={overview.isPending}
        />
        <StatCard
          label="High or critical"
          value={formatNumber(elevatedCustomers.length)}
          hint="Latest verdict per customer"
          numeric
          icon={<CircleAlert className="size-4" />}
          loading={overview.isPending}
        />
        <StatCard
          label="Running now"
          value={formatNumber(overview.runningCount)}
          hint="Analyses in progress"
          numeric
          icon={<Clock3 className="size-4" />}
          loading={overview.isPending}
        />
      </div>

      <div className="grid gap-5 xl:grid-cols-3">
        <div className="flex flex-col gap-4 xl:col-span-2">
          {/* Search and results are one panel: the search is the primary action
              on this screen and the table is its direct answer. */}
          <Card className="overflow-hidden">
            <div className="border-b border-border bg-surface-2/50 px-4 py-4">
              <div className="flex items-center gap-2.5">
                <span
                  aria-hidden="true"
                  className="flex size-7 shrink-0 items-center justify-center rounded-xs bg-accent text-accent-fg"
                >
                  <Search className="size-4" />
                </span>
                <span className="leading-tight">
                  <span className="block text-sm font-semibold tracking-tight-swiss text-fg">
                    Find a customer
                  </span>
                  <span className="block text-2xs text-muted">
                    Start every review here — search by customer ID or name.
                  </span>
                </span>
              </div>

              <form
                role="search"
                onSubmit={(event) => event.preventDefault()}
                className="mt-3"
              >
                <Input
                  label="Search customers"
                  hideLabel
                  type="search"
                  value={queryInput}
                  onChange={(event) => setQueryInput(event.target.value)}
                  placeholder="Customer ID or name — e.g. 3f9a… or Novak"
                  hint="Results update as you type. A full UUID matches a single customer."
                  iconLeft={<Search aria-hidden="true" className="size-4" />}
                  iconRight={
                    queryInput ? (
                      <button
                        type="button"
                        onClick={() => setQueryInput('')}
                        aria-label="Clear search"
                        className="rounded-xs p-0.5 text-subtle transition-colors hover:text-fg"
                      >
                        <X aria-hidden="true" className="size-4" />
                      </button>
                    ) : null
                  }
                  className="h-11 text-base"
                />
              </form>
            </div>

            <CardHeader
              actions={
                results && !customersQuery.isLoading ? (
                  <span className="numeric text-xs text-muted">
                    {formatNumber(results.totalElements)} matching
                  </span>
                ) : null
              }
            >
              <CardTitle>{query ? `Results for “${query}”` : 'All customers'}</CardTitle>
              <p className="mt-0.5 text-2xs text-muted">
                {query
                  ? 'Select a customer to open their activity profile.'
                  : 'Select a customer to open their activity profile, or narrow the list above.'}
              </p>
            </CardHeader>

            <div
              aria-busy={customersQuery.isFetching || undefined}
              className={customersQuery.isFetching && !customersQuery.isLoading ? 'opacity-60' : undefined}
            >
              <Table
                columns={columns}
                rows={results?.content ?? []}
                rowKey={(customer) => customer.customerId}
                loading={customersQuery.isLoading}
                error={customersQuery.error}
                onRetry={() => void customersQuery.refetch()}
                caption="Customer search results"
                onRowClick={(customer) => navigate(`/customers/${customer.customerId}`)}
                emptyTitle={query ? 'No matching customers' : 'No customers on file'}
                emptyDescription={
                  query
                    ? 'Check the spelling, or paste a full customer UUID.'
                    : 'The backend returned an empty customer list. Confirm the seed data was applied.'
                }
              />
            </div>

            {results && results.totalElements > 0 ? (
              <Pagination
                page={results.page}
                size={results.size}
                totalPages={results.totalPages}
                totalElements={results.totalElements}
                onPageChange={setPage}
                onSizeChange={setSize}
                itemLabel="customers"
                disabled={customersQuery.isFetching}
              />
            ) : null}
          </Card>
        </div>

        <div className="flex flex-col gap-4">
          <RecentAnalysesPanel
            rows={overview.rows.slice(0, RECENT_ANALYSES_SHOWN)}
            customerNames={overview.customerNames}
            loading={overview.isPending}
            error={overview.error}
            onRetry={overview.refetch}
          />

          <Card>
            <CardHeader>
              <CardTitle>Watchlist</CardTitle>
              <p className="mt-0.5 text-xs text-muted">
                Customers whose latest analysis came back HIGH or CRITICAL.
              </p>
            </CardHeader>
            {overview.error ? (
              <CardContent className="py-3">
                <p className="text-xs text-muted">
                  Risk levels are unavailable while analysis history cannot be loaded.
                </p>
              </CardContent>
            ) : overview.isPending ? (
              <CardContent className="flex flex-col gap-2 py-3">
                {Array.from({ length: 3 }, (_, index) => (
                  <Skeleton key={index} className="h-6 w-full" />
                ))}
              </CardContent>
            ) : elevatedCustomers.length === 0 ? (
              <CardContent className="py-3">
                <p className="text-xs text-muted">
                  No customer is currently at HIGH or CRITICAL risk.
                </p>
              </CardContent>
            ) : (
              <ul className="divide-y divide-border">
                {[...elevatedCustomers]
                  .sort(
                    (a, b) =>
                      compareRiskLevel(
                        latestRiskByCustomer.get(a.customerId)?.riskLevel ?? null,
                        latestRiskByCustomer.get(b.customerId)?.riskLevel ?? null,
                      ) ||
                      fullName(a.firstName, a.lastName).localeCompare(
                        fullName(b.firstName, b.lastName),
                      ),
                  )
                  .map((customer) => (
                    <li key={customer.customerId}>
                      <Link
                        to={`/customers/${customer.customerId}`}
                        className="flex items-center justify-between gap-3 px-4 py-2 transition-colors hover:bg-surface-2 focus-visible:bg-surface-2"
                      >
                        <span className="min-w-0">
                          <span className="block truncate text-xs font-medium text-fg">
                            {fullName(customer.firstName, customer.lastName)}
                          </span>
                          <span
                            className="block truncate font-mono text-2xs text-subtle"
                            title={customer.customerId}
                          >
                            {shortId(customer.customerId)}
                          </span>
                        </span>
                        <RiskBadge
                          level={latestRiskByCustomer.get(customer.customerId)?.riskLevel ?? null}
                          size="sm"
                        />
                      </Link>
                    </li>
                  ))}
              </ul>
            )}
          </Card>

          <Card>
            <CardContent className="flex flex-col gap-2 py-4">
              <p className="text-xs font-semibold text-fg">Looking for policy wording?</p>
              <p className="text-xs text-muted">
                Search the indexed AML and crypto policy documents the risk agent cites.
              </p>
              <Button
                variant="secondary"
                size="sm"
                className="mt-1 self-start"
                onClick={() => navigate('/knowledge-search')}
                iconLeft={<Search className="size-3.5" />}
              >
                Open knowledge search
              </Button>
            </CardContent>
          </Card>
        </div>
      </div>
    </div>
  )
}
