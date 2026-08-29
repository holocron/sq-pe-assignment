import { cn } from '../../lib/cn'
import { parseNarrative } from './narrative'

export interface NarrativeTextProps {
  text: string | null | undefined
  /** Shown when the agent left the section empty. */
  emptyLabel?: string
  className?: string
}

/**
 * Renders the agent's summary / recommendations as readable prose and bullets
 * — never as the raw TEXT column, and never as raw JSON.
 */
export function NarrativeText({
  text,
  emptyLabel = 'The agent did not record anything here.',
  className,
}: NarrativeTextProps) {
  const blocks = parseNarrative(text)

  if (blocks.length === 0) {
    return <p className={cn('text-sm text-muted', className)}>{emptyLabel}</p>
  }

  return (
    <div className={cn('space-y-3', className)}>
      {blocks.map((block, index) => {
        if (block.kind === 'heading') {
          return (
            <h4
              key={`heading-${index}`}
              className="text-2xs font-semibold tracking-caption text-muted uppercase"
            >
              {block.text}
            </h4>
          )
        }
        if (block.kind === 'list') {
          return (
            <ul key={`list-${index}`} className="space-y-1.5">
              {block.items.map((item, itemIndex) => (
                <li key={itemIndex} className="flex gap-2.5 text-sm leading-relaxed text-fg">
                  <span
                    aria-hidden="true"
                    className="mt-2 size-1.5 shrink-0 rounded-full bg-accent"
                  />
                  <span className="min-w-0">{item}</span>
                </li>
              ))}
            </ul>
          )
        }
        return (
          <p key={`paragraph-${index}`} className="text-sm leading-relaxed text-fg">
            {block.text}
          </p>
        )
      })}
    </div>
  )
}
