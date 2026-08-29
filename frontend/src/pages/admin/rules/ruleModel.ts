/**
 * Editor-side model helpers for the visual risk-rule builder.
 *
 * Everything here is pure so the DSL mapping (BUILD_SPEC section 3) can be
 * reasoned about — and tested — without rendering React. The builder tree is a
 * plain `RuleNode`; these helpers keep it canonical while the user edits:
 *
 *  - `applyFieldChange` / `applyOperatorChange` reset an operator or value that
 *    is no longer valid for the selected field's type;
 *  - `serializeRuleNode` produces the exact JSON persisted in
 *    `risk_rules.threshold_logic` (key order `field, operator, value` /
 *    `op, conditions`, and no `value` key at all for IS_NULL / NOT_NULL);
 *  - `collectIssues` merges the shared validator with catalog-aware checks so a
 *    row that would silently evaluate to `false` server-side is flagged here.
 */
import type {
  FieldCatalogEntry,
  FieldType,
  RuleCondition,
  RuleGroup,
  RuleNode,
  RuleOperator,
  RuleScalar,
  RuleValue,
} from '../../../api/types'
import { formatNumber } from '../../../lib/format'
import {
  RULE_OPERATOR_META,
  catalogEnumValues,
  createCondition,
  createGroup,
  defaultValueForOperator,
  emptyRuleLogic,
  findCatalogEntry,
  getNodeAt,
  isRuleGroup,
  operatorArity,
  operatorsForFieldType,
  updateNodeAt,
  validateRuleNode,
  type RulePath,
} from '../../../lib/rules'

export type Catalog = readonly FieldCatalogEntry[]

/* -------------------------------------------------------------------------- */
/* Paths and labels                                                            */
/* -------------------------------------------------------------------------- */

/** Stable map key for a node path (`[1, 0]` -> `"1.0"`). */
export function pathKey(path: RulePath): string {
  return path.join('.')
}

/** Human numbering used in labels: `[1, 0]` -> `"2.1"`. */
export function pathNumber(path: RulePath): string {
  return path.map((index) => index + 1).join('.')
}

export function groupLabel(path: RulePath): string {
  return path.length === 0 ? 'Root group' : `Group ${pathNumber(path)}`
}

export function conditionLabel(path: RulePath): string {
  return `Condition ${pathNumber(path)}`
}

/* -------------------------------------------------------------------------- */
/* Field catalog lookups                                                       */
/* -------------------------------------------------------------------------- */

export function fieldTypeOf(catalog: Catalog, field: string): FieldType | null {
  return findCatalogEntry(catalog, field)?.type ?? null
}

export function enumValuesOf(catalog: Catalog, field: string): string[] {
  return catalogEnumValues(findCatalogEntry(catalog, field))
}

/** `payment.receiver_bank_country` -> `Receiver bank country` (label wins). */
export function fieldLabel(catalog: Catalog, field: string): string {
  const entry = findCatalogEntry(catalog, field)
  const explicit = entry?.label?.trim()
  if (explicit) return explicit
  const leaf = field.includes('.') ? field.slice(field.lastIndexOf('.') + 1) : field
  const spaced = leaf.replace(/[_-]+/g, ' ').trim()
  if (!spaced) return field
  return spaced.charAt(0).toUpperCase() + spaced.slice(1)
}

/** Option text for the field dropdown — the raw path is always visible. */
export function fieldOptionLabel(entry: FieldCatalogEntry): string {
  const explicit = entry.label?.trim()
  return explicit ? `${explicit} (${entry.field})` : entry.field
}

const GROUP_TITLES: Record<string, string> = {
  transaction: 'Transaction',
  customer: 'Customer',
  card: 'Card activity',
  payment: 'Payment activity',
  crypto: 'Crypto activity',
  agg: 'Aggregates',
}

export function catalogGroupTitle(group: string): string {
  const known = GROUP_TITLES[group]
  if (known) return known
  const spaced = group.replace(/[_-]+/g, ' ').trim()
  return spaced.charAt(0).toUpperCase() + spaced.slice(1)
}

/** The default operator for a field type — first entry of the filtered list. */
export function defaultOperatorForType(type: FieldType | null): RuleOperator {
  return operatorsForFieldType(type)[0]?.operator ?? 'EQ'
}

/* -------------------------------------------------------------------------- */
/* Value coercion                                                              */
/* -------------------------------------------------------------------------- */

function asScalarArray(value: RuleValue | undefined): RuleScalar[] {
  if (Array.isArray(value)) return value
  if (value === null || value === undefined || value === '') return []
  return [value]
}

function firstScalar(value: RuleValue | undefined): RuleScalar | null {
  if (Array.isArray(value)) return value.length > 0 ? (value[0] as RuleScalar) : null
  if (value === null || value === undefined) return null
  return value
}

/**
 * Narrows one scalar to the field's type. `null` means "not representable" —
 * the caller then falls back to the operator/type default.
 */
export function coerceScalar(
  value: RuleScalar | null,
  type: FieldType | null,
  allowed: readonly string[] = [],
): RuleScalar | null {
  if (value === null) return null
  switch (type) {
    case 'number': {
      if (typeof value === 'number') return Number.isFinite(value) ? value : null
      if (typeof value === 'boolean') return null
      const parsed = value.trim() === '' ? Number.NaN : Number(value)
      return Number.isFinite(parsed) ? parsed : null
    }
    case 'boolean': {
      if (typeof value === 'boolean') return value
      if (value === 'true') return true
      if (value === 'false') return false
      return null
    }
    case 'enum': {
      const text = String(value)
      if (allowed.length > 0 && !allowed.includes(text)) return null
      return text
    }
    default:
      return typeof value === 'string' ? value : String(value)
  }
}

/** Reshapes an existing value for `operator`, preserving what still fits. */
export function coerceValue(
  value: RuleValue | undefined,
  operator: RuleOperator,
  type: FieldType | null,
  allowed: readonly string[] = [],
): RuleValue {
  const arity = operatorArity(operator)
  if (arity === 'none') return null

  if (arity === 'many') {
    return asScalarArray(value)
      .map((item) => coerceScalar(item, type, allowed))
      .filter((item): item is RuleScalar => item !== null)
  }

  const fallback = defaultValueForOperator(operator, type)

  if (arity === 'two') {
    const pair = Array.isArray(fallback) ? fallback : ['', '']
    const source = Array.isArray(value) ? value : [firstScalar(value), null]
    const low = coerceScalar((source[0] ?? null) as RuleScalar | null, type, allowed)
    const high = coerceScalar((source[1] ?? null) as RuleScalar | null, type, allowed)
    return [low ?? (pair[0] as RuleScalar), high ?? (pair[1] as RuleScalar)]
  }

  const scalar = coerceScalar(firstScalar(value), type, allowed)
  return scalar === null ? fallback : scalar
}

/* -------------------------------------------------------------------------- */
/* Field / operator changes                                                    */
/* -------------------------------------------------------------------------- */

/**
 * Applies a new field to a condition. An operator that is not valid for the new
 * field type falls back to that type's default, and the value is reset whenever
 * the type changes (a `10000` amount must not survive into a country code).
 */
export function applyFieldChange(
  condition: RuleCondition,
  field: string,
  catalog: Catalog,
): RuleCondition {
  const previousType = fieldTypeOf(catalog, condition.field)
  const entry = findCatalogEntry(catalog, field)
  const type = entry?.type ?? null
  const allowed = catalogEnumValues(entry)
  const valid = operatorsForFieldType(type).map((meta) => meta.operator)
  const operator = valid.includes(condition.operator)
    ? condition.operator
    : (valid[0] ?? 'EQ')
  const sameType = previousType !== null && previousType === type
  const value = sameType
    ? coerceValue(condition.value, operator, type, allowed)
    : defaultValueForOperator(operator, type)
  return { field, operator, value }
}

/** Applies a new operator, reshaping the value to the operator's arity. */
export function applyOperatorChange(
  condition: RuleCondition,
  operator: RuleOperator,
  catalog: Catalog,
): RuleCondition {
  const entry = findCatalogEntry(catalog, condition.field)
  return {
    field: condition.field,
    operator,
    value: coerceValue(condition.value, operator, entry?.type ?? null, catalogEnumValues(entry)),
  }
}

/* -------------------------------------------------------------------------- */
/* Serialisation — this is exactly what is persisted                           */
/* -------------------------------------------------------------------------- */

/**
 * Canonical `threshold_logic` payload: group nodes keep `{op, conditions}`,
 * leaves keep `{field, operator, value}` and drop `value` entirely for the
 * no-argument operators (`IS_NULL` / `NOT_NULL`).
 */
export function serializeRuleNode(node: RuleNode): RuleNode {
  if (isRuleGroup(node)) {
    return { op: node.op, conditions: node.conditions.map(serializeRuleNode) }
  }
  if (operatorArity(node.operator) === 'none') {
    return { field: node.field, operator: node.operator }
  }
  return {
    field: node.field,
    operator: node.operator,
    value: node.value === undefined ? null : node.value,
  }
}

export function serializedJson(node: RuleNode): string {
  return JSON.stringify(serializeRuleNode(node), null, 2)
}

/* -------------------------------------------------------------------------- */
/* Validation                                                                  */
/* -------------------------------------------------------------------------- */

export interface RuleIssues {
  /** Issue messages keyed by `pathKey(path)`. */
  byPath: Map<string, string[]>
  total: number
}

function pushIssue(map: Map<string, string[]>, path: RulePath, message: string): void {
  const key = pathKey(path)
  const bucket = map.get(key)
  if (bucket) bucket.push(message)
  else map.set(key, [message])
}

/** Catalog-aware checks layered on top of the shared structural validator. */
function collectCatalogIssues(
  node: RuleNode,
  catalog: Catalog,
  map: Map<string, string[]>,
  path: RulePath = [],
): void {
  if (isRuleGroup(node)) {
    node.conditions.forEach((child, index) => {
      collectCatalogIssues(child, catalog, map, [...path, index])
    })
    return
  }
  if (catalog.length === 0) return
  const entry = findCatalogEntry(catalog, node.field)
  if (!entry) {
    if (node.field.trim()) {
      pushIssue(map, path, `"${node.field}" is not in the field catalog`)
    }
    return
  }
  const allowed = catalogEnumValues(entry)
  const arity = operatorArity(node.operator)
  if (arity === 'none') return
  const values = Array.isArray(node.value) ? node.value : [node.value]
  for (const raw of values) {
    if (raw === undefined || raw === null || raw === '') continue
    if (entry.type === 'number' && coerceScalar(raw, 'number') === null) {
      pushIssue(map, path, `${fieldLabel(catalog, entry.field)} expects a number`)
      break
    }
    if (entry.type === 'enum' && allowed.length > 0 && !allowed.includes(String(raw))) {
      pushIssue(map, path, `"${String(raw)}" is not an allowed value for ${entry.field}`)
      break
    }
  }
}

/** All blocking issues for the tree, addressable per node path. */
export function collectIssues(node: RuleNode, catalog: Catalog): RuleIssues {
  const byPath = new Map<string, string[]>()
  for (const issue of validateRuleNode(node)) {
    pushIssue(byPath, issue.path, issue.message)
  }
  collectCatalogIssues(node, catalog, byPath)
  let total = 0
  for (const bucket of byPath.values()) total += bucket.length
  return { byPath, total }
}

export function issuesAt(issues: RuleIssues, path: RulePath): string[] {
  return issues.byPath.get(pathKey(path)) ?? []
}

/* -------------------------------------------------------------------------- */
/* Plain-English summary (rule table + editor header)                          */
/* -------------------------------------------------------------------------- */

function describeScalar(value: RuleScalar | null | undefined, type: FieldType | null): string {
  if (value === null || value === undefined) return '(none)'
  if (typeof value === 'boolean') return value ? 'true' : 'false'
  if (typeof value === 'number') return formatNumber(value, { maximumFractionDigits: 2 })
  if (value === '') return '(empty)'
  if (type === 'number') {
    const parsed = Number(value)
    if (Number.isFinite(parsed)) return formatNumber(parsed, { maximumFractionDigits: 2 })
  }
  return value
}

function describeList(values: readonly RuleScalar[], type: FieldType | null, max = 4): string {
  if (values.length === 0) return '(no values)'
  const shown = values.slice(0, max).map((value) => describeScalar(value, type))
  const rest = values.length - shown.length
  return rest > 0 ? `${shown.join(', ')} +${rest} more` : shown.join(', ')
}

export function describeCondition(condition: RuleCondition, catalog: Catalog): string {
  const label = fieldLabel(catalog, condition.field)
  const type = fieldTypeOf(catalog, condition.field)
  const meta = RULE_OPERATOR_META[condition.operator]
  switch (meta.arity) {
    case 'none':
      return `${label} ${meta.label}`
    case 'many':
      return `${label} ${meta.label} ${describeList(
        Array.isArray(condition.value) ? condition.value : [],
        type,
      )}`
    case 'two': {
      const pair = Array.isArray(condition.value) ? condition.value : []
      return `${label} between ${describeScalar(pair[0], type)} and ${describeScalar(pair[1], type)}`
    }
    default:
      return `${label} ${meta.label} ${describeScalar(firstScalar(condition.value), type)}`
  }
}

/**
 * Readable summary of a whole tree, e.g.
 * `Amount greater than 10,000 AND (Receiver bank country is one of IR, KP OR …)`.
 */
export function describeRuleEnglish(node: RuleNode, catalog: Catalog, depth = 0): string {
  if (!isRuleGroup(node)) return describeCondition(node, catalog)
  if (node.conditions.length === 0) return 'no conditions'
  const parts = node.conditions.map((child) => describeRuleEnglish(child, catalog, depth + 1))
  if (node.op === 'NOT') {
    return `NOT (${parts.join(' AND ')})`
  }
  const joined = parts.join(node.op === 'AND' ? ' AND ' : ' OR ')
  return depth === 0 || node.conditions.length === 1 ? joined : `(${joined})`
}

/* -------------------------------------------------------------------------- */
/* Tree helpers used by the editor                                             */
/* -------------------------------------------------------------------------- */

export function cloneNode(node: RuleNode): RuleNode {
  if (isRuleGroup(node)) {
    return { op: node.op, conditions: node.conditions.map(cloneNode) }
  }
  return {
    field: node.field,
    operator: node.operator,
    value: Array.isArray(node.value) ? [...node.value] : node.value,
  }
}

/** Inserts a copy of the node at `path` directly after it. */
export function duplicateNodeAt(root: RuleNode, path: RulePath): RuleNode {
  if (path.length === 0) return root
  const node = getNodeAt(root, path)
  if (!node) return root
  const parentPath = path.slice(0, -1)
  const index = path[path.length - 1]
  return updateNodeAt(root, parentPath, (parent) => {
    if (!isRuleGroup(parent)) return parent
    const conditions = [...parent.conditions]
    conditions.splice(index + 1, 0, cloneNode(node))
    return { ...parent, conditions }
  })
}

/** The builder always edits a group; a bare leaf is wrapped in an AND group. */
export function toRootGroup(node: RuleNode | null | undefined): RuleGroup {
  if (!node) return emptyRuleLogic()
  return isRuleGroup(node) ? node : createGroup('AND', [node])
}

/** Keeps the root a group after an edit, falling back to the previous tree. */
export function asRootGroup(node: RuleNode | null | undefined, fallback: RuleGroup): RuleGroup {
  return node && isRuleGroup(node) ? node : fallback
}

/**
 * A fresh condition seeded from the catalog (`amount` when available) so a new
 * row is always a real, evaluable field rather than a hardcoded guess.
 */
export function newCondition(catalog: Catalog): RuleCondition {
  const entry = findCatalogEntry(catalog, 'amount') ?? catalog[0]
  if (!entry) return createCondition()
  const operator = defaultOperatorForType(entry.type)
  return { field: entry.field, operator, value: defaultValueForOperator(operator, entry.type) }
}

export function newGroup(catalog: Catalog): RuleGroup {
  return createGroup('AND', [newCondition(catalog)])
}

/* -------------------------------------------------------------------------- */
/* datetime conversion for the value inputs                                    */
/* -------------------------------------------------------------------------- */

function pad(value: number): string {
  return String(value).padStart(2, '0')
}

/** ISO instant -> `YYYY-MM-DDTHH:mm` in the operator's local time zone. */
export function isoToLocalInput(value: RuleScalar | null | undefined): string {
  if (typeof value !== 'string' || value.trim() === '') return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
    `T${pad(date.getHours())}:${pad(date.getMinutes())}`
  )
}

/** Local `datetime-local` value -> ISO-8601 UTC, matching the API contract. */
export function localInputToIso(raw: string): string {
  if (!raw) return ''
  const date = new Date(raw)
  return Number.isNaN(date.getTime()) ? raw : date.toISOString()
}
