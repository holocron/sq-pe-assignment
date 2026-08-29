import {
  ACCEPTED_KNOWLEDGE_EXTENSIONS,
  ACCEPTED_KNOWLEDGE_MIME_TYPES,
} from '../../api/knowledge'
import { formatBytes } from '../../lib/format'

/**
 * Client-side guard mirroring the server rule from BUILD_SPEC section 5:
 * `POST /api/knowledge/documents` accepts `.docx` and `.pdf` only.
 *
 * Rejecting here keeps an obvious mistake out of the network layer and gives a
 * specific message; the server's own rejection is still surfaced separately,
 * because this check is a convenience and never the authority.
 */

const EXTENSIONS: readonly string[] = ACCEPTED_KNOWLEDGE_EXTENSIONS
const MIME_TYPES: readonly string[] = ACCEPTED_KNOWLEDGE_MIME_TYPES

/**
 * The server's own ceiling, mirrored exactly: `caa.rag.max-upload-bytes`
 * defaults to 20 MiB and `RagService.ingest` rejects anything above it. Setting
 * a higher value here would let the browser upload a file in full only for the
 * server to refuse it, while the dropzone advertised a limit that does not
 * exist. Keep the two in step.
 */
export const MAX_KNOWLEDGE_FILE_BYTES = 20 * 1024 * 1024

/** Value for the `accept` attribute of the file input. */
export const KNOWLEDGE_ACCEPT_ATTRIBUTE = [
  ...ACCEPTED_KNOWLEDGE_EXTENSIONS,
  ...ACCEPTED_KNOWLEDGE_MIME_TYPES,
].join(',')

/** Human phrasing of the accepted types, reused in labels and messages. */
export const ACCEPTED_KNOWLEDGE_LABEL = '.docx or .pdf'

export type FileRejectionReason = 'type' | 'empty' | 'size' | 'count'

export interface FileAccepted {
  ok: true
  file: File
}

export interface FileRejected {
  ok: false
  reason: FileRejectionReason
  message: string
}

export type KnowledgeFileValidation = FileAccepted | FileRejected

/** Lowercased extension including the dot, or `''` when there is none. */
export function fileExtension(filename: string): string {
  const dot = filename.lastIndexOf('.')
  if (dot < 0 || dot === filename.length - 1) return ''
  return filename.slice(dot).toLowerCase()
}

/**
 * Extension first — it is what the operator sees and what the backend keys on.
 * A file with no extension is still accepted when the browser reports one of
 * the two supported MIME types.
 */
export function isAcceptedKnowledgeFile(file: { name: string; type?: string }): boolean {
  const extension = fileExtension(file.name)
  if (extension !== '') return EXTENSIONS.includes(extension)
  return MIME_TYPES.includes((file.type ?? '').toLowerCase())
}

export function validateKnowledgeFile(file: File): KnowledgeFileValidation {
  if (!isAcceptedKnowledgeFile(file)) {
    const extension = fileExtension(file.name)
    return {
      ok: false,
      reason: 'type',
      message: `“${file.name}”${extension ? ` is a ${extension.slice(1).toUpperCase()} file, which` : ''} cannot be indexed. The knowledge base accepts ${ACCEPTED_KNOWLEDGE_LABEL} documents only.`,
    }
  }
  if (file.size === 0) {
    return {
      ok: false,
      reason: 'empty',
      message: `“${file.name}” is empty. Choose a document that contains policy text.`,
    }
  }
  if (file.size > MAX_KNOWLEDGE_FILE_BYTES) {
    return {
      ok: false,
      reason: 'size',
      message: `“${file.name}” is ${formatBytes(file.size)}. The maximum upload size is ${formatBytes(MAX_KNOWLEDGE_FILE_BYTES)}.`,
    }
  }
  return { ok: true, file }
}

/**
 * One document per upload — the endpoint takes a single multipart `file` part,
 * so a multi-file drop is rejected instead of silently dropping the rest.
 */
export function validateKnowledgeDrop(files: File[]): KnowledgeFileValidation {
  const [file] = files
  if (!file) {
    return {
      ok: false,
      reason: 'count',
      message: 'No file was received. Try dragging the document again.',
    }
  }
  if (files.length > 1) {
    return {
      ok: false,
      reason: 'count',
      message: `${files.length} files were dropped. Upload one document at a time.`,
    }
  }
  return validateKnowledgeFile(file)
}
