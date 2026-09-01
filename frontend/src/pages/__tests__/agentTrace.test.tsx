import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api, getJson, postJson } from '../../api/client'
import type { AgentTraceContentWire, AgentTraceStateWire } from '../../api/types'
import { ToastProvider } from '../../components/ui/Toast'
import { AgentTracePage } from '../admin/AgentTracePage'

vi.mock('../../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/client')>()
  return {
    ...actual,
    getJson: vi.fn(),
    postJson: vi.fn(),
    /* The download path goes through the raw axios client, not getJson. */
    api: { get: vi.fn() },
  }
})

const mockGet = vi.mocked(getJson)
const mockPost = vi.mocked(postJson)
const mockApiGet = vi.mocked(api.get)

/* -------------------------------------------------------------------------- */
/* Fixtures                                                                    */
/* -------------------------------------------------------------------------- */

const NEVER_ENABLED: AgentTraceStateWire = {
  enabled: false,
  fileName: null,
  startedAt: null,
  sizeBytes: 0,
}

const DISABLED_WITH_FILE: AgentTraceStateWire = {
  enabled: false,
  fileName: 'agent-trace-20260831.log',
  startedAt: '2026-08-31T08:00:00Z',
  sizeBytes: 11,
}

const ENABLED: AgentTraceStateWire = {
  enabled: true,
  fileName: 'agent-trace-20260831.log',
  startedAt: '2026-08-31T09:15:00Z',
  sizeBytes: 11,
}

function contentResponse(content: string, sizeBytes: number, fromOffset: number) {
  return { content, sizeBytes, fromOffset } satisfies AgentTraceContentWire
}

/** getJson call counting helper for the content endpoint. */
function contentCalls() {
  return mockGet.mock.calls.filter(([url]) => url === '/admin/agent-trace/content')
}

function mockApi(state: AgentTraceStateWire, content: AgentTraceContentWire): void {
  mockGet.mockImplementation((url: string) => {
    if (url === '/admin/agent-trace') return Promise.resolve(state) as Promise<never>
    if (url === '/admin/agent-trace/content') return Promise.resolve(content) as Promise<never>
    return Promise.reject(new Error(`unexpected GET ${url}`)) as Promise<never>
  })
}

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <MemoryRouter>
          <AgentTracePage />
        </MemoryRouter>
      </ToastProvider>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  mockGet.mockReset()
  mockPost.mockReset()
  mockApiGet.mockReset()
})

/* -------------------------------------------------------------------------- */
/* Status                                                                      */
/* -------------------------------------------------------------------------- */

describe('AgentTracePage status', () => {
  it('renders the never-enabled state honestly: off, no file, honest empty viewer', async () => {
    mockApi(NEVER_ENABLED, contentResponse('', 0, 0))
    renderPage()

    expect(await screen.findByText('Disabled')).toBeInTheDocument()
    expect(screen.getByText('No file yet')).toBeInTheDocument()
    expect(screen.getByText('No trace file yet')).toBeInTheDocument()
    /* No file to read and nothing to download. */
    expect(mockGet.mock.calls.some(([url]) => url === '/admin/agent-trace/content')).toBe(false)
    expect(screen.getByRole('button', { name: /Download trace/ })).toBeDisabled()
  })

  it('shows the file name, start time and size of a stopped trace', async () => {
    mockApi(DISABLED_WITH_FILE, contentResponse('hello trace', 11, 0))
    renderPage()

    expect(await screen.findByText('agent-trace-20260831.log')).toBeInTheDocument()
    expect(screen.getByText(/Started /)).toBeInTheDocument()
    expect(screen.getByText(/11 B/)).toBeInTheDocument()
    /* Disabled but a file exists: loaded once, statically. */
    expect((await screen.findByLabelText('Trace content')).textContent).toContain('hello trace')
    expect(screen.getByText(/last captured content, loaded once/)).toBeInTheDocument()
  })
})

/* -------------------------------------------------------------------------- */
/* Toggle                                                                      */
/* -------------------------------------------------------------------------- */

describe('toggling', () => {
  it('posts {enabled:true} on enable and starts polling the fresh file', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      let state: AgentTraceStateWire = DISABLED_WITH_FILE
      mockGet.mockImplementation((url: string) => {
        if (url === '/admin/agent-trace') return Promise.resolve(state) as Promise<never>
        if (url === '/admin/agent-trace/content') {
          return Promise.resolve(contentResponse('hello trace', 11, 0)) as Promise<never>
        }
        return Promise.reject(new Error(`unexpected GET ${url}`)) as Promise<never>
      })
      mockPost.mockImplementation(() => {
        state = ENABLED
        return Promise.resolve(ENABLED) as Promise<never>
      })

      renderPage()
      const checkbox = await screen.findByRole('checkbox', { name: /Enable verbose tracing/ })
      await waitFor(() => expect(contentCalls().length).toBe(1))

      fireEvent.click(checkbox)

      await waitFor(() => {
        expect(mockPost).toHaveBeenCalledWith('/admin/agent-trace', { enabled: true })
      })
      expect(await screen.findByText('Enabled')).toBeInTheDocument()

      /* The state switched to enabled: the viewer now polls. */
      const before = contentCalls().length
      await act(async () => {
        await vi.advanceTimersByTimeAsync(3_000)
      })
      expect(contentCalls().length).toBeGreaterThan(before)
    } finally {
      vi.useRealTimers()
    }
  })

  it('posts {enabled:false} on disable, stops polling and keeps the content', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      let state: AgentTraceStateWire = ENABLED
      mockGet.mockImplementation((url: string) => {
        if (url === '/admin/agent-trace') return Promise.resolve(state) as Promise<never>
        if (url === '/admin/agent-trace/content') {
          return Promise.resolve(contentResponse('captured work', 13, 0)) as Promise<never>
        }
        return Promise.reject(new Error(`unexpected GET ${url}`)) as Promise<never>
      })
      mockPost.mockImplementation(() => {
        state = { ...DISABLED_WITH_FILE, sizeBytes: 13 }
        return Promise.resolve(state) as Promise<never>
      })

      renderPage()
      expect((await screen.findByLabelText('Trace content')).textContent).toContain(
        'captured work',
      )

      fireEvent.click(screen.getByRole('checkbox', { name: /Enable verbose tracing/ }))

      await waitFor(() => {
        expect(mockPost).toHaveBeenCalledWith('/admin/agent-trace', { enabled: false })
      })
      expect(await screen.findByText('Disabled')).toBeInTheDocument()

      /* Polling stopped, the last content stays on screen. */
      const before = contentCalls().length
      await act(async () => {
        await vi.advanceTimersByTimeAsync(6_000)
      })
      expect(contentCalls().length).toBe(before)
      expect(screen.getByLabelText('Trace content').textContent).toContain('captured work')
    } finally {
      vi.useRealTimers()
    }
  })

  it('disables the checkbox while the toggle is in flight', async () => {
    mockApi(DISABLED_WITH_FILE, contentResponse('', 0, 0))
    let resolvePost: ((value: AgentTraceStateWire) => void) | undefined
    mockPost.mockImplementation(
      () =>
        new Promise<AgentTraceStateWire>((resolve) => {
          resolvePost = resolve
        }) as Promise<never>,
    )
    renderPage()
    const checkbox = await screen.findByRole('checkbox', { name: /Enable verbose tracing/ })

    fireEvent.click(checkbox)
    await waitFor(() => expect(checkbox).toBeDisabled())

    await act(async () => resolvePost?.(ENABLED))
    await waitFor(() => expect(checkbox).toBeEnabled())
  })
})

/* -------------------------------------------------------------------------- */
/* Incremental viewer                                                          */
/* -------------------------------------------------------------------------- */

describe('incremental viewer', () => {
  it('appends new content read from the previous size as offset', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      const chunks = [
        contentResponse('line one\n', 9, 0),
        contentResponse('line two\n', 18, 9),
      ]
      let call = 0
      mockGet.mockImplementation((url: string) => {
        if (url === '/admin/agent-trace') return Promise.resolve(ENABLED) as Promise<never>
        if (url === '/admin/agent-trace/content') {
          return Promise.resolve(chunks[Math.min(call++, chunks.length - 1)]) as Promise<never>
        }
        return Promise.reject(new Error(`unexpected GET ${url}`)) as Promise<never>
      })

      renderPage()
      expect((await screen.findByLabelText('Trace content')).textContent).toContain('line one')

      await act(async () => {
        await vi.advanceTimersByTimeAsync(3_000)
      })

      /* Second read started where the file ended (sizeBytes of read one). */
      expect(contentCalls()[1]).toEqual([
        '/admin/agent-trace/content',
        { params: { offset: 9 } },
      ])
      const panel = screen.getByLabelText('Trace content')
      expect(panel.textContent).toContain('line one')
      expect(panel.textContent).toContain('line two')
    } finally {
      vi.useRealTimers()
    }
  })

  it('replaces the content when the server answers from a lower offset (trace restarted)', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      const chunks = [
        contentResponse('old run line\n', 13, 0),
        /* The file shrank: a restart — the server resent the new file in full. */
        contentResponse('fresh run\n', 10, 0),
      ]
      let call = 0
      mockGet.mockImplementation((url: string) => {
        if (url === '/admin/agent-trace') return Promise.resolve(ENABLED) as Promise<never>
        if (url === '/admin/agent-trace/content') {
          return Promise.resolve(chunks[Math.min(call++, chunks.length - 1)]) as Promise<never>
        }
        return Promise.reject(new Error(`unexpected GET ${url}`)) as Promise<never>
      })

      renderPage()
      expect((await screen.findByLabelText('Trace content')).textContent).toContain('old run line')

      await act(async () => {
        await vi.advanceTimersByTimeAsync(3_000)
      })

      const panel = screen.getByLabelText('Trace content')
      expect(panel.textContent).toContain('fresh run')
      expect(panel.textContent).not.toContain('old run line')
    } finally {
      vi.useRealTimers()
    }
  })

  it('resets the viewer when the trace file name changes', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      mockApi(ENABLED, contentResponse('first file\n', 11, 0))
      const nextState: AgentTraceStateWire = {
        enabled: true,
        fileName: 'agent-trace-20260901.log',
        startedAt: '2026-09-01T07:00:00Z',
        sizeBytes: 12,
      }
      mockPost.mockImplementation(() => Promise.resolve(nextState) as Promise<never>)

      renderPage()
      expect((await screen.findByLabelText('Trace content')).textContent).toContain('first file')

      /* Re-enabling starts a fresh file under a new name. */
      mockGet.mockImplementation((url: string, config?: { params?: { offset?: number } }) => {
        if (url === '/admin/agent-trace') return Promise.resolve(nextState) as Promise<never>
        if (url === '/admin/agent-trace/content') {
          return Promise.resolve(
            contentResponse('second file\n', 12, config?.params?.offset ?? 0),
          ) as Promise<never>
        }
        return Promise.reject(new Error(`unexpected GET ${url}`)) as Promise<never>
      })

      fireEvent.click(screen.getByRole('checkbox', { name: /Enable verbose tracing/ }))
      await waitFor(() => expect(mockPost).toHaveBeenCalled())

      await waitFor(() => {
        const panel = screen.getByLabelText('Trace content')
        expect(panel.textContent).toContain('second file')
        expect(panel.textContent).not.toContain('first file')
      })
      /* The new file was read from the start. */
      expect(
        contentCalls().some(([, config]) => (config as { params?: { offset?: number } })?.params?.offset === 0),
      ).toBe(true)
    } finally {
      vi.useRealTimers()
    }
  })
})

/* -------------------------------------------------------------------------- */
/* Download                                                                    */
/* -------------------------------------------------------------------------- */

describe('download', () => {
  it('fetches the trace through the authed client and saves it as a blob', async () => {
    mockApi(DISABLED_WITH_FILE, contentResponse('hello trace', 11, 0))
    mockApiGet.mockResolvedValue({ data: 'hello trace\n' } as never)

    const createObjectURL = vi.fn(() => 'blob:trace')
    const revokeObjectURL = vi.fn()
    vi.stubGlobal('URL', { ...URL, createObjectURL, revokeObjectURL })
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    try {
      renderPage()
      const button = await screen.findByRole('button', { name: /Download trace/ })
      await waitFor(() => expect(button).toBeEnabled())

      fireEvent.click(button)

      await waitFor(() => {
        expect(mockApiGet).toHaveBeenCalledWith(
          '/admin/agent-trace/download',
          expect.objectContaining({ responseType: 'text' }),
        )
      })
      await waitFor(() => {
        expect(createObjectURL).toHaveBeenCalledWith(expect.any(Blob))
      })
      expect(click).toHaveBeenCalled()
      expect(revokeObjectURL).toHaveBeenCalledWith('blob:trace')
    } finally {
      vi.unstubAllGlobals()
      click.mockRestore()
    }
  })
})
