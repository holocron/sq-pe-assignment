import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import type { AxiosResponse } from 'axios'
import type { ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import { ApiError } from '../../api/errors'
import type { KnowledgeChunk, KnowledgeDocument } from '../../api/types'
import { ToastProvider } from '../../components'
import { KnowledgeSearchPage } from '../KnowledgeSearchPage'
import {
  DocumentUploader,
  extractQueryTerms,
  highlightSegments,
  normalizeSimilarity,
  similarityBand,
  validateKnowledgeFile,
} from '../knowledge'

/* -------------------------------------------------------------------------- */
/* Helpers                                                                     */
/* -------------------------------------------------------------------------- */

function axiosOk<T>(data: T): AxiosResponse<T> {
  return {
    data,
    status: 200,
    statusText: 'OK',
    headers: {},
    config: { headers: {} },
  } as unknown as AxiosResponse<T>
}

function renderWithProviders(ui: ReactNode) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0, staleTime: 0 },
      mutations: { retry: false },
    },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <ToastProvider>{ui}</ToastProvider>
    </QueryClientProvider>,
  )
}

/** jsdom keeps `files` read-only, so it is defined on the element directly. */
function selectFile(input: HTMLElement, file: File): void {
  Object.defineProperty(input, 'files', { value: [file], configurable: true })
  fireEvent.change(input)
}

const uploadedDocument: KnowledgeDocument = {
  documentId: '9f3f2a52-1c3f-4a6f-9d38-2b0f5b0b1f11',
  filename: 'aml-thresholds.pdf',
  title: 'AML Thresholds Policy',
  mimeType: 'application/pdf',
  sizeBytes: 24_576,
  chunkCount: 42,
  status: 'INDEXED',
  uploadedBy: 'admin',
  uploadedAt: '2026-08-29T09:15:00Z',
  error: null,
}

const searchHits: KnowledgeChunk[] = [
  {
    id: 'chunk-1',
    content:
      'Aggregated cash deposits below the reporting threshold must be escalated to the AML desk within one business day.',
    score: 0.88,
    documentId: uploadedDocument.documentId,
    filename: 'aml-thresholds.pdf',
    title: 'AML Thresholds Policy',
    sectionTitle: 'Reporting thresholds',
    chunkIndex: 3,
  },
  {
    // Source attribution nested under metadata — the tolerant wire shape.
    id: 'chunk-2',
    content: 'Wire transfers to sanctioned jurisdictions are prohibited without a compliance waiver.',
    score: 0.72,
    metadata: {
      document_id: 'b71e7a6c-1f0d-4f39-9f2e-9a5f4d6b7c88',
      filename: 'sanctions.docx',
      title: 'Sanctions Policy',
      section_title: 'Prohibited corridors',
      chunk_index: 7,
    },
  },
]

/* -------------------------------------------------------------------------- */
/* File-type validation                                                        */
/* -------------------------------------------------------------------------- */

describe('validateKnowledgeFile', () => {
  it('rejects a .txt file before any request is made', () => {
    const result = validateKnowledgeFile(
      new File(['policy notes'], 'notes.txt', { type: 'text/plain' }),
    )
    expect(result.ok).toBe(false)
    if (result.ok) return
    expect(result.reason).toBe('type')
    expect(result.message).toContain('.docx or .pdf')
  })

  it('accepts a .pdf file', () => {
    const result = validateKnowledgeFile(
      new File(['%PDF-1.7'], 'aml-thresholds.pdf', { type: 'application/pdf' }),
    )
    expect(result.ok).toBe(true)
  })

  it('accepts a .docx file regardless of case', () => {
    const result = validateKnowledgeFile(
      new File(['PK'], 'Sanctions.DOCX', {
        type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
      }),
    )
    expect(result.ok).toBe(true)
  })

  it('rejects an empty file', () => {
    const result = validateKnowledgeFile(new File([], 'empty.pdf', { type: 'application/pdf' }))
    expect(result.ok).toBe(false)
    if (result.ok) return
    expect(result.reason).toBe('empty')
  })
})

describe('query term highlighting', () => {
  it('drops stopwords and short tokens', () => {
    expect(extractQueryTerms('What is the reporting threshold for cash?')).toEqual([
      'reporting',
      'threshold',
      'cash',
    ])
  })

  it('marks whole-word occurrences only', () => {
    const segments = highlightSegments('Thresholds and the threshold rule', ['threshold'])
    expect(segments.filter((segment) => segment.match).map((segment) => segment.text)).toEqual([
      'threshold',
    ])
    expect(segments.map((segment) => segment.text).join('')).toBe(
      'Thresholds and the threshold rule',
    )
  })
})

describe('similarity presentation', () => {
  it('accepts both a 0..1 cosine score and a 0..100 percentage', () => {
    expect(normalizeSimilarity(0.82)).toBeCloseTo(0.82)
    expect(normalizeSimilarity(82)).toBeCloseTo(0.82)
    expect(normalizeSimilarity(null)).toBeNull()
    expect(normalizeSimilarity(Number.NaN)).toBeNull()
  })

  it('labels every band so colour is never the only signal', () => {
    expect(similarityBand(0.9).label).toBe('Strong match')
    expect(similarityBand(0.7).label).toBe('Good match')
    expect(similarityBand(0.55).label).toBe('Moderate match')
    expect(similarityBand(0.2).label).toBe('Weak match')
  })
})

/* -------------------------------------------------------------------------- */
/* Uploader                                                                    */
/* -------------------------------------------------------------------------- */

describe('DocumentUploader', () => {
  it('rejects an unsupported file type without calling the API', async () => {
    const post = vi.spyOn(api, 'post')
    renderWithProviders(<DocumentUploader />)

    selectFile(
      screen.getByLabelText('Policy document file'),
      new File(['policy notes'], 'notes.txt', { type: 'text/plain' }),
    )

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Unsupported file')
    expect(alert).toHaveTextContent(/accepts \.docx or \.pdf documents only/i)
    expect(post).not.toHaveBeenCalled()
  })

  it('uploads an accepted .pdf and reports the indexed chunk count', async () => {
    const post = vi.spyOn(api, 'post').mockResolvedValue(axiosOk(uploadedDocument))
    const onUploaded = vi.fn()
    renderWithProviders(<DocumentUploader onUploaded={onUploaded} />)

    selectFile(
      screen.getByLabelText('Policy document file'),
      new File(['%PDF-1.7'], 'aml-thresholds.pdf', { type: 'application/pdf' }),
    )

    await waitFor(() => expect(post).toHaveBeenCalledTimes(1))
    expect(post).toHaveBeenCalledWith(
      '/knowledge/documents',
      expect.any(FormData),
      expect.objectContaining({ timeout: expect.any(Number) }),
    )

    expect(
      await screen.findByText(/Indexed 42 chunks from “aml-thresholds\.pdf”/),
    ).toBeInTheDocument()
    expect(screen.getByText('AML Thresholds Policy')).toBeInTheDocument()
    expect(screen.getByText('Indexed')).toBeInTheDocument()
    expect(onUploaded).toHaveBeenCalledWith(uploadedDocument)
  })

  it('surfaces a failed ingestion instead of hiding it', async () => {
    vi.spyOn(api, 'post').mockResolvedValue(
      axiosOk<KnowledgeDocument>({
        ...uploadedDocument,
        status: 'FAILED',
        chunkCount: 0,
        error: 'Unreadable PDF: no extractable text layer',
      }),
    )
    renderWithProviders(<DocumentUploader />)

    selectFile(
      screen.getByLabelText('Policy document file'),
      new File(['%PDF-1.7'], 'aml-thresholds.pdf', { type: 'application/pdf' }),
    )

    expect(
      await screen.findByText('Unreadable PDF: no extractable text layer'),
    ).toBeInTheDocument()
    expect(screen.getByText('Failed')).toBeInTheDocument()
  })
})

/* -------------------------------------------------------------------------- */
/* Search page                                                                 */
/* -------------------------------------------------------------------------- */

describe('KnowledgeSearchPage', () => {
  function submitSearch(query: string): void {
    fireEvent.change(screen.getByLabelText('Search the policy knowledge base'), {
      target: { value: query },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Search' }))
  }

  it('shows the idle state before a search is run', () => {
    renderWithProviders(<KnowledgeSearchPage />)
    expect(screen.getByText('Search the knowledge base')).toBeInTheDocument()
  })

  it('renders retrieved chunks with source, section, similarity and highlights', async () => {
    const post = vi.spyOn(api, 'post').mockResolvedValue(axiosOk(searchHits))
    renderWithProviders(<KnowledgeSearchPage />)

    submitSearch('structuring threshold')

    const results = await screen.findAllByRole('article')
    expect(results).toHaveLength(2)
    expect(post.mock.calls[0]?.[0]).toBe('/knowledge/search')
    expect(post.mock.calls[0]?.[1]).toEqual({ query: 'structuring threshold', topK: 5 })

    const [first, second] = results
    if (!first || !second) throw new Error('expected two result cards')

    expect(first).toHaveTextContent(
      'Aggregated cash deposits below the reporting threshold must be escalated',
    )
    expect(within(first).getByText('AML Thresholds Policy')).toBeInTheDocument()
    expect(within(first).getByText('Reporting thresholds')).toBeInTheDocument()
    expect(within(first).getByText('threshold', { selector: 'mark' })).toBeInTheDocument()
    expect(within(first).getByText('Strong match')).toBeInTheDocument()
    expect(within(first).getByText('88%')).toBeInTheDocument()

    // Second hit carries its attribution under `metadata` and still renders.
    expect(within(second).getByText('Sanctions Policy')).toBeInTheDocument()
    expect(within(second).getByText('Prohibited corridors')).toBeInTheDocument()
    expect(within(second).getByText('Good match')).toBeInTheDocument()

    expect(
      await screen.findByText(/2 passages retrieved for “structuring threshold” \(top 5\)/),
    ).toBeInTheDocument()
  })

  it('renders the empty state when nothing matches', async () => {
    vi.spyOn(api, 'post').mockResolvedValue(axiosOk<KnowledgeChunk[]>([]))
    renderWithProviders(<KnowledgeSearchPage />)

    submitSearch('unrelated wording')

    expect(await screen.findByText('No passages matched')).toBeInTheDocument()
  })

  it('renders the error state when retrieval fails', async () => {
    vi.spyOn(api, 'post').mockRejectedValue(
      new ApiError({
        status: 503,
        title: 'Backend unavailable',
        detail: 'The vector store is not reachable.',
      }),
    )
    renderWithProviders(<KnowledgeSearchPage />)

    submitSearch('structuring threshold')

    expect(await screen.findByText('Search failed')).toBeInTheDocument()
    expect(screen.getByText('The vector store is not reachable.')).toBeInTheDocument()
  })
})
