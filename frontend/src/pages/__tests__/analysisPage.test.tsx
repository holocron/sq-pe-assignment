/**
 * `/analyses/:assessmentId` — cancelling a RUNNING analysis.
 *
 * The stream falls back to polling under jsdom (no EventSource), which is the
 * path exercised here; only the HTTP layer is stubbed.
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../api/errors'
import type { AnalysisResultWire } from '../../api/types'
import { ToastProvider } from '../../components/ui/Toast'
import { AnalysisPage } from '../AnalysisPage'

const { getJsonMock, postJsonMock } = vi.hoisted(() => ({
  getJsonMock: vi.fn(),
  postJsonMock: vi.fn(),
}))

vi.mock('../../api/client', async () => {
  const actual = await vi.importActual<typeof import('../../api/client')>('../../api/client')
  return { ...actual, getJson: getJsonMock, postJson: postJsonMock }
})

const CUSTOMER_ID = '0f2a1c44-5b3e-4a51-9d10-8f0a1b2c3d4e'
const ASSESSMENT_ID = 'a1b2c3d4-e5f6-4708-9a0b-1c2d3e4f5061'

const RUNNING_ANALYSIS: AnalysisResultWire = {
  assessmentId: ASSESSMENT_ID,
  customerId: CUSTOMER_ID,
  customerName: 'Mila Novak',
  status: 'RUNNING',
  riskLevel: null,
  totalScore: null,
  rulesTotal: 3,
  rulesEvaluated: 1,
  coverageComplete: false,
  createdAt: '2026-08-29T10:00:00Z',
  summary: null,
  recommendations: null,
  ruleEvaluations: [],
  trace: { steps: [] },
}

function respond(url: string): unknown {
  if (url === `/analyses/${ASSESSMENT_ID}`) return RUNNING_ANALYSIS
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
  if (url === '/rules') return []
  throw new Error(`Unhandled request: ${url}`)
}

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const ui: ReactNode = (
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <MemoryRouter initialEntries={[`/analyses/${ASSESSMENT_ID}`]}>
          <Routes>
            <Route path="/analyses/:assessmentId" element={<AnalysisPage />} />
          </Routes>
        </MemoryRouter>
      </ToastProvider>
    </QueryClientProvider>
  )
  return render(ui)
}

beforeEach(() => {
  getJsonMock.mockImplementation((url: string) => Promise.resolve(respond(url)))
  postJsonMock.mockResolvedValue({ assessmentId: ASSESSMENT_ID, status: 'CANCELLED' })
})

describe('AnalysisPage — cancel a running analysis', () => {
  it('offers a cancel action only while the run is live and confirms before calling the API', async () => {
    renderPage()

    const trigger = await screen.findByRole('button', { name: /cancel run/i })
    expect(postJsonMock).not.toHaveBeenCalled()

    fireEvent.click(trigger)
    const dialog = await screen.findByRole('dialog')
    expect(dialog).toHaveTextContent('Cancel this analysis?')

    fireEvent.click(screen.getByRole('button', { name: /cancel the run/i }))
    await waitFor(() => {
      expect(postJsonMock).toHaveBeenCalledWith(`/analyses/${ASSESSMENT_ID}/cancel`)
    })
    expect(await screen.findByText('Analysis cancelled')).toBeInTheDocument()
  })

  it('treats a 409 as "finished meanwhile" and refetches instead of failing', async () => {
    postJsonMock.mockRejectedValue(
      new ApiError({ status: 409, title: 'Conflict', detail: 'Run is no longer running' }),
    )
    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: /cancel run/i }))
    fireEvent.click(await screen.findByRole('button', { name: /cancel the run/i }))

    expect(await screen.findByText('Run already finished')).toBeInTheDocument()
    await waitFor(() => {
      const detailCalls = getJsonMock.mock.calls.filter(
        ([url]) => url === `/analyses/${ASSESSMENT_ID}`,
      )
      expect(detailCalls.length).toBeGreaterThanOrEqual(2)
    })
  })

  it('does not offer cancel for a terminal run', async () => {
    getJsonMock.mockImplementation((url: string) => {
      if (url === `/analyses/${ASSESSMENT_ID}`) {
        return Promise.resolve({ ...RUNNING_ANALYSIS, status: 'CANCELLED' })
      }
      return Promise.resolve(respond(url))
    })
    renderPage()

    expect(await screen.findByText('Cancelled')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /cancel run/i })).not.toBeInTheDocument()
  })
})
