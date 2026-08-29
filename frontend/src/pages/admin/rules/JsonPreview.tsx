/**
 * Read-only preview of the exact `threshold_logic` JSON that will be persisted.
 * The visual builder is the single source of truth — this pane never edits.
 *
 * It is rendered as a proper code surface: monospace, its own tinted panel and
 * a restrained syntax tint (keys, literals, punctuation). Every colour comes
 * from a semantic token, so the panel stays legible when the theme flips to
 * dark, and no risk colour is used — this is code, not a verdict.
 */
import { Braces, Check, Copy } from 'lucide-react'
import { useEffect, useMemo, useRef, useState } from 'react'
import type { RuleNode } from '../../../api/types'
import { Button } from '../../../components/ui/Button'
import { cn } from '../../../lib/cn'
import { countConditions } from '../../../lib/rules'
import { serializedJson } from './ruleModel'

export interface JsonPreviewProps {
  node: RuleNode
  className?: string
}

type TokenKind = 'key' | 'string' | 'number' | 'literal' | 'punctuation' | 'plain'

interface JsonToken {
  text: string
  kind: TokenKind
}

/* Property names (a string followed by a colon), strings, numbers, the three
   JSON literals, and structural punctuation — in that order, so a key is never
   mistaken for a plain string value. */
const JSON_TOKEN =
  /"(?:\\.|[^"\\])*"(?=\s*:)|"(?:\\.|[^"\\])*"|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?|true|false|null|[{}[\]:,]/g

const TOKEN_CLASS: Record<TokenKind, string> = {
  /* Keys carry the structure, so they get the full-contrast foreground. */
  key: 'text-fg',
  /* Blue is the app's informational, non-risk emphasis. */
  string: 'text-info-fg',
  /* Brand tone for literals — chrome, and it never reads as a verdict. */
  number: 'text-accent-strong',
  literal: 'text-accent-strong',
  punctuation: 'text-subtle',
  plain: 'text-muted',
}

/**
 * Splits formatted JSON into coloured tokens. The concatenation of every
 * segment is byte-for-byte the input string — whitespace and indentation
 * included — so the preview stays copy-and-paste exact.
 */
function tokenizeJson(json: string): JsonToken[] {
  const tokens: JsonToken[] = []
  let cursor = 0

  for (const match of json.matchAll(JSON_TOKEN)) {
    const index = match.index ?? 0
    const text = match[0]
    if (index > cursor) tokens.push({ text: json.slice(cursor, index), kind: 'plain' })

    let kind: TokenKind = 'punctuation'
    if (text.startsWith('"')) {
      kind = json.slice(index + text.length).trimStart().startsWith(':') ? 'key' : 'string'
    } else if (text === 'true' || text === 'false' || text === 'null') {
      kind = 'literal'
    } else if (!'{}[]:,'.includes(text)) {
      kind = 'number'
    }

    tokens.push({ text, kind })
    cursor = index + text.length
  }

  if (cursor < json.length) tokens.push({ text: json.slice(cursor), kind: 'plain' })
  return tokens
}

export function JsonPreview({ node, className }: JsonPreviewProps) {
  const json = serializedJson(node)
  const conditions = countConditions(node)
  const tokens = useMemo(() => tokenizeJson(json), [json])
  const [copied, setCopied] = useState(false)
  const [copyError, setCopyError] = useState<string | null>(null)
  const timer = useRef<number | null>(null)

  useEffect(() => {
    return () => {
      if (timer.current !== null) window.clearTimeout(timer.current)
    }
  }, [])

  const handleCopy = async (): Promise<void> => {
    try {
      await navigator.clipboard.writeText(json)
      setCopyError(null)
      setCopied(true)
      if (timer.current !== null) window.clearTimeout(timer.current)
      timer.current = window.setTimeout(() => setCopied(false), 2000)
    } catch {
      setCopied(false)
      setCopyError('Copying is not available in this browser. Select the JSON and copy manually.')
    }
  }

  return (
    <section
      aria-label="Threshold logic JSON"
      className={cn(
        'flex min-h-0 flex-col overflow-hidden rounded-md border border-border bg-surface',
        className,
      )}
    >
      <header className="flex items-center justify-between gap-2 border-b border-border bg-surface-2/60 px-3 py-2">
        <div className="flex min-w-0 items-start gap-2">
          <Braces aria-hidden="true" className="mt-0.5 size-3.5 shrink-0 text-subtle" />
          <div className="min-w-0">
            <h3 className="font-mono text-xs font-semibold text-fg">threshold_logic</h3>
            <p className="text-2xs text-muted">
              {conditions} condition{conditions === 1 ? '' : 's'} · read-only preview
            </p>
          </div>
        </div>
        <Button
          size="sm"
          variant="secondary"
          onClick={() => void handleCopy()}
          iconLeft={
            copied ? (
              <Check className="size-3.5" aria-hidden="true" />
            ) : (
              <Copy className="size-3.5" aria-hidden="true" />
            )
          }
        >
          {copied ? 'Copied' : 'Copy'}
        </Button>
      </header>
      <pre
        data-testid="rule-json"
        tabIndex={0}
        className="max-h-64 flex-1 overflow-auto bg-surface-2/35 px-3 py-2.5 font-mono text-2xs leading-relaxed text-muted"
      >
        {tokens.map((token, index) => (
          <span key={index} className={TOKEN_CLASS[token.kind]}>
            {token.text}
          </span>
        ))}
      </pre>
      <span aria-live="polite" className="sr-only">
        {copied ? 'JSON copied to clipboard' : ''}
      </span>
      {copyError ? (
        <p role="alert" className="border-t border-border px-3 py-2 text-2xs text-danger-fg">
          {copyError}
        </p>
      ) : null}
    </section>
  )
}
