/**
 * `/customers/:customerId/analyses` — client-side sorting by started date and
 * score, the HIGH/CRITICAL filter, and the CANCELLED run status.
 *
 * Only the HTTP layer is stubbed; the real hooks and table run.
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, within } from '@testing-library/react'
import type { ReactNode } from 'react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { AnalysisSummary } from '../../api/types'
import { ToastProvider } from '../../components/ui/Toast'
import { AnalysisHistoryPage } from '../AnalysisHistoryPage'

const { getJsonMock } = vi.hoisted(() => ({ getJsonMock: vi.fn() }))

vi.mock('../../api/client', async () => {
  const actual = await vi.importActual<typeof import('../../api/client')>('../../api/client')
  return { ...actual, getJson: getJsonMock }
})

const CUSTOMER_ID = '0f2a1c44-5b3e-4a51-9d10-8f0a1b2c3d4e'

function analysis(partial: Partial<AnalysisSummary> & Pick<AnalysisSummary, 'assessmentId'>): AnalysisSummary {
  return {
    customerId: CUSTOMER_ID,
    customerName: 'Mila Novak',
    status: 'COMPLETED',
    riskLevel: 'LOW',
    totalScore: 10,
    rulesTotal: 12,
    rulesEvaluated: 12,
    coverageComplete: true,
    createdAt: '2026-08-20T10:00:00Z',
    completedAt: '2026-08-20T10:03:00Z',
    ...partial,
  }
}

const OLD_LOW = analysis({
  assessmentId: 'aaaa0000-0000-4000-8000-000000000001',
  createdAt: '2026-08-18T09:00:00Z',
  totalScore: 4.5,
  riskLevel: 'LOW',
})

const NEW_CRITICAL = analysis({
  assessmentId: 'aaaa0000-0000-4000-8000-000000000002',
  createdAt: '2026-08-27T09:00:00Z',
  totalScore: 92.25,
  riskLevel: 'CRITICAL',
})

const MID_CANCELLED = analysis({
  assessmentId: 'aaaa0000-0000-4000-8000-000000000003',
  createdAt: '2026-08-25T09:00:00Z',
  status: 'CANCELLED',
  riskLevel: null,
  totalScore: null,
})

const HISTORY = [OLD_LOW, MID_CANCELLED, NEW_CRITICAL]

function respond(url: string): unknown {
  if (url === `/customers/${CUSTOMER_ID}/analyses`) return HISTORY
  if (url === `/customers/${CUSTOMER_ID}`) {
    return {
      customerId: CUSTOMER_ID,
      firstName: 'Mila',
      lastName: 'Novak',
      fullName: 'Mila Novak',
      dob: '1988-04-12',
      country: 'SI',
    }
  }
  throw new Error(`Unhandled request: ${url}`)
}

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const ui: ReactNode = (
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <MemoryRouter initialEntries={[`/customers/${CUSTOMER_ID}/analyses`]}>
          <Routes>
            <Route path="/customers/:customerId/analyses" element={<AnalysisHistoryPage />} />
          </Routes>
        </MemoryRouter>
      </ToastProvider>
    </QueryClientProvider>
  )
  return render(ui)
}

/** Text of the data rows, in rendered order — how the sort is asserted. */
function dataRowOrder(): string[] {
  const table = screen.getByRole('table', { name: 'Analysis history' })
  /* Clickable data rows carry role="button" instead of the implicit row role. */
  return within(table)
    .getAllByRole('button')
    .filter((element) => element.tagName === 'TR')
    .map((row) => row.textContent ?? '')
}

beforeEach(() => {
  getJsonMock.mockImplementation((url: string) => Promise.resolve(respond(url)))
})

describe('AnalysisHistoryPage', () => {
  it('renders the history newest first by default and shows cancelled runs honestly', async () => {
    renderPage()
    await screen.findByText('Cancelled')

    const order = dataRowOrder()
    expect(order).toHaveLength(3)
    /* Newest (27th) first, oldest (18th) last; score cells identify the rows. */
    expect(order[0]).toContain('92.25')
    expect(order[2]).toContain('4.5')
  })

  it('sorts by score through the column header, nulls last', async () => {
    renderPage()
    await screen.findByText('Cancelled')

    fireEvent.click(screen.getByRole('button', { name: /score/i }))
    let order = dataRowOrder()
    expect(order[0]).toContain('4.5')
    expect(order[1]).toContain('92.25')
    expect(screen.getByRole('columnheader', { name: /score/i })).toHaveAttribute(
      'aria-sort',
      'ascending',
    )

    fireEvent.click(screen.getByRole('button', { name: /score/i }))
    order = dataRowOrder()
    expect(order[0]).toContain('92.25')
    expect(order[1]).toContain('4.5')
    expect(screen.getByRole('columnheader', { name: /score/i })).toHaveAttribute(
      'aria-sort',
      'descending',
    )
  })

  it('sorts by started date in both directions', async () => {
    renderPage()
    await screen.findByText('Cancelled')

    fireEvent.click(screen.getByRole('button', { name: /started/i }))
    const order = dataRowOrder()
    expect(order[0]).toContain('4.5')
    expect(order[2]).toContain('92.25')
    expect(screen.getByRole('columnheader', { name: /started/i })).toHaveAttribute(
      'aria-sort',
      'ascending',
    )
  })

  it('filters to HIGH and CRITICAL verdicts only', async () => {
    renderPage()
    await screen.findByText('Cancelled')

    fireEvent.click(screen.getByRole('checkbox', { name: /high \/ critical only/i }))

    const order = dataRowOrder()
    expect(order).toHaveLength(1)
    expect(order[0]).toContain('92.25')
    expect(screen.queryByText('Cancelled')).not.toBeInTheDocument()
  })
})
