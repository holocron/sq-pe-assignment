/**
 * Visual editor for one risk rule: name, scope, weight and the recursive
 * condition builder, with a live JSON preview of the `threshold_logic` that
 * will be persisted and a dry-run tester against real data.
 */
import { CircleCheck, Quote, TriangleAlert } from 'lucide-react'
import { useId, useMemo, useState, type ReactNode } from 'react'
import { errorMessage } from '../../../api/errors'
import { useCreateRule, useFieldCatalog, useUpdateRule } from '../../../api/rules'
import {
  RULE_SCOPES,
  type RiskRule,
  type RiskRuleInput,
  type RuleGroup,
  type RuleScope,
} from '../../../api/types'
import { Button } from '../../../components/ui/Button'
import { ErrorState } from '../../../components/ui/ErrorState'
import { Input } from '../../../components/ui/Input'
import { Modal } from '../../../components/ui/Modal'
import { Select } from '../../../components/ui/Select'
import { Skeleton } from '../../../components/ui/Skeleton'
import { useToast } from '../../../components/ui/Toast'
import { cn } from '../../../lib/cn'
import { appendNodeAt, removeNodeAt, replaceNodeAt, type RulePath } from '../../../lib/rules'
import { ConditionGroup, type BuilderActions } from './ConditionGroup'
import { JsonPreview } from './JsonPreview'
import { RuleTester } from './RuleTester'
import {
  asRootGroup,
  collectIssues,
  describeRuleEnglish,
  duplicateNodeAt,
  newCondition,
  newGroup,
  serializeRuleNode,
  toRootGroup,
} from './ruleModel'

export type RuleEditorMode = 'create' | 'edit' | 'duplicate'

export interface RuleEditorProps {
  mode: RuleEditorMode
  /** The rule being edited or duplicated; omitted when creating. */
  rule?: RiskRule | null
  onClose: () => void
}

const SCOPE_HINTS: Record<RuleScope, string> = {
  ALL: 'Evaluated for every transaction',
  CARD: 'Evaluated for card activity only',
  PAYMENT: 'Evaluated for payment activity only',
  CRYPTO: 'Evaluated for crypto activity only',
}

const MODE_TITLES: Record<RuleEditorMode, string> = {
  create: 'New risk rule',
  edit: 'Edit risk rule',
  duplicate: 'Duplicate risk rule',
}

function initialName(mode: RuleEditorMode, rule: RiskRule | null | undefined): string {
  if (!rule) return ''
  return mode === 'duplicate' ? `${rule.ruleName} (copy)` : rule.ruleName
}

export function RuleEditor({ mode, rule, onClose }: RuleEditorProps) {
  const toast = useToast()
  const weightSliderId = useId()
  const catalogQuery = useFieldCatalog()
  const catalog = useMemo(() => catalogQuery.data ?? [], [catalogQuery.data])

  const [ruleName, setRuleName] = useState(() => initialName(mode, rule))
  const [appliesTo, setAppliesTo] = useState<RuleScope>(rule?.appliesTo ?? 'ALL')
  const [weightDraft, setWeightDraft] = useState(() => String(rule?.weight ?? 10))
  const [root, setRoot] = useState<RuleGroup>(() => toRootGroup(rule?.thresholdLogic))
  const [seeded, setSeeded] = useState(() => Boolean(rule))
  const [nameTouched, setNameTouched] = useState(false)

  // A brand-new rule seeds its first row from the catalog once it arrives, so
  // the starting field is always a real, evaluable one.
  if (!seeded && catalog.length > 0) {
    setSeeded(true)
    setRoot(newGroup(catalog))
  }

  const actions = useMemo<BuilderActions>(
    () => ({
      replaceNode: (path: RulePath, node) =>
        setRoot((current) => asRootGroup(replaceNodeAt(current, path, node), current)),
      removeNode: (path: RulePath) =>
        setRoot((current) => asRootGroup(removeNodeAt(current, path), current)),
      duplicateNode: (path: RulePath) =>
        setRoot((current) => asRootGroup(duplicateNodeAt(current, path), current)),
      addCondition: (path: RulePath) =>
        setRoot((current) => asRootGroup(appendNodeAt(current, path, newCondition(catalog)), current)),
      addGroup: (path: RulePath) =>
        setRoot((current) => asRootGroup(appendNodeAt(current, path, newGroup(catalog)), current)),
    }),
    [catalog],
  )

  const issues = useMemo(() => collectIssues(root, catalog), [root, catalog])
  const weight = Number(weightDraft)
  const nameError = ruleName.trim().length === 0 ? 'Enter a rule name' : null
  const weightError =
    weightDraft.trim() === '' || !Number.isFinite(weight)
      ? 'Enter a weight'
      : weight < 0
        ? 'Weight cannot be negative'
        : weight > 999.99
          ? 'Weight must be 999.99 or lower'
          : null

  const create = useCreateRule()
  const update = useUpdateRule()
  const saving = create.isPending || update.isPending
  const saveError = create.error ?? update.error
  const builderReady = catalogQuery.isSuccess
  const canSave =
    builderReady && !saving && issues.total === 0 && nameError === null && weightError === null

  const blockers: string[] = [
    catalogQuery.isPending ? 'Loading the field catalog…' : null,
    catalogQuery.error ? 'Field catalog unavailable' : null,
    nameError,
    weightError,
    issues.total > 0
      ? `${issues.total} condition issue${issues.total === 1 ? '' : 's'} to fix`
      : null,
  ].filter((blocker): blocker is string => blocker !== null)

  const handleSave = (): void => {
    if (!canSave) return
    const input: RiskRuleInput = {
      ruleName: ruleName.trim(),
      appliesTo,
      weight,
      thresholdLogic: serializeRuleNode(root),
    }
    const onSuccess = (): void => {
      toast.success(
        mode === 'edit' ? 'Rule updated' : 'Rule created',
        `“${input.ruleName}” now applies to ${input.appliesTo}.`,
      )
      onClose()
    }
    if (mode === 'edit' && rule) {
      update.mutate({ ruleId: rule.ruleId, input }, { onSuccess })
    } else {
      create.mutate(input, { onSuccess })
    }
  }

  let builder: ReactNode
  if (catalogQuery.isPending) {
    builder = (
      <div className="flex flex-col gap-2" aria-busy="true">
        <Skeleton className="h-9 w-full" />
        <Skeleton className="h-16 w-full" />
        <Skeleton className="h-16 w-full" />
      </div>
    )
  } else if (catalogQuery.error) {
    builder = (
      <ErrorState
        error={catalogQuery.error}
        title="Field catalog unavailable"
        description="The condition builder needs GET /api/rules/field-catalog to know which fields and operators are valid."
        onRetry={() => void catalogQuery.refetch()}
      />
    )
  } else {
    builder = (
      <ConditionGroup
        group={root}
        path={[]}
        catalog={catalog}
        issues={issues}
        actions={actions}
        disabled={saving}
      />
    )
  }

  return (
    <Modal
      open
      onClose={onClose}
      size="xl"
      closeOnOverlayClick={false}
      title={MODE_TITLES[mode]}
      description={
        mode === 'edit' && rule ? (
          <span className="font-mono text-2xs">{rule.ruleId}</span>
        ) : (
          'Build the condition visually — the JSON on the right is what gets stored.'
        )
      }
      footer={
        <>
          <span
            aria-live="polite"
            className={cn(
              'mr-auto flex items-center gap-1.5 text-2xs',
              blockers.length > 0 ? 'text-danger-fg' : 'text-muted',
            )}
          >
            {blockers.length > 0 ? (
              <>
                <TriangleAlert className="size-3.5" aria-hidden="true" />
                {blockers[0]}
                {blockers.length > 1 ? ` · +${blockers.length - 1} more` : ''}
              </>
            ) : (
              <>
                <CircleCheck className="size-3.5" aria-hidden="true" />
                Ready to save
              </>
            )}
          </span>
          <Button variant="secondary" onClick={onClose} disabled={saving}>
            Cancel
          </Button>
          <Button variant="primary" onClick={handleSave} loading={saving} disabled={!canSave}>
            {mode === 'edit' ? 'Save changes' : 'Create rule'}
          </Button>
        </>
      }
    >
      {saveError ? (
        <div
          role="alert"
          className="mb-3 flex items-start gap-2 rounded-xs border border-danger/40 bg-danger-soft px-3 py-2 text-xs text-danger-fg"
        >
          <TriangleAlert aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />
          <span className="min-w-0">{errorMessage(saveError)}</span>
        </div>
      ) : null}

      <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_22rem]">
        <div className="flex max-h-[62vh] min-w-0 flex-col gap-4 overflow-y-auto pr-1">
          <div className="grid gap-3 sm:grid-cols-[minmax(0,2fr)_minmax(0,1fr)]">
            <Input
              label="Rule name"
              required
              placeholder="High-value payment to a sanctioned jurisdiction"
              value={ruleName}
              disabled={saving}
              error={nameTouched ? nameError : null}
              onBlur={() => setNameTouched(true)}
              onChange={(event) => setRuleName(event.target.value)}
            />
            <Select
              label="Applies to"
              value={appliesTo}
              disabled={saving}
              hint={SCOPE_HINTS[appliesTo]}
              options={RULE_SCOPES.map((scope) => ({ value: scope, label: scope }))}
              onChange={(event) => setAppliesTo(event.target.value as RuleScope)}
            />
          </div>

          <div className="flex flex-col gap-1.5 rounded-md border border-border bg-surface-2/40 px-3 py-2.5">
            <label
              htmlFor={weightSliderId}
              className="text-2xs font-semibold tracking-caption text-muted uppercase"
            >
              Weight
              <span className="ml-1.5 font-normal normal-case text-subtle">
                score added when the rule matches
              </span>
            </label>
            <div className="flex items-center gap-3">
              <input
                id={weightSliderId}
                type="range"
                min={0}
                max={100}
                step={0.5}
                disabled={saving}
                value={Number.isFinite(weight) ? Math.min(Math.max(weight, 0), 100) : 0}
                onChange={(event) => setWeightDraft(event.target.value)}
                className="h-1.5 flex-1 accent-accent"
              />
              <Input
                label="Weight value"
                hideLabel
                type="number"
                min={0}
                max={999.99}
                step={0.5}
                containerClassName="w-24"
                className="numeric text-right"
                value={weightDraft}
                disabled={saving}
                error={weightError}
                onChange={(event) => setWeightDraft(event.target.value)}
              />
            </div>
          </div>

          <div className="flex flex-col gap-2">
            <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1">
              <h3 className="text-2xs font-semibold tracking-caption text-muted uppercase">
                Conditions
              </h3>
              <span className="text-2xs text-subtle">
                Nested AND / OR / NOT groups — the rule triggers when the root group matches.
              </span>
            </div>
            {/* Plain-English reading of the tree, so the builder is verifiable
                without parsing the JSON on the right. */}
            <p className="flex items-start gap-2 rounded-md border border-border border-l-2 border-l-accent bg-surface-2/50 px-3 py-2 text-xs leading-relaxed text-muted">
              <Quote aria-hidden="true" className="mt-0.5 size-3 shrink-0 text-subtle" />
              <span className="min-w-0">{describeRuleEnglish(root, catalog)}</span>
            </p>
            {builder}
          </div>
        </div>

        <div className="flex max-h-[62vh] min-w-0 flex-col gap-3 overflow-y-auto pr-0.5">
          <JsonPreview node={root} />
          <RuleTester thresholdLogic={root} appliesTo={appliesTo} issueCount={issues.total} />
        </div>
      </div>
    </Modal>
  )
}
