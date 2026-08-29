/**
 * One leaf of the rule tree: `{ field, operator, value }`.
 *
 * The three controls sit on a fixed grid — field, operator, value, actions — so
 * every row in a rule lines up on the same columns however deeply it is nested.
 * The field dropdown is populated from `GET /api/rules/field-catalog` and
 * grouped by category, the operator dropdown offers exactly the operators that
 * catalog entry declares, and the value control adapts to the field type.
 */
import { Copy, Trash2 } from 'lucide-react'
import type { RuleCondition, RuleOperator } from '../../../api/types'
import { Button } from '../../../components/ui/Button'
import { Select } from '../../../components/ui/Select'
import { cn } from '../../../lib/cn'
import {
  RULE_OPERATOR_META,
  findCatalogEntry,
  groupFieldCatalog,
  type RulePath,
} from '../../../lib/rules'
import {
  applyFieldChange,
  applyOperatorChange,
  catalogGroupTitle,
  conditionLabel,
  enumValuesOf,
  fieldOptionLabel,
  operatorsForEntry,
  type Catalog,
} from './ruleModel'
import { ValueEditor } from './ValueEditor'

export interface ConditionRowProps {
  condition: RuleCondition
  path: RulePath
  catalog: Catalog
  issues: readonly string[]
  onChange: (path: RulePath, next: RuleCondition) => void
  onRemove: (path: RulePath) => void
  onDuplicate: (path: RulePath) => void
  disabled?: boolean
}

/** field · operator · value · actions — one shared column rhythm per row. */
const ROW_GRID =
  'grid grid-cols-1 items-start gap-1.5 sm:grid-cols-2 ' +
  'lg:grid-cols-[minmax(0,1.5fr)_minmax(0,0.9fr)_minmax(0,1.4fr)_auto]'

export function ConditionRow({
  condition,
  path,
  catalog,
  issues,
  onChange,
  onRemove,
  onDuplicate,
  disabled = false,
}: ConditionRowProps) {
  const label = conditionLabel(path)
  const entry = findCatalogEntry(catalog, condition.field)
  const type = entry?.type ?? null
  const enumValues = enumValuesOf(catalog, condition.field)
  /* The catalog states, per field, which operators the backend will accept. */
  const operators = operatorsForEntry(entry)
  const invalid = issues.length > 0
  const groups = groupFieldCatalog(catalog)
  const unknownField = catalog.length > 0 && !entry && condition.field.length > 0

  return (
    <div
      role="group"
      aria-label={label}
      className={cn(
        'rounded-xs border px-2 py-2 transition-colors',
        invalid ? 'border-danger/50 bg-danger-soft/30' : 'border-border bg-surface',
      )}
    >
      <div className={ROW_GRID}>
        <Select
          label="Field"
          hideLabel
          containerClassName="min-w-0"
          value={condition.field}
          disabled={disabled}
          aria-invalid={unknownField || undefined}
          onChange={(event) => onChange(path, applyFieldChange(condition, event.target.value, catalog))}
        >
          {!entry && condition.field.length > 0 ? (
            <option value={condition.field}>
              {unknownField ? `${condition.field} — not in catalog` : condition.field}
            </option>
          ) : null}
          {groups.map((group) => (
            <optgroup key={group.group} label={catalogGroupTitle(group.group)}>
              {group.entries.map((option) => (
                <option key={option.field} value={option.field}>
                  {fieldOptionLabel(option)}
                </option>
              ))}
            </optgroup>
          ))}
        </Select>

        <Select
          label="Operator"
          hideLabel
          containerClassName="min-w-0"
          value={condition.operator}
          disabled={disabled}
          options={operators.map((meta) => ({ value: meta.operator, label: meta.label }))}
          onChange={(event) =>
            onChange(path, applyOperatorChange(condition, event.target.value as RuleOperator, catalog))
          }
        />

        <div className="min-w-0">
          <ValueEditor
            operator={condition.operator}
            type={type}
            enumValues={enumValues}
            enumClosed={entry?.valuesClosed !== false}
            value={condition.value}
            invalid={invalid}
            disabled={disabled}
            onChange={(value) => onChange(path, { ...condition, value })}
          />
        </div>

        <div className="flex shrink-0 items-center justify-end gap-0.5 sm:col-span-2 lg:col-span-1">
          <Button
            variant="ghost"
            size="icon"
            className="size-8"
            aria-label={`Duplicate ${label}`}
            title="Duplicate condition"
            disabled={disabled}
            onClick={() => onDuplicate(path)}
          >
            <Copy className="size-3.5" aria-hidden="true" />
          </Button>
          <Button
            variant="ghost"
            size="icon"
            className="size-8"
            aria-label={`Remove ${label}`}
            title="Remove condition"
            disabled={disabled}
            onClick={() => onRemove(path)}
          >
            <Trash2 className="size-3.5" aria-hidden="true" />
          </Button>
        </div>
      </div>

      {/* Machine reading of the row: the exact field path, its catalog type and
          the operator symbol that will end up in the stored JSON. */}
      <p className="mt-1.5 flex flex-wrap items-center gap-x-1.5 gap-y-1 text-2xs text-subtle">
        <span className="font-mono text-muted">{condition.field}</span>
        <span aria-hidden="true">·</span>
        <span>{type ?? 'unknown type'}</span>
        <span aria-hidden="true">·</span>
        <span className="rounded-xxs border border-border bg-surface-2 px-1 font-mono text-muted">
          {RULE_OPERATOR_META[condition.operator].symbol}
        </span>
      </p>

      {invalid ? (
        <p role="alert" className="mt-1 text-2xs font-medium text-danger-fg">
          {issues.join(' · ')}
        </p>
      ) : null}
    </div>
  )
}
