/**
 * `/customers/:customerId/analyses` — analysis history, newest first.
 *
 * Defaults to the customer in the route and can widen to every customer
 * (`?scope=all`). There is no global history endpoint in the contract, so the
 * all-customers view fans out over `GET /api/customers/{id}/analyses` for the
 * customers returned by `GET /api/customers`.
 */
import { ChevronRight, Clock, Play, ScrollText, ShieldCheck, TriangleAlert } from 'lucide-react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import {
  ANALYSES_FANOUT_CUSTOMER_LIMIT,
  sortAnalysesNewestFirst,
  useAnalysesAcrossCustomers,
  useCustomerAnalyses,
  useStartAnalysis,
} from '../api/analyses'
import { useCustomer } from '../api/customers'
import { errorMessage } from '../api/errors'
import type { AnalysisSummary } from '../api/types'
import { Badge } from '../components/ui/Badge'
import { Button } from '../components/ui/Button'
import { Card } from '../components/ui/Card'
import { EmptyState } from '../components/ui/EmptyState'
import { LinkButton } from '../components/ui/LinkButton'
import { PageHeader } from '../components/ui/PageHeader'
import { RiskBadge } from '../components/ui/RiskBadge'
import { Spinner } from '../components/ui/Spinner'
import { StatCard } from '../components/ui/StatCard'
import { Table, type Column } from '../components/ui/Table'
import { useToast } from '../components/ui/Toast'
import { cn } from '../lib/cn'
import {
  EM_DASH,
  formatDateTime,
  formatDuration,
  formatNumber,
  formatRelativeTime,
  fullName,
  shortId,
} from '../lib/format'

type Scope = 'customer' | 'all'

function StatusCell({ status }: { status: AnalysisSummary['status'] }) {
  if (status === 'RUNNING') {
    return (
      <Badge tone="info">
        <Spinner size="xs" label="Running" />
        Running
      </Badge>
    )
  }
  return (
    <Badge tone={status === 'FAILED' ? 'danger' : 'neutral'} dot>
      {status === 'FAILED' ? 'Failed' : 'Completed'}
    </Badge>
  )
}

export function AnalysisHistoryPage() {
  const { customerId } = useParams<{ customerId: string }>()
  const [searchParams, setSearchParams] = useSearchParams()
  const navigate = useNavigate()
  const toast = useToast()

  const scope: Scope = !customerId || searchParams.get('scope') === 'all' ? 'all' : 'customer'

  const customerQuery = useCustomer(customerId)
  const historyQuery = useCustomerAnalyses(customerId, {
    enabled: scope === 'customer' && Boolean(customerId),
    refetchInterval: (query) =>
      query.state.data?.some((item) => item.status === 'RUNNING') ? 5000 : false,
  })

  const across = useAnalysesAcrossCustomers({ enabled: scope === 'all' })

  const rows =
    scope === 'all' ? across.rows : sortAnalysesNewestFirst(historyQuery.data ?? [])
  const loading = scope === 'all' ? across.isPending : historyQuery.isPending
  const error = scope === 'all' ? across.error : historyQuery.error
  const retry = () => {
    if (scope === 'all') {
      across.refetch()
      return
    }
    void historyQuery.refetch()
  }

  const startAnalysis = useStartAnalysis({
    onSuccess: (run) => {
      toast.success('Analysis started', 'The agent is working through the full rule set.')
      navigate(`/analyses/${run.assessmentId}`)
    },
    onError: (mutationError) => {
      toast.error('Could not start the analysis', errorMessage(mutationError))
    },
  })

  const completedRows = rows.filter((row) => row.status === 'COMPLETED')
  const elevated = rows.filter(
    (row) => row.riskLevel === 'HIGH' || row.riskLevel === 'CRITICAL',
  ).length
  const agentComplete = completedRows.filter((row) => row.coverageComplete === true).length
  const customerName = customerQuery.data
    ? fullName(customerQuery.data.firstName, customerQuery.data.lastName)
    : null

  const setScope = (next: Scope) => {
    const params = new URLSearchParams(searchParams)
    if (next === 'all') params.set('scope', 'all')
    else params.delete('scope')
    setSearchParams(params, { replace: true })
  }

  const columns: Column<AnalysisSummary>[] = [
    {
      key: 'started',
      header: 'Started',
      className: 'w-48',
      cell: (row) => (
        <div className="min-w-0">
          <span className="numeric block text-fg">{formatDateTime(row.createdAt)}</span>
          <span className="block text-2xs text-subtle">{formatRelativeTime(row.createdAt)}</span>
        </div>
      ),
    },
    ...(scope === 'all'
      ? [
          {
            key: 'customer',
            header: 'Customer',
            cell: (row: AnalysisSummary) => (
              <div className="min-w-0">
                <span className="block truncate text-fg">
                  {across.customerNames.get(row.customerId) || 'Unknown customer'}
                </span>
                <span className="block font-mono text-2xs text-subtle" title={row.customerId}>
                  {shortId(row.customerId)}
                </span>
              </div>
            ),
          } satisfies Column<AnalysisSummary>,
        ]
      : []),
    {
      key: 'status',
      header: 'Status',
      cell: (row) => <StatusCell status={row.status} />,
    },
    {
      key: 'risk',
      header: 'Risk level',
      cell: (row) => <RiskBadge level={row.riskLevel} />,
    },
    {
      key: 'score',
      header: 'Score',
      align: 'right',
      cell: (row) => (
        <span className="numeric text-fg">
          {row.totalScore === null || row.totalScore === undefined
            ? EM_DASH
            : formatNumber(row.totalScore, { maximumFractionDigits: 2 })}
        </span>
      ),
    },
    {
      key: 'coverage',
      header: 'Coverage',
      align: 'right',
      cell: (row) =>
        row.rulesTotal ? (
          <span className="flex items-center justify-end gap-1.5">
            <span className="numeric font-medium text-fg">
              {row.rulesEvaluated ?? row.rulesTotal}
              <span className="px-0.5 font-normal text-subtle">/</span>
              {row.rulesTotal}
            </span>
            {row.coverageComplete === false ? (
              <Badge
                tone="warning"
                title="The deterministic engine backfilled at least one rule the agent left unevaluated."
              >
                Backfilled
              </Badge>
            ) : null}
          </span>
        ) : (
          <span className="text-subtle">{EM_DASH}</span>
        ),
    },
    {
      key: 'duration',
      header: 'Duration',
      align: 'right',
      cell: (row) => <span className="numeric text-muted">{formatDuration(row.durationMs)}</span>,
    },
    {
      key: 'requestedBy',
      header: 'Requested by',
      cell: (row) => <span className="truncate text-muted">{row.requestedBy ?? EM_DASH}</span>,
    },
    {
      key: 'open',
      header: 'Open analysis',
      hideHeader: true,
      align: 'right',
      className: 'w-10',
      cell: () => <ChevronRight aria-hidden="true" className="size-4 text-subtle" />,
    },
  ]

  return (
    <div className="space-y-4">
      <PageHeader
        eyebrow={
          customerId ? (
            <Link
              to={`/customers/${customerId}`}
              className="rounded-xs underline-offset-4 hover:text-fg hover:underline focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
            >
              {customerName ?? 'Customer'}
            </Link>
          ) : undefined
        }
        title="Analysis history"
        description={
          scope === 'all'
            ? `Every analysis recorded for the first ${ANALYSES_FANOUT_CUSTOMER_LIMIT} customers, newest first.`
            : 'Every AI risk analysis recorded for this customer, newest first.'
        }
        actions={
          <>
            {customerId ? (
              <LinkButton
                to={`/customers/${customerId}`}
                iconLeft={<ScrollText aria-hidden="true" className="size-4" />}
              >
                Customer activity
              </LinkButton>
            ) : null}
            {customerId ? (
              <Button
                variant="primary"
                loading={startAnalysis.isPending}
                onClick={() => startAnalysis.mutate(customerId)}
                iconLeft={<Play aria-hidden="true" className="size-4" />}
              >
                Run new analysis
              </Button>
            ) : null}
          </>
        }
      />

      {customerId ? (
        <div className="flex flex-wrap items-center gap-2.5">
          <span className="text-2xs font-semibold tracking-caption text-muted uppercase">
            Scope
          </span>
          <div
            role="group"
            aria-label="Analysis scope"
            className="inline-flex rounded-md border border-border bg-surface p-0.5 shadow-panel"
          >
            {(
              [
                { id: 'customer' as const, label: 'This customer' },
                { id: 'all' as const, label: 'All customers' },
              ] satisfies { id: Scope; label: string }[]
            ).map((option) => (
              <button
                key={option.id}
                type="button"
                aria-pressed={scope === option.id}
                onClick={() => setScope(option.id)}
                className={cn(
                  'rounded-xs px-3 py-1.5 text-xs font-medium transition-colors focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none',
                  scope === option.id
                    ? 'bg-accent-soft text-accent-soft-fg'
                    : 'text-muted hover:bg-surface-2 hover:text-fg',
                )}
              >
                {option.label}
              </button>
            ))}
          </div>
        </div>
      ) : null}

      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard
          label="Analyses"
          value={formatNumber(rows.length)}
          icon={<ScrollText className="size-3.5" />}
          numeric
          loading={loading}
        />
        <StatCard
          label="Elevated verdicts"
          value={formatNumber(elevated)}
          hint="HIGH or CRITICAL"
          icon={<TriangleAlert className="size-3.5" />}
          numeric
          loading={loading}
        />
        <StatCard
          label="Agent-complete coverage"
          value={`${agentComplete} / ${completedRows.length}`}
          hint="Runs needing no deterministic backfill"
          icon={<ShieldCheck className="size-3.5" />}
          numeric
          loading={loading}
        />
        <StatCard
          label="Most recent run"
          value={rows[0] ? formatRelativeTime(rows[0].createdAt) : EM_DASH}
          hint={rows[0] ? formatDateTime(rows[0].createdAt) : undefined}
          icon={<Clock className="size-3.5" />}
          loading={loading}
        />
      </div>

      <Card>
        <Table
          columns={columns}
          rows={rows}
          rowKey={(row) => row.assessmentId}
          loading={loading}
          error={error}
          onRetry={retry}
          caption="Analysis history, newest first"
          onRowClick={(row) => navigate(`/analyses/${row.assessmentId}`)}
          rowClassName={(row) =>
            row.status === 'RUNNING' ? 'border-l-2 border-l-accent bg-surface-2/60' : undefined
          }
          stickyHeader
          empty={
            <EmptyState
              title="No analyses yet"
              description={
                scope === 'all'
                  ? 'No customer has been analysed yet. Open a customer and run the AI analysis to create one.'
                  : 'This customer has never been analysed. Run the AI analysis to produce a risk verdict and a full rule-coverage record.'
              }
              action={
                customerId ? (
                  <Button
                    variant="primary"
                    loading={startAnalysis.isPending}
                    onClick={() => startAnalysis.mutate(customerId)}
                    iconLeft={<Play aria-hidden="true" className="size-4" />}
                  >
                    Run AI analysis
                  </Button>
                ) : undefined
              }
            />
          }
        />
      </Card>
    </div>
  )
}
