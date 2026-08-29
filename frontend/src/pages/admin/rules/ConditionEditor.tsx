/**
 * The writing surface for `threshold_logic`.
 *
 * The condition is handed to the model verbatim, so this is the one control in
 * the product where the text *is* the behaviour. It gets a generous auto-growing
 * area, a live budget against the server's limit, blocking validation for the
 * two ways a condition is unusable (empty, or pasted JSON from the old DSL) and
 * an advisory checklist that teaches what a judgeable condition looks like.
 */
import { Check, Lightbulb, Minus } from 'lucide-react'
import { useEffect, useId, type RefObject } from 'react'
import { RULE_CONDITION_MAX_LENGTH, type FieldCatalogEntry } from '../../../api/types'
import { Button } from '../../../components/ui/Button'
import { INPUT_BASE, INPUT_TONE, INPUT_TONE_INVALID } from '../../../components/ui/Input'
import { cn } from '../../../lib/cn'
import { conditionChecks } from './conditionText'
import { RULE_TEMPLATES, type RuleTemplate } from './templates'

export interface ConditionEditorProps {
  value: string
  onChange: (value: string) => void
  /** Owned by the editor so the field reference can insert at the caret. */
  textareaRef: RefObject<HTMLTextAreaElement | null>
  catalog: readonly FieldCatalogEntry[]
  error?: string | null
  disabled?: boolean
  /** Replaces name / scope / weight / condition — offered on a blank page. */
  onUseTemplate: (template: RuleTemplate) => void
  /** Appends the example's prose without discarding existing text. */
  onAppendTemplate: (template: RuleTemplate) => void
}

const MIN_HEIGHT_PX = 176
const MAX_HEIGHT_PX = 420

export function ConditionEditor({
  value,
  onChange,
  textareaRef,
  catalog,
  error,
  disabled = false,
  onUseTemplate,
  onAppendTemplate,
}: ConditionEditorProps) {
  const fieldId = useId()
  const counterId = `${fieldId}-counter`
  const hintId = `${fieldId}-hint`
  const errorId = `${fieldId}-error`

  const length = value.length
  const remaining = RULE_CONDITION_MAX_LENGTH - length
  const over = remaining < 0
  const nearLimit = !over && remaining <= RULE_CONDITION_MAX_LENGTH * 0.1
  const checks = conditionChecks(value, catalog)
  const blank = value.trim().length === 0

  /* Auto-grow: the height follows the content up to a cap, after which the box
     scrolls. Guarded because jsdom reports a scrollHeight of 0. */
  useEffect(() => {
    const element = textareaRef.current
    if (!element) return
    element.style.height = 'auto'
    if (element.scrollHeight > 0) {
      element.style.height = `${Math.min(Math.max(element.scrollHeight, MIN_HEIGHT_PX), MAX_HEIGHT_PX)}px`
    }
  }, [value, textareaRef])

  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1">
        <label
          htmlFor={fieldId}
          className="text-2xs font-semibold tracking-caption text-muted uppercase"
        >
          Rule condition<span className="text-danger-fg"> *</span>
        </label>
        <p
          id={counterId}
          aria-live="polite"
          className={cn(
            'numeric text-2xs',
            over ? 'font-semibold text-danger-fg' : nearLimit ? 'text-warning-fg' : 'text-subtle',
          )}
        >
          {length} / {RULE_CONDITION_MAX_LENGTH} characters
        </p>
      </div>

      <textarea
        id={fieldId}
        ref={textareaRef}
        value={value}
        disabled={disabled}
        spellCheck
        aria-invalid={error ? true : undefined}
        aria-describedby={`${counterId} ${error ? errorId : hintId}`}
        placeholder="Triggered when the customer makes three or more payments between 8 000 and 9 999.99 within any rolling 24 hours…"
        onChange={(event) => onChange(event.target.value)}
        style={{ minHeight: `${MIN_HEIGHT_PX}px`, maxHeight: `${MAX_HEIGHT_PX}px` }}
        className={cn(
          INPUT_BASE,
          error ? INPUT_TONE_INVALID : INPUT_TONE,
          'resize-y overflow-y-auto py-2 text-sm leading-relaxed',
        )}
      />

      {error ? (
        <p id={errorId} role="alert" className="text-2xs font-medium text-danger-fg">
          {error}
        </p>
      ) : (
        <p id={hintId} className="text-2xs leading-relaxed text-subtle">
          This text is given to the agent word for word. It reads the condition, calls its tools for
          the customer’s data and decides both whether the rule is triggered and what it scores.
        </p>
      )}

      <ul className="flex flex-col gap-1 rounded-md border border-border bg-surface-2/40 px-3 py-2">
        <li className="text-2xs font-semibold tracking-caption text-muted uppercase">
          A condition the agent can judge
        </li>
        {checks.map((check) => (
          <li key={check.id} className="flex items-start gap-1.5 text-2xs leading-relaxed">
            {check.met ? (
              <Check aria-hidden="true" className="mt-0.5 size-3 shrink-0 text-success" />
            ) : (
              <Minus aria-hidden="true" className="mt-0.5 size-3 shrink-0 text-subtle" />
            )}
            <span className={check.met ? 'text-fg' : 'text-muted'}>
              {check.label}
              <span className="sr-only">{check.met ? ' — done' : ' — not yet'}</span>
              <span className="ml-1 text-subtle">{check.hint}</span>
            </span>
          </li>
        ))}
      </ul>

      {blank ? (
        <section aria-label="Starter conditions" className="flex flex-col gap-1.5">
          <h4 className="flex items-center gap-1.5 text-2xs font-semibold tracking-caption text-muted uppercase">
            <Lightbulb aria-hidden="true" className="size-3" />
            Start from an example
          </h4>
          <ul className="grid gap-1.5 sm:grid-cols-2">
            {RULE_TEMPLATES.map((template) => (
              <li key={template.id}>
                <button
                  type="button"
                  disabled={disabled}
                  onClick={() => onUseTemplate(template)}
                  aria-label={`Use the ${template.title} example`}
                  className="flex h-full w-full flex-col gap-0.5 rounded-xs border border-border bg-surface px-2.5 py-2 text-left transition-colors hover:border-accent hover:bg-surface-2 focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none disabled:cursor-not-allowed disabled:opacity-60"
                >
                  <span className="flex items-baseline justify-between gap-2">
                    <span className="text-xs font-medium text-fg">{template.title}</span>
                    <span className="numeric text-2xs text-subtle">
                      {template.appliesTo} · {template.weight}
                    </span>
                  </span>
                  <span className="line-clamp-2 text-2xs leading-relaxed text-muted">
                    {template.condition}
                  </span>
                </button>
              </li>
            ))}
          </ul>
        </section>
      ) : (
        <div className="flex flex-wrap items-center gap-1.5">
          <span className="text-2xs font-semibold tracking-caption text-subtle uppercase">
            Add an example
          </span>
          {RULE_TEMPLATES.map((template) => (
            <Button
              key={template.id}
              size="sm"
              variant="ghost"
              disabled={disabled}
              className="h-6 px-1.5 text-2xs"
              aria-label={`Append the ${template.title} example`}
              onClick={() => onAppendTemplate(template)}
            >
              {template.title}
            </Button>
          ))}
        </div>
      )}
    </div>
  )
}
