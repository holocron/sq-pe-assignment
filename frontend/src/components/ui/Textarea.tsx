import { useId, type ReactNode, type Ref, type TextareaHTMLAttributes } from 'react'
import { cn } from '../../lib/cn'
import { INPUT_BASE, INPUT_TONE, INPUT_TONE_INVALID } from './Input'

export interface TextareaProps extends TextareaHTMLAttributes<HTMLTextAreaElement> {
  label?: ReactNode
  hideLabel?: boolean
  hint?: ReactNode
  error?: string | null
  containerClassName?: string
  ref?: Ref<HTMLTextAreaElement>
}

export function Textarea({
  label,
  hideLabel = false,
  hint,
  error,
  className,
  containerClassName,
  id,
  ...props
}: TextareaProps) {
  const generatedId = useId()
  const textareaId = id ?? generatedId
  const hintId = `${textareaId}-hint`
  const errorId = `${textareaId}-error`

  return (
    <div className={cn('flex flex-col gap-1', containerClassName)}>
      {label ? (
        <label
          htmlFor={textareaId}
          className={cn(
            'text-2xs font-semibold tracking-caption text-muted uppercase',
            hideLabel && 'sr-only',
          )}
        >
          {label}
        </label>
      ) : null}
      <textarea
        id={textareaId}
        aria-invalid={error ? true : undefined}
        aria-describedby={cn(hint ? hintId : undefined, error ? errorId : undefined) || undefined}
        className={cn(
          INPUT_BASE,
          error ? INPUT_TONE_INVALID : INPUT_TONE,
          'min-h-24 resize-y font-mono text-xs leading-relaxed',
          className,
        )}
        {...props}
      />
      {hint && !error ? (
        <p id={hintId} className="text-2xs text-subtle">
          {hint}
        </p>
      ) : null}
      {error ? (
        <p id={errorId} role="alert" className="text-2xs font-medium text-danger-fg">
          {error}
        </p>
      ) : null}
    </div>
  )
}
