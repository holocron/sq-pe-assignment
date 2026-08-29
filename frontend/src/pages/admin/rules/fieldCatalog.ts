/**
 * Grouping and search for `GET /api/rules/field-catalog`.
 *
 * The catalog is no longer a set of operands for an expression builder — it is
 * the contract that tells an author which facts the agent's tools can actually
 * fetch. A condition that names something outside it is a condition the agent
 * has to invent an answer for, so the reference panel is what keeps prose rules
 * honest.
 */
import {
  FIELD_CATEGORIES,
  type FieldCatalogEntry,
  type FieldCategory,
  type FieldType,
  type RuleScope,
} from '../../../api/types'

interface FieldCategoryMeta {
  label: string
  description: string
}

const FIELD_CATEGORY_META: Record<FieldCategory, FieldCategoryMeta> = {
  transaction: {
    label: 'Transaction',
    description: 'On every transaction, whatever its activity type.',
  },
  customer: {
    label: 'Customer',
    description: 'Attributes of the account holder.',
  },
  card: {
    label: 'Card',
    description: 'Only present on card activity.',
  },
  payment: {
    label: 'Payment',
    description: 'Only present on payment activity.',
  },
  crypto: {
    label: 'Crypto',
    description: 'Only present on crypto activity.',
  },
  aggregate: {
    label: 'Aggregates',
    description: 'Rolling customer-level counters the agent can read directly.',
  },
}

export const FIELD_TYPE_LABELS: Record<FieldType, string> = {
  number: 'number',
  string: 'text',
  enum: 'one of',
  boolean: 'yes / no',
  datetime: 'timestamp',
  date: 'date',
}

export interface FieldGroup {
  category: FieldCategory
  label: string
  description: string
  entries: FieldCatalogEntry[]
}

/**
 * Mirrors the backend's `FieldDefinition.availableIn`: a field scoped to one
 * activity type is absent from the others, so a CARD-only field in a PAYMENT
 * rule is a condition the agent can never satisfy.
 */
export function isInScope(entry: FieldCatalogEntry, scope: RuleScope): boolean {
  return entry.appliesTo === 'ALL' || scope === 'ALL' || entry.appliesTo === scope
}

function matchesSearch(entry: FieldCatalogEntry, needle: string): boolean {
  if (needle.length === 0) return true
  const haystack = `${entry.field} ${entry.label} ${entry.description ?? ''} ${entry.options.join(' ')}`
  return haystack.toLowerCase().includes(needle)
}

/** Groups the catalog into the panel's fixed category order, search applied. */
export function groupCatalog(
  catalog: readonly FieldCatalogEntry[],
  search = '',
): FieldGroup[] {
  const needle = search.trim().toLowerCase()
  const groups: FieldGroup[] = []
  for (const category of FIELD_CATEGORIES) {
    const entries = catalog.filter(
      (entry) => entry.category === category && matchesSearch(entry, needle),
    )
    if (entries.length === 0) continue
    groups.push({
      category,
      label: FIELD_CATEGORY_META[category].label,
      description: FIELD_CATEGORY_META[category].description,
      entries,
    })
  }
  return groups
}

/** Categories opened by default: the always-available ones plus the scope's own. */
export function defaultOpenCategories(scope: RuleScope): FieldCategory[] {
  const base: FieldCategory[] = ['transaction', 'customer', 'aggregate']
  if (scope === 'ALL') return [...FIELD_CATEGORIES]
  const scoped = scope.toLowerCase() as FieldCategory
  return FIELD_CATEGORIES.includes(scoped) ? [...base, scoped] : base
}
