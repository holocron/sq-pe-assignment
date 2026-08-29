import { ArrowLeft, History, Sparkles, UserX } from 'lucide-react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { sortAnalysesNewestFirst, useCustomerAnalyses, useStartAnalysis } from '../api/analyses'
import { useCustomer, useCustomerSummary } from '../api/customers'
import { errorMessage, isApiError } from '../api/errors'
import { Button } from '../components/ui/Button'
import { Card } from '../components/ui/Card'
import { EmptyState } from '../components/ui/EmptyState'
import { LinkButton } from '../components/ui/LinkButton'
import { useToast } from '../components/ui/Toast'
import { fullName } from '../lib/format'
import {
  ActivityBreakdownCard,
  ActivityPanel,
  ActivitySummaryCards,
  ActivityTimelineCard,
  CustomerAnalysesPanel,
  CustomerHeader,
} from './customer'

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
          action={<LinkButton to="/dashboard">Back to customer search</LinkButton>}
        />
      </Card>
    )
  }

  const customerName = customerQuery.data
    ? fullName(customerQuery.data.firstName, customerQuery.data.lastName)
    : 'this customer'

  return (
    <div className="flex flex-col gap-4">
      <nav aria-label="Breadcrumb">
        <Link
          to="/dashboard"
          className="inline-flex w-fit items-center gap-1.5 rounded-xs text-xs font-medium text-muted transition-colors hover:text-accent-strong"
        >
          <ArrowLeft aria-hidden="true" className="size-3.5" />
          Customer search
        </Link>
      </nav>

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
          <ActivityTimelineCard
            customerId={customerId}
            currencies={summaryQuery.data?.currencies ?? []}
          />
        </div>
        <ActivityBreakdownCard
          summary={summaryQuery.data}
          loading={summaryQuery.isLoading}
          error={summaryQuery.error}
          onRetry={() => void summaryQuery.refetch()}
        />
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
