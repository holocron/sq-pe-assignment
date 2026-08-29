/**
 * Presentational body of the analysis page.
 *
 * Kept free of data fetching so a persisted `AnalysisResult` can be rendered
 * (and tested) on its own: verdict, narrative, rule coverage and the ReAct
 * trace. While a run is live the trace moves above the coverage table, because
 * that is where the interesting thing is happening.
 */
import { FileText, ListChecks, RotateCw, TriangleAlert } from 'lucide-react'
import { useMemo } from 'react'
import type { AnalysisResult, RiskRule, TraceStep, UUID } from '../../api/types'
import { ErrorBoundary, RouteErrorPanel } from '../../components/ErrorBoundary'
import { Button } from '../../components/ui/Button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../../components/ui/Card'
import { LiveRunPanel } from './LiveRunPanel'
import { NarrativeText } from './NarrativeText'
import { RuleCoverageTable } from './RuleCoverageTable'
import { TraceViewer } from './TraceViewer'
import { VerdictHeader } from './VerdictHeader'
import { coverageStats } from './coverage'

export interface AnalysisLiveState {
  connected: boolean
  error: string | null
  elapsedMs: number | null
  pollIntervalMs: number
}

export interface AnalysisResultViewProps {
  analysis: AnalysisResult
  /** Persisted trace merged with any live SSE steps; defaults to the persisted trace. */
  steps?: TraceStep[]
  /** `GET /api/rules`, used to show each rule's threshold logic when expanded. */
  rules?: RiskRule[]
  live?: AnalysisLiveState
  /** Re-runs the analysis for this customer. */
  onRerun?: () => void
  rerunPending?: boolean
  className?: string
}

export function AnalysisResultView({
  analysis,
  steps,
  rules,
  live,
  onRerun,
  rerunPending = false,
  className,
}: AnalysisResultViewProps) {
  const running = analysis.status === 'RUNNING'
  const failed = analysis.status === 'FAILED'
  const traceSteps = steps ?? analysis.trace
  const stats = useMemo(() => coverageStats(analysis), [analysis])

  const ruleNames = useMemo(() => {
    const map = new Map<UUID, string>()
    for (const rule of rules ?? []) map.set(rule.ruleId, rule.ruleName)
    for (const evaluation of analysis.ruleEvaluations) {
      map.set(evaluation.ruleId, evaluation.ruleName)
    }
    return map
  }, [rules, analysis.ruleEvaluations])

  /* Both panels render whatever the run persisted, row by row. A malformed
     evaluation or trace step must cost the operator that one panel, not the
     verdict, the narrative and the other panel with it. */
  const coverage = (
    <ErrorBoundary
      resetKeys={[analysis.assessmentId]}
      fallback={(props) => <RouteErrorPanel {...props} />}
    >
      <RuleCoverageTable
        evaluations={analysis.ruleEvaluations}
        stats={stats}
        rules={rules}
        running={running}
      />
    </ErrorBoundary>
  )

  const trace = (
    <ErrorBoundary
      resetKeys={[analysis.assessmentId]}
      fallback={(props) => <RouteErrorPanel {...props} />}
    >
      <TraceViewer
        steps={traceSteps}
        running={running}
        ruleNames={ruleNames}
        live={running && live ? { connected: live.connected } : undefined}
      />
    </ErrorBoundary>
  )

  return (
    <div className={className}>
      <div className="space-y-4">
        {running && live ? (
          <LiveRunPanel
            elapsedMs={live.elapsedMs}
            stepCount={traceSteps.length}
            connected={live.connected}
            streamError={live.error}
            pollIntervalMs={live.pollIntervalMs}
            lastStep={traceSteps.length > 0 ? traceSteps[traceSteps.length - 1] : null}
            progressPercent={stats.total > 0 ? stats.percent : null}
            progressLabel={stats.total > 0 ? `${stats.evaluated} / ${stats.total} rules` : null}
          />
        ) : null}

        {failed ? (
          <Card className="border-l-4 border-l-danger">
            <CardContent className="flex flex-wrap items-start gap-3 py-4">
              <span
                aria-hidden="true"
                className="flex size-8 shrink-0 items-center justify-center rounded-full border border-danger/35 bg-danger-soft text-danger-fg"
              >
                <TriangleAlert className="size-4" />
              </span>
              <div className="min-w-0 flex-1">
                <p className="text-2xs font-semibold tracking-caption text-danger-fg uppercase">
                  Run aborted
                </p>
                <p className="mt-1 text-sm font-medium text-fg">This analysis failed</p>
                <p className="mt-1 text-sm leading-relaxed whitespace-pre-wrap text-muted">
                  {analysis.error ?? 'The backend did not record a reason for the failure.'}
                </p>
                <p className="mt-1.5 text-xs text-muted">
                  Anything the run recorded before it stopped is still shown below.
                </p>
              </div>
              {onRerun ? (
                <Button
                  variant="primary"
                  onClick={onRerun}
                  loading={rerunPending}
                  iconLeft={<RotateCw aria-hidden="true" className="size-3.5" />}
                >
                  Run the analysis again
                </Button>
              ) : null}
            </CardContent>
          </Card>
        ) : null}

        <VerdictHeader analysis={analysis} stats={stats} elapsedMs={live?.elapsedMs ?? null} />

        {running ? (
          <>
            {trace}
            {coverage}
          </>
        ) : (
          <>
            <div className="grid gap-4 lg:grid-cols-2">
              <Card>
                <CardHeader
                  actions={<FileText aria-hidden="true" className="size-4 shrink-0 text-subtle" />}
                >
                  <CardTitle>Summary</CardTitle>
                  <CardDescription>What the agent concluded, in its own words.</CardDescription>
                </CardHeader>
                <CardContent className="py-4">
                  <NarrativeText
                    text={analysis.summary}
                    emptyLabel="The agent did not record a summary for this run."
                  />
                </CardContent>
              </Card>

              <Card>
                <CardHeader
                  actions={
                    <ListChecks aria-hidden="true" className="size-4 shrink-0 text-subtle" />
                  }
                >
                  <CardTitle>Recommended actions</CardTitle>
                  <CardDescription>What customer care should do next.</CardDescription>
                </CardHeader>
                <CardContent className="py-4">
                  <NarrativeText
                    text={analysis.recommendations}
                    emptyLabel="The agent did not record any recommendations for this run."
                  />
                </CardContent>
              </Card>
            </div>

            {coverage}
            {trace}
          </>
        )}
      </div>
    </div>
  )
}
