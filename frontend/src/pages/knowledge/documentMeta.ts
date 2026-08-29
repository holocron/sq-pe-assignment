import type { KnowledgeDocumentStatus } from '../../api/types'
import type { BadgeTone } from '../../components'
import { humanizeToken } from '../../lib/format'
import { fileExtension } from './fileValidation'

export interface KnowledgeStatusMeta {
  label: string
  tone: BadgeTone
  /** Tooltip text — the badge never relies on colour alone. */
  description: string
  /** True while the backend is still chunking/embedding the document. */
  inFlight: boolean
  failed: boolean
}

const STATUS_META: Record<KnowledgeDocumentStatus, KnowledgeStatusMeta> = {
  PENDING: {
    label: 'Pending',
    tone: 'neutral',
    description: 'Queued for ingestion — not searchable yet.',
    inFlight: true,
    failed: false,
  },
  PROCESSING: {
    label: 'Processing',
    tone: 'info',
    description: 'Chunking and embedding in progress.',
    inFlight: true,
    failed: false,
  },
  INDEXED: {
    label: 'Indexed',
    tone: 'success',
    description: 'Chunks are embedded and retrievable by the agent.',
    inFlight: false,
    failed: false,
  },
  READY: {
    label: 'Ready',
    tone: 'success',
    description: 'Chunks are embedded and retrievable by the agent.',
    inFlight: false,
    failed: false,
  },
  FAILED: {
    label: 'Failed',
    tone: 'danger',
    description: 'Ingestion failed — this document is not searchable.',
    inFlight: false,
    failed: true,
  },
}

/** Tolerant lookup: an unrecognised status still renders, never crashes. */
export function knowledgeStatusMeta(status: string | null | undefined): KnowledgeStatusMeta {
  const key = (status ?? '').trim().toUpperCase()
  const known = STATUS_META[key as KnowledgeDocumentStatus] as KnowledgeStatusMeta | undefined
  return (
    known ?? {
      label: humanizeToken(status),
      tone: 'neutral',
      description: 'Unrecognised ingestion status reported by the backend.',
      inFlight: false,
      failed: false,
    }
  )
}

const MIME_LABELS: Record<string, string> = {
  'application/pdf': 'PDF',
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document': 'DOCX',
  'application/msword': 'DOC',
}

const EXTENSION_LABELS: Record<string, string> = {
  '.pdf': 'PDF',
  '.docx': 'DOCX',
  '.doc': 'DOC',
}

/** Short, table-friendly type label derived from the MIME type or filename. */
export function documentTypeLabel(
  mimeType: string | null | undefined,
  filename: string | null | undefined,
): string {
  const mime = (mimeType ?? '').trim().toLowerCase()
  const byMime = MIME_LABELS[mime] as string | undefined
  if (byMime) return byMime
  const extension = fileExtension(filename ?? '')
  const byExtension = EXTENSION_LABELS[extension] as string | undefined
  if (byExtension) return byExtension
  if (extension) return extension.slice(1).toUpperCase()
  return mime ? mime.split('/').pop()?.toUpperCase() ?? 'FILE' : 'FILE'
}
