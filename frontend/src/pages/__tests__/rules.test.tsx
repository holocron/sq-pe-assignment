import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { deleteJson, getJson, postJson } from '../../api/client'
import { ApiError } from '../../api/errors'
import type {
  CustomerSummary,
  FieldCatalogEntryWire,
  RiskRuleWire,
  SpringPage,
} from '../../api/types'
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

/* -------------------------------------------------------------------------- */
/* Fixtures                                                                    */
/* -------------------------------------------------------------------------- */

/**
 * `GET /api/rules/field-catalog` in the shape the backend serialises: `type` is
 * the Java enum name in UPPER CASE and `category` is the reference panel's
 * grouping. `agg.tx_count_24h` deliberately omits `category` — the panel has to
 * derive it from the field path, because a catalog entry that lands in the
 * wrong group is a field an author never finds.
 */
const CATALOG: FieldCatalogEntryWire[] = [
  {
    field: 'amount',
    label: 'Amount',
    type: 'NUMBER',
    category: 'transaction',
    appliesTo: 'ALL',
    options: [],
    nullable: false,
    example: '12500.00',
    description: 'Transaction amount in the transaction currency.',
  },
  {
    field: 'status',
    label: 'Status',
    type: 'ENUM',
    category: 'transaction',
    appliesTo: 'ALL',
    options: ['Completed', 'Pending', 'Failed', 'Reversed'],
    nullable: false,
    description: 'Processing outcome of the transaction.',
  },
  {
    field: 'customer.country',
    label: 'Customer country',
    type: 'STRING',
    category: 'customer',
    appliesTo: 'ALL',
    options: [],
    nullable: false,
    description: 'ISO 3166-1 alpha-2 country of the customer.',
  },
  {
    field: 'card.card_present',
    label: 'Card present',
    type: 'BOOLEAN',
    category: 'card',
    appliesTo: 'CARD',
    options: ['true', 'false'],
    nullable: false,
    description: 'False means a card-not-present authorisation.',
  },
  {
    field: 'payment.receiver_bank_country',
    label: 'Receiver bank country',
    type: 'STRING',
    category: 'payment',
    appliesTo: 'PAYMENT',
    options: [],
    nullable: false,
    description: 'ISO 3166-1 alpha-2 country of the beneficiary bank.',
  },
  {
    field: 'crypto.exchange_name',
    label: 'Exchange name',
    type: 'STRING',
    category: 'crypto',
    appliesTo: 'CRYPTO',
    options: [],
    nullable: true,
    description: 'Counterparty exchange, empty when unattributed.',
  },
  {
    field: 'agg.tx_count_24h',
    label: 'Transactions in the previous 24h',
    type: 'NUMBER',
    // No `category`: grouping falls back to the `agg.` prefix.
    appliesTo: 'ALL',
    options: [],
    nullable: false,
    description: 'Customer-level velocity relative to the transaction.',
  },
]

const LONG_CONDITION =
  'Triggered when a payment is sent to a beneficiary bank in a sanctioned or high-risk jurisdiction — ' +
  'payment.receiver_bank_country in IR, KP, SY, RU, BY or AF. A single such payment triggers the rule ' +
  'regardless of amount, and the score should reach the full weight when the amount exceeds 10 000.'

const EXISTING_RULE: RiskRuleWire = {
  ruleId: '11111111-2222-3333-4444-555555555555',
  ruleName: 'Payment to a sanctioned jurisdiction',
  appliesTo: 'PAYMENT',
  thresholdLogic: LONG_CONDITION,
  // DECIMAL(5,2) may serialise as a string.
  weight: '35.00',
}

const CUSTOMER: CustomerSummary = {
  customerId: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
  firstName: 'Mila',
  lastName: 'Novak',
  country: 'CH',
}

const CUSTOMER_PAGE: SpringPage<CustomerSummary> = {
  content: [CUSTOMER],
  page: 0,
  size: 6,
  totalElements: 1,
  totalPages: 1,
}

function mockApi(rules: RiskRuleWire[]): void {
  mockGet.mockImplementation((url: string) => {
    if (url === '/rules') return Promise.resolve(rules) as Promise<never>
    if (url === '/rules/field-catalog') return Promise.resolve(CATALOG) as Promise<never>
    if (url === '/customers') return Promise.resolve(CUSTOMER_PAGE) as Promise<never>
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
  const dialog = await screen.findByRole('dialog')
  // The reference panel only renders once the catalog resolves.
  await screen.findByRole('button', { name: 'Insert amount into the condition' })
  return dialog
}

function conditionBox(): HTMLTextAreaElement {
  return screen.getByLabelText(/Rule condition/) as HTMLTextAreaElement
}

function typeCondition(text: string): void {
  fireEvent.change(conditionBox(), { target: { value: text } })
}

const VALID_CONDITION =
  'Triggered when the customer makes three or more payments between 8 000 and 9 999.99 within any rolling 24 hours.'

beforeEach(() => {
  mockApi([])
  mockPost.mockReset()
  mockDelete.mockReset()
})

/* -------------------------------------------------------------------------- */
/* Catalogue screen                                                            */
/* -------------------------------------------------------------------------- */

describe('RulesPage', () => {
  it('renders the empty state when there are no rules', async () => {
    renderRulesPage()
    expect(await screen.findByText('No risk rules yet')).toBeInTheDocument()
  })

  it('lists rules with a readable excerpt of the condition', async () => {
    mockApi([EXISTING_RULE])
    renderRulesPage()

    expect(await screen.findByText('Payment to a sanctioned jurisdiction')).toBeInTheDocument()
    const excerpt = screen.getByText(/Triggered when a payment is sent to a beneficiary bank/)
    // Truncated for the cell, but the full prose stays available on hover.
    expect(excerpt).toHaveAttribute('title', LONG_CONDITION)
    expect(excerpt.textContent?.length).toBeLessThan(LONG_CONDITION.length)
    // Scoped to the row: the same figure is also the catalogue's combined weight.
    const row = excerpt.closest('tr') as HTMLElement
    expect(within(row).getByText('35.00')).toBeInTheDocument()
    expect(within(row).getByText('PAYMENT')).toBeInTheDocument()
  })

  it('calls out a rule that has no condition at all', async () => {
    mockApi([{ ...EXISTING_RULE, thresholdLogic: '   ' }])
    renderRulesPage()
    expect(
      await screen.findByText('No condition stored — the agent has nothing to judge.'),
    ).toBeInTheDocument()
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
      await screen.findByRole('button', { name: 'Delete Payment to a sanctioned jurisdiction' }),
    )
    const dialog = await screen.findByRole('dialog')
    expect(mockDelete).not.toHaveBeenCalled()

    /* DELETE cascades to risk_assessments, so the admin is destroying the
       recorded evidence of every past analysis, not only changing future ones.
       The dialog has to say that out loud. */
    expect(dialog).toHaveTextContent(/past analyses/)
    expect(dialog).toHaveTextContent(/removed from the audit table/)

    fireEvent.click(screen.getByRole('button', { name: 'Delete rule' }))
    await waitFor(() => {
      expect(mockDelete).toHaveBeenCalledWith(`/rules/${EXISTING_RULE.ruleId}`)
    })
  })
})

/* -------------------------------------------------------------------------- */
/* Condition editor                                                            */
/* -------------------------------------------------------------------------- */

describe('condition editor', () => {
  it('saves the condition as the prose that was typed', async () => {
    renderRulesPage()
    await openNewRuleEditor()

    fireEvent.change(screen.getByLabelText(/Rule name/), {
      target: { value: 'Structuring below the reporting threshold' },
    })
    fireEvent.change(screen.getByLabelText('Applies to'), { target: { value: 'PAYMENT' } })
    fireEvent.change(screen.getByLabelText('Weight value'), { target: { value: '30' } })
    typeCondition(`  ${VALID_CONDITION}  `)

    mockPost.mockResolvedValue({
      ruleId: 'bbbbbbbb-cccc-dddd-eeee-ffffffffffff',
      ruleName: 'Structuring below the reporting threshold',
      appliesTo: 'PAYMENT',
      thresholdLogic: VALID_CONDITION,
      weight: 30,
    } as never)

    const save = screen.getByRole('button', { name: 'Create rule' })
    expect(save).toBeEnabled()
    fireEvent.click(save)

    await waitFor(() => {
      expect(mockPost).toHaveBeenCalledWith('/rules', {
        ruleName: 'Structuring below the reporting threshold',
        appliesTo: 'PAYMENT',
        weight: 30,
        thresholdLogic: VALID_CONDITION,
      })
    })
  })

  it('counts characters against the server limit', async () => {
    renderRulesPage()
    await openNewRuleEditor()

    expect(screen.getByText('0 / 2000 characters')).toBeInTheDocument()
    typeCondition(VALID_CONDITION)
    expect(
      screen.getByText(`${VALID_CONDITION.length} / 2000 characters`),
    ).toBeInTheDocument()

    typeCondition('x'.repeat(2001))
    expect(screen.getByText('2001 / 2000 characters')).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('1 character(s) over the 2000 limit.')
    expect(screen.getByRole('button', { name: 'Create rule' })).toBeDisabled()
  })

  it('refuses a condition too short for the agent to judge', async () => {
    renderRulesPage()
    await openNewRuleEditor()

    fireEvent.change(screen.getByLabelText(/Rule name/), { target: { value: 'Too short' } })
    typeCondition('big payments')

    // Inline on the field, and repeated as the footer's first blocker.
    expect(screen.getByRole('alert')).toHaveTextContent(
      'Too short to judge — write at least 20 characters.',
    )
    expect(screen.getByRole('button', { name: 'Create rule' })).toBeDisabled()
  })

  /* The old editor stored a JSON DSL. Pasting one in now would be persisted
     verbatim and fed to the model as if it were prose, so it is rejected with
     an explanation rather than silently accepted. */
  it('rejects a pasted JSON rule from the old DSL', async () => {
    renderRulesPage()
    await openNewRuleEditor()

    fireEvent.change(screen.getByLabelText(/Rule name/), { target: { value: 'Pasted DSL' } })
    typeCondition(
      JSON.stringify({
        op: 'AND',
        conditions: [{ field: 'amount', operator: 'GT', value: 10000 }],
      }),
    )

    expect(screen.getByRole('alert')).toHaveTextContent(
      'Conditions are plain English now, not JSON. Describe when the rule is triggered.',
    )
    expect(screen.getByRole('button', { name: 'Create rule' })).toBeDisabled()
  })

  it('coaches the author until the condition is judgeable', async () => {
    renderRulesPage()
    await openNewRuleEditor()

    const checklist = screen.getByText('A condition the agent can judge').closest('ul')
    expect(checklist).not.toBeNull()
    expect(within(checklist as HTMLElement).getAllByText(/not yet/)).toHaveLength(3)

    typeCondition(
      'Triggered when agg.tx_count_24h reaches 8 or more within any rolling 24 hours.',
    )
    expect(within(checklist as HTMLElement).queryByText(/not yet/)).not.toBeInTheDocument()
  })
})

/* -------------------------------------------------------------------------- */
/* Starter templates                                                           */
/* -------------------------------------------------------------------------- */

describe('starter templates', () => {
  it('fills a blank rule from an example', async () => {
    renderRulesPage()
    await openNewRuleEditor()

    fireEvent.click(screen.getByRole('button', { name: 'Use the Structuring example' }))

    expect(screen.getByLabelText(/Rule name/)).toHaveValue(
      'Structuring — repeated payments below the reporting threshold',
    )
    expect(screen.getByLabelText('Applies to')).toHaveValue('PAYMENT')
    expect(screen.getByLabelText('Weight value')).toHaveValue(30)
    expect(conditionBox().value).toContain('8 000 and 9 999.99')
    expect(screen.getByRole('button', { name: 'Create rule' })).toBeEnabled()
  })

  it('appends a second example instead of discarding what is written', async () => {
    renderRulesPage()
    await openNewRuleEditor()

    typeCondition(VALID_CONDITION)
    fireEvent.click(screen.getByRole('button', { name: 'Append the Velocity spike example' }))

    const text = conditionBox().value
    expect(text.startsWith(VALID_CONDITION)).toBe(true)
    expect(text).toContain('agg.tx_count_24h')
  })
})

/* -------------------------------------------------------------------------- */
/* Available-data reference                                                    */
/* -------------------------------------------------------------------------- */

describe('available data reference', () => {
  it('groups the catalog by category, deriving the group when the API omits it', async () => {
    renderRulesPage()
    await openNewRuleEditor()

    const panel = screen.getByRole('region', { name: 'Available data' })
    const groups = within(panel)
      .getAllByRole('button', { expanded: true })
      .map((button) => button.textContent)
    expect(groups.join(' ')).toMatch(/Transaction/)
    expect(groups.join(' ')).toMatch(/Aggregates/)

    // `agg.tx_count_24h` has no `category` on the wire; the `agg.` prefix decides.
    const aggregates = within(panel).getByRole('button', { name: /Aggregates/ })
    const list = aggregates.closest('li') as HTMLElement
    expect(
      within(list).getByRole('button', { name: 'Insert agg.tx_count_24h into the condition' }),
    ).toBeInTheDocument()
  })

  it('inserts a field name into the condition at the caret', async () => {
    renderRulesPage()
    await openNewRuleEditor()

    typeCondition('Triggered when')
    fireEvent.click(
      screen.getByRole('button', { name: 'Insert agg.tx_count_24h into the condition' }),
    )
    expect(conditionBox().value).toBe('Triggered when agg.tx_count_24h')
  })

  it('marks fields that do not exist on the rule scope', async () => {
    renderRulesPage()
    await openNewRuleEditor()

    fireEvent.change(screen.getByLabelText('Applies to'), { target: { value: 'CARD' } })
    const panel = screen.getByRole('region', { name: 'Available data' })
    const payment = within(panel).getByRole('button', {
      name: 'Insert payment.receiver_bank_country into the condition',
    })
    expect(within(payment).getByText('PAYMENT only')).toBeInTheDocument()

    const card = within(panel).getByRole('button', {
      name: 'Insert card.card_present into the condition',
    })
    expect(within(card).queryByText(/only/)).not.toBeInTheDocument()
  })

  it('filters the reference by search', async () => {
    renderRulesPage()
    await openNewRuleEditor()

    const panel = screen.getByRole('region', { name: 'Available data' })
    fireEvent.change(within(panel).getByLabelText('Search available data'), {
      target: { value: 'beneficiary' },
    })

    expect(
      within(panel).getByRole('button', {
        name: 'Insert payment.receiver_bank_country into the condition',
      }),
    ).toBeInTheDocument()
    expect(
      within(panel).queryByRole('button', { name: 'Insert amount into the condition' }),
    ).not.toBeInTheDocument()
  })

  /* The catalog is reference material now, not a grammar, so losing it must
     degrade the panel without preventing a rule from being written. */
  it('still allows saving when the field catalog is unavailable', async () => {
    mockGet.mockImplementation((url: string) => {
      if (url === '/rules') return Promise.resolve([]) as Promise<never>
      if (url === '/rules/field-catalog') {
        return Promise.reject(new ApiError({ status: 503, title: 'Backend unavailable' }))
      }
      return Promise.reject(new Error(`unexpected GET ${url}`)) as Promise<never>
    })
    renderRulesPage()

    fireEvent.click(await screen.findByRole('button', { name: 'New rule' }))
    await screen.findByRole('dialog')
    expect(await screen.findByText('Field reference unavailable')).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText(/Rule name/), { target: { value: 'Written blind' } })
    typeCondition(VALID_CONDITION)
    expect(screen.getByRole('button', { name: 'Create rule' })).toBeEnabled()
  })
})

/* -------------------------------------------------------------------------- */
/* Rule tester                                                                 */
/* -------------------------------------------------------------------------- */

async function pickCustomer(): Promise<HTMLElement> {
  const panel = screen.getByRole('region', { name: 'Rule test' })
  fireEvent.change(within(panel).getByLabelText('Customer to test against'), {
    target: { value: 'Mila' },
  })
  fireEvent.click(await within(panel).findByRole('button', { name: /Mila Novak/ }))
  return panel
}

describe('rule tester', () => {
  it('needs a customer before it will run', async () => {
    renderRulesPage()
    await openNewRuleEditor()
    typeCondition(VALID_CONDITION)

    const panel = screen.getByRole('region', { name: 'Rule test' })
    expect(within(panel).getByRole('button', { name: /Run the agent/ })).toBeDisabled()

    await pickCustomer()
    expect(within(panel).getByRole('button', { name: /Run the agent/ })).toBeEnabled()
  })

  it('blocks the test while the condition is not judgeable', async () => {
    renderRulesPage()
    await openNewRuleEditor()

    const panel = screen.getByRole('region', { name: 'Rule test' })
    expect(
      within(panel).getByText(
        'Write a valid condition before testing — the agent is given this exact text.',
      ),
    ).toBeInTheDocument()
  })

  it('explains that a model is running, then renders the verdict and evidence', async () => {
    renderRulesPage()
    await openNewRuleEditor()
    typeCondition(VALID_CONDITION)
    fireEvent.change(screen.getByLabelText('Weight value'), { target: { value: '30' } })
    fireEvent.change(screen.getByLabelText(/Rule name/), { target: { value: 'Structuring' } })
    const panel = await pickCustomer()

    /* Held in an object so TypeScript does not narrow the binding to `null`
       between the mock's closure and the assertion below. */
    const pending: { resolve: (value: unknown) => void } = { resolve: () => undefined }
    mockPost.mockImplementation(
      () =>
        new Promise((resolve) => {
          pending.resolve = resolve as (value: unknown) => void
        }) as Promise<never>,
    )

    fireEvent.click(within(panel).getByRole('button', { name: /Run the agent/ }))

    expect(await within(panel).findByText('A model is evaluating this rule')).toBeInTheDocument()
    expect(within(panel).getByText(/live model call/)).toBeInTheDocument()

    await waitFor(() => {
      expect(mockPost).toHaveBeenCalledWith(
        '/rules/test',
        {
          ruleName: 'Structuring',
          thresholdLogic: VALID_CONDITION,
          appliesTo: 'ALL',
          weight: 30,
          customerId: CUSTOMER.customerId,
        },
        /* Longer than the backend's own 240s judge timeout on purpose: the
           server's 504 has to arrive before the browser gives up. */
        { timeout: 300_000 },
      )
    })

    pending.resolve({
      triggered: true,
      score: 24.5,
      weight: 30,
      rationale: 'Four payments of 9 400 in eleven hours, none reaching 10 000.',
      matchedTransactions: [
        {
          transactionId: '99999999-8888-7777-6666-555555555555',
          activityType: 'PAYMENT',
          amount: 9400,
          currency: 'USD',
          status: 'Completed',
          createdAt: '2026-08-01T10:00:00Z',
          reason: 'Third of four near-threshold payments.',
        },
      ],
      evaluatedTransactionCount: 42,
      model: 'gpt-oss-120b',
      durationMs: 18_400,
    })

    expect(await within(panel).findByText('Triggered')).toBeInTheDocument()
    expect(within(panel).getByText('24.50')).toBeInTheDocument()
    expect(within(panel).getByText(/1 transaction cited of 42 in scope/)).toBeInTheDocument()
    expect(
      within(panel).getByText('Four payments of 9 400 in eleven hours, none reaching 10 000.'),
    ).toBeInTheDocument()
    expect(within(panel).getByText('99999999')).toBeInTheDocument()
    expect(
      within(panel).getByText('Third of four near-threshold payments.'),
    ).toBeInTheDocument()

    /* The verdict is a judgement, not a calculation. Saying so is the whole
       honesty requirement of dropping the deterministic engine. */
    expect(within(panel).getByText(/Running it again .* can produce a different/)).toBeInTheDocument()
  })

  it('warns when the agent estimated more than the rule’s weight', async () => {
    renderRulesPage()
    await openNewRuleEditor()
    typeCondition(VALID_CONDITION)
    fireEvent.change(screen.getByLabelText('Weight value'), { target: { value: '10' } })
    const panel = await pickCustomer()

    mockPost.mockResolvedValue({
      triggered: true,
      score: 40,
      weight: 10,
      matchedCount: 6,
      matchedTransactions: [{ transactionId: '99999999-8888-7777-6666-555555555555' }],
    } as never)

    fireEvent.click(within(panel).getByRole('button', { name: /Run the agent/ }))

    expect(
      await within(panel).findByText(/The agent estimated more than the rule’s weight/),
    ).toBeInTheDocument()
    /* The backend caps the evidence it returns, so a count of six over one row
       is not a contradiction — but it has to be named, not silently shown. */
    expect(
      within(panel).getByText('Showing 1 of the 6 transactions the agent cited.'),
    ).toBeInTheDocument()
    expect(within(panel).getByText('99999999')).toBeInTheDocument()
  })

  it('reports a model timeout honestly instead of as an empty result', async () => {
    renderRulesPage()
    await openNewRuleEditor()
    typeCondition(VALID_CONDITION)
    const panel = await pickCustomer()

    mockPost.mockRejectedValue(
      new ApiError({
        status: 0,
        title: 'Request timed out',
        detail: 'The backend did not respond in time. Please retry.',
      }),
    )

    fireEvent.click(within(panel).getByRole('button', { name: /Run the agent/ }))

    expect(
      await within(panel).findByText('The model did not answer in time'),
    ).toBeInTheDocument()
    expect(within(panel).getByText(/nothing was saved/)).toBeInTheDocument()
    expect(within(panel).getByRole('button', { name: 'Try again' })).toBeInTheDocument()
  })
})
