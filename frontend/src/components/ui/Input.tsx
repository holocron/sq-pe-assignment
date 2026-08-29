import { useId, type InputHTMLAttributes, type ReactNode, type Ref } from 'react'
import { cn } from '../../lib/cn'

export interface InputProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'size'> {
  /** Always provide a label — it is rendered as a real <label>. */
  label?: ReactNode
  /** Hides the label visually but keeps it for screen readers. */
  hideLabel?: boolean
  hint?: ReactNode
  error?: string | null
  iconLeft?: ReactNode
  /** Rendered inside the field on the right, e.g. a unit or a clear button. */
  iconRight?: ReactNode
  containerClassName?: string
  ref?: Ref<HTMLInputElement>
}

/**
 * Shared field shell: 4px radius, hairline grey-300 border, and a brand-orange
 * focus treatment (accent border + 2px ring) that replaces the global outline.
 *
 * The border and ring COLOURS live in the tone constants below rather than in
 * the base, so the invalid state replaces them instead of relying on CSS source
 * order to win (there is no tailwind-merge in this project — see `lib/cn.ts`).
 */
export const INPUT_BASE =
  'w-full rounded-xs border bg-surface px-2.5 py-1.5 text-sm text-fg ' +
  'placeholder:text-subtle outline-none transition-colors ' +
  'focus-visible:ring-2 focus-visible:outline-none ' +
  'disabled:cursor-not-allowed disabled:opacity-60'

export const INPUT_TONE =
  'border-border hover:border-border-strong focus-visible:border-accent focus-visible:ring-ring'

export const INPUT_TONE_INVALID =
  'border-danger hover:border-danger focus-visible:border-danger focus-visible:ring-danger'

export function Input({
  label,
  hideLabel = false,
  hint,
  error,
  iconLeft,
  iconRight,
  className,
  containerClassName,
  id,
  ...props
}: InputProps) {
  const generatedId = useId()
  const inputId = id ?? generatedId
  const hintId = `${inputId}-hint`
  const errorId = `${inputId}-error`

  return (
    <div className={cn('flex flex-col gap-1', containerClassName)}>
      {label ? (
        <label
          htmlFor={inputId}
          className={cn(
            'text-2xs font-semibold tracking-caption text-muted uppercase',
            hideLabel && 'sr-only',
          )}
        >
          {label}
          {props.required ? <span className="text-danger-fg"> *</span> : null}
        </label>
      ) : null}
      <div className="relative flex items-center">
        {iconLeft ? (
          <span
            aria-hidden="true"
            className="pointer-events-none absolute left-2.5 flex text-subtle"
          >
            {iconLeft}
          </span>
        ) : null}
        <input
          id={inputId}
          aria-invalid={error ? true : undefined}
          aria-describedby={cn(hint ? hintId : undefined, error ? errorId : undefined) || undefined}
          className={cn(
            INPUT_BASE,
            error ? INPUT_TONE_INVALID : INPUT_TONE,
            iconLeft && 'pl-8',
            iconRight && 'pr-8',
            className,
          )}
          {...props}
        />
        {iconRight ? (
          <span className="absolute right-2 flex text-subtle">{iconRight}</span>
        ) : null}
      </div>
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
