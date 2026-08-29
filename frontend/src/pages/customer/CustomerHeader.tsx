import { differenceInYears, parseISO } from 'date-fns'
import { CalendarDays, Globe2, Hash } from 'lucide-react'
import type { ReactNode } from 'react'
import type { AnalysisSummary, Customer } from '../../api/types'
import { Card } from '../../components/ui/Card'
import { ErrorState } from '../../components/ui/ErrorState'
import { RiskBadge } from '../../components/ui/RiskBadge'
import { Skeleton } from '../../components/ui/Skeleton'
import {
  EM_DASH,
  formatCountry,
  formatDate,
  formatDateTime,
  formatRelativeTime,
  fullName,
  initials,
} from '../../lib/format'
import { CopyButton } from './CopyButton'

export interface CustomerHeaderProps {
  customerId: string
  customer: Customer | undefined
  loading: boolean
  error: unknown
  onRetry: () => void
  /** Newest completed run, used for the headline risk level. */
  latestAnalysis: AnalysisSummary | null
  analysesLoading: boolean
  /** "Run AI risk analysis" and the history link. */
  actions?: ReactNode
}

function ageOf(customer: Customer): number | null {
  if (typeof customer.age === 'number') return customer.age
  if (!customer.dob) return null
  const dob = parseISO(customer.dob)
  return Number.isNaN(dob.getTime()) ? null : differenceInYears(new Date(), dob)
}

function MetaItem({ icon, label, children }: { icon: ReactNode; label: string; children: ReactNode }) {
  return (
    <div className="flex min-w-0 items-start gap-2">
      <span aria-hidden="true" className="mt-0.5 text-subtle">
        {icon}
      </span>
      <span className="min-w-0">
        <span className="block text-2xs font-semibold tracking-caption text-subtle uppercase">
          {label}
        </span>
        <span className="mt-0.5 flex min-w-0 items-center gap-1 text-xs text-fg">{children}</span>
      </span>
    </div>
  )
}

/** Identity block at the top of the customer profile. */
export function CustomerHeader({
  customerId,
  customer,
  loading,
  error,
  onRetry,
  latestAnalysis,
  analysesLoading,
  actions,
}: CustomerHeaderProps) {
  if (error) {
    return (
      <Card>
        <ErrorState
          error={error}
          onRetry={onRetry}
          description="This customer could not be loaded. They may have been removed, or the backend is unavailable."
        />
      </Card>
    )
  }

  const name = customer ? fullName(customer.firstName, customer.lastName) : ''
  const age = customer ? ageOf(customer) : null

  return (
    <Card className="overflow-hidden">
      <div className="flex flex-col gap-4 px-4 py-4 lg:flex-row lg:items-start lg:justify-between">
        <div className="flex min-w-0 gap-3.5">
          <span
            aria-hidden="true"
            className="flex size-11 shrink-0 items-center justify-center rounded-full bg-surface-2 text-sm font-semibold text-muted"
          >
            {loading ? '' : initials(name)}
          </span>

          <div className="min-w-0">
            <div className="flex flex-wrap items-center gap-2.5">
              {loading ? (
                <Skeleton className="h-6 w-52" />
              ) : (
                <h1 className="truncate text-lg font-semibold tracking-tight-swiss text-fg">
                  {name || EM_DASH}
                </h1>
              )}
              {analysesLoading ? (
                <Skeleton className="h-5 w-24" pill />
              ) : latestAnalysis ? (
                <RiskBadge
                  level={latestAnalysis.riskLevel}
                  score={latestAnalysis.totalScore}
                  showScore={latestAnalysis.totalScore !== null}
                  size="md"
                />
              ) : (
                <span className="text-xs text-subtle">No AI analysis yet</span>
              )}
            </div>

            <p className="mt-1 text-xs text-muted">
              {latestAnalysis ? (
                <>
                  Latest analysis{' '}
                  <span title={formatDateTime(latestAnalysis.createdAt)}>
                    {formatRelativeTime(latestAnalysis.createdAt)}
                  </span>
                  {latestAnalysis.status !== 'COMPLETED'
                    ? ` — ${latestAnalysis.status.toLowerCase()}`
                    : ''}
                  {latestAnalysis.coverageComplete === false
                    ? ' — incomplete: some rules were never judged'
                    : ''}
                </>
              ) : (
                'Customer profile — card, payment and crypto activity on file.'
              )}
            </p>
          </div>
        </div>

        {actions ? <div className="flex shrink-0 flex-wrap items-center gap-2">{actions}</div> : null}
      </div>

      {/* Identity strip: the reference data an operator quotes on a case. */}
      <div className="grid gap-x-6 gap-y-3 border-t border-border bg-surface-2/40 px-4 py-3 sm:grid-cols-3">
        <MetaItem icon={<Hash className="size-3.5" />} label="Customer ID">
          <span
            className="truncate rounded-xs border border-border bg-surface px-1.5 py-0.5 font-mono text-2xs text-fg"
            title={customerId}
          >
            {customerId}
          </span>
          <CopyButton value={customerId} label="Customer ID" />
        </MetaItem>

        <MetaItem icon={<CalendarDays className="size-3.5" />} label="Date of birth">
          {loading ? (
            <Skeleton className="h-3.5 w-28" />
          ) : (
            <span className="numeric">
              {formatDate(customer?.dob)}
              {age !== null ? (
                <span className="ml-1.5 text-2xs text-subtle">({age} yrs)</span>
              ) : null}
            </span>
          )}
        </MetaItem>

        <MetaItem icon={<Globe2 className="size-3.5" />} label="Country">
          {loading ? (
            <Skeleton className="h-3.5 w-24" />
          ) : (
            <span>
              {customer?.country || EM_DASH}
              {customer?.country ? (
                <span className="ml-1.5 text-2xs text-subtle">
                  {formatCountry(customer.country)}
                </span>
              ) : null}
            </span>
          )}
        </MetaItem>
      </div>
    </Card>
  )
}
