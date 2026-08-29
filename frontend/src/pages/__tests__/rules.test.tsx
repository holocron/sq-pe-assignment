import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { deleteJson, getJson, postJson } from '../../api/client'
import type { FieldCatalogEntry, RiskRuleWire } from '../../api/types'
import { ToastProvider } from '../../components/ui/Toast'
import { RulesPage } from '../admin/RulesPage'

vi.mock('../../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/client')>()
  return {
    ...actual,
    getJson: vi.fn(),
    postJson: vi.fn(),
    putJson: vi.fn(),
    deleteJson: vi.fn(),
  }
})

const mockGet = vi.mocked(getJson)
const mockPost = vi.mocked(postJson)
const mockDelete = vi.mocked(deleteJson)

/** Subset of the catalog served by `GET /api/rules/field-catalog` (spec §3). */
const CATALOG: FieldCatalogEntry[] = [
  { field: 'amount', type: 'number' },
  { field: 'currency', type: 'string' },
  { field: 'status', type: 'enum', values: ['Completed', 'Pending', 'Failed', 'Reversed'] },
  { field: 'created_at', type: 'datetime' },
  { field: 'customer.country', type: 'string' },
  { field: 'card.card_present', type: 'boolean' },
  { field: 'card.decline_reason', type: 'string' },
  { field: 'payment.receiver_bank_country', type: 'string' },
  { field: 'agg.tx_count_24h', type: 'number' },
]

const EXISTING_RULE: RiskRuleWire = {
  ruleId: '11111111-2222-3333-4444-555555555555',
  ruleName: 'High-value SWIFT to sanctioned country',
  appliesTo: 'PAYMENT',
  // TEXT column: the backend may hand the DSL over as a JSON string.
  thresholdLogic: JSON.stringify({
    op: 'AND',
    conditions: [
      { field: 'amount', operator: 'GT', value: 10000 },
      { field: 'payment.receiver_bank_country', operator: 'IN', value: ['IR', 'KP'] },
    ],
  }),
  weight: '25.00',
}

function mockApi(rules: RiskRuleWire[]): void {
  mockGet.mockImplementation((url: string) => {
    if (url === '/rules') return Promise.resolve(rules) as Promise<never>
    if (url === '/rules/field-catalog') return Promise.resolve(CATALOG) as Promise<never>
    return Promise.reject(new Error(`unexpected GET ${url}`)) as Promise<never>
  })
}

function renderRulesPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <RulesPage />
      </ToastProvider>
    </QueryClientProvider>,
  )
}

async function openNewRuleEditor(): Promise<HTMLElement> {
  fireEvent.click(await screen.findByRole('button', { name: 'New rule' }))
  return screen.findByRole('dialog')
}

function jsonPreview(): unknown {
  return JSON.parse(screen.getByTestId('rule-json').textContent ?? 'null')
}

beforeEach(() => {
  mockApi([])
  mockPost.mockReset()
  mockDelete.mockReset()
})

describe('RulesPage', () => {
  it('renders the empty state when there are no rules', async () => {
    renderRulesPage()
    expect(await screen.findByText('No risk rules yet')).toBeInTheDocument()
  })

  it('lists rules with a plain-English summary of the condition', async () => {
    mockApi([EXISTING_RULE])
    renderRulesPage()

    expect(await screen.findByText('High-value SWIFT to sanctioned country')).toBeInTheDocument()
    expect(screen.getByText(/Amount greater than/)).toBeInTheDocument()
    expect(screen.getByText(/Receiver bank country is one of IR, KP/)).toBeInTheDocument()
    expect(screen.getByText('2 conditions · 1 level deep')).toBeInTheDocument()
  })

  it('surfaces a failed rule fetch with a retry affordance', async () => {
    mockGet.mockImplementation((url: string) => {
      if (url === '/rules/field-catalog') return Promise.resolve(CATALOG) as Promise<never>
      return Promise.reject(new Error('boom')) as Promise<never>
    })
    renderRulesPage()
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument()
  })

  it('deletes a rule only after the confirmation is accepted', async () => {
    mockApi([EXISTING_RULE])
    mockDelete.mockResolvedValue(undefined)
    renderRulesPage()

    fireEvent.click(
      await screen.findByRole('button', { name: 'Delete High-value SWIFT to sanctioned country' }),
    )
    expect(await screen.findByRole('dialog')).toBeInTheDocument()
    expect(mockDelete).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', { name: 'Delete rule' }))
    await waitFor(() => {
      expect(mockDelete).toHaveBeenCalledWith(`/rules/${EXISTING_RULE.ruleId}`)
    })
  })
})

describe('visual condition builder', () => {
  it('builds a nested AND/OR rule and emits the exact threshold_logic DSL', async () => {
    renderRulesPage()
    await openNewRuleEditor()

    fireEvent.change(screen.getByLabelText(/Rule name/), {
      target: { value: 'High-value cross-border payment' },
    })
    fireEvent.change(screen.getByLabelText('Applies to'), { target: { value: 'PAYMENT' } })
    fireEvent.change(screen.getByLabelText('Weight value'), { target: { value: '30' } })

    // Root condition: amount >= 10000
    const first = screen.getByRole('group', { name: 'Condition 1' })
    fireEvent.change(within(first).getByLabelText('Operator'), { target: { value: 'GTE' } })
    fireEvent.change(within(first).getByLabelText('Value'), { target: { value: '10000' } })

    // Nested group, switched from AND to OR
    fireEvent.click(screen.getByRole('button', { name: 'Add nested group to Root group' }))
    const nested = await screen.findByRole('group', { name: 'Group 2' })
    fireEvent.click(within(nested).getByRole('button', { name: 'Set Group 2 to Any of (OR)' }))

    // Nested condition 1: receiver bank country IN [IR, KP]
    const nestedFirst = screen.getByRole('group', { name: 'Condition 2.1' })
    fireEvent.change(within(nestedFirst).getByLabelText('Field'), {
      target: { value: 'payment.receiver_bank_country' },
    })
    // Switching a number field to a string field resets the invalid operator...
    expect(within(nestedFirst).getByLabelText('Operator')).toHaveValue('EQ')
    // ...and the incomplete row blocks saving.
    expect(screen.getByRole('button', { name: 'Create rule' })).toBeDisabled()

    fireEvent.change(within(nestedFirst).getByLabelText('Operator'), { target: { value: 'IN' } })
    const chipInput = within(nestedFirst).getByLabelText('Values')
    fireEvent.change(chipInput, { target: { value: 'IR' } })
    fireEvent.click(within(nestedFirst).getByRole('button', { name: 'Add value' }))
    fireEvent.change(chipInput, { target: { value: 'KP' } })
    fireEvent.click(within(nestedFirst).getByRole('button', { name: 'Add value' }))

    // Nested condition 2: customer.country != US
    fireEvent.click(within(nested).getByRole('button', { name: 'Add condition to Group 2' }))
    const nestedSecond = await screen.findByRole('group', { name: 'Condition 2.2' })
    fireEvent.change(within(nestedSecond).getByLabelText('Field'), {
      target: { value: 'customer.country' },
    })
    fireEvent.change(within(nestedSecond).getByLabelText('Operator'), { target: { value: 'NEQ' } })
    fireEvent.change(within(nestedSecond).getByLabelText('Value'), { target: { value: 'US' } })

    const expected = {
      op: 'AND',
      conditions: [
        { field: 'amount', operator: 'GTE', value: 10000 },
        {
          op: 'OR',
          conditions: [
            { field: 'payment.receiver_bank_country', operator: 'IN', value: ['IR', 'KP'] },
            { field: 'customer.country', operator: 'NEQ', value: 'US' },
          ],
        },
      ],
    }

    // The preview is byte-for-byte what gets persisted, key order included.
    expect(jsonPreview()).toEqual(expected)
    expect(screen.getByTestId('rule-json').textContent).toBe(JSON.stringify(expected, null, 2))

    mockPost.mockResolvedValue({
      ruleId: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
      ruleName: 'High-value cross-border payment',
      appliesTo: 'PAYMENT',
      thresholdLogic: expected,
      weight: 30,
    } as never)

    const save = screen.getByRole('button', { name: 'Create rule' })
    expect(save).toBeEnabled()
    fireEvent.click(save)

    await waitFor(() => {
      expect(mockPost).toHaveBeenCalledWith('/rules', {
        ruleName: 'High-value cross-border payment',
        appliesTo: 'PAYMENT',
        weight: 30,
        thresholdLogic: expected,
      })
    })
  })

  it('filters operators and swaps the value control to match the field type', async () => {
    renderRulesPage()
    await openNewRuleEditor()

    const row = screen.getByRole('group', { name: 'Condition 1' })
    const operator = within(row).getByLabelText('Operator')
    expect(within(operator).getByRole('option', { name: 'greater than' })).toBeInTheDocument()
    expect(within(operator).queryByRole('option', { name: 'contains' })).not.toBeInTheDocument()

    fireEvent.change(within(row).getByLabelText('Field'), {
      target: { value: 'card.card_present' },
    })

    // `GT` is not valid for a boolean, so the operator falls back to `EQ`
    // and the value control becomes a checkbox.
    expect(within(row).getByLabelText('Operator')).toHaveValue('EQ')
    expect(within(row).queryByRole('option', { name: 'greater than' })).not.toBeInTheDocument()

    const checkbox = within(row).getByLabelText('Value is true')
    expect(checkbox).toBeChecked()
    expect(jsonPreview()).toEqual({
      op: 'AND',
      conditions: [{ field: 'card.card_present', operator: 'EQ', value: true }],
    })

    fireEvent.click(checkbox)
    expect(jsonPreview()).toEqual({
      op: 'AND',
      conditions: [{ field: 'card.card_present', operator: 'EQ', value: false }],
    })
  })

  it('offers the allowed values of an enum field', async () => {
    renderRulesPage()
    await openNewRuleEditor()

    const row = screen.getByRole('group', { name: 'Condition 1' })
    fireEvent.change(within(row).getByLabelText('Field'), { target: { value: 'status' } })
    const value = within(row).getByLabelText('Value')
    expect(within(value).getByRole('option', { name: 'Reversed' })).toBeInTheDocument()

    fireEvent.change(value, { target: { value: 'Failed' } })
    expect(jsonPreview()).toEqual({
      op: 'AND',
      conditions: [{ field: 'status', operator: 'EQ', value: 'Failed' }],
    })
  })

  it('omits the value key entirely for the no-argument operators', async () => {
    renderRulesPage()
    await openNewRuleEditor()

    const row = screen.getByRole('group', { name: 'Condition 1' })
    fireEvent.change(within(row).getByLabelText('Field'), {
      target: { value: 'card.decline_reason' },
    })
    fireEvent.change(within(row).getByLabelText('Operator'), { target: { value: 'NOT_NULL' } })

    expect(within(row).getByText('No value needed')).toBeInTheDocument()
    expect(screen.getByTestId('rule-json').textContent).toBe(
      JSON.stringify(
        { op: 'AND', conditions: [{ field: 'card.decline_reason', operator: 'NOT_NULL' }] },
        null,
        2,
      ),
    )
  })

  it('renders two inputs for BETWEEN and emits a two-element array', async () => {
    renderRulesPage()
    await openNewRuleEditor()

    const row = screen.getByRole('group', { name: 'Condition 1' })
    fireEvent.change(within(row).getByLabelText('Operator'), { target: { value: 'BETWEEN' } })
    fireEvent.change(within(row).getByLabelText('Minimum'), { target: { value: '5000' } })
    fireEvent.change(within(row).getByLabelText('Maximum'), { target: { value: '9999.99' } })

    expect(jsonPreview()).toEqual({
      op: 'AND',
      conditions: [{ field: 'amount', operator: 'BETWEEN', value: [5000, 9999.99] }],
    })
  })

  it('removes a nested group again', async () => {
    renderRulesPage()
    await openNewRuleEditor()

    fireEvent.click(screen.getByRole('button', { name: 'Add nested group to Root group' }))
    expect(await screen.findByRole('group', { name: 'Group 2' })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Remove Group 2' }))
    await waitFor(() => {
      expect(screen.queryByRole('group', { name: 'Group 2' })).not.toBeInTheDocument()
    })
    expect(jsonPreview()).toEqual({
      op: 'AND',
      conditions: [{ field: 'amount', operator: 'GT', value: 0 }],
    })
  })
})

describe('rule tester', () => {
  it('posts the built logic to /api/rules/test and reports matches', async () => {
    renderRulesPage()
    await openNewRuleEditor()

    mockPost.mockResolvedValue({
      matchedCount: 2,
      degraded: true,
      sampleMatches: [
        {
          transactionId: '99999999-8888-7777-6666-555555555555',
          activityType: 'PAYMENT',
          amount: 12500,
          currency: 'USD',
          status: 'Completed',
          createdAt: '2026-08-01T10:00:00Z',
        },
      ],
    } as never)

    const panel = screen.getByRole('region', { name: 'Rule test' })
    fireEvent.click(within(panel).getByRole('button', { name: 'Test rule' }))

    await waitFor(() => {
      expect(mockPost).toHaveBeenCalledWith('/rules/test', {
        thresholdLogic: {
          op: 'AND',
          conditions: [{ field: 'amount', operator: 'GT', value: 0 }],
        },
        appliesTo: 'ALL',
        customerId: null,
      })
    })

    expect(await within(panel).findByText('2')).toBeInTheDocument()
    expect(within(panel).getByText('Degraded evaluation')).toBeInTheDocument()
    expect(within(panel).getByText('99999999')).toBeInTheDocument()
  })
})
