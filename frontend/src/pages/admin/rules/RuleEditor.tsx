/**
 * Authoring surface for one risk rule.
 *
 * Identity and weight on the left with the condition below them, the data
 * reference on the right, and the model-backed test underneath. The catalog is
 * reference material rather than a grammar now, so a failed field-catalog fetch
 * degrades the panel but never blocks saving a rule.
 */
import { CircleCheck, TriangleAlert } from 'lucide-react'
import { useEffect, useMemo, useRef, useState } from 'react'
import { errorMessage, isApiError } from '../../../api/errors'
import { useCreateRule, useFieldCatalog, useRules, useUpdateRule } from '../../../api/rules'
import {
  RULE_NAME_MAX_LENGTH,
  RULE_SCOPES,
  RULE_WEIGHT_MAX,
  RULE_WEIGHT_MIN,
  type RiskRule,
  type RiskRuleInput,
  type RuleScope,
} from '../../../api/types'
import { Button } from '../../../components/ui/Button'
import { Input } from '../../../components/ui/Input'
import { Modal } from '../../../components/ui/Modal'
import { Select } from '../../../components/ui/Select'
import { useToast } from '../../../components/ui/Toast'
import { cn } from '../../../lib/cn'
import { ConditionEditor } from './ConditionEditor'
import { FieldReference } from './FieldReference'
import { RuleTester } from './RuleTester'
import { WeightControl } from './WeightControl'
import {
  RULE_SCOPE_HINTS,
  appendParagraph,
  insertAtCaret,
  validateCondition,
} from './conditionText'
import type { RuleTemplate } from './templates'

export type RuleEditorMode = 'create' | 'edit' | 'duplicate'

export interface RuleEditorProps {
  mode: RuleEditorMode
  /** The rule being edited or duplicated; omitted when creating. */
  rule?: RiskRule | null
  onClose: () => void
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

/** First message the backend attached to a field, when it sent any. */
function serverFieldError(error: unknown, field: string): string | null {
  if (!isApiError(error)) return null
  return error.fieldErrors[field]?.[0] ?? null
}

export function RuleEditor({ mode, rule, onClose }: RuleEditorProps) {
  const toast = useToast()
  const catalogQuery = useFieldCatalog()
  const catalog = useMemo(() => catalogQuery.data ?? [], [catalogQuery.data])
  const rulesQuery = useRules()
  const conditionRef = useRef<HTMLTextAreaElement | null>(null)

  const [ruleName, setRuleName] = useState(() => initialName(mode, rule))
  const [appliesTo, setAppliesTo] = useState<RuleScope>(rule?.appliesTo ?? 'ALL')
  const [weightDraft, setWeightDraft] = useState(() => String(rule?.weight ?? 10))
  const [condition, setCondition] = useState(() => rule?.thresholdLogic ?? '')
  const [nameTouched, setNameTouched] = useState(false)
  const [submitted, setSubmitted] = useState(false)

  /* The caret can only be restored once React has flushed the new value into
     the DOM, so an insertion parks the position in a ref and the next commit
     applies and clears it. A ref rather than state: this is a DOM detail, and
     storing it in state would render twice for every inserted field. */
  const pendingCaret = useRef<number | null>(null)
  useEffect(() => {
    const caret = pendingCaret.current
    if (caret === null) return
    pendingCaret.current = null
    const element = conditionRef.current
    if (element) {
      element.focus()
      element.setSelectionRange(caret, caret)
    }
  })

  const create = useCreateRule()
  const update = useUpdateRule()
  const saving = create.isPending || update.isPending
  const saveError = create.error ?? update.error

  const trimmedName = ruleName.trim()
  const weight = Number(weightDraft)
  const nameError =
    trimmedName.length === 0
      ? 'Enter a rule name'
      : trimmedName.length > RULE_NAME_MAX_LENGTH
        ? `Rule names are limited to ${RULE_NAME_MAX_LENGTH} characters.`
        : null
  const weightError =
    weightDraft.trim() === '' || !Number.isFinite(weight)
      ? 'Enter a weight'
      : weight < RULE_WEIGHT_MIN
        ? `Weight must be at least ${RULE_WEIGHT_MIN}`
        : weight > RULE_WEIGHT_MAX
          ? `Weight must be ${RULE_WEIGHT_MAX} or lower`
          : null
  const conditionError = validateCondition(condition)

  /* The weight only means something next to the rest of the catalogue: it is a
     share of the maximum score any run can reach. A rule being edited counts
     once, under its draft weight, not twice. */
  const otherRulesWeight = useMemo(() => {
    const rules = rulesQuery.data ?? []
    if (rules.length === 0) return null
    return rules
      .filter((item) => !(mode === 'edit' && rule ? item.ruleId === rule.ruleId : false))
      .reduce((total, item) => total + (Number.isFinite(item.weight) ? item.weight : 0), 0)
  }, [rulesQuery.data, mode, rule])

  const showNameError = nameTouched || submitted
  /* A pristine, untouched condition is not "wrong" yet — the checklist and the
     footer already say what is missing without shouting at a blank page. */
  const showConditionError = submitted || condition.trim().length > 0
  const canSave = !saving && nameError === null && weightError === null && conditionError === null

  const blockers = [nameError, conditionError, weightError].filter(
    (blocker): blocker is string => blocker !== null,
  )

  const insertField = (field: string): void => {
    const element = conditionRef.current
    const start = element?.selectionStart ?? condition.length
    const end = element?.selectionEnd ?? condition.length
    const { text, caret } = insertAtCaret(condition, field, start, end)
    pendingCaret.current = caret
    setCondition(text)
  }

  /**
   * A starter always replaces the (blank) condition and fills a blank name, but
   * only a brand-new rule takes the example's scope and weight — silently
   * re-scoping a rule someone is editing would change which transactions it is
   * ever judged against.
   */
  const useTemplate = (template: RuleTemplate): void => {
    setCondition(template.condition)
    if (trimmedName.length === 0) setRuleName(template.ruleName)
    if (mode === 'create') {
      setAppliesTo(template.appliesTo)
      setWeightDraft(String(template.weight))
    }
  }

  const handleSave = (): void => {
    setSubmitted(true)
    if (!canSave) return
    const input: RiskRuleInput = {
      ruleName: trimmedName,
      appliesTo,
      weight,
      thresholdLogic: condition.trim(),
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
          'Describe the condition in plain English — the agent reads it, fetches the data and judges the verdict.'
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

      <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_21rem]">
        <div className="flex max-h-[64vh] min-w-0 flex-col gap-4 overflow-y-auto pr-1">
          <div className="grid gap-3 sm:grid-cols-[minmax(0,2fr)_minmax(0,1fr)]">
            <Input
              label="Rule name"
              required
              maxLength={RULE_NAME_MAX_LENGTH}
              placeholder="Payment to a sanctioned or high-risk jurisdiction"
              value={ruleName}
              disabled={saving}
              error={showNameError ? (nameError ?? serverFieldError(saveError, 'ruleName')) : null}
              onBlur={() => setNameTouched(true)}
              onChange={(event) => setRuleName(event.target.value)}
            />
            <Select
              label="Applies to"
              value={appliesTo}
              disabled={saving}
              hint={RULE_SCOPE_HINTS[appliesTo]}
              options={RULE_SCOPES.map((scope) => ({ value: scope, label: scope }))}
              onChange={(event) => setAppliesTo(event.target.value as RuleScope)}
            />
          </div>

          <WeightControl
            value={weightDraft}
            onChange={setWeightDraft}
            error={weightError ?? serverFieldError(saveError, 'weight')}
            disabled={saving}
            otherRulesWeight={otherRulesWeight}
          />

          <ConditionEditor
            value={condition}
            onChange={setCondition}
            textareaRef={conditionRef}
            catalog={catalog}
            disabled={saving}
            error={
              (showConditionError ? conditionError : null) ??
              serverFieldError(saveError, 'thresholdLogic')
            }
            onUseTemplate={useTemplate}
            onAppendTemplate={(template) =>
              setCondition((current) => appendParagraph(current, template.condition))
            }
          />

          <RuleTester
            /* The column scrolls, so its children are flex items that may be shrunk below their
               content. The tester clips (overflow-hidden), so shrinking it hides the customer
               picker and the verdict instead of extending the column's scroll. */
            className="shrink-0"
            ruleName={ruleName}
            thresholdLogic={condition.trim()}
            appliesTo={appliesTo}
            weight={Number.isFinite(weight) ? weight : 0}
            blockedReason={
              conditionError
                ? 'Write a valid condition before testing — the agent is given this exact text.'
                : weightError
                  ? 'Set a valid weight before testing — the agent caps its score at it.'
                  : saving
                    ? 'Saving the rule…'
                    : null
            }
          />
        </div>

        <FieldReference
          className="max-h-[64vh]"
          catalog={catalog}
          loading={catalogQuery.isPending}
          error={catalogQuery.error}
          onRetry={() => void catalogQuery.refetch()}
          scope={appliesTo}
          onInsert={insertField}
        />
      </div>
    </Modal>
  )
}
