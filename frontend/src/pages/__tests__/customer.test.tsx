import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import type { ReactNode } from 'react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { CustomerSummary, SpringPage, TransactionWire } from '../../api/types'
import { ToastProvider } from '../../components/ui/Toast'
import { formatMoney } from '../../lib/format'
import { CustomerPage } from '../CustomerPage'
import { DashboardPage } from '../DashboardPage'
import { ActivityPanel } from '../customer/ActivityPanel'

/* The HTTP layer is the only thing mocked — the real query hooks, normalisers
   and formatters all run, so a contract drift shows up here. */
const { getJsonMock } = vi.hoisted(() => ({ getJsonMock: vi.fn() }))

vi.mock('../../api/client', async () => {
  const actual = await vi.importActual<typeof import('../../api/client')>('../../api/client')
  return { ...actual, getJson: getJsonMock }
})

const CUSTOMER_ID = '0f2a1c44-5b3e-4a51-9d10-8f0a1b2c3d4e'
const OTHER_CUSTOMER_ID = 'b1d9f8e7-6c5a-4b3d-9e2f-1a0b9c8d7e6f'

const CUSTOMERS: CustomerSummary[] = [
  {
    customerId: CUSTOMER_ID,
    firstName: 'Mila',
    lastName: 'Novak',
    dob: '1988-04-12',
    country: 'SI',
    age: 38,
    transactionCount: 48,
    totalAmount: 128400.5,
    lastActivityAt: '2026-08-27T09:15:00Z',
    lastRiskLevel: 'HIGH',
  },
  {
    customerId: OTHER_CUSTOMER_ID,
    firstName: 'Ada',
    lastName: 'Sterling',
    dob: '1979-11-02',
    country: 'GB',
    age: 46,
    transactionCount: 12,
    totalAmount: 4200,
    lastActivityAt: '2026-08-20T11:00:00Z',
    lastRiskLevel: 'LOW',
  },
]

const CARD_TRANSACTION: TransactionWire = {
  transactionId: 'f47ac10b-58cc-4372-a567-0e02b2c3d479',
  customerId: CUSTOMER_ID,
  activityType: 'CARD',
  amount: 12500,
  currency: 'USD',
  status: 'Failed',
  createdAt: '2026-08-27T09:15:00Z',
  card: {
    transactionId: 'f47ac10b-58cc-4372-a567-0e02b2c3d479',
    cardPan: '4111111111114242',
    cardType: 'Credit',
    merchantName: 'Aurora Electronics',
    mccCode: '5732',
    cardPresent: false,
    authorizationCode: 'A1B2C3',
    declineReason: 'Insufficient funds',
  },
}

const PAYMENT_TRANSACTION: TransactionWire = {
  transactionId: '6c1f8b2a-3d4e-4f50-8a1b-2c3d4e5f6a7b',
  customerId: CUSTOMER_ID,
  activityType: 'PAYMENT',
  amount: 9800,
  currency: 'EUR',
  status: 'Completed',
  createdAt: '2026-08-26T14:02:00Z',
  payment: {
    transactionId: '6c1f8b2a-3d4e-4f50-8a1b-2c3d4e5f6a7b',
    paymentMethod: 'SWIFT',
    senderAccount: 'DE89370400440532013000',
    receiverAccount: 'IR580540105180021273113007',
    receiverBankCountry: 'IR',
  },
}

const CRYPTO_TRANSACTION: TransactionWire = {
  transactionId: '9a8b7c6d-5e4f-4a3b-8c9d-0e1f2a3b4c5d',
  customerId: CUSTOMER_ID,
  activityType: 'CRYPTO',
  amount: 4200,
  currency: 'USD',
  status: 'Pending',
  createdAt: '2026-08-25T22:41:00Z',
  crypto: {
    transactionId: '9a8b7c6d-5e4f-4a3b-8c9d-0e1f2a3b4c5d',
    blockchain: 'XMR',
    walletAddressFrom: '48jL9mQ1p2R3s4T5u6V7w8X9y0Z1a2B3c4D5e6F7g8H9i0J1',
    walletAddressTo: '4AdUndXHHZ6cfufTMvppY6JwXNouMBzSkbLYfpAV5Usx3sk',
    txHash: '0x9f8e7d6c5b4a39281706f5e4d3c2b1a09f8e7d6c5b4a3928',
    exchangeName: null,
  },
}

const TRANSACTIONS = [CARD_TRANSACTION, PAYMENT_TRANSACTION, CRYPTO_TRANSACTION]

const SUMMARY = {
  customerId: CUSTOMER_ID,
  totalTransactions: 3,
  totalAmount: 26500,
  currencies: ['USD', 'EUR'],
  countries: ['IR', 'US'],
  byActivityType: [
    { activityType: 'CARD', count: 1, totalAmount: 12500 },
    { activityType: 'PAYMENT', count: 1, totalAmount: 9800 },
    { activityType: 'CRYPTO', count: 1, totalAmount: 4200 },
  ],
  byStatus: [
    { status: 'Completed', count: 1 },
    { status: 'Failed', count: 1 },
    { status: 'Pending', count: 1 },
  ],
  firstActivityAt: '2026-08-25T22:41:00Z',
  lastActivityAt: '2026-08-27T09:15:00Z',
}

type Params = Record<string, string | number | undefined>

function page<T>(content: T[], params: Params): SpringPage<T> {
  const size = Number(params.size ?? 20)
  return {
    content,
    size,
    number: Number(params.page ?? 0),
    totalElements: content.length,
    totalPages: content.length === 0 ? 0 : Math.ceil(content.length / size),
    first: true,
    last: true,
    empty: content.length === 0,
  }
}

function respond(url: string, params: Params): unknown {
  if (url === '/customers') {
    const query = String(params.query ?? '').toLowerCase()
    const matches = query
      ? CUSTOMERS.filter((customer) =>
          `${customer.firstName} ${customer.lastName} ${customer.customerId}`
            .toLowerCase()
            .includes(query),
        )
      : CUSTOMERS
    return page(matches, params)
  }
  if (url.endsWith('/summary')) return SUMMARY
  if (url.endsWith('/activity')) {
    const rows = TRANSACTIONS.filter(
      (transaction) =>
        (!params.type || transaction.activityType === params.type) &&
        (!params.status || transaction.status === params.status),
    )
    return page(rows, params)
  }
  if (url.endsWith('/analyses')) return []
  if (url.startsWith('/customers/')) {
    return {
      customerId: CUSTOMER_ID,
      firstName: 'Mila',
      lastName: 'Novak',
      dob: '1988-04-12',
      country: 'SI',
      age: 38,
    }
  }
  if (url.startsWith('/transactions/')) {
    const id = url.slice('/transactions/'.length)
    return TRANSACTIONS.find((transaction) => transaction.transactionId === id) ?? null
  }
  throw new Error(`Unhandled request: ${url}`)
}

/** Records every call so assertions can check the query string we sent. */
function calledWith(url: string, key: string, value: string): boolean {
  return getJsonMock.mock.calls.some((call) => {
    const [calledUrl, config] = call as [string, { params?: Record<string, unknown> } | undefined]
    return calledUrl === url && config?.params?.[key] === value
  })
}

function renderWithProviders(ui: ReactNode, initialEntries: string[] = ['/']) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <MemoryRouter initialEntries={initialEntries}>{ui}</MemoryRouter>
      </ToastProvider>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  getJsonMock.mockImplementation(
    (url: string, config?: { params?: Params }) =>
      Promise.resolve(respond(url, config?.params ?? {})),
  )
})

describe('customer activity table', () => {
  it('renders the shared columns on the All tab', async () => {
    renderWithProviders(<ActivityPanel customerId={CUSTOMER_ID} summary={undefined} />)

    expect(await screen.findByText('Aurora Electronics · MCC 5732')).toBeInTheDocument()
    expect(screen.getByText('SWIFT · to IR')).toBeInTheDocument()
    expect(screen.getByText('XMR · no exchange')).toBeInTheDocument()
    expect(screen.getByText(formatMoney(12500, 'USD'))).toBeInTheDocument()
    expect(screen.getByText(formatMoney(9800, 'EUR'))).toBeInTheDocument()
  })

  it('renders card-specific columns and filters the request by type', async () => {
    renderWithProviders(<ActivityPanel customerId={CUSTOMER_ID} summary={undefined} />)
    await screen.findByText('Aurora Electronics · MCC 5732')

    fireEvent.click(screen.getByRole('tab', { name: /^card/i }))

    expect(await screen.findByText('•••• 4242')).toBeInTheDocument()
    expect(screen.getByText('Aurora Electronics')).toBeInTheDocument()
    expect(screen.getByText('5732')).toBeInTheDocument()
    expect(screen.getByText('Card not present')).toBeInTheDocument()
    expect(screen.getByText('Insufficient funds')).toBeInTheDocument()
    expect(screen.queryByText('SWIFT')).not.toBeInTheDocument()
    await waitFor(() => {
      expect(calledWith(`/customers/${CUSTOMER_ID}/activity`, 'type', 'CARD')).toBe(true)
    })
  })

  it('renders payment and crypto specific columns', async () => {
    renderWithProviders(<ActivityPanel customerId={CUSTOMER_ID} summary={undefined} />)
    await screen.findByText('Aurora Electronics · MCC 5732')

    fireEvent.click(screen.getByRole('tab', { name: /^payment/i }))
    expect(await screen.findByText('SWIFT')).toBeInTheDocument()
    expect(screen.getByText('IR')).toBeInTheDocument()
    expect(screen.getByTitle('IR580540105180021273113007')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('tab', { name: /^crypto/i }))
    expect(await screen.findByText('XMR')).toBeInTheDocument()
    expect(screen.getByText('Unattributed')).toBeInTheDocument()
    expect(
      screen.getByTitle('0x9f8e7d6c5b4a39281706f5e4d3c2b1a09f8e7d6c5b4a3928'),
    ).toBeInTheDocument()
  })

  it('sends the status filter and shows the empty state when nothing matches', async () => {
    renderWithProviders(<ActivityPanel customerId={CUSTOMER_ID} summary={undefined} />)
    await screen.findByText('Aurora Electronics · MCC 5732')

    fireEvent.change(screen.getByLabelText('Status'), { target: { value: 'Reversed' } })

    expect(await screen.findByText('No transactions found')).toBeInTheDocument()
    expect(
      screen.getByText('No records match the current status or date filters.'),
    ).toBeInTheDocument()
    expect(calledWith(`/customers/${CUSTOMER_ID}/activity`, 'status', 'Reversed')).toBe(true)
  })

  it('opens the full transaction record when a row is selected', async () => {
    renderWithProviders(<ActivityPanel customerId={CUSTOMER_ID} summary={undefined} />)

    fireEvent.click(await screen.findByText('Aurora Electronics · MCC 5732'))

    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText('Transaction detail')).toBeInTheDocument()
    expect(await within(dialog).findByText('A1B2C3')).toBeInTheDocument()
    expect(within(dialog).getByText('f47ac10b-58cc-4372-a567-0e02b2c3d479')).toBeInTheDocument()
    expect(within(dialog).getByText('Insufficient funds')).toBeInTheDocument()
    expect(getJsonMock).toHaveBeenCalledWith(
      '/transactions/f47ac10b-58cc-4372-a567-0e02b2c3d479',
    )
  })

  it('surfaces a failed activity request', async () => {
    getJsonMock.mockImplementation((url: string) => {
      if (url.endsWith('/activity')) {
        return Promise.reject(
          Object.assign(new Error('Boom'), { name: 'ApiError' }),
        )
      }
      return Promise.resolve(respond(url, {}))
    })

    renderWithProviders(<ActivityPanel customerId={CUSTOMER_ID} summary={undefined} />)

    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /try again/i })).toBeInTheDocument()
  })
})

describe('dashboard customer search', () => {
  function renderDashboard() {
    return renderWithProviders(
      <Routes>
        <Route path="/dashboard" element={<DashboardPage />} />
        <Route path="/customers/:customerId" element={<p>Customer profile route</p>} />
      </Routes>,
      ['/dashboard'],
    )
  }

  it('lists every customer before a search is entered', async () => {
    renderDashboard()

    expect(await screen.findByText('Mila Novak')).toBeInTheDocument()
    expect(screen.getByText('Ada Sterling')).toBeInTheDocument()
    expect(screen.getByText('All customers')).toBeInTheDocument()
  })

  it('debounces the query, sends it to the API and narrows the results', async () => {
    renderDashboard()
    await screen.findByText('Ada Sterling')

    fireEvent.change(screen.getByLabelText('Search customers'), {
      target: { value: 'Novak' },
    })

    await waitFor(
      () => {
        expect(calledWith('/customers', 'query', 'Novak')).toBe(true)
      },
      { timeout: 3000 },
    )
    await waitFor(() => {
      expect(screen.queryByText('Ada Sterling')).not.toBeInTheDocument()
    })
    expect(screen.getByText('Mila Novak')).toBeInTheDocument()
  })

  it('opens the customer profile from a result row', async () => {
    renderDashboard()

    fireEvent.click(await screen.findByText('Mila Novak'))

    expect(await screen.findByText('Customer profile route')).toBeInTheDocument()
  })

  it('shows an empty state when the search matches nothing', async () => {
    renderDashboard()
    await screen.findByText('Mila Novak')

    fireEvent.change(screen.getByLabelText('Search customers'), {
      target: { value: 'zzzz' },
    })

    expect(await screen.findByText('No matching customers', {}, { timeout: 3000 })).toBeInTheDocument()
  })
})

describe('customer profile page', () => {
  it('composes the profile, aggregates and activity for a customer', async () => {
    renderWithProviders(
      <Routes>
        <Route path="/customers/:customerId" element={<CustomerPage />} />
      </Routes>,
      [`/customers/${CUSTOMER_ID}`],
    )

    expect(await screen.findByRole('heading', { level: 1, name: 'Mila Novak' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /run ai risk analysis/i })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /analysis history/i })).toHaveAttribute(
      'href',
      `/customers/${CUSTOMER_ID}/analyses`,
    )
    expect(screen.getByText(CUSTOMER_ID)).toBeInTheDocument()

    // Aggregates from GET /customers/{id}/summary.
    expect(await screen.findByText('Counterparty countries')).toBeInTheDocument()
    expect(screen.getByText('IR, US')).toBeInTheDocument()

    // The activity ledger is wired to the same customer.
    expect(await screen.findByText('Aurora Electronics · MCC 5732')).toBeInTheDocument()
  })
})
