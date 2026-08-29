import { ChevronDown } from 'lucide-react'
import { useId, type ReactNode, type Ref, type SelectHTMLAttributes } from 'react'
import { cn } from '../../lib/cn'
import { INPUT_TONE, INPUT_TONE_INVALID } from './Input'

export interface SelectOption {
  value: string
  label: string
  disabled?: boolean
}

export interface SelectProps extends Omit<SelectHTMLAttributes<HTMLSelectElement>, 'size'> {
  label?: ReactNode
  hideLabel?: boolean
  hint?: ReactNode
  error?: string | null
  /** Convenience alternative to passing <option> children. */
  options?: SelectOption[]
  /** Placeholder option rendered first with an empty value. */
  placeholder?: string
  containerClassName?: string
  ref?: Ref<HTMLSelectElement>
}

export function Select({
  label,
  hideLabel = false,
  hint,
  error,
  options,
  placeholder,
  className,
  containerClassName,
  children,
  id,
  ...props
}: SelectProps) {
  const generatedId = useId()
  const selectId = id ?? generatedId
  const hintId = `${selectId}-hint`
  const errorId = `${selectId}-error`

  return (
    <div className={cn('flex flex-col gap-1', containerClassName)}>
      {label ? (
        <label
          htmlFor={selectId}
          className={cn(
            'text-2xs font-semibold tracking-caption text-muted uppercase',
            hideLabel && 'sr-only',
          )}
        >
          {label}
        </label>
      ) : null}
      {/* The chevron is a real icon rather than a background data-URL, so it
          inherits the theme tokens instead of hard-coding a colour. */}
      <div className="relative flex items-center">
        <select
          id={selectId}
          aria-invalid={error ? true : undefined}
          aria-describedby={cn(hint ? hintId : undefined, error ? errorId : undefined) || undefined}
          className={cn(
            'w-full appearance-none rounded-xs border bg-surface py-1.5 pr-8 pl-2.5',
            'text-sm text-fg outline-none transition-colors',
            'focus-visible:ring-2 focus-visible:outline-none',
            'disabled:cursor-not-allowed disabled:opacity-60',
            error ? INPUT_TONE_INVALID : INPUT_TONE,
            className,
          )}
          {...props}
        >
          {placeholder ? <option value="">{placeholder}</option> : null}
          {options?.map((option) => (
            <option key={option.value} value={option.value} disabled={option.disabled}>
              {option.label}
            </option>
          ))}
          {children}
        </select>
        <ChevronDown
          aria-hidden="true"
          className="pointer-events-none absolute right-2 size-3.5 text-subtle"
        />
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
