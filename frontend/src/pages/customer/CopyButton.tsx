import { Check, Copy } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { useToast } from '../../components/ui/Toast'
import { cn } from '../../lib/cn'

export interface CopyButtonProps {
  /** Text placed on the clipboard. */
  value: string
  /** Used in the tooltip and the confirmation toast, e.g. "Customer ID". */
  label?: string
  className?: string
}

/** Copies an identifier to the clipboard with a short inline confirmation. */
export function CopyButton({ value, label = 'Value', className }: CopyButtonProps) {
  const { success, error } = useToast()
  const [copied, setCopied] = useState(false)
  const timer = useRef<number | null>(null)

  useEffect(() => () => {
    if (timer.current !== null) window.clearTimeout(timer.current)
  }, [])

  async function copy() {
    try {
      if (!navigator.clipboard) throw new Error('Clipboard unavailable')
      await navigator.clipboard.writeText(value)
      setCopied(true)
      success(`${label} copied`)
      if (timer.current !== null) window.clearTimeout(timer.current)
      timer.current = window.setTimeout(() => setCopied(false), 1600)
    } catch {
      error('Could not copy', 'Your browser blocked clipboard access. Select the text manually.')
    }
  }

  return (
    <button
      type="button"
      onClick={() => void copy()}
      aria-label={`Copy ${label.toLowerCase()}`}
      title={`Copy ${label.toLowerCase()}`}
      className={cn(
        // A visible hairline so the copy affordance reads as a control, on the
        // Swissquote 4px radius rather than the card radius.
        'inline-flex size-6 shrink-0 items-center justify-center rounded-xs border border-border',
        'bg-surface text-subtle transition-colors hover:bg-surface-2 hover:text-fg',
        'focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none',
        className,
      )}
    >
      {copied ? (
        <Check aria-hidden="true" className="size-3.5" />
      ) : (
        <Copy aria-hidden="true" className="size-3.5" />
      )}
      <span className="sr-only" role="status">
        {copied ? `${label} copied to clipboard` : ''}
      </span>
    </button>
  )
}
