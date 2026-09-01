import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { getJson, postJson, putJson } from '../../api/client'
import { ApiError } from '../../api/errors'
import type { LlmSettingsWire } from '../../api/types'
import { ToastProvider } from '../../components/ui/Toast'
import { LlmSettingsPage } from '../admin/LlmSettingsPage'

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
const mockPut = vi.mocked(putJson)

/* -------------------------------------------------------------------------- */
/* Fixtures                                                                    */
/* -------------------------------------------------------------------------- */

const SETTINGS: LlmSettingsWire = {
  baseUrl: 'http://localhost:11434/v1',
  chatModel: 'gpt-oss-120b',
  toolModel: 'qwen3-32b',
  embedModel: 'text-embedding-3-small',
  embedDimension: 1536,
  chatApiKeySet: true,
  embedApiKeySet: false,
  source: 'database',
  updatedAt: '2026-08-30T12:00:00Z',
  updatedBy: 'admin',
}

const IDLE_REEMBED = {
  running: false,
  totalDocuments: 0,
  completedDocuments: 0,
  failedDocuments: 0,
  lastError: null,
}

const MODEL_LIST = { models: ['gpt-oss-120b', 'qwen3-32b', 'text-embedding-3-small', 'bge-m3'] }

function mockApi(settings: LlmSettingsWire = SETTINGS): void {
  mockGet.mockImplementation((url: string) => {
    if (url === '/admin/llm-settings') return Promise.resolve(settings) as Promise<never>
    if (url === '/admin/llm-settings/reembed-status') {
      return Promise.resolve(IDLE_REEMBED) as Promise<never>
    }
    if (url === '/admin/llm-settings/models') return Promise.resolve(MODEL_LIST) as Promise<never>
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
          <LlmSettingsPage />
        </MemoryRouter>
      </ToastProvider>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  mockApi()
  mockPost.mockReset()
  mockPut.mockReset()
})

/* -------------------------------------------------------------------------- */
/* Current settings                                                            */
/* -------------------------------------------------------------------------- */

describe('LlmSettingsPage', () => {
  it('renders the current settings with source and dimension shown', async () => {
    renderPage()

    expect(await screen.findByLabelText(/Base URL/)).toHaveValue('http://localhost:11434/v1')
    expect(screen.getByLabelText(/^Reasoning model \*$/)).toHaveValue('gpt-oss-120b')
    expect(screen.getByLabelText(/^Embedding model \*$/)).toHaveValue('text-embedding-3-small')
    expect(screen.getByText('Database override')).toBeInTheDocument()
    expect(screen.getByText('Embedding dimension 1536')).toBeInTheDocument()
    /* The stored keys are never shown — only acknowledged as placeholders. */
    expect(screen.getByLabelText('Chat models API key')).toHaveAttribute(
      'placeholder',
      '•••• configured',
    )
    expect(screen.getByLabelText('Embedding model API key')).toHaveAttribute(
      'placeholder',
      'Not configured',
    )
  })

  it('surfaces a failed settings load with a retry affordance', async () => {
    mockGet.mockImplementation((url: string) => {
      if (url === '/admin/llm-settings') return Promise.reject(new Error('boom')) as Promise<never>
      if (url === '/admin/llm-settings/reembed-status') {
        return Promise.resolve(IDLE_REEMBED) as Promise<never>
      }
      return Promise.reject(new Error(`unexpected GET ${url}`)) as Promise<never>
    })
    renderPage()
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument()
  })

  it('requires an http(s) base URL before saving', async () => {
    renderPage()
    const baseUrl = await screen.findByLabelText(/Base URL/)
    fireEvent.change(baseUrl, { target: { value: 'localhost:11434/v1' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save settings' }))

    expect(screen.getByRole('alert')).toHaveTextContent(/Must be an http\(s\) URL/)
    expect(mockPut).not.toHaveBeenCalled()
  })
})

/* -------------------------------------------------------------------------- */
/* Model dropdowns                                                             */
/* -------------------------------------------------------------------------- */

describe('model list', () => {
  it('populates the chat and embedding selects from the models endpoint', async () => {
    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: 'Fetch models' }))

    await waitFor(() => {
      expect(mockGet).toHaveBeenCalledWith('/admin/llm-settings/models', {
        params: { baseUrl: 'http://localhost:11434/v1' },
      })
    })

    const chatSelect = (await screen.findByLabelText(/^Reasoning model$/)) as HTMLSelectElement
    expect(chatSelect.tagName).toBe('SELECT')
    expect(chatSelect).toHaveValue('gpt-oss-120b')
    expect(screen.getAllByRole('option', { name: 'qwen3-32b' }).length).toBeGreaterThan(0)
    expect(screen.getAllByRole('option', { name: 'bge-m3' }).length).toBeGreaterThan(0)
  })

  it('falls back to free-text fields with the error shown when the endpoint is unreachable', async () => {
    mockGet.mockImplementation((url: string) => {
      if (url === '/admin/llm-settings') return Promise.resolve(SETTINGS) as Promise<never>
      if (url === '/admin/llm-settings/reembed-status') {
        return Promise.resolve(IDLE_REEMBED) as Promise<never>
      }
      if (url === '/admin/llm-settings/models') {
        return Promise.reject(
          new ApiError({
            status: 502,
            title: 'Backend unavailable',
            detail: 'The endpoint did not answer.',
          }),
        ) as Promise<never>
      }
      return Promise.reject(new Error(`unexpected GET ${url}`)) as Promise<never>
    })
    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: 'Fetch models' }))

    expect(await screen.findByText('Model list unavailable')).toBeInTheDocument()
    expect(screen.getByText('The endpoint did not answer.')).toBeInTheDocument()
    const chatInput = screen.getByLabelText(/^Reasoning model \*$/)
    expect(chatInput.tagName).toBe('INPUT')
    expect(chatInput).toHaveValue('gpt-oss-120b')
    expect(screen.getByLabelText(/^Embedding model \*$/)).toHaveValue('text-embedding-3-small')
  })
})

/* -------------------------------------------------------------------------- */
/* Save                                                                        */
/* -------------------------------------------------------------------------- */

describe('saving', () => {
  it('saves without confirmReembed when the embedding model is unchanged', async () => {
    mockPut.mockResolvedValue({ ...SETTINGS, reembedStarted: false } as never)
    renderPage()
    await screen.findByLabelText(/Base URL/)

    fireEvent.click(screen.getByRole('button', { name: 'Save settings' }))

    await waitFor(() => {
      expect(mockPut).toHaveBeenCalledWith('/admin/llm-settings', {
        baseUrl: 'http://localhost:11434/v1',
        chatModel: 'gpt-oss-120b',
        toolModel: 'qwen3-32b',
        embedModel: 'text-embedding-3-small',
      })
    })
    /* No confirmation was asked for, and no empty apiKey was sent. */
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('omits untouched keys and sends the dirty per-model keys on save', async () => {
    mockPut.mockResolvedValue({ ...SETTINGS, chatApiKeySet: true, reembedStarted: false } as never)
    renderPage()
    await screen.findByLabelText(/Base URL/)

    fireEvent.change(screen.getByLabelText('Chat models API key'), {
      target: { value: 'sk-chat-new' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Save settings' }))

    await waitFor(() => {
      expect(mockPut).toHaveBeenCalledWith('/admin/llm-settings', {
        baseUrl: 'http://localhost:11434/v1',
        chatModel: 'gpt-oss-120b',
        toolModel: 'qwen3-32b',
        embedModel: 'text-embedding-3-small',
        chatApiKey: 'sk-chat-new',
      })
    })
    /* The embedding key field was never touched — omitted, so the server
       keeps the stored one (omitted ≠ ''). */
    expect(mockPut.mock.calls[0][1]).not.toHaveProperty('embedApiKey')
  })

  it('sends an empty string for a key the admin typed into and then cleared', async () => {
    mockPut.mockResolvedValue({ ...SETTINGS, chatApiKeySet: false, reembedStarted: false } as never)
    renderPage()
    await screen.findByLabelText(/Base URL/)
    const chatKey = screen.getByLabelText('Chat models API key')

    fireEvent.change(chatKey, { target: { value: 'sk-temp' } })
    fireEvent.change(chatKey, { target: { value: '' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save settings' }))

    /* Cleared, not untouched: '' tells the server "explicitly no key". */
    await waitFor(() => {
      expect(mockPut).toHaveBeenCalledWith(
        '/admin/llm-settings',
        expect.objectContaining({ chatApiKey: '' }),
      )
    })
  })

  it('asks for confirmation before changing the embedding model, then sends confirmReembed', async () => {
    mockPut.mockResolvedValue({
      ...SETTINGS,
      embedModel: 'bge-m3',
      reembedStarted: false,
    } as never)
    renderPage()
    const embedField = await screen.findByLabelText(/^Embedding model \*$/)
    fireEvent.change(embedField, { target: { value: 'bge-m3' } })

    fireEvent.click(screen.getByRole('button', { name: 'Save settings' }))

    const dialog = await screen.findByRole('dialog')
    expect(dialog).toHaveTextContent(/rebuilds every knowledge-base vector/i)
    expect(dialog).toHaveTextContent(/re-embedded automatically/)
    expect(mockPut).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', { name: 'Re-embed and save' }))
    await waitFor(() => {
      expect(mockPut).toHaveBeenCalledWith('/admin/llm-settings', {
        baseUrl: 'http://localhost:11434/v1',
        chatModel: 'gpt-oss-120b',
        toolModel: 'qwen3-32b',
        embedModel: 'bge-m3',
        confirmReembed: true,
      })
    })
  })

  it('does nothing when the confirmation is cancelled', async () => {
    renderPage()
    const embedField = await screen.findByLabelText(/^Embedding model \*$/)
    fireEvent.change(embedField, { target: { value: 'bge-m3' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save settings' }))

    await screen.findByRole('dialog')
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }))

    expect(mockPut).not.toHaveBeenCalled()
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })
  })

  it('shows the same confirmation modal when the server answers 409', async () => {
    mockPut.mockRejectedValue(
      new ApiError({
        status: 409,
        title: 'Embedding model change requires re-embedding',
      }),
    )
    renderPage()
    await screen.findByLabelText(/Base URL/)

    /* Even a save that went straight to PUT (no client-side change detected
       here because the loaded value races the mock) surfaces the modal, not a
       toast. */
    fireEvent.change(await screen.findByLabelText(/^Embedding model \*$/), {
      target: { value: 'text-embedding-3-small' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Save settings' }))

    const dialog = await screen.findByRole('dialog')
    expect(dialog).toHaveTextContent(/Change the embedding model/)
  })
})

/* -------------------------------------------------------------------------- */
/* Connection test                                                             */
/* -------------------------------------------------------------------------- */

describe('connection test', () => {
  it('sends the dirty per-model keys and omits untouched ones', async () => {
    mockPost.mockResolvedValue({
      chat: { ok: true, detail: null },
      tool: { ok: true, detail: null },
      embed: { ok: true, detail: null, dimension: 1536 },
    } as never)
    renderPage()
    await screen.findByLabelText(/Base URL/)

    fireEvent.change(screen.getByLabelText('Embedding model API key'), {
      target: { value: 'sk-embed-new' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Test connection' }))

    await waitFor(() => {
      expect(mockPost).toHaveBeenCalledWith('/admin/llm-settings/test', {
        baseUrl: 'http://localhost:11434/v1',
        chatModel: 'gpt-oss-120b',
        toolModel: 'qwen3-32b',
        embedModel: 'text-embedding-3-small',
        embedApiKey: 'sk-embed-new',
      })
    })
    expect(mockPost.mock.calls[0][1]).not.toHaveProperty('chatApiKey')
  })

  it('renders per-model results with the embedding dimension', async () => {
    mockPost.mockResolvedValue({
      chat: { ok: true, detail: 'Answered in 240 ms.' },
      tool: { ok: true, detail: 'Answered in 95 ms.' },
      embed: { ok: false, detail: 'Model not found on the endpoint.', dimension: null },
    } as never)
    renderPage()
    await screen.findByLabelText(/Base URL/)

    fireEvent.click(screen.getByRole('button', { name: 'Test connection' }))

    await waitFor(() => {
      expect(mockPost).toHaveBeenCalledWith('/admin/llm-settings/test', {
        baseUrl: 'http://localhost:11434/v1',
        chatModel: 'gpt-oss-120b',
        toolModel: 'qwen3-32b',
        embedModel: 'text-embedding-3-small',
      })
    })

    const results = await screen.findByLabelText('Connection test results')
    expect(results).toHaveTextContent('Reasoning model — OK')
    expect(results).toHaveTextContent('Answered in 240 ms.')
    expect(results).toHaveTextContent('Tooling model — OK')
    expect(results).toHaveTextContent('Embedding model — failed')
    expect(results).toHaveTextContent('Model not found on the endpoint.')
  })

  it('shows the embedding dimension when the probe reports it', async () => {
    mockPost.mockResolvedValue({
      chat: { ok: true, detail: null },
      tool: { ok: true, detail: null },
      embed: { ok: true, detail: 'Embedded a probe sentence.', dimension: 1024 },
    } as never)
    renderPage()
    await screen.findByLabelText(/Base URL/)

    fireEvent.click(screen.getByRole('button', { name: 'Test connection' }))

    const results = await screen.findByLabelText('Connection test results')
    expect(results).toHaveTextContent('Embedding model — OK')
    expect(results).toHaveTextContent(/dimension 1.?024/)
  })
})

/* -------------------------------------------------------------------------- */
/* Re-embed progress                                                           */
/* -------------------------------------------------------------------------- */

describe('re-embed progress', () => {
  it('polls while running and shows the terminal summary with failures', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      mockPut.mockResolvedValue({ ...SETTINGS, reembedStarted: true } as never)
      const running = {
        running: true,
        totalDocuments: 12,
        completedDocuments: 3,
        failedDocuments: 0,
        lastError: null,
      }
      const done = {
        running: false,
        totalDocuments: 12,
        completedDocuments: 11,
        failedDocuments: 1,
        lastError: 'handbook.docx: embedding timed out',
      }
      mockGet.mockImplementation((url: string) => {
        if (url === '/admin/llm-settings') return Promise.resolve(SETTINGS) as Promise<never>
        if (url === '/admin/llm-settings/reembed-status') {
          return Promise.resolve(wasDone ? done : running) as Promise<never>
        }
        return Promise.reject(new Error(`unexpected GET ${url}`)) as Promise<never>
      })
      let wasDone = false

      renderPage()
      await screen.findByLabelText(/Base URL/)

      fireEvent.click(screen.getByRole('button', { name: 'Save settings' }))
      await waitFor(() => expect(mockPut).toHaveBeenCalled())

      /* The save started the rebuild: progress is shown and polling continues. */
      expect(
        await screen.findByText(/Re-embedding documents — 3 of 12/),
      ).toBeInTheDocument()
      expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '25')

      /* Next poll: the rebuild is done, with one honest failure. */
      wasDone = true
      await act(async () => {
        await vi.advanceTimersByTimeAsync(3_000)
      })

      expect(await screen.findByText('Re-embedding finished with 1 failure')).toBeInTheDocument()
      expect(screen.getByText(/11 of 12 documents re-embedded/)).toBeInTheDocument()
      expect(screen.getByText('handbook.docx: embedding timed out')).toBeInTheDocument()
      expect(screen.queryByText(/Re-embedding documents —/)).not.toBeInTheDocument()
    } finally {
      vi.useRealTimers()
    }
  })
})
