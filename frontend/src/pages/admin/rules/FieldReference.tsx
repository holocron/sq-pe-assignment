/**
 * "Available data" — the reference panel next to the condition.
 *
 * It answers the only question that matters while writing a prose rule: what
 * can the agent actually look up? Fields are grouped the way the backend
 * categorises them, searchable, and clicking one drops its name into the
 * condition at the caret so the author names it exactly as the agent sees it.
 *
 * Fields outside the rule's scope are shown, not hidden: they are the reason a
 * condition would never fire, and that is worth seeing before saving.
 */
import { ChevronRight, Database, Search } from 'lucide-react'
import { useMemo, useState } from 'react'
import type { FieldCatalogEntry, FieldCategory, RuleScope } from '../../../api/types'
import { Badge } from '../../../components/ui/Badge'
import { EmptyState } from '../../../components/ui/EmptyState'
import { ErrorState } from '../../../components/ui/ErrorState'
import { Input } from '../../../components/ui/Input'
import { Skeleton } from '../../../components/ui/Skeleton'
import { cn } from '../../../lib/cn'
import {
  FIELD_TYPE_LABELS,
  defaultOpenCategories,
  groupCatalog,
  isInScope,
} from './fieldCatalog'

export interface FieldReferenceProps {
  catalog: readonly FieldCatalogEntry[]
  loading?: boolean
  error?: unknown
  onRetry?: () => void
  /** Scope of the rule being edited; drives which groups open and dim. */
  scope: RuleScope
  onInsert: (field: string) => void
  className?: string
}

export function FieldReference({
  catalog,
  loading = false,
  error,
  onRetry,
  scope,
  onInsert,
  className,
}: FieldReferenceProps) {
  const [search, setSearch] = useState('')
  const [open, setOpen] = useState<FieldCategory[]>(() => defaultOpenCategories(scope))

  const groups = useMemo(() => groupCatalog(catalog, search), [catalog, search])
  const searching = search.trim().length > 0

  const toggle = (category: FieldCategory): void => {
    setOpen((current) =>
      current.includes(category)
        ? current.filter((item) => item !== category)
        : [...current, category],
    )
  }

  return (
    <section
      aria-label="Available data"
      className={cn(
        'flex min-h-0 flex-col overflow-hidden rounded-md border border-border bg-surface',
        className,
      )}
    >
      <header className="flex flex-col gap-1.5 border-b border-border bg-surface-2/60 px-3 py-2">
        <div className="flex items-center gap-1.5">
          <Database aria-hidden="true" className="size-3.5 text-subtle" />
          <h3 className="text-xs font-semibold text-fg">Available data</h3>
          {loading || error ? null : (
            <span className="numeric ml-auto text-2xs text-subtle">{catalog.length} fields</span>
          )}
        </div>
        <p className="text-2xs leading-relaxed text-muted">
          Everything the agent’s tools can fetch. Name these in the condition — anything else it can
          only guess at.
        </p>
        <Input
          label="Search available data"
          hideLabel
          className="h-7 text-xs"
          placeholder="Search fields…"
          value={search}
          iconLeft={<Search className="size-3.5" aria-hidden="true" />}
          onChange={(event) => setSearch(event.target.value)}
        />
      </header>

      <div className="min-h-0 flex-1 overflow-y-auto">
        {loading ? (
          <div className="flex flex-col gap-2 p-3" aria-busy="true">
            <Skeleton className="h-4 w-24" />
            <Skeleton className="h-8 w-full" />
            <Skeleton className="h-8 w-full" />
            <Skeleton className="h-4 w-20" />
            <Skeleton className="h-8 w-full" />
          </div>
        ) : error ? (
          <ErrorState
            compact
            error={error}
            title="Field reference unavailable"
            description="GET /api/rules/field-catalog is what tells an author which data the agent can fetch."
            onRetry={onRetry}
          />
        ) : groups.length === 0 ? (
          <EmptyState
            compact
            title={searching ? 'No field matches' : 'The catalog is empty'}
            description={
              searching
                ? `Nothing in the catalog mentions “${search.trim()}”.`
                : 'The backend returned no fields, so there is nothing to reference.'
            }
          />
        ) : (
          <ul className="flex flex-col">
            {groups.map((group) => {
              const expanded = searching || open.includes(group.category)
              return (
                <li key={group.category} className="border-b border-border/60 last:border-b-0">
                  <button
                    type="button"
                    aria-expanded={expanded}
                    onClick={() => toggle(group.category)}
                    className="flex w-full items-center gap-1.5 px-3 py-1.5 text-left transition-colors hover:bg-surface-2 focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
                  >
                    <ChevronRight
                      aria-hidden="true"
                      className={cn(
                        'size-3 shrink-0 text-subtle transition-transform',
                        expanded && 'rotate-90',
                      )}
                    />
                    <span className="text-2xs font-semibold tracking-caption text-muted uppercase">
                      {group.label}
                    </span>
                    <span className="numeric ml-auto text-2xs text-subtle">
                      {group.entries.length}
                    </span>
                  </button>

                  {expanded ? (
                    <>
                      <p className="px-3 pb-1 text-2xs text-subtle">{group.description}</p>
                      <ul className="flex flex-col pb-1.5">
                        {group.entries.map((entry) => {
                          const inScope = isInScope(entry, scope)
                          return (
                            <li key={entry.field}>
                              <button
                                type="button"
                                aria-label={`Insert ${entry.field} into the condition`}
                                onClick={() => onInsert(entry.field)}
                                className={cn(
                                  'flex w-full flex-col gap-0.5 border-l-2 border-transparent px-3 py-1.5 text-left transition-colors',
                                  'hover:border-l-accent hover:bg-surface-2 focus-visible:border-l-accent focus-visible:bg-surface-2 focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none',
                                  !inScope && 'opacity-60',
                                )}
                              >
                                <span className="flex flex-wrap items-baseline gap-x-1.5 gap-y-0.5">
                                  <code className="font-mono text-2xs font-medium text-fg">
                                    {entry.field}
                                  </code>
                                  <span className="text-2xs text-subtle">
                                    {FIELD_TYPE_LABELS[entry.type]}
                                  </span>
                                  {entry.nullable ? (
                                    <span className="text-2xs text-subtle">· may be empty</span>
                                  ) : null}
                                  {inScope ? null : (
                                    <Badge tone="warning" title="Absent on this rule's activity type">
                                      {entry.appliesTo} only
                                    </Badge>
                                  )}
                                </span>
                                {entry.description ? (
                                  <span className="text-2xs leading-relaxed text-muted">
                                    {entry.description}
                                  </span>
                                ) : null}
                                {entry.options.length > 0 ? (
                                  <span className="text-2xs leading-relaxed text-subtle">
                                    Values: {entry.options.join(', ')}
                                  </span>
                                ) : entry.example ? (
                                  <span className="text-2xs leading-relaxed text-subtle">
                                    e.g. {entry.example}
                                  </span>
                                ) : null}
                              </button>
                            </li>
                          )
                        })}
                      </ul>
                    </>
                  ) : null}
                </li>
              )
            })}
          </ul>
        )}
      </div>
    </section>
  )
}
