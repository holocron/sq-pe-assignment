/**
 * Application-level wiring: every route in `App.tsx` must resolve to a real
 * screen, the nav must only offer links that exist, and the admin routes must
 * be gated for operators.
 *
 * The whole app is mounted — real router, real auth context, real query hooks —
 * with only the HTTP layer stubbed, so a broken route or a dangling nav link
 * fails here rather than in the browser.
 */
import { render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type {
  ActivitySummary,
  AnalysisResultWire,
  AnalysisSummary,
  AppUser,
  Customer,
  CustomerSummary,
  KnowledgeDocument,
  Role,
  SpringPage,
  TransactionWire,
  User,
} from '../../api/types'
import { writeStoredAuth } from '../../auth/storage'
import App, { queryClient } from '../../App'

const { getJsonMock, postJsonMock } = vi.hoisted(() => ({
  getJsonMock: vi.fn(),
  postJsonMock: vi.fn(),
}))

vi.mock('../../api/client', async () => {
  const actual = await vi.importActual<typeof import('../../api/client')>('../../api/client')
  return { ...actual, getJson: getJsonMock, postJson: postJsonMock }
})

const CUSTOMER_ID = '3f9a2c10-1b4d-4f27-9c8a-7e5d6b4a3c21'
const ASSESSMENT_ID = 'a1b2c3d4-e5f6-4708-9a0b-1c2d3e4f5061'

const CUSTOMER: Customer = {
  customerId: CUSTOMER_ID,
  firstName: 'Mila',
  lastName: 'Novak',
  dob: '1988-04-12',
  country: 'SI',
  age: 38,
}

const CUSTOMER_ROW: CustomerSummary = {
  customerId: CUSTOMER_ID,
  firstName: 'Mila',
  lastName: 'Novak',
  dob: '1988-04-12',
  country: 'SI',
  transactionCount: 48,
  totalAmount: 128_400.5,
  lastActivityAt: '2026-08-27T09:15:00Z',
}

const SUMMARY: ActivitySummary = {
  customerId: CUSTOMER_ID,
  totalTransactions: 48,
  totalAmount: 128_400.5,
  currencies: ['EUR'],
  countries: ['SI', 'IR'],
  byActivityType: [
    { activityType: 'CARD', count: 30, totalAmount: 40_000 },
    { activityType: 'PAYMENT', count: 15, totalAmount: 80_000 },
    { activityType: 'CRYPTO', count: 3, totalAmount: 8_400.5 },
  ],
  byStatus: [{ status: 'Completed', count: 46 }],
}

const ANALYSIS_ROW: AnalysisSummary = {
  assessmentId: ASSESSMENT_ID,
  customerId: CUSTOMER_ID,
  status: 'COMPLETED',
  riskLevel: 'HIGH',
  totalScore: 61.5,
  rulesTotal: 10,
  rulesEvaluated: 10,
  coverageComplete: true,
  createdAt: '2026-08-28T10:00:00Z',
  completedAt: '2026-08-28T10:01:30Z',
  requestedBy: 'operator1',
}

const ANALYSIS: AnalysisResultWire = {
  ...ANALYSIS_ROW,
  summary: 'Structuring behaviour across payments just under the reporting threshold.',
  recommendations: 'Escalate to enhanced due diligence.',
  ruleEvaluations: [
    {
      ruleId: 'd0e1f2a3-b4c5-4678-9a0b-1c2d3e4f5060',
      ruleName: 'Structuring under reporting threshold',
      appliesTo: 'PAYMENT',
      weight: 30,
      triggered: true,
      scoreContribution: 30,
      rationale: 'Nine payments between 9,000 and 9,999 in 30 days.',
      transactionIds: [],
      source: 'AGENT',
    },
  ],
  trace: { steps: [{ n: 1, type: 'final', risk_level: 'HIGH' }] },
}

const USERS: AppUser[] = [
  {
    userId: 'c0ffee00-1111-4222-8333-444455556666',
    username: 'admin',
    fullName: 'Ada Admin',
    role: 'ADMIN',
    enabled: true,
    createdAt: '2026-01-01T00:00:00Z',
  },
]

const DOCUMENTS: KnowledgeDocument[] = [
  {
    documentId: 'dddddddd-1111-4222-8333-444455556666',
    filename: 'aml-thresholds.pdf',
    title: 'AML Thresholds Policy',
    mimeType: 'application/pdf',
    sizeBytes: 24_576,
    chunkCount: 12,
    status: 'INDEXED',
    uploadedBy: 'admin',
    uploadedAt: '2026-08-01T08:00:00Z',
  },
]

function page<T>(content: T[]): SpringPage<T> {
  return {
    content,
    page: { size: 20, number: 0, totalElements: content.length, totalPages: 1 },
  }
}

function respond(url: string): unknown {
  if (url === '/auth/me') return signedInUser
  if (url === '/customers') return page([CUSTOMER_ROW])
  if (url === `/customers/${CUSTOMER_ID}`) return CUSTOMER
  if (url === `/customers/${CUSTOMER_ID}/summary`) return SUMMARY
  if (url === `/customers/${CUSTOMER_ID}/activity`) return page<TransactionWire>([])
  if (url === `/customers/${CUSTOMER_ID}/analyses`) return [ANALYSIS_ROW]
  if (url === `/analyses/${ASSESSMENT_ID}`) return ANALYSIS
  if (url === '/rules') return []
  if (url === '/rules/field-catalog') return []
  if (url === '/users') return USERS
  if (url === '/knowledge/documents') return DOCUMENTS
  throw new Error(`Unhandled request: ${url}`)
}

let signedInUser: User = { username: 'operator1', fullName: 'Olive Operator', role: 'OPERATOR' }

function signIn(role: Role): void {
  signedInUser =
    role === 'ADMIN'
      ? { username: 'admin', fullName: 'Ada Admin', role }
      : { username: 'operator1', fullName: 'Olive Operator', role }
  writeStoredAuth({ token: 'test-token', expiresAt: null, user: signedInUser })
}

function renderAt(path: string) {
  window.history.pushState({}, '', path)
  return render(<App />)
}

beforeEach(() => {
  queryClient.clear()
  getJsonMock.mockImplementation((url: string) => Promise.resolve(respond(url)))
  postJsonMock.mockResolvedValue({ assessmentId: ASSESSMENT_ID, status: 'RUNNING' })
})

describe('routing', () => {
  it('sends an unauthenticated visitor to the sign-in screen', async () => {
    renderAt('/dashboard')
    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
  })

  it('redirects the index route to the dashboard', async () => {
    signIn('OPERATOR')
    renderAt('/')
    expect(await screen.findByRole('heading', { name: 'Customer activity' })).toBeInTheDocument()
    await waitFor(() => expect(window.location.pathname).toBe('/dashboard'))
  })

  it.each([
    ['/dashboard', 'Customer activity'],
    [`/customers/${CUSTOMER_ID}`, 'Mila Novak'],
    [`/customers/${CUSTOMER_ID}/analyses`, 'Analysis history'],
    ['/analyses', 'Analysis history'],
    [`/analyses/${ASSESSMENT_ID}`, 'AI risk analysis'],
    ['/knowledge-search', 'Knowledge search'],
  ])('resolves %s for an operator', async (path, heading) => {
    signIn('OPERATOR')
    renderAt(path)
    expect(await screen.findByRole('heading', { name: heading })).toBeInTheDocument()
  })

  it.each([
    ['/admin/rules', 'Risk rules'],
    ['/admin/knowledge', 'Knowledge base'],
    ['/admin/users', 'Users'],
  ])('resolves %s for an admin', async (path, heading) => {
    signIn('ADMIN')
    renderAt(path)
    expect(await screen.findByRole('heading', { name: heading })).toBeInTheDocument()
  })

  it('renders the not-found page for an unknown path', async () => {
    signIn('OPERATOR')
    renderAt('/does-not-exist')
    expect(await screen.findByText('Page not found')).toBeInTheDocument()
  })
})

describe('role gating', () => {
  it('refuses the admin routes for an operator without leaking the screen', async () => {
    signIn('OPERATOR')
    renderAt('/admin/rules')
    expect(await screen.findByText('Not permitted')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Risk rules' })).not.toBeInTheDocument()
  })

  it('hides the admin nav section from operators', async () => {
    signIn('OPERATOR')
    renderAt('/dashboard')
    const nav = await screen.findByRole('navigation', { name: 'Main' })
    expect(within(nav).getByRole('link', { name: 'Dashboard' })).toBeInTheDocument()
    expect(within(nav).getByRole('link', { name: 'Analyses' })).toBeInTheDocument()
    expect(within(nav).getByRole('link', { name: 'Knowledge Search' })).toBeInTheDocument()
    expect(within(nav).queryByRole('link', { name: 'Risk Rules' })).not.toBeInTheDocument()
    expect(within(nav).queryByRole('link', { name: 'Users' })).not.toBeInTheDocument()
  })

  it('shows the admin nav section to admins', async () => {
    signIn('ADMIN')
    renderAt('/dashboard')
    const nav = await screen.findByRole('navigation', { name: 'Main' })
    expect(within(nav).getByRole('link', { name: 'Risk Rules' })).toBeInTheDocument()
    expect(within(nav).getByRole('link', { name: 'Knowledge Base' })).toBeInTheDocument()
    expect(within(nav).getByRole('link', { name: 'Users' })).toBeInTheDocument()
  })

  it('points every nav link at a path the router serves', async () => {
    signIn('ADMIN')
    renderAt('/dashboard')
    const nav = await screen.findByRole('navigation', { name: 'Main' })
    const targets = within(nav)
      .getAllByRole('link')
      .map((link) => link.getAttribute('href'))
    expect(targets).toEqual([
      '/dashboard',
      '/analyses',
      '/knowledge-search',
      '/admin/rules',
      '/admin/knowledge',
      '/admin/users',
    ])
  })
})
