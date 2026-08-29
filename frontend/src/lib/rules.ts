import {
  RULE_OPERATORS,
  type FieldCatalogEntry,
  type FieldType,
  type RuleCondition,
  type RuleGroup,
  type RuleGroupOp,
  type RuleNode,
  type RuleOperator,
  type RuleValue,
} from '../api/types'

/* -------------------------------------------------------------------------- */
/* Narrowing                                                                   */
/* -------------------------------------------------------------------------- */

export function isRuleGroup(node: RuleNode): node is RuleGroup {
  return 'op' in node && Array.isArray((node as RuleGroup).conditions)
}

export function isRuleCondition(node: RuleNode): node is RuleCondition {
  return !isRuleGroup(node)
}

/* -------------------------------------------------------------------------- */
/* Operator metadata (drives the editor's operator dropdown and value input)   */
/* -------------------------------------------------------------------------- */

/** How many values the operator consumes. */
export type OperatorArity = 'none' | 'one' | 'two' | 'many'

export interface RuleOperatorMeta {
  operator: RuleOperator
  label: string
  /** Compact symbol for the JSON preview and rule summaries. */
  symbol: string
  arity: OperatorArity
  fieldTypes: readonly FieldType[]
}

const ALL_TYPES: readonly FieldType[] = [
  'number',
  'string',
  'enum',
  'boolean',
  'datetime',
  'date',
]

export const RULE_OPERATOR_META: Record<RuleOperator, RuleOperatorMeta> = {
  GT: { operator: 'GT', label: 'greater than', symbol: '>', arity: 'one', fieldTypes: ['number', 'datetime', 'date'] },
  GTE: { operator: 'GTE', label: 'greater than or equal', symbol: '>=', arity: 'one', fieldTypes: ['number', 'datetime', 'date'] },
  LT: { operator: 'LT', label: 'less than', symbol: '<', arity: 'one', fieldTypes: ['number', 'datetime', 'date'] },
  LTE: { operator: 'LTE', label: 'less than or equal', symbol: '<=', arity: 'one', fieldTypes: ['number', 'datetime', 'date'] },
  EQ: { operator: 'EQ', label: 'equals', symbol: '=', arity: 'one', fieldTypes: ALL_TYPES },
  NEQ: { operator: 'NEQ', label: 'not equal to', symbol: '!=', arity: 'one', fieldTypes: ALL_TYPES },
  IN: { operator: 'IN', label: 'is one of', symbol: 'in', arity: 'many', fieldTypes: ['number', 'string', 'enum'] },
  NOT_IN: { operator: 'NOT_IN', label: 'is not one of', symbol: 'not in', arity: 'many', fieldTypes: ['number', 'string', 'enum'] },
  CONTAINS: { operator: 'CONTAINS', label: 'contains', symbol: '~', arity: 'one', fieldTypes: ['string', 'enum'] },
  NOT_CONTAINS: { operator: 'NOT_CONTAINS', label: 'does not contain', symbol: '!~', arity: 'one', fieldTypes: ['string', 'enum'] },
  BETWEEN: { operator: 'BETWEEN', label: 'between', symbol: 'between', arity: 'two', fieldTypes: ['number', 'datetime', 'date'] },
  IS_NULL: { operator: 'IS_NULL', label: 'is empty', symbol: 'is null', arity: 'none', fieldTypes: ALL_TYPES },
  NOT_NULL: { operator: 'NOT_NULL', label: 'is not empty', symbol: 'is not null', arity: 'none', fieldTypes: ALL_TYPES },
  MATCHES: { operator: 'MATCHES', label: 'matches regex', symbol: 'matches', arity: 'one', fieldTypes: ['string', 'enum'] },
}

export function operatorsForFieldType(type: FieldType | null | undefined): RuleOperatorMeta[] {
  if (!type) return RULE_OPERATORS.map((operator) => RULE_OPERATOR_META[operator])
  return RULE_OPERATORS.map((operator) => RULE_OPERATOR_META[operator]).filter((meta) =>
    meta.fieldTypes.includes(type),
  )
}

export function operatorArity(operator: RuleOperator): OperatorArity {
  return RULE_OPERATOR_META[operator].arity
}

export const RULE_GROUP_OP_LABELS: Record<RuleGroupOp, string> = {
  AND: 'All of',
  OR: 'Any of',
  NOT: 'None of',
}

/* -------------------------------------------------------------------------- */
/* Field catalog helpers                                                       */
/* -------------------------------------------------------------------------- */

export function fieldCatalogLabel(entry: FieldCatalogEntry): string {
  return entry.label?.trim() || entry.field
}

/**
 * Allowed values for an `enum` field. The catalog may send them as `values`,
 * or only describe them in `notes` (`"Completed, Pending, Failed, Reversed"`).
 */
export function catalogEnumValues(entry: FieldCatalogEntry | null | undefined): string[] {
  if (!entry) return []
  if (entry.values && entry.values.length > 0) return entry.values
  if (entry.type !== 'enum' || !entry.notes) return []
  return entry.notes
    .split(',')
    .map((part) => part.trim())
    .filter((part) => part.length > 0 && part !== '...' && part !== '…')
}

/** Groups catalog entries by their prefix (`amount`, `card.*`, `agg.*`, ...). */
export function groupFieldCatalog(
  entries: readonly FieldCatalogEntry[],
): { group: string; entries: FieldCatalogEntry[] }[] {
  const groups = new Map<string, FieldCatalogEntry[]>()
  for (const entry of entries) {
    const explicit = entry.group?.trim()
    const derived = entry.field.includes('.') ? entry.field.split('.')[0] : 'transaction'
    const key = explicit && explicit.length > 0 ? explicit : derived
    const bucket = groups.get(key)
    if (bucket) bucket.push(entry)
    else groups.set(key, [entry])
  }
  return [...groups.entries()].map(([group, groupEntries]) => ({ group, entries: groupEntries }))
}

export function findCatalogEntry(
  entries: readonly FieldCatalogEntry[] | undefined,
  field: string,
): FieldCatalogEntry | undefined {
  return entries?.find((entry) => entry.field === field)
}

/* -------------------------------------------------------------------------- */
/* Node factories                                                              */
/* -------------------------------------------------------------------------- */

export function createCondition(field = 'amount', operator: RuleOperator = 'GT'): RuleCondition {
  return { field, operator, value: defaultValueForOperator(operator, 'number') }
}

export function createGroup(op: RuleGroupOp = 'AND', conditions: RuleNode[] = []): RuleGroup {
  return { op, conditions }
}

/** The empty document a brand-new rule starts from. */
export function emptyRuleLogic(): RuleGroup {
  return createGroup('AND', [createCondition()])
}

export function defaultValueForOperator(
  operator: RuleOperator,
  type: FieldType | null | undefined,
): RuleValue {
  switch (operatorArity(operator)) {
    case 'none':
      return null
    case 'many':
      return []
    case 'two':
      return type === 'number' ? [0, 0] : ['', '']
    case 'one':
    default:
      if (type === 'number') return 0
      if (type === 'boolean') return true
      return ''
  }
}

/* -------------------------------------------------------------------------- */
/* Immutable tree editing — paths are arrays of `conditions` indices           */
/* -------------------------------------------------------------------------- */

export type RulePath = readonly number[]

export function getNodeAt(root: RuleNode, path: RulePath): RuleNode | null {
  let current: RuleNode = root
  for (const index of path) {
    if (!isRuleGroup(current)) return null
    const next = current.conditions[index]
    if (!next) return null
    current = next
  }
  return current
}

/** Returns a new tree with the node at `path` replaced by `next` (null removes it). */
export function replaceNodeAt(
  root: RuleNode,
  path: RulePath,
  next: RuleNode | null,
): RuleNode | null {
  if (path.length === 0) return next
  if (!isRuleGroup(root)) return root
  const [head, ...rest] = path
  const index = head ?? -1
  const child = root.conditions[index]
  if (!child) return root
  const replacement = rest.length === 0 ? next : replaceNodeAt(child, rest, next)
  const conditions =
    replacement === null
      ? root.conditions.filter((_, i) => i !== index)
      : root.conditions.map((node, i) => (i === index ? replacement : node))
  return { ...root, conditions }
}

export function updateNodeAt(
  root: RuleNode,
  path: RulePath,
  updater: (node: RuleNode) => RuleNode,
): RuleNode {
  const target = getNodeAt(root, path)
  if (!target) return root
  return replaceNodeAt(root, path, updater(target)) ?? root
}

export function removeNodeAt(root: RuleNode, path: RulePath): RuleNode {
  if (path.length === 0) return root
  return replaceNodeAt(root, path, null) ?? emptyRuleLogic()
}

/** Appends a child to the group at `path`. No-op when the target is a leaf. */
export function appendNodeAt(root: RuleNode, path: RulePath, node: RuleNode): RuleNode {
  return updateNodeAt(root, path, (target) =>
    isRuleGroup(target) ? { ...target, conditions: [...target.conditions, node] } : target,
  )
}

export function countConditions(node: RuleNode): number {
  return isRuleGroup(node)
    ? node.conditions.reduce((total, child) => total + countConditions(child), 0)
    : 1
}

export function ruleDepth(node: RuleNode): number {
  return isRuleGroup(node)
    ? 1 + node.conditions.reduce((max, child) => Math.max(max, ruleDepth(child)), 0)
    : 0
}

/* -------------------------------------------------------------------------- */
/* Parsing and validation                                                      */
/* -------------------------------------------------------------------------- */

function isOperator(value: unknown): value is RuleOperator {
  return typeof value === 'string' && (RULE_OPERATORS as readonly string[]).includes(value)
}

function isGroupOp(value: unknown): value is RuleGroupOp {
  return value === 'AND' || value === 'OR' || value === 'NOT'
}

/** Structurally validates unknown JSON as a `RuleNode`; returns null if invalid. */
export function parseRuleNode(value: unknown): RuleNode | null {
  if (typeof value === 'string') {
    const trimmed = value.trim()
    if (trimmed.length === 0) return null
    try {
      return parseRuleNode(JSON.parse(trimmed) as unknown)
    } catch {
      return null
    }
  }
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return null
  const record = value as Record<string, unknown>
  if ('op' in record) {
    if (!isGroupOp(record.op) || !Array.isArray(record.conditions)) return null
    const conditions: RuleNode[] = []
    for (const child of record.conditions) {
      const parsed = parseRuleNode(child)
      if (!parsed) return null
      conditions.push(parsed)
    }
    return { op: record.op, conditions }
  }
  if (typeof record.field !== 'string' || !isOperator(record.operator)) return null
  const condition: RuleCondition = { field: record.field, operator: record.operator }
  if (record.value !== undefined) condition.value = record.value as RuleValue
  return condition
}

export interface RuleValidationIssue {
  path: RulePath
  message: string
}

/** Editor-side validation. An empty array means the tree is submittable. */
export function validateRuleNode(node: RuleNode, path: RulePath = []): RuleValidationIssue[] {
  const issues: RuleValidationIssue[] = []
  if (isRuleGroup(node)) {
    if (node.conditions.length === 0) {
      issues.push({ path, message: `${RULE_GROUP_OP_LABELS[node.op]} group has no conditions` })
    }
    if (node.op === 'NOT' && node.conditions.length > 1) {
      issues.push({ path, message: 'A NOT group should contain exactly one condition' })
    }
    node.conditions.forEach((child, index) => {
      issues.push(...validateRuleNode(child, [...path, index]))
    })
    return issues
  }
  if (!node.field.trim()) {
    issues.push({ path, message: 'Select a field' })
  }
  const arity = operatorArity(node.operator)
  const value = node.value
  if (arity === 'many') {
    if (!Array.isArray(value) || value.length === 0) {
      issues.push({ path, message: `${node.field}: provide at least one value` })
    }
  } else if (arity === 'two') {
    if (!Array.isArray(value) || value.length !== 2) {
      issues.push({ path, message: `${node.field}: BETWEEN needs exactly two values` })
    }
  } else if (arity === 'one') {
    if (value === undefined || value === null || value === '') {
      issues.push({ path, message: `${node.field}: provide a value` })
    }
    if (node.operator === 'MATCHES' && typeof value === 'string') {
      try {
        new RegExp(value)
      } catch {
        issues.push({ path, message: `${node.field}: invalid regular expression` })
      }
    }
  }
  return issues
}

/* -------------------------------------------------------------------------- */
/* Human-readable rendering                                                    */
/* -------------------------------------------------------------------------- */

function formatRuleValue(value: RuleValue | undefined): string {
  if (value === undefined || value === null) return ''
  if (Array.isArray(value)) return `[${value.map((item) => String(item)).join(', ')}]`
  return typeof value === 'string' ? `"${value}"` : String(value)
}

export function describeRuleCondition(condition: RuleCondition): string {
  const meta = RULE_OPERATOR_META[condition.operator]
  const value = formatRuleValue(condition.value)
  return value ? `${condition.field} ${meta.symbol} ${value}` : `${condition.field} ${meta.symbol}`
}

/** One-line summary for rule lists, e.g. `amount > 10000 AND (…)`. */
export function describeRuleNode(node: RuleNode, depth = 0): string {
  if (isRuleCondition(node)) return describeRuleCondition(node)
  if (node.conditions.length === 0) return '(empty)'
  const joined = node.conditions
    .map((child) => describeRuleNode(child, depth + 1))
    .join(node.op === 'NOT' ? ' AND ' : ` ${node.op} `)
  const body = node.op === 'NOT' ? `NOT (${joined})` : joined
  return depth === 0 || node.conditions.length === 1 ? body : `(${body})`
}

export function ruleLogicToJson(node: RuleNode): string {
  return JSON.stringify(node, null, 2)
}
