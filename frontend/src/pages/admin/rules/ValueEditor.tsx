/**
 * Value inputs for one condition row. The control is chosen from the field's
 * catalog type and the operator's arity:
 *
 *   number   -> numeric input          boolean  -> checkbox
 *   enum     -> select of allowed values
 *   datetime -> datetime-local (stored as an ISO-8601 UTC instant)
 *   date     -> date input             string   -> text input
 *   IN / NOT_IN -> multi-value chip input      BETWEEN -> two inputs
 *   IS_NULL / NOT_NULL -> no value input at all
 */
import { Plus, X } from 'lucide-react'
import { useEffect, useRef, useState, type KeyboardEvent } from 'react'
import type { FieldType, RuleOperator, RuleScalar, RuleValue } from '../../../api/types'
import { Button } from '../../../components/ui/Button'
import { Checkbox } from '../../../components/ui/Checkbox'
import { Input } from '../../../components/ui/Input'
import { Select } from '../../../components/ui/Select'
import { cn } from '../../../lib/cn'
import { operatorArity } from '../../../lib/rules'
import { coerceScalar, isoToLocalInput, localInputToIso } from './ruleModel'

/* -------------------------------------------------------------------------- */
/* Numeric input with a typing-friendly draft                                  */
/* -------------------------------------------------------------------------- */

function numberDraft(value: RuleScalar | null | undefined): string {
  if (value === null || value === undefined || value === '') return ''
  return String(value)
}

interface NumberFieldProps {
  label: string
  value: RuleScalar | null | undefined
  onChange: (value: RuleScalar) => void
  invalid?: boolean
  disabled?: boolean
  placeholder?: string
}

/**
 * Keeps the raw keystrokes locally so `1.` or `-` do not snap back mid-typing,
 * while always committing a real `number` (or `''` when incomplete) upstream.
 */
function NumberField({ label, value, onChange, invalid, disabled, placeholder }: NumberFieldProps) {
  const [draft, setDraft] = useState(() => numberDraft(value))
  const committed = useRef<RuleScalar | null | undefined>(value)

  useEffect(() => {
    if (value !== committed.current) {
      committed.current = value
      setDraft(numberDraft(value))
    }
  }, [value])

  return (
    <Input
      label={label}
      hideLabel
      type="number"
      inputMode="decimal"
      step="any"
      placeholder={placeholder ?? 'Number'}
      value={draft}
      disabled={disabled}
      aria-invalid={invalid || undefined}
      className={cn('numeric text-right', invalid && 'border-danger')}
      onChange={(event) => {
        const raw = event.target.value
        setDraft(raw)
        const parsed = raw.trim() === '' ? Number.NaN : Number(raw)
        const next: RuleScalar = Number.isFinite(parsed) ? parsed : ''
        committed.current = next
        onChange(next)
      }}
    />
  )
}

/* -------------------------------------------------------------------------- */
/* Single scalar value                                                         */
/* -------------------------------------------------------------------------- */

interface ScalarFieldProps {
  label: string
  type: FieldType | null
  enumValues: readonly string[]
  operator: RuleOperator
  value: RuleScalar | null | undefined
  onChange: (value: RuleScalar) => void
  invalid?: boolean
  disabled?: boolean
}

function ScalarField({
  label,
  type,
  enumValues,
  operator,
  value,
  onChange,
  invalid,
  disabled,
}: ScalarFieldProps) {
  if (type === 'number') {
    return (
      <NumberField
        label={label}
        value={value}
        onChange={onChange}
        invalid={invalid}
        disabled={disabled}
      />
    )
  }

  if (type === 'boolean') {
    return (
      <div className="flex h-8 items-center">
        <Checkbox
          label="Value is true"
          checked={value === true}
          disabled={disabled}
          onChange={(event) => onChange(event.target.checked)}
        />
      </div>
    )
  }

  if (type === 'enum' && enumValues.length > 0) {
    return (
      <Select
        label={label}
        hideLabel
        placeholder="Select a value"
        value={typeof value === 'string' ? value : ''}
        disabled={disabled}
        aria-invalid={invalid || undefined}
        className={cn(invalid && 'border-danger')}
        options={enumValues.map((option) => ({ value: option, label: option }))}
        onChange={(event) => onChange(event.target.value)}
      />
    )
  }

  if (type === 'datetime') {
    return (
      <Input
        label={label}
        hideLabel
        type="datetime-local"
        value={isoToLocalInput(value)}
        disabled={disabled}
        aria-invalid={invalid || undefined}
        className={cn(invalid && 'border-danger')}
        onChange={(event) => onChange(localInputToIso(event.target.value))}
      />
    )
  }

  if (type === 'date') {
    return (
      <Input
        label={label}
        hideLabel
        type="date"
        value={typeof value === 'string' ? value : ''}
        disabled={disabled}
        aria-invalid={invalid || undefined}
        className={cn(invalid && 'border-danger')}
        onChange={(event) => onChange(event.target.value)}
      />
    )
  }

  return (
    <Input
      label={label}
      hideLabel
      type="text"
      placeholder={operator === 'MATCHES' ? 'Regular expression' : 'Value'}
      value={value === null || value === undefined ? '' : String(value)}
      disabled={disabled}
      aria-invalid={invalid || undefined}
      className={cn(operator === 'MATCHES' && 'font-mono text-xs', invalid && 'border-danger')}
      onChange={(event) => onChange(event.target.value)}
    />
  )
}

/* -------------------------------------------------------------------------- */
/* Multi-value chip input (IN / NOT_IN)                                        */
/* -------------------------------------------------------------------------- */

interface ChipValuesProps {
  label: string
  type: FieldType | null
  enumValues: readonly string[]
  values: readonly RuleScalar[]
  onChange: (values: RuleScalar[]) => void
  invalid?: boolean
  disabled?: boolean
}

function ChipValues({
  label,
  type,
  enumValues,
  values,
  onChange,
  invalid,
  disabled,
}: ChipValuesProps) {
  const [draft, setDraft] = useState('')
  const [error, setError] = useState<string | null>(null)
  const useEnumPicker = type === 'enum' && enumValues.length > 0
  const remaining = enumValues.filter((option) => !values.some((value) => String(value) === option))

  const addRaw = (raw: string): void => {
    const parts = raw
      .split(',')
      .map((part) => part.trim())
      .filter((part) => part.length > 0)
    if (parts.length === 0) return
    const next: RuleScalar[] = [...values]
    for (const part of parts) {
      const coerced = coerceScalar(part, type, enumValues)
      if (coerced === null) {
        setError(type === 'number' ? 'Enter a number' : `"${part}" is not an allowed value`)
        return
      }
      if (next.some((value) => String(value) === String(coerced))) continue
      next.push(coerced)
    }
    setError(null)
    setDraft('')
    onChange(next)
  }

  const handleKeyDown = (event: KeyboardEvent<HTMLInputElement>): void => {
    if (event.key === 'Enter' || event.key === ',') {
      event.preventDefault()
      addRaw(draft)
      return
    }
    if (event.key === 'Backspace' && draft === '' && values.length > 0) {
      onChange(values.slice(0, -1))
    }
  }

  return (
    <div className="flex min-w-0 flex-col gap-1.5">
      {values.length > 0 ? (
        <ul className="flex flex-wrap items-center gap-1">
          {values.map((value, index) => (
            <li key={`${String(value)}-${index}`}>
              <span className="inline-flex items-center gap-1 rounded-full border border-border bg-surface-2 py-0.5 pr-1 pl-2 text-xs text-fg">
                <span className="numeric max-w-40 truncate">{String(value)}</span>
                <button
                  type="button"
                  disabled={disabled}
                  aria-label={`Remove ${String(value)}`}
                  onClick={() => onChange(values.filter((_, i) => i !== index))}
                  className="rounded-full p-0.5 text-subtle transition-colors hover:bg-surface-3 hover:text-fg focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
                >
                  <X className="size-3" aria-hidden="true" />
                </button>
              </span>
            </li>
          ))}
        </ul>
      ) : null}

      {useEnumPicker ? (
        <Select
          label={label}
          hideLabel
          placeholder={remaining.length > 0 ? 'Add a value…' : 'All values added'}
          value=""
          disabled={disabled || remaining.length === 0}
          options={remaining.map((option) => ({ value: option, label: option }))}
          onChange={(event) => {
            if (event.target.value) addRaw(event.target.value)
          }}
        />
      ) : (
        <div className="flex items-start gap-1.5">
          <Input
            label={label}
            hideLabel
            containerClassName="flex-1 min-w-0"
            placeholder={type === 'number' ? 'Add a number…' : 'Add a value…'}
            value={draft}
            disabled={disabled}
            aria-invalid={invalid || undefined}
            className={cn(invalid && 'border-danger')}
            onChange={(event) => {
              setDraft(event.target.value)
              setError(null)
            }}
            onKeyDown={handleKeyDown}
            onBlur={() => addRaw(draft)}
          />
          <Button
            size="md"
            variant="secondary"
            className="shrink-0"
            aria-label="Add value"
            disabled={disabled || draft.trim() === ''}
            onClick={() => addRaw(draft)}
            iconLeft={<Plus className="size-3.5" aria-hidden="true" />}
          >
            Add
          </Button>
        </div>
      )}

      {error ? (
        <p role="alert" className="text-2xs font-medium text-danger-fg">
          {error}
        </p>
      ) : (
        <p className="text-2xs text-subtle">
          {values.length === 0
            ? 'Press Enter or comma to add each value'
            : `${values.length} value${values.length === 1 ? '' : 's'}`}
        </p>
      )}
    </div>
  )
}

/* -------------------------------------------------------------------------- */
/* Public entry point                                                          */
/* -------------------------------------------------------------------------- */

export interface ValueEditorProps {
  operator: RuleOperator
  type: FieldType | null
  enumValues: readonly string[]
  value: RuleValue | undefined
  onChange: (value: RuleValue) => void
  invalid?: boolean
  disabled?: boolean
}

export function ValueEditor({
  operator,
  type,
  enumValues,
  value,
  onChange,
  invalid,
  disabled,
}: ValueEditorProps) {
  const arity = operatorArity(operator)

  if (arity === 'none') {
    return (
      <p className="flex h-8 items-center rounded-xs border border-dashed border-border px-2.5 text-xs text-subtle italic">
        No value needed
      </p>
    )
  }

  if (arity === 'many') {
    return (
      <ChipValues
        label="Values"
        type={type}
        enumValues={enumValues}
        values={Array.isArray(value) ? value : []}
        onChange={onChange}
        invalid={invalid}
        disabled={disabled}
      />
    )
  }

  if (arity === 'two') {
    const pair: RuleScalar[] = Array.isArray(value) ? value : []
    const isTemporal = type === 'datetime' || type === 'date'
    const setAt = (index: 0 | 1, next: RuleScalar): void => {
      const updated: RuleScalar[] = [pair[0] ?? '', pair[1] ?? '']
      updated[index] = next
      onChange(updated)
    }
    return (
      <div className="flex min-w-0 items-center gap-1.5">
        <div className="min-w-0 flex-1">
          <ScalarField
            label={isTemporal ? 'From' : 'Minimum'}
            type={type}
            enumValues={enumValues}
            operator={operator}
            value={pair[0]}
            onChange={(next) => setAt(0, next)}
            invalid={invalid}
            disabled={disabled}
          />
        </div>
        <span className="shrink-0 text-2xs text-subtle" aria-hidden="true">
          and
        </span>
        <div className="min-w-0 flex-1">
          <ScalarField
            label={isTemporal ? 'To' : 'Maximum'}
            type={type}
            enumValues={enumValues}
            operator={operator}
            value={pair[1]}
            onChange={(next) => setAt(1, next)}
            invalid={invalid}
            disabled={disabled}
          />
        </div>
      </div>
    )
  }

  return (
    <ScalarField
      label="Value"
      type={type}
      enumValues={enumValues}
      operator={operator}
      value={Array.isArray(value) ? value[0] : value}
      onChange={onChange}
      invalid={invalid}
      disabled={disabled}
    />
  )
}
