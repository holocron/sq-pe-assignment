import { CircleCheckBig, FileUp, FileX2, TriangleAlert, Upload } from 'lucide-react'
import { useId, useRef, useState, type DragEvent } from 'react'
import { useUploadKnowledgeDocument } from '../../api/knowledge'
import { errorMessage, errorTitle } from '../../api/errors'
import type { KnowledgeDocument } from '../../api/types'
import { Badge, Spinner, buttonClasses, useToast } from '../../components'
import { cn } from '../../lib/cn'
import { formatBytes, formatNumber } from '../../lib/format'
import { knowledgeStatusMeta } from './documentMeta'
import {
  ACCEPTED_KNOWLEDGE_LABEL,
  KNOWLEDGE_ACCEPT_ATTRIBUTE,
  MAX_KNOWLEDGE_FILE_BYTES,
  validateKnowledgeDrop,
} from './fileValidation'

export interface DocumentUploaderProps {
  /** Called after the server accepts the upload, whatever the ingest status. */
  onUploaded?: (document: KnowledgeDocument) => void
  className?: string
}

/**
 * Drag-and-drop / file-picker uploader for policy documents.
 *
 * Unsupported files are rejected in the UI before any request is made, and the
 * server's own rejection (415, 413, ingest failure) is surfaced verbatim rather
 * than swallowed.
 */
export function DocumentUploader({ onUploaded, className }: DocumentUploaderProps) {
  const inputId = useId()
  const hintId = `${inputId}-hint`
  const dragDepth = useRef(0)

  const [isDragging, setIsDragging] = useState(false)
  const [rejection, setRejection] = useState<string | null>(null)
  const [progress, setProgress] = useState(0)
  const [currentFile, setCurrentFile] = useState<{ name: string; size: number } | null>(null)
  const [result, setResult] = useState<KnowledgeDocument | null>(null)

  const toast = useToast()
  const upload = useUploadKnowledgeDocument({
    onSuccess: (document) => {
      setResult(document)
      setCurrentFile(null)
      if (document.status === 'FAILED') {
        toast.error('Ingestion failed', `${document.filename} was stored but could not be indexed.`)
      } else {
        toast.success('Upload complete', `${document.filename} was accepted.`)
      }
      onUploaded?.(document)
    },
    onError: (error) => {
      setCurrentFile(null)
      toast.error(errorTitle(error), errorMessage(error))
    },
  })

  function startUpload(files: File[]): void {
    const validation = validateKnowledgeDrop(files)
    if (!validation.ok) {
      setRejection(validation.message)
      setResult(null)
      return
    }
    setRejection(null)
    setResult(null)
    setProgress(0)
    upload.reset()
    setCurrentFile({ name: validation.file.name, size: validation.file.size })
    upload.mutate({ file: validation.file, onProgress: setProgress })
  }

  function handleDrop(event: DragEvent<HTMLLabelElement>): void {
    event.preventDefault()
    dragDepth.current = 0
    setIsDragging(false)
    if (upload.isPending) return
    startUpload(event.dataTransfer ? Array.from(event.dataTransfer.files) : [])
  }

  const serverError = upload.error
  const alertMessage = rejection ?? (serverError ? errorMessage(serverError) : null)
  const alertTitle = rejection ? 'Unsupported file' : serverError ? errorTitle(serverError) : null
  const resultMeta = result ? knowledgeStatusMeta(result.status) : null

  /* Four visual states, one class string each so no two backgrounds collide:
     dragging (brand tint) · rejected (danger tint) · uploading · default. */
  const dropzoneState = isDragging
    ? 'border-accent bg-accent-soft/50 ring-2 ring-ring/30'
    : rejection !== null
      ? 'border-danger/60 bg-danger-soft/50'
      : 'border-border-strong bg-surface-2/40 hover:border-accent/60 hover:bg-surface-2'

  return (
    <div className={cn('flex flex-col gap-3', className)}>
      <label
        onDragEnter={(event) => {
          event.preventDefault()
          dragDepth.current += 1
          setIsDragging(true)
        }}
        onDragOver={(event) => event.preventDefault()}
        onDragLeave={() => {
          dragDepth.current = Math.max(0, dragDepth.current - 1)
          if (dragDepth.current === 0) setIsDragging(false)
        }}
        onDrop={handleDrop}
        className={cn(
          'flex cursor-pointer flex-col items-center justify-center gap-2.5 rounded-md border-2 border-dashed px-6 py-8 text-center transition-colors',
          'has-[input:focus-visible]:border-accent has-[input:focus-visible]:ring-2 has-[input:focus-visible]:ring-ring',
          dropzoneState,
          upload.isPending && 'pointer-events-none opacity-70',
        )}
      >
        <input
          id={inputId}
          type="file"
          className="sr-only"
          accept={KNOWLEDGE_ACCEPT_ATTRIBUTE}
          aria-label="Policy document file"
          aria-describedby={hintId}
          disabled={upload.isPending}
          onChange={(event) => {
            startUpload(event.target.files ? Array.from(event.target.files) : [])
            event.target.value = ''
          }}
        />
        <span
          aria-hidden="true"
          className={cn(
            'flex size-10 items-center justify-center rounded-full transition-colors',
            isDragging
              ? 'bg-accent text-accent-fg'
              : rejection !== null
                ? 'bg-danger-soft text-danger-fg'
                : 'bg-surface-3 text-muted',
          )}
        >
          {rejection !== null ? <FileX2 className="size-5" /> : <FileUp className="size-5" />}
        </span>
        <span className="text-sm font-medium text-fg">
          {isDragging ? 'Drop the document to upload it' : 'Drag a policy document here'}
        </span>
        <span
          aria-hidden="true"
          className={cn(buttonClasses({ variant: 'secondary', size: 'sm' }), 'pointer-events-none')}
        >
          Browse files
        </span>
        <span id={hintId} className="text-xs text-muted">
          {ACCEPTED_KNOWLEDGE_LABEL} only, up to {formatBytes(MAX_KNOWLEDGE_FILE_BYTES)}. One document
          per upload.
        </span>
      </label>

      {upload.isPending && currentFile ? (
        <div className="rounded-md border border-border bg-surface-2/40 px-3 py-2.5">
          <div className="flex items-center justify-between gap-3 text-xs">
            <span className="flex min-w-0 items-center gap-2 text-fg">
              <Upload aria-hidden="true" className="size-3.5 shrink-0 text-muted" />
              <span className="truncate font-medium">{currentFile.name}</span>
              <span className="shrink-0 text-subtle">{formatBytes(currentFile.size)}</span>
            </span>
            <span className="numeric shrink-0 text-muted">{progress}%</span>
          </div>
          <div
            role="progressbar"
            aria-valuenow={progress}
            aria-valuemin={0}
            aria-valuemax={100}
            aria-label={`Uploading ${currentFile.name}`}
            className="mt-2 h-1.5 w-full overflow-hidden rounded-full bg-surface-3"
          >
            <div
              className="h-full rounded-full bg-accent transition-[width] duration-200"
              style={{ width: `${progress}%` }}
            />
          </div>
          <p className="mt-2 flex items-center gap-1.5 text-2xs text-muted">
            <Spinner size="xs" label="Uploading" />
            {progress >= 100
              ? 'Uploaded. Chunking and embedding on the server…'
              : 'Uploading to the knowledge base…'}
          </p>
        </div>
      ) : null}

      {alertMessage ? (
        <div
          role="alert"
          className="flex items-start gap-2.5 rounded-md border border-danger/40 bg-danger-soft px-3 py-2.5"
        >
          <TriangleAlert aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-danger" />
          <div className="min-w-0 text-xs">
            <p className="font-medium text-danger-fg">{alertTitle}</p>
            <p className="mt-0.5 text-fg">{alertMessage}</p>
          </div>
        </div>
      ) : null}

      {result && resultMeta ? (
        <div
          role={resultMeta.failed ? 'alert' : 'status'}
          className={cn(
            'rounded-md border px-3 py-2.5',
            resultMeta.failed ? 'border-danger/40 bg-danger-soft' : 'border-border bg-surface-2/40',
          )}
        >
          <div className="flex flex-wrap items-center gap-2">
            {resultMeta.failed ? (
              <TriangleAlert aria-hidden="true" className="size-4 shrink-0 text-danger" />
            ) : (
              <CircleCheckBig aria-hidden="true" className="size-4 shrink-0 text-success" />
            )}
            <span className="min-w-0 truncate text-sm font-medium text-fg">
              {result.title || result.filename}
            </span>
            <Badge tone={resultMeta.tone} dot title={resultMeta.description}>
              {resultMeta.label}
            </Badge>
          </div>
          <p className="mt-1 text-xs text-muted">
            {resultMeta.failed
              ? `Ingestion failed for “${result.filename}”. The file was stored but produced no searchable chunks, so the agent cannot cite it.`
              : `Indexed ${formatNumber(result.chunkCount)} chunks from “${result.filename}” (${formatBytes(result.sizeBytes)}).`}
          </p>
          {result.error ? (
            <div className="mt-2 rounded-xs border border-danger/30 bg-surface px-2 py-1.5">
              <p className="text-2xs font-semibold tracking-caption text-muted uppercase">
                Reported error
              </p>
              <p className="mt-0.5 font-mono text-2xs break-words text-danger-fg">{result.error}</p>
            </div>
          ) : null}
        </div>
      ) : null}
    </div>
  )
}
