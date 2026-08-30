/**
 * Runs the draft condition past the model for one customer.
 *
 * This is no longer a cheap dry run: `POST /api/rules/test` asks the agent to
 * read the condition, fetch the customer's activity and judge it, which on the
 * local model server takes two to three minutes and costs a model call. The
 * panel therefore makes the wait legible, and reports the verdict for what it
 * is — one judgement, not a reproducible calculation.
 */
import { Bot, FlaskConical, Search, X } from 'lucide-react'
import { useMemo, useState } from 'react'
import { useCustomers } from '../../../api/customers'
import { isApiError } from '../../../api/errors'
import { RULE_TEST_TIMEOUT_MS, useTestRule } from '../../../api/rules'
import type { CustomerSummary, RuleScope, RuleTestMatch } from '../../../api/types'
import { Badge } from '../../../components/ui/Badge'
import { Button } from '../../../components/ui/Button'
import { EmptyState } from '../../../components/ui/EmptyState'
import { ErrorState } from '../../../components/ui/ErrorState'
import { Input } from '../../../components/ui/Input'
import { Spinner } from '../../../components/ui/Spinner'
import { StatusBadge } from '../../../components/ui/StatusBadge'
import { Table, type Column } from '../../../components/ui/Table'
import { cn } from '../../../lib/cn'
import { formatDateTime, formatDuration, formatMoney, formatNumber, fullName, shortId } from '../../../lib/format'
import { useElapsedMs } from '../../analysis/useElapsed'

export interface RuleTesterProps {
  ruleName: string
  thresholdLogic: string
  appliesTo: RuleScope
  weight: number
  /** Non-null when the draft is not testable yet; explains why. */
  blockedReason: string | null
  className?: string
}

const MATCH_COLUMNS: Column<RuleTestMatch>[] = [
  {
    key: 'transaction',
    header: 'Transaction',
    cell: (row) => (
      <span className="font-mono text-2xs" title={row.transactionId}>
        {shortId(row.transactionId)}
      </span>
    ),
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
    cell: (row) => <StatusBadge status={row.status} />,
  },
  {
    key: 'createdAt',
    header: 'Date',
    cell: (row) => <span className="text-2xs text-muted">{formatDateTime(row.createdAt)}</span>,
  },
  {
    key: 'reason',
    header: 'Why it counts',
    cell: (row) => (
      <span className="text-2xs leading-relaxed text-muted">{row.reason ?? '—'}</span>
    ),
  },
]

export function RuleTester({
  ruleName,
  thresholdLogic,
  appliesTo,
  weight,
  blockedReason,
  className,
}: RuleTesterProps) {
  const [query, setQuery] = useState('')
  const [customer, setCustomer] = useState<CustomerSummary | null>(null)
  const test = useTestRule()

  /* The mutation already records when it was submitted, so the elapsed counter
     is derived from it and driven by the app's shared one-second clock rather
     than a timer of its own. */
  const startedAt = useMemo(
    () => (test.submittedAt ? new Date(test.submittedAt).toISOString() : null),
    [test.submittedAt],
  )
  const elapsedMs = useElapsedMs(startedAt, test.isPending)
  const elapsed = elapsedMs === null ? 0 : Math.floor(elapsedMs / 1000)

  const searchEnabled = customer === null && query.trim().length >= 2
  const customers = useCustomers(
    { query: query.trim(), page: 0, size: 6 },
    { enabled: searchEnabled },
  )

  const customerName = customer
    ? fullName(customer.firstName, customer.lastName)
    : 'the selected customer'

  const runTest = (): void => {
    if (!customer || blockedReason) return
    test.mutate({
      // `RuleTestRequest.ruleName` is @Size(max = 160); a name too long to save
      // must not turn the test into a 400 as well.
      ruleName: (ruleName.trim() || 'Untitled rule').slice(0, 160),
      thresholdLogic,
      appliesTo,
      weight,
      customerId: customer.customerId,
    })
  }

  const result = test.data
  const timedOut = isApiError(test.error) && test.error.isNetworkError

  return (
    <section
      aria-label="Rule test"
      className={cn(
        'flex flex-col overflow-hidden rounded-md border border-border bg-surface',
        className,
      )}
    >
      <header className="flex flex-wrap items-center justify-between gap-2 border-b border-border bg-surface-2/60 px-3 py-2">
        <div className="min-w-0">
          <h3 className="text-xs font-semibold text-fg">Test rule</h3>
          <p className="text-2xs leading-relaxed text-muted">
            Asks the agent to judge this condition for one customer. Nothing is saved.
          </p>
        </div>
        <Button
          size="sm"
          variant="secondary"
          disabled={blockedReason !== null || customer === null}
          loading={test.isPending}
          onClick={runTest}
          iconLeft={<FlaskConical className="size-3.5" aria-hidden="true" />}
        >
          Run the agent’s judgement
        </Button>
      </header>

      <div className="flex flex-col gap-1.5 border-b border-border px-3 py-2">
        {customer ? (
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
              aria-label="Clear the selected customer"
              disabled={test.isPending}
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
              label="Customer to test against"
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
                Type at least two characters. A judgement needs real activity to read, so a customer
                is required.
              </p>
            )}
          </div>
        )}

        {blockedReason ? <p className="text-2xs text-muted">{blockedReason}</p> : null}
      </div>

      <div className="min-h-0 flex-1">
        {test.isPending ? (
          <div
            role="status"
            aria-live="polite"
            className="flex flex-col items-center gap-1.5 px-4 py-6 text-center"
          >
            <Spinner size="md" label="Running the rule test" />
            <p className="text-xs font-medium text-fg">A model is evaluating this rule</p>
            <p className="max-w-sm text-2xs leading-relaxed text-muted">
              The agent is reading the condition, fetching {customerName}’s activity with its tools
              and deciding whether the rule is triggered. This is a live model call — on the local
              model server it takes two to three minutes.
            </p>
            <p className="numeric text-2xs text-subtle">
              {elapsed}s elapsed · giving up after {Math.round(RULE_TEST_TIMEOUT_MS / 1000)}s
            </p>
          </div>
        ) : timedOut ? (
          <ErrorState
            compact
            error={test.error}
            title="The model did not answer in time"
            description={`No verdict came back within ${Math.round(RULE_TEST_TIMEOUT_MS / 1000)} seconds. The rule is unchanged and nothing was saved — retry, or shorten the condition so there is less for the agent to work through.`}
            onRetry={runTest}
          />
        ) : test.error ? (
          <ErrorState compact error={test.error} onRetry={runTest} />
        ) : result ? (
          <div className="flex flex-col">
            <div className="flex flex-col gap-2 border-b border-border px-3 py-2.5">
              <div className="flex flex-wrap items-center gap-2">
                <Badge
                  tone={result.triggered ? 'danger' : 'neutral'}
                  size="md"
                  dot
                  title="The agent's verdict for this rule"
                >
                  {result.triggered ? 'Triggered' : 'Not triggered'}
                </Badge>
                <p className="text-2xs text-muted">
                  Score{' '}
                  <span className="numeric font-semibold text-fg">
                    {result.score === null
                      ? '—'
                      : formatNumber(result.score, {
                          minimumFractionDigits: 2,
                          maximumFractionDigits: 2,
                        })}
                  </span>{' '}
                  of {formatNumber(result.weight ?? weight, { maximumFractionDigits: 2 })}
                </p>
                {result.matchedCount > 0 ? (
                  <p className="numeric text-2xs text-muted">
                    {result.matchedCount} transaction{result.matchedCount === 1 ? '' : 's'} cited
                    {result.evaluatedCount !== null ? ` of ${result.evaluatedCount} in scope` : ''}
                  </p>
                ) : null}
              </div>

              {/* The model may cite more evidence than the backend returns.
                  Showing four rows under a count of thirty would read as a
                  contradiction, so the gap is named. */}
              {result.evidenceTruncated ? (
                <p className="numeric text-2xs text-muted">
                  Showing {result.matches.length} of the {result.matchedCount} transactions the
                  agent cited.
                </p>
              ) : null}

              {result.score !== null && result.score !== (result.weight ?? weight) ? (
                <p className="text-2xs leading-relaxed text-warning-fg">
                  The score above is this preview’s own estimate. In an analysis run a triggered
                  rule contributes exactly its weight, so this rule would be recorded as{' '}
                  {formatNumber(result.weight ?? weight, { maximumFractionDigits: 2 })}.
                </p>
              ) : null}

              <p className="flex items-start gap-1.5 text-2xs leading-relaxed text-subtle">
                <Bot aria-hidden="true" className="mt-0.5 size-3 shrink-0" />
                <span>
                  This preview is a model judgement, not a calculation — running it again on the
                  same data can produce a different verdict or score. An analysis run does not work
                  this way: there the agent writes SQL for this condition, Postgres executes it, and
                  the rule triggers exactly when the query returns rows. Read this as a sense check
                  on the wording, not as the verdict the run will reach.
                  {result.model ? ` Model: ${result.model}.` : ''}
                  {result.durationMs !== null ? ` Took ${formatDuration(result.durationMs)}.` : ''}
                </span>
              </p>
            </div>

            {result.rationale ? (
              <div className="border-b border-border px-3 py-2.5">
                <h4 className="text-2xs font-semibold tracking-caption text-muted uppercase">
                  Agent rationale
                </h4>
                <p className="mt-1 text-xs leading-relaxed whitespace-pre-line text-fg">
                  {result.rationale}
                </p>
              </div>
            ) : null}

            {result.notes.length > 0 ? (
              <ul className="space-y-0.5 border-b border-border px-3 py-2 text-2xs leading-relaxed text-warning-fg">
                {result.notes.map((note) => (
                  <li key={note}>{note}</li>
                ))}
              </ul>
            ) : null}

            <Table
              dense
              caption="Transactions the agent cited"
              columns={MATCH_COLUMNS}
              rows={result.matches}
              rowKey={(row, index) => `${row.transactionId}-${index}`}
              emptyTitle="No transactions cited"
              emptyDescription={
                result.triggered
                  ? 'The agent triggered the rule without naming evidence — treat the verdict with care.'
                  : 'The agent found nothing in this customer’s activity that satisfies the condition.'
              }
            />
          </div>
        ) : (
          <EmptyState
            compact
            icon={<FlaskConical className="size-4" />}
            title="Not tested yet"
            description="Pick a customer and run the judgement to see how the agent reads this condition."
          />
        )}
      </div>
    </section>
  )
}
