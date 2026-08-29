import { Bookmark, FileText, Hash } from 'lucide-react'
import { useId, useState } from 'react'
import type { KnowledgeChunk } from '../../api/types'
import { Button } from '../../components'
import { cn } from '../../lib/cn'
import { EM_DASH, shortId } from '../../lib/format'
import { SimilarityMeter } from './SimilarityMeter'
import { highlightSegments } from './highlight'

/** Longer passages collapse so a page of results stays scannable. */
const COLLAPSE_THRESHOLD = 420

export interface ChunkResultCardProps {
  chunk: KnowledgeChunk
  /** 1-based position in the result list. */
  rank: number
  /** Query terms to highlight inside the passage. */
  terms: string[]
  className?: string
}

/**
 * One retrieved passage. Attribution — source document, section title, chunk
 * index — is the point of the card: an operator has to be able to go and read
 * the paragraph the agent is quoting.
 */
export function ChunkResultCard({ chunk, rank, terms, className }: ChunkResultCardProps) {
  const [expanded, setExpanded] = useState(false)
  const headingId = useId()

  const content = chunk.content ?? ''
  const segments = highlightSegments(content, terms)
  const collapsible = content.length > COLLAPSE_THRESHOLD
  const documentLabel = chunk.title?.trim() || chunk.filename?.trim() || 'Untitled document'
  const sectionTitle = chunk.sectionTitle?.trim() ?? ''
  const showFilename = Boolean(chunk.filename && chunk.title && chunk.filename !== chunk.title)

  return (
    <article
      aria-labelledby={headingId}
      className={cn('rounded-md border border-border bg-surface shadow-panel', className)}
    >
      <header className="flex flex-wrap items-start justify-between gap-x-4 gap-y-2 border-b border-border bg-surface-2/40 px-4 py-2.5">
        <div className="flex min-w-0 items-start gap-2.5">
          <span
            aria-hidden="true"
            className="numeric mt-0.5 inline-flex size-6 shrink-0 items-center justify-center rounded-xs border border-border bg-surface text-2xs font-semibold text-muted"
          >
            {rank}
          </span>
          <div className="min-w-0">
            <h3
              id={headingId}
              className="flex items-center gap-1.5 text-sm font-semibold tracking-tight-swiss text-fg"
            >
              <FileText aria-hidden="true" className="size-3.5 shrink-0 text-subtle" />
              <span className="truncate">{documentLabel}</span>
              <span className="sr-only">, result {rank}</span>
            </h3>
            <p className="mt-1 flex flex-wrap items-center gap-x-2 gap-y-1 text-2xs text-muted">
              <span className="inline-flex min-w-0 items-center gap-1">
                <Bookmark aria-hidden="true" className="size-3 shrink-0 text-subtle" />
                {sectionTitle ? (
                  <span className="truncate font-medium text-fg">{sectionTitle}</span>
                ) : (
                  <span className="text-subtle">No section title</span>
                )}
              </span>
              {showFilename ? (
                <>
                  <span aria-hidden="true" className="text-subtle">
                    ·
                  </span>
                  <span className="truncate font-mono text-subtle" title={chunk.filename ?? undefined}>
                    {chunk.filename}
                  </span>
                </>
              ) : null}
            </p>
          </div>
        </div>
        <SimilarityMeter score={chunk.score} className="shrink-0" />
      </header>

      <div className="px-4 py-3.5">
        <p
          className={cn(
            'text-sm leading-relaxed whitespace-pre-wrap text-fg',
            collapsible && !expanded && 'line-clamp-6',
          )}
        >
          {segments.map((segment, index) =>
            segment.match ? (
              // Brand tint plus an underline: the highlight never leans on
              // colour alone, and it is never a risk colour.
              <mark
                key={index}
                className="rounded-xxs border-b border-accent/50 bg-accent-soft px-0.5 text-accent-soft-fg"
              >
                {segment.text}
              </mark>
            ) : (
              <span key={index}>{segment.text}</span>
            ),
          )}
        </p>
        {collapsible ? (
          <Button
            variant="link"
            size="sm"
            className="mt-2 text-xs"
            aria-expanded={expanded}
            onClick={() => setExpanded((current) => !current)}
          >
            {expanded ? 'Show less' : 'Show full passage'}
          </Button>
        ) : null}
      </div>

      <footer className="flex flex-wrap items-center gap-x-3 gap-y-1.5 border-t border-border px-4 py-2 text-2xs text-subtle">
        <span className="inline-flex items-center gap-1">
          <Hash aria-hidden="true" className="size-3" />
          <span className="numeric">
            Chunk {typeof chunk.chunkIndex === 'number' ? chunk.chunkIndex : EM_DASH}
          </span>
        </span>
        <span className="inline-flex items-center gap-1" title={chunk.documentId ?? undefined}>
          Document
          <span className="font-mono">{chunk.documentId ? shortId(chunk.documentId) : EM_DASH}</span>
        </span>
      </footer>
    </article>
  )
}
