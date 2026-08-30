/**
 * The statement that decided a rule, rendered as code.
 *
 * Shared by the coverage table and the trace viewer so the same query reads
 * identically wherever a reviewer meets it. Styling is on the theme tokens
 * (`surface-2`, `border`, `fg`), which is what keeps it legible in both themes —
 * per DESIGN_SYSTEM.md the risk ramp means a risk level, and a SQL statement is
 * not one, so nothing here borrows those colours.
 */
import { Database } from 'lucide-react'
import { cn } from '../../lib/cn'
import { dedentSql } from './sql'

export interface SqlBlockProps {
  sql: string
  /** Uppercase caption above the block, e.g. `Query the agent wrote`. */
  title?: string
  /** Accessible name; defaults to `title`. */
  label?: string
  /** Quiet note under the block — provenance, timing, truncation. */
  note?: string
  /** Muted treatment for the wrapped statement, which is the backend's text. */
  secondary?: boolean
  className?: string
}

const CAPTION = 'text-2xs font-semibold tracking-caption text-muted uppercase'

export function SqlBlock({
  sql,
  title,
  label,
  note,
  secondary = false,
  className,
}: SqlBlockProps) {
  const text = dedentSql(sql)

  return (
    <div className={cn('min-w-0', className)}>
      {title ? (
        <h5 className={cn('flex items-center gap-1.5', CAPTION)}>
          <Database aria-hidden="true" className="size-3" />
          {title}
        </h5>
      ) : null}
      <pre
        aria-label={label ?? title ?? 'SQL query'}
        className={cn(
          'mt-1.5 max-h-64 overflow-auto rounded-xs border px-2.5 py-2 font-mono text-2xs leading-relaxed whitespace-pre',
          secondary
            ? 'border-border/70 bg-surface-2/50 text-muted'
            : 'border-border bg-surface-2 text-fg',
        )}
      >
        <code>{text}</code>
      </pre>
      {note ? <p className="mt-1 text-2xs leading-relaxed text-subtle">{note}</p> : null}
    </div>
  )
}
