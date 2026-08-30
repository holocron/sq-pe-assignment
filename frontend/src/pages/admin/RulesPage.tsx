/**
 * Admin screen for the risk-rule catalogue.
 *
 * `risk_rules.threshold_logic` is prose: the agent reads each condition and
 * writes the SQL that answers it, and the row count Postgres returns is the
 * verdict. The table therefore shows the condition as written rather than a
 * rendering of a parsed expression, and the editor is an authoring tool for that
 * prose — prose that has to survive being translated into a query.
 */
import { Copy, Database, ListFilter, Pencil, Plus, Scale, Trash2 } from 'lucide-react'
import { useMemo, useState } from 'react'
import { errorMessage } from '../../api/errors'
import { useDeleteRule, useFieldCatalog, useRules } from '../../api/rules'
import { RULE_SCOPES, type RiskRule, type RuleScope } from '../../api/types'
import { Badge } from '../../components/ui/Badge'
import { Button } from '../../components/ui/Button'
import { Card } from '../../components/ui/Card'
import { EmptyState } from '../../components/ui/EmptyState'
import { Input } from '../../components/ui/Input'
import { Modal } from '../../components/ui/Modal'
import { PageHeader } from '../../components/ui/PageHeader'
import { Select } from '../../components/ui/Select'
import { StatCard } from '../../components/ui/StatCard'
import { Table, type Column } from '../../components/ui/Table'
import { useToast } from '../../components/ui/Toast'
import { formatNumber, shortId } from '../../lib/format'
import { RuleEditor, type RuleEditorMode } from './rules/RuleEditor'
import { conditionExcerpt } from './rules/conditionText'

const SCOPE_FILTER_ALL = 'ALL_SCOPES'

interface EditorState {
  mode: RuleEditorMode
  rule: RiskRule | null
}

function buildColumns(
  onEdit: (rule: RiskRule) => void,
  onDuplicate: (rule: RiskRule) => void,
  onDelete: (rule: RiskRule) => void,
): Column<RiskRule>[] {
  return [
    {
      key: 'name',
      header: 'Rule',
      className: 'w-64',
      cell: (rule) => (
        <div className="min-w-0">
          <p className="truncate font-medium text-fg" title={rule.ruleName}>
            {rule.ruleName}
          </p>
          <p className="mt-0.5 font-mono text-2xs text-subtle" title={rule.ruleId}>
            {shortId(rule.ruleId)}
          </p>
        </div>
      ),
    },
    {
      key: 'appliesTo',
      header: 'Applies to',
      className: 'w-28',
      cell: (rule) => <Badge tone="outline">{rule.appliesTo}</Badge>,
    },
    {
      key: 'weight',
      header: 'Weight',
      align: 'right',
      className: 'w-24',
      cell: (rule) => (
        <span className="numeric font-medium text-fg">
          {formatNumber(rule.weight, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
        </span>
      ),
    },
    {
      key: 'condition',
      header: 'Condition',
      cell: (rule) => {
        const excerpt = conditionExcerpt(rule.thresholdLogic)
        if (excerpt.length === 0) {
          return (
            <p className="text-xs text-danger-fg">
              No condition stored — the agent has nothing to judge.
            </p>
          )
        }
        return (
          <div className="min-w-0 max-w-xl">
            <p className="line-clamp-2 text-xs leading-relaxed text-fg" title={rule.thresholdLogic}>
              {excerpt}
            </p>
          </div>
        )
      },
    },
    {
      key: 'actions',
      header: 'Actions',
      align: 'right',
      className: 'w-32',
      hideHeader: true,
      cell: (rule) => (
        <div className="flex items-center justify-end gap-0.5">
          <Button
            variant="ghost"
            size="icon"
            className="size-8"
            aria-label={`Edit ${rule.ruleName}`}
            title="Edit rule"
            onClick={() => onEdit(rule)}
          >
            <Pencil className="size-3.5" aria-hidden="true" />
          </Button>
          <Button
            variant="ghost"
            size="icon"
            className="size-8"
            aria-label={`Duplicate ${rule.ruleName}`}
            title="Duplicate rule"
            onClick={() => onDuplicate(rule)}
          >
            <Copy className="size-3.5" aria-hidden="true" />
          </Button>
          <Button
            variant="ghost"
            size="icon"
            className="size-8"
            aria-label={`Delete ${rule.ruleName}`}
            title="Delete rule"
            onClick={() => onDelete(rule)}
          >
            <Trash2 className="size-3.5" aria-hidden="true" />
          </Button>
        </div>
      ),
    },
  ]
}

export function RulesPage() {
  const toast = useToast()
  const rulesQuery = useRules()
  const catalogQuery = useFieldCatalog()
  const deleteRule = useDeleteRule()

  const [search, setSearch] = useState('')
  const [scopeFilter, setScopeFilter] = useState<RuleScope | typeof SCOPE_FILTER_ALL>(
    SCOPE_FILTER_ALL,
  )
  const [editor, setEditor] = useState<EditorState | null>(null)
  const [pendingDelete, setPendingDelete] = useState<RiskRule | null>(null)

  const catalog = useMemo(() => catalogQuery.data ?? [], [catalogQuery.data])
  const rules = useMemo(() => rulesQuery.data ?? [], [rulesQuery.data])

  const filtered = useMemo(() => {
    const needle = search.trim().toLowerCase()
    return rules.filter((rule) => {
      if (scopeFilter !== SCOPE_FILTER_ALL && rule.appliesTo !== scopeFilter) return false
      if (needle.length === 0) return true
      return `${rule.ruleName} ${rule.thresholdLogic}`.toLowerCase().includes(needle)
    })
  }, [rules, search, scopeFilter])

  const combinedWeight = useMemo(
    () => rules.reduce((total, rule) => total + (Number.isFinite(rule.weight) ? rule.weight : 0), 0),
    [rules],
  )

  const columns = useMemo(
    () =>
      buildColumns(
        (rule) => setEditor({ mode: 'edit', rule }),
        (rule) => setEditor({ mode: 'duplicate', rule }),
        (rule) => setPendingDelete(rule),
      ),
    [],
  )

  const confirmDelete = (): void => {
    const target = pendingDelete
    if (!target) return
    deleteRule.mutate(target.ruleId, {
      onSuccess: () => {
        toast.success('Rule deleted', `“${target.ruleName}” is no longer judged.`)
        setPendingDelete(null)
      },
      onError: (error) => {
        toast.error('Could not delete rule', errorMessage(error))
      },
    })
  }

  const filtersActive = search.trim().length > 0 || scopeFilter !== SCOPE_FILTER_ALL

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title="Risk rules"
        description="Each rule states its condition in plain English. The agent reads it, fetches the customer's data and judges whether the rule is triggered — and every applicable rule must have a verdict before a run can complete."
        actions={
          <Button
            variant="primary"
            onClick={() => setEditor({ mode: 'create', rule: null })}
            iconLeft={<Plus className="size-4" aria-hidden="true" />}
          >
            New rule
          </Button>
        }
      />

      <div className="grid gap-3 sm:grid-cols-3">
        <StatCard
          label="Rules"
          value={rules.length}
          hint="judged on every analysis in scope"
          icon={<ListFilter className="size-4" />}
          loading={rulesQuery.isPending}
        />
        <StatCard
          label="Combined weight"
          value={formatNumber(combinedWeight, {
            minimumFractionDigits: 2,
            maximumFractionDigits: 2,
          })}
          hint="maximum attainable risk score"
          icon={<Scale className="size-4" />}
          loading={rulesQuery.isPending}
          numeric
        />
        <StatCard
          label="Available data"
          value={catalog.length}
          hint="fields the agent can fetch"
          icon={<Database className="size-4" />}
          loading={catalogQuery.isPending}
        />
      </div>

      <Card>
        <div className="flex flex-wrap items-end gap-3 border-b border-border bg-surface-2/40 px-4 py-3">
          <Input
            label="Search rules"
            hideLabel
            containerClassName="min-w-56 flex-1"
            placeholder="Filter by rule name or condition text…"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
          />
          <Select
            label="Filter by scope"
            hideLabel
            containerClassName="w-44"
            value={scopeFilter}
            onChange={(event) =>
              setScopeFilter(event.target.value as RuleScope | typeof SCOPE_FILTER_ALL)
            }
          >
            <option value={SCOPE_FILTER_ALL}>All scopes</option>
            {RULE_SCOPES.map((scope) => (
              <option key={scope} value={scope}>
                {scope}
              </option>
            ))}
          </Select>
          <p className="numeric ml-auto pb-1.5 text-2xs text-muted">
            {filtered.length} of {rules.length} rule{rules.length === 1 ? '' : 's'}
          </p>
        </div>

        <Table
          caption="Risk rules"
          columns={columns}
          rows={filtered}
          rowKey={(rule) => rule.ruleId}
          loading={rulesQuery.isPending}
          error={rulesQuery.error}
          onRetry={() => void rulesQuery.refetch()}
          empty={
            filtersActive ? (
              <EmptyState
                title="No rules match your filters"
                description="Clear the search or scope filter to see every rule."
                action={
                  <Button
                    size="sm"
                    onClick={() => {
                      setSearch('')
                      setScopeFilter(SCOPE_FILTER_ALL)
                    }}
                  >
                    Clear filters
                  </Button>
                }
              />
            ) : (
              <EmptyState
                icon={<ListFilter className="size-5" />}
                title="No risk rules yet"
                description="Create the first rule to give the agent something to judge."
                action={
                  <Button
                    variant="primary"
                    size="sm"
                    onClick={() => setEditor({ mode: 'create', rule: null })}
                    iconLeft={<Plus className="size-3.5" aria-hidden="true" />}
                  >
                    Create the first rule
                  </Button>
                }
              />
            )
          }
        />
      </Card>

      {editor ? (
        <RuleEditor
          key={`${editor.mode}-${editor.rule?.ruleId ?? 'new'}`}
          mode={editor.mode}
          rule={editor.rule}
          onClose={() => setEditor(null)}
        />
      ) : null}

      <Modal
        open={pendingDelete !== null}
        onClose={() => setPendingDelete(null)}
        closeOnOverlayClick={false}
        size="sm"
        title="Delete rule"
        description={pendingDelete ? `“${pendingDelete.ruleName}”` : undefined}
        footer={
          <>
            <Button
              variant="secondary"
              onClick={() => setPendingDelete(null)}
              disabled={deleteRule.isPending}
            >
              Cancel
            </Button>
            <Button variant="danger" loading={deleteRule.isPending} onClick={confirmDelete}>
              Delete rule
            </Button>
          </>
        }
      >
        <div className="flex flex-col gap-2 text-sm text-muted">
          <p>
            This cannot be undone. Future analyses will no longer judge this rule, and the maximum
            attainable risk score drops by{' '}
            <span className="numeric font-medium text-fg">
              {formatNumber(pendingDelete?.weight ?? 0, {
                minimumFractionDigits: 2,
                maximumFractionDigits: 2,
              })}
            </span>
            .
          </p>
          {/* `risk_assessments` cascades on the rule, so past evidence goes with it. Completed
              analyses still render their coverage table from their own stored trace, but the
              per-(transaction, rule) audit rows are gone. Say so - a compliance tool must not
              destroy evidence behind a dialog that only mentions the future. */}
          <p>
            The recorded evidence this rule contributed to{' '}
            <span className="font-medium text-fg">past analyses</span> is deleted with it. Completed
            analyses keep the coverage table they were saved with, but their per-transaction rows
            for this rule are removed from the audit table.
          </p>
        </div>
      </Modal>
    </div>
  )
}
