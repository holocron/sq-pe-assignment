/**
 * Admin screen for verbose agent tracing. While enabled, the server writes the
 * agent's reasoning and full tool calls/results to a trace file that restarts
 * fresh on every (re-)enable. The viewer reads that file incrementally from a
 * byte offset and polls while tracing is on; when the server reports the file
 * shrank (`fromOffset` reset) or the file name changes, the viewer drops the
 * old content instead of stitching two files together.
 */
import { Download, FileTerminal, ScrollText } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import {
  AGENT_TRACE_POLL_INTERVAL_MS,
  downloadAgentTrace,
  fetchAgentTraceContent,
  useAgentTraceState,
  useSetAgentTraceEnabled,
} from '../../api/agentTrace'
import { errorMessage } from '../../api/errors'
import type { AgentTraceState } from '../../api/types'
import { BackLink } from '../../components/ui/BackLink'
import { Badge } from '../../components/ui/Badge'
import { Button } from '../../components/ui/Button'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '../../components/ui/Card'
import { Checkbox } from '../../components/ui/Checkbox'
import { EmptyState } from '../../components/ui/EmptyState'
import { ErrorState } from '../../components/ui/ErrorState'
import { PageHeader } from '../../components/ui/PageHeader'
import { Skeleton } from '../../components/ui/Skeleton'
import { useToast } from '../../components/ui/Toast'
import { formatBytes, formatDateTime } from '../../lib/format'

/** Scroll-back tolerance: within this many px of the bottom, the viewer keeps following the tail. */
const FOLLOW_TAIL_THRESHOLD_PX = 40

/**
 * The trace viewer. Owns the incremental read: content is state, the read
 * offset and the file the content belongs to are refs (they drive fetch
 * behaviour, not rendering). The next read offset is the server's `sizeBytes`
 * — a byte count, safe against multi-byte characters where `content.length`
 * would not be.
 */
function TraceViewer({ state }: { state: AgentTraceState }) {
  const [content, setContent] = useState('')
  const [loaded, setLoaded] = useState(false)
  const [loadError, setLoadError] = useState<string | null>(null)
  const offsetRef = useRef(0)
  const followTailRef = useRef(true)
  const panelRef = useRef<HTMLPreElement | null>(null)

  const enabled = state.enabled
  const fileName = state.fileName

  /* Restart detection, adjusted during render (not in an effect): a file-name
     change (restart while on, or a fresh enable after off) starts the viewer
     over — previous content must not leak into the new file's view. The read
     offset itself lives in a ref (it drives fetch behaviour, not rendering)
     and is reset by the effect below, which re-runs on the same change. */
  const [seenFileName, setSeenFileName] = useState<string | null>(null)
  if (fileName !== seenFileName) {
    setSeenFileName(fileName)
    setContent('')
    setLoadError(null)
    setLoaded(false)
  }

  useEffect(() => {
    /* Never enabled: there is no file to read. */
    if (fileName === null) return

    let cancelled = false
    offsetRef.current = 0
    followTailRef.current = true

    async function tick(): Promise<void> {
      try {
        const chunk = await fetchAgentTraceContent(offsetRef.current)
        if (cancelled) return
        if (chunk.fromOffset < offsetRef.current) {
          /* The file shrank — the trace restarted: this is the new file in full. */
          setContent(chunk.content)
          followTailRef.current = true
        } else if (chunk.content.length > 0) {
          setContent((previous) => previous + chunk.content)
        }
        offsetRef.current = chunk.sizeBytes
        setLoaded(true)
        setLoadError(null)
      } catch (error) {
        if (cancelled) return
        setLoaded(true)
        setLoadError(errorMessage(error))
      }
    }

    void tick()
    /* Enabled: keep polling incrementally. Disabled: one static snapshot. */
    const interval = enabled ? setInterval(() => void tick(), AGENT_TRACE_POLL_INTERVAL_MS) : null
    return () => {
      cancelled = true
      if (interval !== null) clearInterval(interval)
    }
  }, [enabled, fileName])

  /* Follow the tail unless the operator scrolled up. */
  useEffect(() => {
    const panel = panelRef.current
    if (panel && followTailRef.current) panel.scrollTop = panel.scrollHeight
  }, [content])

  function handleScroll(): void {
    const panel = panelRef.current
    if (!panel) return
    followTailRef.current =
      panel.scrollHeight - panel.scrollTop - panel.clientHeight < FOLLOW_TAIL_THRESHOLD_PX
  }

  if (fileName === null) {
    return (
      <EmptyState
        icon={<FileTerminal className="size-5" />}
        title="No trace file yet"
        description="Tracing has never been enabled. Switch it on to start a fresh trace file capturing the agent's reasoning and tool calls."
      />
    )
  }

  return (
    <div className="flex flex-col gap-2">
      <p className="text-2xs text-subtle">
        {enabled
          ? `Live — appending every few seconds. Scrolling up pauses auto-follow; scroll back to the bottom to resume.`
          : `Tracing is off — this is the last captured content, loaded once.`}
      </p>
      {loadError ? (
        <p role="alert" className="text-2xs text-danger-fg">
          Could not read the trace: {loadError}
        </p>
      ) : null}
      <pre
        ref={panelRef}
        onScroll={handleScroll}
        aria-label="Trace content"
        aria-live="off"
        className="h-96 overflow-auto rounded-md border border-border bg-surface p-3 font-mono text-2xs break-words whitespace-pre-wrap text-fg"
      >
        {content}
        {loaded && content.length === 0 ? (
          <span className="text-subtle">The trace file is empty so far.</span>
        ) : null}
        {!loaded ? <span className="text-subtle">Loading the trace…</span> : null}
      </pre>
    </div>
  )
}

export function AgentTracePage() {
  const toast = useToast()
  const trace = useAgentTraceState()
  const [downloading, setDownloading] = useState(false)

  const toggle = useSetAgentTraceEnabled({
    onSuccess: (next) => {
      toast.success(
        next.enabled ? 'Tracing enabled' : 'Tracing disabled',
        next.enabled
          ? 'A new trace file was started; previous content was replaced in the viewer.'
          : undefined,
      )
    },
    onError: (error) => {
      toast.error('Could not update tracing', errorMessage(error))
    },
  })

  const state = trace.data
  const mutating = toggle.isPending

  async function handleDownload(): Promise<void> {
    setDownloading(true)
    try {
      const text = await downloadAgentTrace()
      const blob = new Blob([text], { type: 'text/plain' })
      const url = URL.createObjectURL(blob)
      const anchor = document.createElement('a')
      anchor.href = url
      anchor.download = state?.fileName ?? 'agent-trace.log'
      document.body.append(anchor)
      anchor.click()
      anchor.remove()
      URL.revokeObjectURL(url)
    } catch (error) {
      toast.error('Could not download the trace', errorMessage(error))
    } finally {
      setDownloading(false)
    }
  }

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        eyebrow={<BackLink to="/dashboard">Dashboard</BackLink>}
        title="Agent trace"
        description="Verbose agent tracing — reasoning and full tool calls and results — written server-side to a file. Enabling tracing always starts a fresh file; the previous content is replaced in the viewer."
      />

      {trace.isLoading ? (
        <Card>
          <CardContent className="flex flex-col gap-3 py-4">
            <Skeleton className="h-4 w-48" />
            <Skeleton className="h-9 w-full" />
            <Skeleton className="h-64 w-full" />
          </CardContent>
        </Card>
      ) : trace.isError || !state ? (
        <ErrorState
          title="Agent trace status unavailable"
          error={trace.error}
          onRetry={() => void trace.refetch()}
        />
      ) : (
        <>
          <Card>
            <CardHeader>
              <CardTitle>Tracing</CardTitle>
              <CardDescription>
                Status:{' '}
                {state.enabled ? (
                  <Badge tone="success" dot>
                    Enabled
                  </Badge>
                ) : (
                  <Badge tone="neutral" dot>
                    Disabled
                  </Badge>
                )}{' '}
                {state.fileName ? (
                  <Badge tone="outline">{state.fileName}</Badge>
                ) : (
                  <Badge tone="outline">No file yet</Badge>
                )}
                {state.startedAt ? (
                  <span className="ml-2 text-2xs text-subtle">
                    Started {formatDateTime(state.startedAt)} · {formatBytes(state.sizeBytes)}
                  </span>
                ) : null}
              </CardDescription>
            </CardHeader>
            <CardContent className="flex flex-col gap-4">
              <Checkbox
                label="Enable verbose tracing"
                hint="Records the agent's reasoning and full tool calls and results. A new trace file is started each time tracing is enabled; previous content is replaced in the viewer."
                checked={state.enabled}
                disabled={mutating}
                onChange={(event) => toggle.mutate({ enabled: event.target.checked })}
              />
              <div className="flex flex-wrap items-center gap-2 border-t border-border pt-4">
                <Button
                  variant="secondary"
                  onClick={() => void handleDownload()}
                  loading={downloading}
                  disabled={mutating || state.fileName === null}
                  iconLeft={<Download className="size-3.5" />}
                >
                  Download trace
                </Button>
              </div>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>
                <span className="inline-flex items-center gap-2">
                  <ScrollText aria-hidden="true" className="size-4 text-subtle" />
                  Trace content
                </span>
              </CardTitle>
            </CardHeader>
            <CardContent>
              <TraceViewer
                key={`${state.fileName ?? 'none'}`}
                state={state}
              />
            </CardContent>
          </Card>
        </>
      )}
    </div>
  )
}
