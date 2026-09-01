/**
 * Admin screen for the runtime LLM configuration: the OpenAI-compatible
 * endpoint, the reasoning model the orchestrator/judge/wand run on, the
 * tooling model the rule subagents run on, and the embedding model the
 * knowledge base is indexed with.
 *
 * The embedding model is the dangerous field: changing it invalidates every
 * stored vector, so a save that alters it is gated behind a confirmation modal
 * (and the server still answers 409 without `confirmReembed`), and the rebuild
 * that follows is polled and reported honestly — including its failures.
 */
import { CircleCheckBig, CircleX, PlugZap, RefreshCw, TriangleAlert } from 'lucide-react'
import { useMemo, useState } from 'react'
import { errorMessage } from '../../api/errors'
import {
  REEMBED_POLL_INTERVAL_MS,
  useLlmModels,
  useLlmSettings,
  useReembedStatus,
  useTestLlmConnection,
  useUpdateLlmSettings,
} from '../../api/llmSettings'
import type {
  LlmConnectionTest,
  LlmSettings,
  LlmSettingsInput,
  ReembedStatus,
} from '../../api/types'
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
import { ErrorState } from '../../components/ui/ErrorState'
import { Input } from '../../components/ui/Input'
import { Modal } from '../../components/ui/Modal'
import { PageHeader } from '../../components/ui/PageHeader'
import { Select } from '../../components/ui/Select'
import { Skeleton } from '../../components/ui/Skeleton'
import { Spinner } from '../../components/ui/Spinner'
import { useToast } from '../../components/ui/Toast'
import { formatDateTime, formatNumber } from '../../lib/format'

const HTTP_URL_PATTERN = /^https?:\/\/.+/i

function validateBaseUrl(baseUrl: string): string | null {
  const trimmed = baseUrl.trim()
  if (trimmed.length === 0) return 'Base URL is required.'
  if (!HTTP_URL_PATTERN.test(trimmed)) return 'Must be an http(s) URL, e.g. http://localhost:11434/v1.'
  return null
}

/** One chat/embed probe line under "Test connection". */
function ProbeLine({
  label,
  ok,
  detail,
  extra,
}: {
  label: string
  ok: boolean
  detail: string | null
  extra?: string | null
}) {
  return (
    <div className="flex items-start gap-2.5 rounded-xs border border-border bg-surface px-2.5 py-2">
      {ok ? (
        <CircleCheckBig aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-success" />
      ) : (
        <CircleX aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-danger" />
      )}
      <div className="min-w-0 text-xs">
        <p className="font-medium text-fg">
          {label} — {ok ? 'OK' : 'failed'}
          {extra ? <span className="text-muted"> · {extra}</span> : null}
        </p>
        {detail ? <p className="mt-0.5 break-words text-muted">{detail}</p> : null}
      </div>
    </div>
  )
}

/**
 * Live re-embed progress. Polls only while the server reports the rebuild
 * running; the terminal snapshot (including failures) is kept after polling
 * stops so the outcome survives the end of the run. The finished summary is
 * derived during render — the documented "adjusting state when data changes"
 * pattern — not from an effect.
 */
function ReembedProgress() {
  const reembedStatus = useReembedStatus({
    refetchInterval: (query) =>
      query.state.data?.running ? REEMBED_POLL_INTERVAL_MS : false,
  })
  const status = reembedStatus.data

  const [lastSeen, setLastSeen] = useState<ReembedStatus | undefined>(undefined)
  const [wasRunning, setWasRunning] = useState(false)
  const [finishedRun, setFinishedRun] = useState<ReembedStatus | null>(null)
  if (status !== lastSeen) {
    setLastSeen(status)
    if (status?.running) {
      setWasRunning(true)
      setFinishedRun(null)
    } else if (status && wasRunning) {
      setWasRunning(false)
      setFinishedRun(status)
    }
  }

  const progressPercent =
    status && status.totalDocuments > 0
      ? Math.round((status.completedDocuments / status.totalDocuments) * 100)
      : 0

  return (
    <>
      {status?.running ? (
        <Card>
          <CardHeader>
            <CardTitle>Re-embedding documents</CardTitle>
            <CardDescription>
              Knowledge-base vectors are being rebuilt with the new embedding model. Retrieval keeps
              working against the old index until the rebuild finishes.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <div className="flex items-center justify-between gap-3 text-xs">
              <span className="flex min-w-0 items-center gap-2 text-fg">
                <Spinner size="xs" label="Re-embedding" />
                <span className="font-medium">
                  Re-embedding documents — {formatNumber(status.completedDocuments)} of{' '}
                  {formatNumber(status.totalDocuments)}
                </span>
              </span>
              <span className="numeric shrink-0 text-muted">{progressPercent}%</span>
            </div>
            <div
              role="progressbar"
              aria-valuenow={progressPercent}
              aria-valuemin={0}
              aria-valuemax={100}
              aria-label="Re-embedding progress"
              className="mt-2 h-1.5 w-full overflow-hidden rounded-full bg-surface-3"
            >
              <div
                className="h-full rounded-full bg-accent transition-[width] duration-200"
                style={{ width: `${progressPercent}%` }}
              />
            </div>
            {status.failedDocuments > 0 ? (
              <p className="mt-2 text-2xs text-danger-fg">
                {formatNumber(status.failedDocuments)} document
                {status.failedDocuments === 1 ? '' : 's'} failed so far.
              </p>
            ) : null}
            {status.lastError ? (
              <p className="mt-1 font-mono text-2xs break-words text-danger-fg">
                {status.lastError}
              </p>
            ) : null}
          </CardContent>
        </Card>
      ) : null}

      {finishedRun ? (
        <div
          role="status"
          className={
            finishedRun.failedDocuments > 0
              ? 'flex items-start gap-2.5 rounded-md border border-warning/40 bg-warning-soft px-4 py-3'
              : 'flex items-start gap-2.5 rounded-md border border-success/40 bg-success-soft px-4 py-3'
          }
        >
          {finishedRun.failedDocuments > 0 ? (
            <TriangleAlert aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-warning" />
          ) : (
            <CircleCheckBig aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-success" />
          )}
          <div className="min-w-0 text-xs">
            <p className="text-sm font-medium text-fg">
              {finishedRun.failedDocuments > 0
                ? `Re-embedding finished with ${formatNumber(finishedRun.failedDocuments)} failure${finishedRun.failedDocuments === 1 ? '' : 's'}`
                : 'Re-embedding finished'}
            </p>
            <p className="mt-0.5 text-muted">
              {formatNumber(finishedRun.completedDocuments)} of{' '}
              {formatNumber(finishedRun.totalDocuments)} documents re-embedded.
              {finishedRun.failedDocuments > 0
                ? ' Failed documents are not searchable — re-upload them from the knowledge base.'
                : ''}
            </p>
            {finishedRun.lastError ? (
              <p className="mt-1 font-mono text-2xs break-words text-danger-fg">
                {finishedRun.lastError}
              </p>
            ) : null}
          </div>
        </div>
      ) : null}
    </>
  )
}

/**
 * The settings form. State initialises from the loaded settings and the
 * component is keyed by `updatedAt`, so a save that persists new values
 * remounts the form on what the server actually stored.
 */
function LlmSettingsForm({ settings }: { settings: LlmSettings }) {
  const toast = useToast()

  const [baseUrl, setBaseUrl] = useState(settings.baseUrl)
  const [chatModel, setChatModel] = useState(settings.chatModel)
  const [toolModel, setToolModel] = useState(settings.toolModel)
  const [embedModel, setEmbedModel] = useState(settings.embedModel)
  /* Per-model keys: a field starts "untouched" (not sent on save/test — the
     server keeps the stored key). Once the admin edits it, the field is dirty
     and its content is sent verbatim — an emptied field therefore goes out as
     '' which the server reads as "explicitly no key" (local model servers). */
  const [chatApiKey, setChatApiKey] = useState<string | null>(null)
  const [embedApiKey, setEmbedApiKey] = useState<string | null>(null)
  const [baseUrlError, setBaseUrlError] = useState<string | null>(null)

  /* Model dropdowns are populated on demand; a 502/unreachable endpoint drops
     the form back to free-text fields with the current values kept. */
  const [models, setModels] = useState<string[] | null>(null)
  const [modelsError, setModelsError] = useState<string | null>(null)
  const modelsQuery = useLlmModels({
    onSuccess: (list) => {
      setModels(list)
      setModelsError(null)
    },
    onError: (error) => {
      setModels(null)
      setModelsError(errorMessage(error))
    },
  })
  const freeTextModels = models === null

  const testConnection = useTestLlmConnection()
  const [testResult, setTestResult] = useState<LlmConnectionTest | null>(null)

  /* Embedding-model change confirmation. */
  const [confirmReembedOpen, setConfirmReembedOpen] = useState(false)
  const embedModelChanged = embedModel.trim() !== settings.embedModel.trim()

  const update = useUpdateLlmSettings({
    onSuccess: (result) => {
      toast.success(
        'LLM settings saved',
        result.reembedStarted
          ? 'Re-embedding of the knowledge base has started.'
          : undefined,
      )
    },
    onError: (error) => {
      /* 409 is not an error to toast: it is the server asking for the same
         confirmation the modal already offers, so the modal is shown instead. */
      if (error.status === 409) {
        setConfirmReembedOpen(true)
        return
      }
      toast.error('Could not save LLM settings', errorMessage(error))
    },
  })

  const modelOptions = useMemo(() => {
    if (models === null) return []
    const ids = [...models]
    /* A configured model the endpoint no longer advertises must stay
       selectable — dropping it would rewrite settings just by opening the page. */
    for (const current of [chatModel, toolModel, embedModel]) {
      if (current && !ids.includes(current)) ids.push(current)
    }
    return ids.map((id) => ({ value: id, label: id }))
  }, [models, chatModel, toolModel, embedModel])

  const busy = update.isPending

  function currentInput(): LlmSettingsInput {
    return {
      baseUrl,
      chatModel,
      toolModel,
      embedModel,
      ...(chatApiKey !== null ? { chatApiKey } : {}),
      ...(embedApiKey !== null ? { embedApiKey } : {}),
    }
  }

  function handleFetchModels(): void {
    const error = validateBaseUrl(baseUrl)
    setBaseUrlError(error)
    if (error) return
    /* The models listing takes one endpoint-level key — the dirty chat key. */
    const apiKey = chatApiKey?.trim()
    modelsQuery.mutate({ baseUrl, ...(apiKey ? { apiKey } : {}) })
  }

  function handleTestConnection(): void {
    const error = validateBaseUrl(baseUrl)
    setBaseUrlError(error)
    if (error) return
    setTestResult(null)
    testConnection.mutate(currentInput(), {
      onSuccess: setTestResult,
      onError: (error) => toast.error('Connection test failed', errorMessage(error)),
    })
  }

  function doSave(confirmReembed: boolean): void {
    update.mutate({ ...currentInput(), ...(confirmReembed ? { confirmReembed: true } : {}) })
  }

  function handleSave(): void {
    const error = validateBaseUrl(baseUrl)
    setBaseUrlError(error)
    if (error) return
    /* Only the embedding model gates the confirmation — a base-URL change alone
       rebuilds nothing. */
    if (embedModelChanged) {
      setConfirmReembedOpen(true)
      return
    }
    doSave(false)
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Endpoint and models</CardTitle>
        <CardDescription>
          Source:{' '}
          {settings.source === 'database' ? (
            <Badge tone="info">Database override</Badge>
          ) : (
            <Badge tone="neutral">Environment</Badge>
          )}{' '}
          <Badge tone="outline">Embedding dimension {settings.embedDimension ?? 'unknown'}</Badge>
          {settings.updatedAt ? (
            <span className="ml-2 text-2xs text-subtle">
              Last saved {formatDateTime(settings.updatedAt)}
              {settings.updatedBy ? ` by ${settings.updatedBy}` : ''}
            </span>
          ) : null}
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <Input
          label="Base URL"
          required
          value={baseUrl}
          onChange={(event) => {
            setBaseUrl(event.target.value)
            if (baseUrlError) setBaseUrlError(null)
          }}
          placeholder="http://localhost:11434/v1"
          error={baseUrlError}
          hint="Any OpenAI-compatible endpoint."
        />

        <div className="flex flex-col gap-2">
          <div className="flex items-center justify-between gap-3">
            <p className="text-2xs text-subtle">
              {freeTextModels
                ? 'Model ids are entered manually — fetch the list from the endpoint to pick from a dropdown.'
                : `${formatNumber(modelOptions.length)} models advertised by the endpoint.`}
            </p>
            <Button
              variant="secondary"
              size="sm"
              onClick={handleFetchModels}
              loading={modelsQuery.isPending}
              iconLeft={<RefreshCw className="size-3.5" />}
            >
              Fetch models
            </Button>
          </div>
          {modelsError ? (
            <div
              role="alert"
              className="flex items-start gap-2.5 rounded-md border border-danger/40 bg-danger-soft px-3 py-2.5"
            >
              <TriangleAlert aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-danger" />
              <div className="min-w-0 text-xs">
                <p className="font-medium text-danger-fg">Model list unavailable</p>
                <p className="mt-0.5 text-fg">{modelsError}</p>
                <p className="mt-0.5 text-muted">
                  Falling back to free-text fields — the configured values are kept.
                </p>
              </div>
            </div>
          ) : null}
        </div>

        <div className="grid gap-4 sm:grid-cols-3">
          {freeTextModels ? (
            <>
              <Input
                label="Reasoning model"
                required
                value={chatModel}
                onChange={(event) => setChatModel(event.target.value)}
                placeholder="gpt-oss-120b"
                hint="Orchestrator, rule judge, Enhance wand."
              />
              <Input
                label="Tooling model"
                required
                value={toolModel}
                onChange={(event) => setToolModel(event.target.value)}
                placeholder="qwen3-32b"
                hint="Rule subagents' tool loops; shares the chat API key."
              />
              <Input
                label="Embedding model"
                required
                value={embedModel}
                onChange={(event) => setEmbedModel(event.target.value)}
                placeholder="text-embedding-3-small"
              />
            </>
          ) : (
            <>
              <Select
                label="Reasoning model"
                required
                value={chatModel}
                onChange={(event) => setChatModel(event.target.value)}
                options={modelOptions}
                placeholder="Select a reasoning model"
              />
              <Select
                label="Tooling model"
                required
                value={toolModel}
                onChange={(event) => setToolModel(event.target.value)}
                options={modelOptions}
                placeholder="Select a tooling model"
              />
              <Select
                label="Embedding model"
                required
                value={embedModel}
                onChange={(event) => setEmbedModel(event.target.value)}
                options={modelOptions}
                placeholder="Select an embedding model"
              />
            </>
          )}
        </div>

        <div className="grid gap-4 sm:grid-cols-2">
          <Input
            label="Chat models API key"
            type="password"
            value={chatApiKey ?? ''}
            onChange={(event) => setChatApiKey(event.target.value)}
            placeholder={settings.chatApiKeySet ? '•••• configured' : 'Not configured'}
            hint="Shared by the reasoning and tooling models. Leave empty for no key (local model servers); leave untouched to keep the current key."
            autoComplete="off"
          />
          <Input
            label="Embedding model API key"
            type="password"
            value={embedApiKey ?? ''}
            onChange={(event) => setEmbedApiKey(event.target.value)}
            placeholder={settings.embedApiKeySet ? '•••• configured' : 'Not configured'}
            hint="Leave empty for no key (local model servers); leave untouched to keep the current key."
            autoComplete="off"
          />
        </div>

        <div className="flex flex-wrap items-center gap-2 border-t border-border pt-4">
          <Button
            variant="primary"
            onClick={handleSave}
            loading={busy}
            disabled={
              chatModel.trim() === '' || toolModel.trim() === '' || embedModel.trim() === ''
            }
          >
            Save settings
          </Button>
          <Button
            variant="secondary"
            onClick={handleTestConnection}
            loading={testConnection.isPending}
            iconLeft={<PlugZap className="size-3.5" />}
          >
            Test connection
          </Button>
        </div>

        {testResult ? (
          <div
            aria-label="Connection test results"
            className="flex flex-col gap-1.5 rounded-md border border-border bg-surface-2/40 px-3 py-2.5"
          >
            <ProbeLine
              label="Reasoning model"
              ok={testResult.chat.ok}
              detail={testResult.chat.detail}
            />
            <ProbeLine
              label="Tooling model"
              ok={testResult.tool.ok}
              detail={testResult.tool.detail}
            />
            <ProbeLine
              label="Embedding model"
              ok={testResult.embed.ok}
              detail={testResult.embed.detail}
              extra={
                testResult.embed.dimension !== null
                  ? `dimension ${formatNumber(testResult.embed.dimension)}`
                  : null
              }
            />
          </div>
        ) : null}
      </CardContent>

      <Modal
        open={confirmReembedOpen}
        onClose={() => setConfirmReembedOpen(false)}
        title="Change the embedding model?"
        description="This rebuilds every knowledge-base vector."
        closeOnOverlayClick={false}
        footer={
          <>
            <Button variant="secondary" onClick={() => setConfirmReembedOpen(false)}>
              Cancel
            </Button>
            <Button
              variant="primary"
              onClick={() => {
                setConfirmReembedOpen(false)
                doSave(true)
              }}
              loading={busy}
            >
              Re-embed and save
            </Button>
          </>
        }
      >
        <p className="text-xs text-muted">
          Switching from <span className="font-medium text-fg">{settings.embedModel}</span> to{' '}
          <span className="font-medium text-fg">{embedModel}</span> invalidates all stored
          embeddings. All knowledge-base vectors must be rebuilt, and the documents will be
          re-embedded automatically with the new model after saving.
        </p>
      </Modal>
    </Card>
  )
}

export function LlmSettingsPage() {
  const settings = useLlmSettings()

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        eyebrow={<BackLink to="/dashboard">Dashboard</BackLink>}
        title="LLM settings"
        description="OpenAI-compatible endpoint and models used for analyses and knowledge-base embeddings. Saved settings take effect at runtime; the API keys are stored server-side and never shown."
      />

      {settings.isLoading ? (
        <Card>
          <CardContent className="flex flex-col gap-3 py-4">
            <Skeleton className="h-4 w-48" />
            <Skeleton className="h-9 w-full" />
            <Skeleton className="h-9 w-full" />
            <Skeleton className="h-9 w-full" />
          </CardContent>
        </Card>
      ) : settings.isError || !settings.data ? (
        <ErrorState
          title="LLM settings unavailable"
          error={settings.error}
          onRetry={() => void settings.refetch()}
        />
      ) : (
        <>
          <LlmSettingsForm key={settings.data.updatedAt ?? 'current'} settings={settings.data} />
          <ReembedProgress />
        </>
      )}
    </div>
  )
}
