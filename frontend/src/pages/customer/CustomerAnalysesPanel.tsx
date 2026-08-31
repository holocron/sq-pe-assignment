import { Activity, ChevronRight } from 'lucide-react'
import { Link } from 'react-router-dom'
import type { AnalysisSummary } from '../../api/types'
import { Badge } from '../../components/ui/Badge'
import { Card, CardHeader, CardTitle } from '../../components/ui/Card'
import { EmptyState } from '../../components/ui/EmptyState'
import { ErrorState } from '../../components/ui/ErrorState'
import { RiskBadge } from '../../components/ui/RiskBadge'
import { Skeleton } from '../../components/ui/Skeleton'
import { formatDateTime, formatNumber, formatRelativeTime } from '../../lib/format'

export interface CustomerAnalysesPanelProps {
  customerId: string
  analyses: AnalysisSummary[] | undefined
  loading: boolean
  error: unknown
  onRetry: () => void
  /** Newest runs shown inline; the rest live on the history page. */
  limit?: number
}

/** The customer's most recent AI risk analyses, newest first. */
export function CustomerAnalysesPanel({
  customerId,
  analyses,
  loading,
  error,
  onRetry,
  limit = 5,
}: CustomerAnalysesPanelProps) {
  const rows = (analyses ?? []).slice(0, limit)

  return (
    <Card className="flex flex-col">
      <CardHeader
        actions={
          <Link
            to={`/customers/${customerId}/analyses`}
            className="rounded-xs text-xs font-medium text-accent-strong underline-offset-4 hover:underline"
          >
            Full history
          </Link>
        }
      >
        <CardTitle>AI risk analyses</CardTitle>
        <p className="mt-0.5 text-xs text-muted">
          {analyses ? `${formatNumber(analyses.length)} run${analyses.length === 1 ? '' : 's'} on record` : 'Assessment history for this customer.'}
        </p>
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
          {Array.from({ length: 3 }, (_, index) => (
            <div key={index} className="flex items-center gap-3">
              <Skeleton className="h-3.5 w-28" />
              <Skeleton className="ml-auto h-5 w-20" pill />
            </div>
          ))}
        </div>
      ) : rows.length === 0 ? (
        <EmptyState
          compact
          icon={<Activity className="size-5" />}
          title="No analysis yet"
          description="Run an AI risk analysis to score this customer against every applicable rule."
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
                  <span
                    className="block truncate text-xs font-medium text-fg"
                    title={formatDateTime(analysis.createdAt)}
                  >
                    {formatRelativeTime(analysis.createdAt)}
                    {analysis.requestedBy ? (
                      <span className="font-normal text-muted"> · {analysis.requestedBy}</span>
                    ) : null}
                  </span>
                  <span className="block truncate text-2xs text-subtle">
                    <span className="numeric">
                      {formatNumber(analysis.rulesEvaluated ?? 0)}/
                      {formatNumber(analysis.rulesTotal ?? 0)}
                    </span>{' '}
                    rules evaluated
                    {analysis.coverageComplete === false ? ' · fallback used' : ''}
                  </span>
                </span>
                {analysis.status !== 'COMPLETED' ? (
                  <Badge
                    tone={
                      analysis.status === 'FAILED'
                        ? 'danger'
                        : analysis.status === 'CANCELLED'
                          ? 'warning'
                          : 'info'
                    }
                    dot
                  >
                    {analysis.status === 'RUNNING'
                      ? 'Running'
                      : analysis.status === 'CANCELLED'
                        ? 'Cancelled'
                        : 'Failed'}
                  </Badge>
                ) : null}
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
