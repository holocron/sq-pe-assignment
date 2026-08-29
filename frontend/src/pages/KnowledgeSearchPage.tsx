import { BookOpen, Bot, Search, SearchX } from 'lucide-react'
import { useMemo, useState, type FormEvent } from 'react'
import { useKnowledgeSearch } from '../api/knowledge'
import type { KnowledgeSearchRequest } from '../api/types'
import {
  Button,
  Card,
  CardContent,
  CardHeader,
  CardTitle,
  EmptyState,
  ErrorState,
  Input,
  PageHeader,
  Select,
  Skeleton,
  type SelectOption,
} from '../components'
import { ChunkResultCard, extractQueryTerms } from './knowledge'

const TOP_K_OPTIONS: SelectOption[] = [
  { value: '3', label: '3 passages' },
  { value: '5', label: '5 passages' },
  { value: '8', label: '8 passages' },
  { value: '10', label: '10 passages' },
  { value: '15', label: '15 passages' },
]

/** Starting points that exercise the seeded AML / sanctions / crypto policies. */
const EXAMPLE_QUERIES = [
  'Cash structuring reporting threshold',
  'Sanctioned jurisdictions for wire transfers',
  'Crypto mixer and privacy coin exposure',
]

const DEFAULT_TOP_K = 5

/**
 * Operator-facing RAG search. Deliberately uses the same endpoint and the same
 * top-K retrieval the agent's `search_policy_knowledge` tool calls, so a
 * reviewer can see exactly what the model gets to read.
 */
export function KnowledgeSearchPage() {
  const [queryInput, setQueryInput] = useState('')
  const [topK, setTopK] = useState(DEFAULT_TOP_K)
  const [request, setRequest] = useState<KnowledgeSearchRequest | null>(null)

  const search = useKnowledgeSearch(request)
  const results = useMemo(() => search.data ?? [], [search.data])
  const terms = useMemo(() => extractQueryTerms(request?.query ?? ''), [request?.query])

  function runSearch(rawQuery: string, nextTopK: number): void {
    const query = rawQuery.trim()
    if (query.length === 0) return
    const unchanged = request !== null && request.query === query && request.topK === nextTopK
    setRequest({ query, topK: nextTopK })
    if (unchanged) void search.refetch()
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>): void {
    event.preventDefault()
    runSearch(queryInput, topK)
  }

  function handleTopKChange(value: string): void {
    const parsed = Number(value)
    const nextTopK = Number.isFinite(parsed) && parsed > 0 ? parsed : DEFAULT_TOP_K
    setTopK(nextTopK)
    if (request) runSearch(request.query, nextTopK)
  }

  function handleExample(example: string): void {
    setQueryInput(example)
    runSearch(example, topK)
  }

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title="Knowledge search"
        description="Vector search over the indexed policy documents. This is the same retrieval the AI risk agent performs, so what you see here is what the model reads when it cites policy."
      />

      <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_20rem]">
        <div className="flex min-w-0 flex-col gap-4">
          <Card>
            <CardContent>
              <form onSubmit={handleSubmit} className="flex flex-wrap items-end gap-3">
                <Input
                  label="Search the policy knowledge base"
                  placeholder="e.g. reporting threshold for structured cash deposits"
                  value={queryInput}
                  onChange={(event) => setQueryInput(event.target.value)}
                  iconLeft={<Search className="size-3.5" />}
                  containerClassName="min-w-64 flex-1"
                  autoComplete="off"
                  spellCheck={false}
                />
                <Select
                  label="Passages to retrieve"
                  options={TOP_K_OPTIONS}
                  value={String(topK)}
                  onChange={(event) => handleTopKChange(event.target.value)}
                  containerClassName="w-40"
                />
                <Button
                  type="submit"
                  variant="primary"
                  loading={search.isFetching}
                  disabled={queryInput.trim().length === 0}
                  iconLeft={<Search className="size-3.5" />}
                >
                  Search
                </Button>
              </form>

              <div className="mt-3 flex flex-wrap items-center gap-1.5 border-t border-border pt-3">
                <span className="text-2xs font-semibold tracking-caption text-subtle uppercase">
                  Try
                </span>
                {EXAMPLE_QUERIES.map((example) => (
                  <button
                    key={example}
                    type="button"
                    onClick={() => handleExample(example)}
                    className="rounded-full border border-border bg-surface-2 px-2.5 py-1 text-2xs text-muted transition-colors hover:border-accent/50 hover:bg-accent-soft hover:text-accent-soft-fg focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
                  >
                    {example}
                  </button>
                ))}
              </div>
            </CardContent>
          </Card>

          <section aria-label="Search results" className="flex flex-col gap-3">
            <p
              aria-live="polite"
              className="numeric min-h-4 text-xs text-muted tracking-tight-swiss"
            >
              {request && !search.isLoading && !search.isError
                ? `${results.length} passage${results.length === 1 ? '' : 's'} retrieved for “${request.query}” (top ${request.topK ?? DEFAULT_TOP_K}).`
                : ''}
            </p>

            {request === null ? (
              <Card>
                <EmptyState
                  icon={<Search className="size-5" />}
                  title="Search the knowledge base"
                  description="Enter a policy question or a few keywords. Results are ranked by embedding similarity, not keyword matching, so plain-language questions work well."
                />
              </Card>
            ) : search.isLoading ? (
              <div className="flex flex-col gap-3" aria-hidden="true">
                {Array.from({ length: 3 }, (_, index) => (
                  <Card key={index} className="p-4">
                    <div className="flex items-center justify-between gap-4">
                      <Skeleton className="h-4 w-48" />
                      <Skeleton className="h-6 w-32" pill />
                    </div>
                    <div className="mt-3 space-y-2">
                      <Skeleton className="h-3 w-full" />
                      <Skeleton className="h-3 w-full" />
                      <Skeleton className="h-3 w-3/4" />
                    </div>
                  </Card>
                ))}
              </div>
            ) : search.isError ? (
              <Card>
                <ErrorState
                  error={search.error}
                  title="Search failed"
                  onRetry={() => void search.refetch()}
                />
              </Card>
            ) : results.length === 0 ? (
              <Card>
                <EmptyState
                  icon={<SearchX className="size-5" />}
                  title="No passages matched"
                  description="Nothing in the indexed policy documents was close enough to this query. Try different wording, raise the number of passages, or check that the relevant document has been uploaded."
                />
              </Card>
            ) : (
              results.map((chunk, index) => (
                <ChunkResultCard
                  key={chunk.id ?? `${chunk.documentId ?? 'chunk'}-${chunk.chunkIndex ?? index}`}
                  chunk={chunk}
                  rank={index + 1}
                  terms={terms}
                />
              ))
            )}
          </section>
        </div>

        <aside className="flex flex-col gap-4">
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2">
                <Bot aria-hidden="true" className="size-4 text-muted" />
                Same tool as the agent
              </CardTitle>
            </CardHeader>
            <CardContent className="text-xs leading-relaxed text-muted">
              <p>
                During an analysis the ReAct agent calls its{' '}
                <code className="rounded-xs bg-surface-2 px-1 font-mono text-2xs text-fg">
                  search_policy_knowledge
                </code>{' '}
                tool to ground its reasoning in bank policy instead of inventing rules. That tool hits
                this same endpoint with the same parameters.
              </p>
              <ol className="mt-3 space-y-2">
                <li className="flex gap-2">
                  <span className="numeric mt-px flex size-4 shrink-0 items-center justify-center rounded-full bg-surface-2 text-2xs font-semibold text-muted">
                    1
                  </span>
                  <span>Your query is embedded with the same model used to index the documents.</span>
                </li>
                <li className="flex gap-2">
                  <span className="numeric mt-px flex size-4 shrink-0 items-center justify-center rounded-full bg-surface-2 text-2xs font-semibold text-muted">
                    2
                  </span>
                  <span>
                    pgvector ranks every stored chunk by cosine similarity to that embedding.
                  </span>
                </li>
                <li className="flex gap-2">
                  <span className="numeric mt-px flex size-4 shrink-0 items-center justify-center rounded-full bg-surface-2 text-2xs font-semibold text-muted">
                    3
                  </span>
                  <span>
                    The top passages come back with their source document and section, which is what the
                    agent quotes.
                  </span>
                </li>
              </ol>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2">
                <BookOpen aria-hidden="true" className="size-4 text-muted" />
                Reading the results
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-2 text-xs leading-relaxed text-muted">
              <p>
                Similarity is a semantic score, not a keyword count — a strong match need not repeat your
                wording. Highlighted words are the literal query terms that happen to appear.
              </p>
              <p>
                Only documents with an <span className="font-medium text-fg">Indexed</span> status are
                searchable. Administrators manage them under Admin → Knowledge Base.
              </p>
            </CardContent>
          </Card>
        </aside>
      </div>
    </div>
  )
}
