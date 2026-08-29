import { FileText, Layers, RotateCw, TriangleAlert } from 'lucide-react'
import { Link } from 'react-router-dom'
import { useKnowledgeDocuments } from '../../api/knowledge'
import {
  Button,
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
  PageHeader,
  StatCard,
} from '../../components'
import { EM_DASH, formatNumber } from '../../lib/format'
import { DocumentTable, DocumentUploader } from '../knowledge'

/**
 * Admin view of the RAG corpus: upload `.docx`/`.pdf` policy documents, watch
 * ingestion, and remove documents. Ingestion failures are shown, never hidden —
 * a failed document silently missing from retrieval is the worst outcome here.
 */
export function KnowledgePage() {
  const documents = useKnowledgeDocuments()
  const rows = documents.data ?? []
  const failed = rows.filter((document) => document.status === 'FAILED')
  const indexed = rows.filter((document) => document.status !== 'FAILED')
  const totalChunks = indexed.reduce((sum, document) => sum + (document.chunkCount ?? 0), 0)

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title="Knowledge base"
        description="Policy documents the AI risk agent retrieves from. Each upload is chunked, embedded and stored in pgvector; operators can query the same index from Knowledge search."
        actions={
          <Button
            variant="secondary"
            onClick={() => void documents.refetch()}
            loading={documents.isFetching}
            iconLeft={<RotateCw className="size-3.5" />}
          >
            Refresh
          </Button>
        }
      />

      <div className="grid gap-3 sm:grid-cols-3">
        <StatCard
          label="Documents"
          value={documents.isError ? EM_DASH : formatNumber(rows.length)}
          loading={documents.isLoading}
          hint="In the knowledge base"
          icon={<FileText className="size-4" />}
          numeric
        />
        <StatCard
          label="Indexed chunks"
          value={documents.isError ? EM_DASH : formatNumber(totalChunks)}
          loading={documents.isLoading}
          hint="Retrievable passages"
          icon={<Layers className="size-4" />}
          numeric
        />
        <StatCard
          label="Failed ingestions"
          value={documents.isError ? EM_DASH : formatNumber(failed.length)}
          loading={documents.isLoading}
          hint={failed.length > 0 ? 'Not searchable — re-upload needed' : 'None'}
          icon={<TriangleAlert className="size-4" />}
          numeric
        />
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Upload a policy document</CardTitle>
          <CardDescription>
            AML thresholds, sanctioned jurisdictions, crypto risk policy — whatever the agent should be
            able to cite. Uploading is immediate; embedding happens on the server.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <DocumentUploader />
        </CardContent>
      </Card>

      {failed.length > 0 ? (
        <div
          role="alert"
          className="flex items-start gap-2.5 rounded-md border border-danger/40 bg-danger-soft px-4 py-3"
        >
          <TriangleAlert aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-danger" />
          <div className="min-w-0 flex-1 text-xs">
            <p className="text-sm font-medium text-danger-fg">
              {failed.length} document{failed.length === 1 ? '' : 's'} failed to ingest
            </p>
            <p className="mt-0.5 text-muted">
              These documents are not searchable and the agent cannot cite them. Re-upload them, or
              delete them so the corpus stops advertising policy it cannot serve.
            </p>
            {/* Every failure is listed with the server's verbatim reason — a
                silently missing document is the worst outcome on this screen. */}
            <ul className="mt-2.5 flex flex-col gap-1.5">
              {failed.map((document) => (
                <li
                  key={document.documentId}
                  className="rounded-xs border border-danger/25 bg-surface px-2.5 py-1.5"
                >
                  <p className="truncate font-medium text-fg" title={document.filename}>
                    {document.title || document.filename}
                  </p>
                  <p className="mt-0.5 font-mono text-2xs break-words text-danger-fg">
                    {document.error ?? 'No error detail was returned by the server.'}
                  </p>
                </li>
              ))}
            </ul>
          </div>
        </div>
      ) : null}

      <Card>
        <CardHeader>
          <CardTitle>Indexed documents</CardTitle>
          <CardDescription>
            {formatNumber(rows.length)} document{rows.length === 1 ? '' : 's'} ·{' '}
            {formatNumber(totalChunks)} retrievable chunks · verify retrieval from{' '}
            <Link
              to="/knowledge-search"
              className="font-medium text-accent-strong underline-offset-4 hover:underline focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
            >
              Knowledge search
            </Link>
            .
          </CardDescription>
        </CardHeader>
        <DocumentTable
          documents={rows}
          loading={documents.isLoading}
          error={documents.error}
          onRetry={() => void documents.refetch()}
        />
      </Card>
    </div>
  )
}
