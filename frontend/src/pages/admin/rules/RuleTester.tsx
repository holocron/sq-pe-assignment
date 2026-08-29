/**
 * Dry-runs the rule under construction against real data via
 * `POST /api/rules/test`, optionally scoped to one customer, so an admin can
 * confirm what a rule actually matches before saving it.
 */
import { FlaskConical, Search, X } from 'lucide-react'
import { useState } from 'react'
import { useCustomers } from '../../../api/customers'
import { useTestRule } from '../../../api/rules'
import type { CustomerSummary, RuleNode, RuleScope, RuleTestMatch } from '../../../api/types'
import { Badge } from '../../../components/ui/Badge'
import { Button } from '../../../components/ui/Button'
import { EmptyState } from '../../../components/ui/EmptyState'
import { ErrorState } from '../../../components/ui/ErrorState'
import { Input } from '../../../components/ui/Input'
import { Spinner } from '../../../components/ui/Spinner'
import { StatusBadge } from '../../../components/ui/StatusBadge'
import { Table, type Column } from '../../../components/ui/Table'
import { cn } from '../../../lib/cn'
import { formatDateTime, formatMoney, fullName, shortId } from '../../../lib/format'
import { serializeRuleNode } from './ruleModel'

export interface RuleTesterProps {
  thresholdLogic: RuleNode
  appliesTo: RuleScope
  /** Number of unresolved builder issues; testing is blocked while non-zero. */
  issueCount: number
  className?: string
}

const MATCH_COLUMNS: Column<RuleTestMatch>[] = [
  {
    key: 'transaction',
    header: 'Transaction',
    cell: (row) => <span className="font-mono text-2xs">{shortId(row.transactionId)}</span>,
  },
  {
    key: 'type',
    header: 'Type',
    cell: (row) => <span className="text-2xs text-muted">{row.activityType ?? '—'}</span>,
  },
  {
    key: 'amount',
    header: 'Amount',
    align: 'right',
    cell: (row) => (
      <span className="numeric text-2xs">{formatMoney(row.amount, row.currency)}</span>
    ),
  },
  {
    key: 'status',
    header: 'Status',
    cell: (row) => <StatusBadge status={row.status ?? null} />,
  },
  {
    key: 'createdAt',
    header: 'Date',
    cell: (row) => <span className="text-2xs text-muted">{formatDateTime(row.createdAt)}</span>,
  },
]

/* Segmented control, matching the AND/OR/NOT toggle in the condition builder. */
const SEGMENT =
  'h-6 rounded-xxs border-b-2 px-2.5 text-2xs font-medium transition-colors outline-none ' +
  'focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-1 focus-visible:ring-offset-surface-2'
const SEGMENT_ACTIVE = 'border-b-accent bg-surface text-fg shadow-panel'
const SEGMENT_IDLE = 'border-b-transparent text-muted hover:bg-surface-3 hover:text-fg'

export function RuleTester({ thresholdLogic, appliesTo, issueCount, className }: RuleTesterProps) {
  const [scoped, setScoped] = useState(false)
  const [query, setQuery] = useState('')
  const [customer, setCustomer] = useState<CustomerSummary | null>(null)
  const test = useTestRule()

  const searchEnabled = scoped && customer === null && query.trim().length >= 2
  const customers = useCustomers(
    { query: query.trim(), page: 0, size: 6 },
    { enabled: searchEnabled },
  )

  const blocked = issueCount > 0
  const result = test.data

  const runTest = (): void => {
    test.mutate({
      thresholdLogic: serializeRuleNode(thresholdLogic),
      appliesTo,
      customerId: scoped ? (customer?.customerId ?? null) : null,
    })
  }

  return (
    <section
      aria-label="Rule test"
      className={cn(
        'flex flex-col overflow-hidden rounded-md border border-border bg-surface',
        className,
      )}
    >
      <header className="flex items-center justify-between gap-2 border-b border-border bg-surface-2/60 px-3 py-2">
        <div className="min-w-0">
          <h3 className="text-xs font-semibold text-fg">Test against data</h3>
          <p className="text-2xs text-muted">
            Evaluates the rule with the deterministic engine — nothing is saved.
          </p>
        </div>
        <Button
          size="sm"
          variant="secondary"
          disabled={blocked}
          loading={test.isPending}
          onClick={runTest}
          iconLeft={<FlaskConical className="size-3.5" aria-hidden="true" />}
        >
          Test rule
        </Button>
      </header>

      <div className="flex flex-col gap-2 border-b border-border px-3 py-2">
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-2xs font-semibold tracking-caption text-subtle uppercase">Scope</span>
          <div
            role="group"
            aria-label="Test scope"
            className="inline-flex items-center gap-px rounded-xs border border-border bg-surface-2 p-0.5"
          >
            <button
              type="button"
              aria-pressed={!scoped}
              onClick={() => setScoped(false)}
              className={cn(SEGMENT, scoped ? SEGMENT_IDLE : SEGMENT_ACTIVE)}
            >
              All customers
            </button>
            <button
              type="button"
              aria-pressed={scoped}
              onClick={() => setScoped(true)}
              className={cn(SEGMENT, scoped ? SEGMENT_ACTIVE : SEGMENT_IDLE)}
            >
              One customer
            </button>
          </div>
        </div>

        {scoped ? (
          customer ? (
            <div className="flex items-center gap-2 rounded-xs border border-border bg-surface-2 px-2 py-1.5">
              <span className="min-w-0 flex-1 truncate text-xs text-fg">
                {fullName(customer.firstName, customer.lastName)}
                <span className="ml-1.5 font-mono text-2xs text-subtle">
                  {shortId(customer.customerId)}
                </span>
              </span>
              <Button
                variant="ghost"
                size="icon"
                className="size-8"
                aria-label="Clear customer scope"
                onClick={() => {
                  setCustomer(null)
                  setQuery('')
                }}
              >
                <X className="size-3.5" aria-hidden="true" />
              </Button>
            </div>
          ) : (
            <div className="flex flex-col gap-1">
              <Input
                label="Find a customer"
                hideLabel
                placeholder="Search by name or customer id…"
                value={query}
                iconLeft={<Search className="size-3.5" aria-hidden="true" />}
                onChange={(event) => setQuery(event.target.value)}
              />
              {searchEnabled ? (
                customers.isPending ? (
                  <p className="flex items-center gap-1.5 px-1 text-2xs text-muted">
                    <Spinner size="xs" label="Searching customers" /> Searching…
                  </p>
                ) : customers.error ? (
                  <ErrorState
                    compact
                    error={customers.error}
                    onRetry={() => void customers.refetch()}
                  />
                ) : (customers.data?.content.length ?? 0) === 0 ? (
                  <p className="px-1 text-2xs text-muted">No customer matches “{query.trim()}”.</p>
                ) : (
                  <ul className="flex flex-col gap-0.5">
                    {customers.data?.content.map((row) => (
                      <li key={row.customerId}>
                        <button
                          type="button"
                          onClick={() => setCustomer(row)}
                          className="flex w-full items-center justify-between gap-2 rounded-xs border border-transparent px-2 py-1 text-left text-xs text-fg transition-colors hover:border-border hover:bg-surface-2 focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
                        >
                          <span className="truncate">{fullName(row.firstName, row.lastName)}</span>
                          <span className="font-mono text-2xs text-subtle">
                            {shortId(row.customerId)}
                          </span>
                        </button>
                      </li>
                    ))}
                  </ul>
                )
              ) : (
                <p className="px-1 text-2xs text-subtle">
                  Type at least two characters to search customers.
                </p>
              )}
            </div>
          )
        ) : null}

        {blocked ? (
          <p className="text-2xs text-muted">
            Fix {issueCount} issue{issueCount === 1 ? '' : 's'} in the builder before testing.
          </p>
        ) : null}
      </div>

      <div className="min-h-0 flex-1">
        {test.isPending ? (
          <p className="flex items-center justify-center gap-2 px-3 py-6 text-xs text-muted">
            <Spinner size="sm" label="Running rule test" /> Evaluating transactions…
          </p>
        ) : test.error ? (
          <ErrorState compact error={test.error} onRetry={runTest} />
        ) : result ? (
          <div className="flex flex-col">
            <div className="flex flex-wrap items-center gap-3 border-b border-border px-3 py-2">
              <div>
                <p className="numeric text-lg leading-tight font-semibold tracking-tight-swiss text-fg">
                  {result.matchedCount}
                </p>
                <p className="text-2xs text-muted">
                  matching transaction{result.matchedCount === 1 ? '' : 's'}
                  {typeof result.evaluatedCount === 'number'
                    ? ` of ${result.evaluatedCount} evaluated`
                    : ''}
                </p>
              </div>
              {result.degraded ? (
                <Badge tone="warning" dot title="A condition could not be evaluated">
                  Degraded evaluation
                </Badge>
              ) : (
                <Badge tone="neutral">All conditions evaluated</Badge>
              )}
              {/* `notes` carries the concrete reason a leaf could not be
                  evaluated — without it the badge alone tells the admin
                  nothing about which condition to fix. */}
              {result.notes.length > 0 ? (
                <ul className="w-full space-y-0.5 text-2xs leading-relaxed text-warning-fg">
                  {result.notes.map((note) => (
                    <li key={note}>{note}</li>
                  ))}
                </ul>
              ) : null}
            </div>
            <Table
              dense
              caption="Sample matching transactions"
              columns={MATCH_COLUMNS}
              rows={result.sampleMatches}
              rowKey={(row, index) => `${row.transactionId}-${index}`}
              emptyTitle="No matching transactions"
              emptyDescription="This rule would not trigger on the current data."
            />
          </div>
        ) : (
          <EmptyState
            compact
            icon={<FlaskConical className="size-4" />}
            title="Not tested yet"
            description="Run the test to see which transactions this rule would match."
          />
        )}
      </div>
    </section>
  )
}
