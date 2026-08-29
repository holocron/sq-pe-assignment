/**
 * `/analyses/:assessmentId` — the AI analysis experience.
 *
 * While the run is RUNNING the page subscribes to
 * `GET /api/analyses/{id}/stream` and renders the ReAct trace step by step as
 * it arrives, with polling of `GET /api/analyses/{id}` as the fallback if the
 * stream drops. Once the run finishes the persisted result takes over.
 */
import { ArrowLeft, ChevronRight, RotateCw, ScrollText } from 'lucide-react'
import { useMemo } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useAnalysis, useAnalysisStream, useStartAnalysis } from '../api/analyses'
import { useCustomer } from '../api/customers'
import { errorMessage } from '../api/errors'
import { useRules } from '../api/rules'
import { Button } from '../components/ui/Button'
import { Card, CardContent } from '../components/ui/Card'
import { EmptyState } from '../components/ui/EmptyState'
import { ErrorState } from '../components/ui/ErrorState'
import { LinkButton } from '../components/ui/LinkButton'
import { PageHeader } from '../components/ui/PageHeader'
import { Skeleton } from '../components/ui/Skeleton'
import { useToast } from '../components/ui/Toast'
import { fullName, shortId } from '../lib/format'
import { AnalysisResultView } from './analysis/AnalysisResultView'
import { mergeTraceSteps } from './analysis/trace'
import { useElapsedMs } from './analysis/useElapsed'

/** Fallback poll cadence while a run is in flight, in milliseconds. */
const POLL_INTERVAL_MS = 4000

function AnalysisSkeleton() {
  return (
    <div className="space-y-4">
      <Card className="overflow-hidden border-l-4 border-l-border-strong">
        <div className="grid gap-5 p-4 sm:p-5 lg:grid-cols-[minmax(0,1fr)_minmax(0,22rem)] lg:gap-8">
          <div className="space-y-3">
            <Skeleton className="h-2.5 w-28" />
            <Skeleton className="h-7 w-40" pill />
            <Skeleton className="h-3 w-56" />
          </div>
          <div className="space-y-3">
            <Skeleton className="h-2.5 w-24" />
            <Skeleton className="h-9 w-32" />
            <Skeleton className="h-2 w-full" pill />
          </div>
        </div>
        <div className="grid grid-cols-2 gap-px border-t border-border bg-border sm:grid-cols-3 lg:grid-cols-6">
          {Array.from({ length: 6 }, (_, index) => (
            <div key={index} className="space-y-1.5 bg-surface px-4 py-2.5">
              <Skeleton className="h-2.5 w-16" />
              <Skeleton className="h-4 w-24" />
            </div>
          ))}
        </div>
      </Card>
      <Card>
        <CardContent className="space-y-4 py-5">
          {Array.from({ length: 6 }, (_, index) => (
            <div key={index} className="flex gap-3">
              <Skeleton className="size-7 shrink-0" pill />
              <div className="flex-1 space-y-2">
                <Skeleton className="h-3.5 w-48" />
                <Skeleton className="h-3 w-72" />
              </div>
            </div>
          ))}
        </CardContent>
      </Card>
    </div>
  )
}

export function AnalysisPage() {
  const { assessmentId } = useParams<{ assessmentId: string }>()
  const navigate = useNavigate()
  const toast = useToast()

  // Polling runs for the whole of a RUNNING analysis: it is the fallback when
  // the SSE stream drops, and it is also how the persisted rule evaluations
  // reach the coverage table, which the stream does not carry.
  const analysisQuery = useAnalysis(assessmentId, { pollIntervalMs: POLL_INTERVAL_MS })
  const analysis = analysisQuery.data
  const status = analysis?.status
  const running = status === 'RUNNING'

  // The live trace: opened only while the run is going, closed as soon as the
  // refreshed result reports a terminal status.
  const stream = useAnalysisStream(assessmentId, { enabled: running })

  // Operators may read rules too; a 403 simply hides the threshold-logic panel.
  const rulesQuery = useRules({ retry: false })
  const customerQuery = useCustomer(analysis?.customerId)

  const steps = useMemo(
    () => mergeTraceSteps(analysis?.trace ?? [], stream.steps),
    [analysis?.trace, stream.steps],
  )
  const elapsedMs = useElapsedMs(analysis?.createdAt, running)

  const startAnalysis = useStartAnalysis({
    onSuccess: (run) => {
      toast.success('Analysis started', 'The agent is working through the full rule set.')
      navigate(`/analyses/${run.assessmentId}`)
    },
    onError: (error) => {
      toast.error('Could not start the analysis', errorMessage(error))
    },
  })

  const rerun = () => {
    if (!analysis?.customerId) return
    startAnalysis.mutate(analysis.customerId)
  }

  const customerName = customerQuery.data
    ? fullName(customerQuery.data.firstName, customerQuery.data.lastName)
    : null

  if (!assessmentId) {
    return (
      <EmptyState
        title="No analysis selected"
        description="Open an analysis from a customer's history to see its result."
        action={
          <LinkButton to="/dashboard">Back to dashboard</LinkButton>
        }
      />
    )
  }

  return (
    <div className="space-y-4">
      <PageHeader
        eyebrow={
          analysis ? (
            <span className="flex flex-wrap items-center gap-1">
              <Link
                to={`/customers/${analysis.customerId}`}
                className="rounded-xs px-0.5 underline-offset-4 hover:text-fg hover:underline focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
              >
                {customerName ?? 'Customer'}
              </Link>
              <ChevronRight aria-hidden="true" className="size-3 text-subtle" />
              <Link
                to={`/customers/${analysis.customerId}/analyses`}
                className="rounded-xs px-0.5 underline-offset-4 hover:text-fg hover:underline focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
              >
                Analyses
              </Link>
              <ChevronRight aria-hidden="true" className="size-3 text-subtle" />
              <span className="font-mono text-fg">{shortId(assessmentId)}</span>
            </span>
          ) : (
            <Link
              to="/dashboard"
              className="inline-flex items-center gap-1.5 rounded-xs underline-offset-4 hover:text-fg hover:underline focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
            >
              <ArrowLeft aria-hidden="true" className="size-3.5" />
              Dashboard
            </Link>
          )
        }
        title="AI risk analysis"
        description={
          <span className="flex flex-wrap items-center gap-1.5 text-xs">
            <span className="text-2xs font-semibold tracking-caption text-muted uppercase">
              Assessment
            </span>
            <code className="rounded-xxs border border-border bg-surface-2 px-1.5 py-0.5 font-mono text-2xs text-muted">
              {assessmentId}
            </code>
          </span>
        }
        actions={
          <>
            {analysis ? (
              <LinkButton
                to={`/customers/${analysis.customerId}/analyses`}
                iconLeft={<ScrollText aria-hidden="true" className="size-4" />}
              >
                Analysis history
              </LinkButton>
            ) : null}
            <Button
              variant="primary"
              onClick={rerun}
              disabled={!analysis?.customerId}
              loading={startAnalysis.isPending}
              iconLeft={<RotateCw aria-hidden="true" className="size-4" />}
            >
              Re-run analysis
            </Button>
          </>
        }
      />

      {analysisQuery.isPending ? (
        <AnalysisSkeleton />
      ) : analysisQuery.isError ? (
        <Card>
          {analysisQuery.error?.isNotFound ? (
            <EmptyState
              title="Analysis not found"
              description="This assessment id does not exist, or it has been removed."
              action={
                <LinkButton to="/dashboard">Back to dashboard</LinkButton>
              }
            />
          ) : (
            <ErrorState
              error={analysisQuery.error}
              onRetry={() => {
                void analysisQuery.refetch()
              }}
            />
          )}
        </Card>
      ) : analysis ? (
        <AnalysisResultView
          analysis={analysis}
          steps={steps}
          rules={rulesQuery.data}
          live={{
            connected: stream.connected,
            error: stream.error,
            elapsedMs,
            pollIntervalMs: POLL_INTERVAL_MS,
          }}
          onRerun={rerun}
          rerunPending={startAnalysis.isPending}
        />
      ) : null}
    </div>
  )
}
