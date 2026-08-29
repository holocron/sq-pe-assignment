import { describe, expect, it } from 'vitest'
import type { RuleGroup, RuleNode } from '../api/types'
import {
  appendNodeAt,
  catalogEnumValues,
  createCondition,
  describeRuleNode,
  getNodeAt,
  isRuleGroup,
  operatorsForFieldType,
  parseRuleNode,
  removeNodeAt,
  updateNodeAt,
  validateRuleNode,
} from './rules'

const SPEC_EXAMPLE = {
  op: 'AND',
  conditions: [
    { field: 'amount', operator: 'GT', value: 10000 },
    {
      op: 'OR',
      conditions: [
        {
          field: 'payment.receiver_bank_country',
          operator: 'IN',
          value: ['IR', 'KP', 'SY', 'RU', 'AF'],
        },
        { field: 'customer.country', operator: 'NEQ', value: 'US' },
      ],
    },
  ],
}

describe('parseRuleNode', () => {
  it('parses the DSL example from the spec', () => {
    const node = parseRuleNode(SPEC_EXAMPLE)
    expect(node).not.toBeNull()
    expect(isRuleGroup(node as RuleNode)).toBe(true)
    const group = node as RuleGroup
    expect(group.op).toBe('AND')
    expect(group.conditions).toHaveLength(2)
  })

  it('accepts a JSON string, because threshold_logic is a TEXT column', () => {
    expect(parseRuleNode(JSON.stringify(SPEC_EXAMPLE))).not.toBeNull()
  })

  it('rejects malformed nodes', () => {
    expect(parseRuleNode({ op: 'XOR', conditions: [] })).toBeNull()
    expect(parseRuleNode({ field: 'amount', operator: 'NOPE' })).toBeNull()
    expect(parseRuleNode('not json')).toBeNull()
    expect(parseRuleNode(null)).toBeNull()
  })
})

describe('tree editing', () => {
  const root = parseRuleNode(SPEC_EXAMPLE) as RuleNode

  it('reads a nested node by path', () => {
    const nested = getNodeAt(root, [1, 0])
    expect(nested).toMatchObject({ field: 'payment.receiver_bank_country', operator: 'IN' })
  })

  it('updates immutably', () => {
    const updated = updateNodeAt(root, [0], () => createCondition('amount', 'LT'))
    expect(getNodeAt(updated, [0])).toMatchObject({ operator: 'LT' })
    expect(getNodeAt(root, [0])).toMatchObject({ operator: 'GT' })
  })

  it('appends into a nested group', () => {
    const appended = appendNodeAt(root, [1], createCondition('currency', 'EQ'))
    expect((getNodeAt(appended, [1]) as RuleGroup).conditions).toHaveLength(3)
  })

  it('removes by path', () => {
    const removed = removeNodeAt(root, [0])
    expect((removed as RuleGroup).conditions).toHaveLength(1)
  })
})

describe('validateRuleNode', () => {
  it('flags empty groups and missing values', () => {
    const issues = validateRuleNode({
      op: 'AND',
      conditions: [
        { field: 'amount', operator: 'GT' },
        { op: 'OR', conditions: [] },
      ],
    })
    expect(issues).toHaveLength(2)
  })

  it('accepts a complete tree', () => {
    expect(validateRuleNode(parseRuleNode(SPEC_EXAMPLE) as RuleNode)).toEqual([])
  })

  it('rejects an invalid regex for MATCHES', () => {
    const issues = validateRuleNode({ field: 'card.merchant_name', operator: 'MATCHES', value: '([' })
    expect(issues).toHaveLength(1)
  })
})

describe('operator filtering', () => {
  it('offers only sensible operators per field type', () => {
    const booleanOps = operatorsForFieldType('boolean').map((meta) => meta.operator)
    expect(booleanOps).toContain('EQ')
    expect(booleanOps).not.toContain('GT')

    const numberOps = operatorsForFieldType('number').map((meta) => meta.operator)
    expect(numberOps).toContain('BETWEEN')
    expect(numberOps).not.toContain('MATCHES')
  })
})

describe('catalogEnumValues', () => {
  it('falls back to parsing the notes column', () => {
    expect(
      catalogEnumValues({
        field: 'status',
        type: 'enum',
        notes: 'Completed, Pending, Failed, Reversed',
      }),
    ).toEqual(['Completed', 'Pending', 'Failed', 'Reversed'])
  })
})

describe('describeRuleNode', () => {
  it('renders a readable one-liner', () => {
    expect(describeRuleNode(parseRuleNode(SPEC_EXAMPLE) as RuleNode)).toBe(
      'amount > 10000 AND (payment.receiver_bank_country in [IR, KP, SY, RU, AF] OR customer.country != "US")',
    )
  })
})
