import { History, Sparkles, UserX } from 'lucide-react'
import { lazy, Suspense } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { sortAnalysesNewestFirst, useCustomerAnalyses, useStartAnalysis } from '../api/analyses'
import { useCustomer, useCustomerSummary } from '../api/customers'
import { errorMessage, isApiError } from '../api/errors'
import { BackLink } from '../components/ui/BackLink'
import { Button } from '../components/ui/Button'
import { Card } from '../components/ui/Card'
import { EmptyState } from '../components/ui/EmptyState'
import { LinkButton } from '../components/ui/LinkButton'
import { Skeleton } from '../components/ui/Skeleton'
import { useToast } from '../components/ui/Toast'
import { fullName } from '../lib/format'
import {
  ActivityPanel,
  ActivitySummaryCards,
  CustomerAnalysesPanel,
  CustomerHeader,
} from './customer'

/* The charts carry recharts (~100 kB gzipped); they load on demand so the rest
   of the page — and every route that never draws a chart — stays free of it. */
const ActivityTimelineCard = lazy(() =>
  import('./customer/ActivityCharts').then((m) => ({ default: m.ActivityTimelineCard })),
)
const ActivityBreakdownCard = lazy(() =>
  import('./customer/ActivityCharts').then((m) => ({ default: m.ActivityBreakdownCard })),
)

/** Placeholder with the card's footprint so the grid does not jump. */
function ChartFallback() {
  return (
    <Card className="p-4">
      <Skeleton className="h-4 w-40" />
      <Skeleton className="mt-4 h-48 w-full" />
    </Card>
  )
}

/**
 * The operator's working view of one customer: identity, aggregates, volume
 * charts, the full activity ledger and the entry point to the risk agent.
 */
export function CustomerPage() {
  const { customerId } = useParams<{ customerId: string }>()
  const navigate = useNavigate()
  const toast = useToast()

  const customerQuery = useCustomer(customerId)
  const summaryQuery = useCustomerSummary(customerId)
  const analysesQuery = useCustomerAnalyses(customerId)

  const startAnalysis = useStartAnalysis({
    onSuccess: (run) => {
      toast.success('Analysis started', 'Following the agent live.')
      navigate(`/analyses/${run.assessmentId}`)
    },
    onError: (error) => {
      toast.error('Could not start the analysis', errorMessage(error))
    },
  })

  // The contract says history comes back newest first; sort anyway so the
  // headline verdict cannot silently become the oldest run.
  const analyses = analysesQuery.data ? sortAnalysesNewestFirst(analysesQuery.data) : undefined
  const latestAnalysis = analyses && analyses.length > 0 ? analyses[0] : null
  const notFound = isApiError(customerQuery.error) && customerQuery.error.isNotFound

  if (!customerId || notFound) {
    return (
      <Card>
        <EmptyState
          icon={<UserX className="size-5" />}
          title="Customer not found"
          description="This customer does not exist, or the identifier in the URL is not a valid UUID."
          action={<LinkButton to="/dashboard">Back to customer activity</LinkButton>}
        />
      </Card>
    )
  }

  const customerName = customerQuery.data
    ? fullName(customerQuery.data.firstName, customerQuery.data.lastName)
    : 'this customer'

  return (
    <div className="flex flex-col gap-4">
      <BackLink to="/dashboard" className="text-xs text-muted">
        Customer activity
      </BackLink>

      <CustomerHeader
        customerId={customerId}
        customer={customerQuery.data}
        loading={customerQuery.isLoading}
        error={customerQuery.error}
        onRetry={() => void customerQuery.refetch()}
        latestAnalysis={latestAnalysis}
        analysesLoading={analysesQuery.isLoading}
        actions={
          <>
            <LinkButton
              to={`/customers/${customerId}/analyses`}
              iconLeft={<History aria-hidden="true" className="size-4" />}
            >
              Analysis history
            </LinkButton>
            <Button
              variant="primary"
              loading={startAnalysis.isPending}
              onClick={() => startAnalysis.mutate(customerId)}
              iconLeft={startAnalysis.isPending ? undefined : <Sparkles className="size-4" />}
              title={`Run the ReAct risk agent over ${customerName}`}
            >
              {startAnalysis.isPending ? 'Starting…' : 'Run AI risk analysis'}
            </Button>
          </>
        }
      />

      <ActivitySummaryCards
        summary={summaryQuery.data}
        loading={summaryQuery.isLoading}
        error={summaryQuery.error}
        onRetry={() => void summaryQuery.refetch()}
      />

      <div className="grid gap-4 lg:grid-cols-2 xl:grid-cols-4">
        <div className="lg:col-span-2">
          <Suspense fallback={<ChartFallback />}>
            <ActivityTimelineCard
              customerId={customerId}
              currencies={summaryQuery.data?.currencies ?? []}
            />
          </Suspense>
        </div>
        <Suspense fallback={<ChartFallback />}>
          <ActivityBreakdownCard
            summary={summaryQuery.data}
            loading={summaryQuery.isLoading}
            error={summaryQuery.error}
            onRetry={() => void summaryQuery.refetch()}
          />
        </Suspense>
        <CustomerAnalysesPanel
          customerId={customerId}
          analyses={analyses}
          loading={analysesQuery.isLoading}
          error={analysesQuery.error}
          onRetry={() => void analysesQuery.refetch()}
        />
      </div>

      <ActivityPanel customerId={customerId} summary={summaryQuery.data} />
    </div>
  )
}
