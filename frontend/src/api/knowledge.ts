import {
  useMutation,
  useQuery,
  useQueryClient,
  type UseMutationResult,
  type UseQueryResult,
} from '@tanstack/react-query'
import { api, deleteJson, getJson, postJson } from './client'
import type { ApiError } from './errors'
import type { MutationOpts, QueryOpts } from './query'
import { queryKeys } from './queryKeys'
import type {
  JsonObject,
  KnowledgeChunk,
  KnowledgeDocument,
  KnowledgeSearchRequest,
  UUID,
} from './types'

/** Accepted upload types (BUILD_SPEC section 5). */
export const ACCEPTED_KNOWLEDGE_MIME_TYPES = [
  'application/pdf',
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
] as const
export const ACCEPTED_KNOWLEDGE_EXTENSIONS = ['.pdf', '.docx'] as const

function metadataString(metadata: JsonObject | null | undefined, key: string): string | null {
  const value = metadata?.[key]
  return typeof value === 'string' ? value : null
}

function metadataNumber(metadata: JsonObject | null | undefined, key: string): number | null {
  const value = metadata?.[key]
  if (typeof value === 'number') return value
  if (typeof value === 'string' && value.trim() !== '' && Number.isFinite(Number(value))) {
    return Number(value)
  }
  return null
}

/** Source attribution may be flattened or nested under `metadata` — flatten it. */
export function normalizeChunk(chunk: KnowledgeChunk): KnowledgeChunk {
  const metadata = chunk.metadata ?? null
  return {
    ...chunk,
    content: chunk.content ?? '',
    documentId: chunk.documentId ?? metadataString(metadata, 'document_id') ?? metadataString(metadata, 'documentId'),
    filename: chunk.filename ?? metadataString(metadata, 'filename'),
    title: chunk.title ?? metadataString(metadata, 'title'),
    sectionTitle:
      chunk.sectionTitle ??
      metadataString(metadata, 'section_title') ??
      metadataString(metadata, 'sectionTitle'),
    chunkIndex:
      chunk.chunkIndex ??
      metadataNumber(metadata, 'chunk_index') ??
      metadataNumber(metadata, 'chunkIndex'),
  }
}

/** `GET /api/knowledge/documents` */
export async function fetchKnowledgeDocuments(): Promise<KnowledgeDocument[]> {
  return (await getJson<KnowledgeDocument[]>('/knowledge/documents')) ?? []
}

/** `POST /api/knowledge/documents` (multipart `file`, ADMIN) */
export async function uploadKnowledgeDocument(
  file: File,
  onProgress?: (percent: number) => void,
): Promise<KnowledgeDocument> {
  const form = new FormData()
  form.append('file', file)
  const response = await api.post<KnowledgeDocument>('/knowledge/documents', form, {
    timeout: 120_000,
    onUploadProgress: (event) => {
      if (!onProgress) return
      const total = event.total ?? 0
      onProgress(total > 0 ? Math.round((event.loaded / total) * 100) : 0)
    },
  })
  return response.data
}

/** `DELETE /api/knowledge/documents/{documentId}` (ADMIN) */
export function deleteKnowledgeDocument(documentId: UUID): Promise<void> {
  return deleteJson(`/knowledge/documents/${documentId}`)
}

/** `POST /api/knowledge/search` */
export async function searchKnowledge(
  request: KnowledgeSearchRequest,
): Promise<KnowledgeChunk[]> {
  const chunks = await postJson<KnowledgeChunk[], KnowledgeSearchRequest>('/knowledge/search', {
    query: request.query,
    topK: request.topK ?? 5,
  })
  return (chunks ?? []).map(normalizeChunk)
}

/* -------------------------------------------------------------------------- */
/* Hooks                                                                       */
/* -------------------------------------------------------------------------- */

export function useKnowledgeDocuments(
  options?: QueryOpts<KnowledgeDocument[]>,
): UseQueryResult<KnowledgeDocument[], ApiError> {
  return useQuery<KnowledgeDocument[], ApiError, KnowledgeDocument[], readonly unknown[]>({
    queryKey: queryKeys.knowledge.documents(),
    queryFn: fetchKnowledgeDocuments,
    ...options,
  })
}

export interface UploadKnowledgeVariables {
  file: File
  onProgress?: (percent: number) => void
}

export function useUploadKnowledgeDocument(
  options?: MutationOpts<KnowledgeDocument, UploadKnowledgeVariables>,
): UseMutationResult<KnowledgeDocument, ApiError, UploadKnowledgeVariables> {
  const queryClient = useQueryClient()
  return useMutation<KnowledgeDocument, ApiError, UploadKnowledgeVariables>({
    mutationFn: ({ file, onProgress }) => uploadKnowledgeDocument(file, onProgress),
    ...options,
    onSuccess: (data, variables, onMutateResult, context) => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.knowledge.documents() })
      options?.onSuccess?.(data, variables, onMutateResult, context)
    },
  })
}

export function useDeleteKnowledgeDocument(
  options?: MutationOpts<void, UUID>,
): UseMutationResult<void, ApiError, UUID> {
  const queryClient = useQueryClient()
  return useMutation<void, ApiError, UUID>({
    mutationFn: deleteKnowledgeDocument,
    ...options,
    onSuccess: (data, variables, onMutateResult, context) => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.knowledge.documents() })
      options?.onSuccess?.(data, variables, onMutateResult, context)
    },
  })
}

/**
 * Cached RAG search. Pass `null` until the operator submits a query; the hook
 * stays disabled and the page can show its empty state.
 */
export function useKnowledgeSearch(
  request: KnowledgeSearchRequest | null,
  options?: QueryOpts<KnowledgeChunk[]>,
): UseQueryResult<KnowledgeChunk[], ApiError> {
  const normalized: KnowledgeSearchRequest = {
    query: request?.query.trim() ?? '',
    topK: request?.topK ?? 5,
  }
  return useQuery<KnowledgeChunk[], ApiError, KnowledgeChunk[], readonly unknown[]>({
    queryKey: queryKeys.knowledge.search(normalized),
    queryFn: () => searchKnowledge(normalized),
    enabled: normalized.query.length > 0,
    staleTime: 60_000,
    ...options,
  })
}

/** Imperative variant for search-on-submit UIs. */
export function useKnowledgeSearchMutation(
  options?: MutationOpts<KnowledgeChunk[], KnowledgeSearchRequest>,
): UseMutationResult<KnowledgeChunk[], ApiError, KnowledgeSearchRequest> {
  return useMutation<KnowledgeChunk[], ApiError, KnowledgeSearchRequest>({
    mutationFn: searchKnowledge,
    ...options,
  })
}
