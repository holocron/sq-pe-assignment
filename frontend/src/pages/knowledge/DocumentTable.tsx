import { FileText, FileWarning, Trash2 } from 'lucide-react'
import { useState } from 'react'
import { errorMessage, errorTitle } from '../../api/errors'
import { useDeleteKnowledgeDocument } from '../../api/knowledge'
import type { KnowledgeDocument } from '../../api/types'
import { Badge, Button, EmptyState, Modal, Table, useToast, type Column } from '../../components'
import { cn } from '../../lib/cn'
import { formatBytes, formatDateTime, formatNumber, formatRelativeTime } from '../../lib/format'
import { documentTypeLabel, knowledgeStatusMeta } from './documentMeta'

export interface DocumentTableProps {
  documents: KnowledgeDocument[]
  loading?: boolean
  error?: unknown
  onRetry?: () => void
}

/** Existing knowledge-base documents, with a confirmed delete per row. */
export function DocumentTable({ documents, loading, error, onRetry }: DocumentTableProps) {
  const [pendingDelete, setPendingDelete] = useState<KnowledgeDocument | null>(null)
  const toast = useToast()
  const remove = useDeleteKnowledgeDocument()

  function confirmDelete(): void {
    const target = pendingDelete
    if (!target) return
    remove.mutate(target.documentId, {
      onSuccess: () => {
        setPendingDelete(null)
        toast.success('Document deleted', `${target.filename} and its chunks were removed.`)
      },
      onError: (deleteError) => {
        toast.error(errorTitle(deleteError), errorMessage(deleteError))
      },
    })
  }

  const columns: Column<KnowledgeDocument>[] = [
    {
      key: 'title',
      header: 'Document',
      className: 'min-w-64',
      cell: (document) => {
        const meta = knowledgeStatusMeta(document.status)
        return (
          <div className="flex min-w-0 items-start gap-2.5">
            <span
              aria-hidden="true"
              className={cn(
                'mt-0.5 flex size-7 shrink-0 items-center justify-center rounded-xs border',
                meta.failed
                  ? 'border-danger/40 bg-danger-soft text-danger-fg'
                  : 'border-border bg-surface-2 text-subtle',
              )}
            >
              {meta.failed ? <FileWarning className="size-3.5" /> : <FileText className="size-3.5" />}
            </span>
            <div className="min-w-0">
              <span
                className="block truncate font-medium text-fg"
                title={document.title || document.filename}
              >
                {document.title || document.filename}
              </span>
              <span
                className="block truncate font-mono text-2xs text-subtle"
                title={document.filename}
              >
                {document.filename}
              </span>
              {meta.failed ? (
                <span className="mt-1 block text-2xs break-words text-danger-fg">
                  {document.error ?? 'Ingestion failed — no error detail was returned.'}
                </span>
              ) : null}
            </div>
          </div>
        )
      },
    },
    {
      key: 'type',
      header: 'Type',
      className: 'w-20',
      cell: (document) => (
        <Badge tone="outline" title={document.mimeType}>
          {documentTypeLabel(document.mimeType, document.filename)}
        </Badge>
      ),
    },
    {
      key: 'size',
      header: 'Size',
      align: 'right',
      className: 'w-24',
      cell: (document) => <span className="numeric text-muted">{formatBytes(document.sizeBytes)}</span>,
    },
    {
      key: 'chunks',
      header: 'Chunks',
      align: 'right',
      className: 'w-20',
      cell: (document) => {
        const meta = knowledgeStatusMeta(document.status)
        const count = document.chunkCount ?? 0
        return (
          <span className={cn('numeric', count > 0 && !meta.failed ? 'text-fg' : 'text-subtle')}>
            {formatNumber(document.chunkCount)}
          </span>
        )
      },
    },
    {
      key: 'uploadedBy',
      header: 'Uploaded by',
      className: 'w-32',
      cell: (document) => (
        <span className="block truncate text-muted" title={document.uploadedBy}>
          {document.uploadedBy}
        </span>
      ),
    },
    {
      key: 'uploadedAt',
      header: 'Uploaded',
      className: 'w-44',
      cell: (document) => (
        <span
          className="numeric whitespace-nowrap text-muted"
          title={formatRelativeTime(document.uploadedAt)}
        >
          {formatDateTime(document.uploadedAt)}
        </span>
      ),
    },
    {
      key: 'status',
      header: 'Status',
      className: 'w-28',
      cell: (document) => {
        const meta = knowledgeStatusMeta(document.status)
        return (
          <Badge tone={meta.tone} dot title={meta.description}>
            {meta.label}
          </Badge>
        )
      },
    },
    {
      key: 'actions',
      header: 'Actions',
      hideHeader: true,
      align: 'right',
      className: 'w-12',
      cell: (document) => (
        <Button
          variant="ghost"
          size="icon"
          className="size-8"
          aria-label={`Delete ${document.filename}`}
          title="Delete document"
          onClick={() => setPendingDelete(document)}
        >
          <Trash2 className="size-3.5" />
        </Button>
      ),
    },
  ]

  return (
    <>
      <Table
        dense
        columns={columns}
        rows={documents}
        rowKey={(document) => document.documentId}
        loading={loading}
        error={error}
        onRetry={onRetry}
        caption="Knowledge base documents"
        stickyHeader
        empty={
          <EmptyState
            icon={<FileText className="size-5" />}
            title="No documents indexed yet"
            description="Upload an AML, sanctions or crypto policy document above. Until then the risk agent has no policy to cite."
          />
        }
      />

      <Modal
        open={pendingDelete !== null}
        onClose={() => {
          if (!remove.isPending) setPendingDelete(null)
        }}
        title="Delete document"
        description="This removes the document and every chunk embedded from it."
        size="sm"
        closeOnOverlayClick={false}
        footer={
          <>
            <Button variant="secondary" onClick={() => setPendingDelete(null)} disabled={remove.isPending}>
              Cancel
            </Button>
            <Button variant="danger" onClick={confirmDelete} loading={remove.isPending}>
              Delete document
            </Button>
          </>
        }
      >
        {pendingDelete ? (
          <div className="space-y-2 text-sm text-fg">
            <p>
              <span className="font-medium">{pendingDelete.title || pendingDelete.filename}</span> will
              no longer be retrievable by the risk agent.
            </p>
            <p className="text-xs text-muted">
              {formatNumber(pendingDelete.chunkCount)} chunks · uploaded by {pendingDelete.uploadedBy} ·{' '}
              {formatDateTime(pendingDelete.uploadedAt)}
            </p>
          </div>
        ) : null}
      </Modal>
    </>
  )
}
