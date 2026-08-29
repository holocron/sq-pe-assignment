import { useId, type InputHTMLAttributes, type ReactNode, type Ref } from 'react'
import { cn } from '../../lib/cn'

export interface CheckboxProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'type'> {
  label: ReactNode
  hint?: ReactNode
  containerClassName?: string
  ref?: Ref<HTMLInputElement>
}

export function Checkbox({
  label,
  hint,
  className,
  containerClassName,
  id,
  ...props
}: CheckboxProps) {
  const generatedId = useId()
  const checkboxId = id ?? generatedId
  return (
    <div className={cn('flex items-start gap-2', containerClassName)}>
      <input
        id={checkboxId}
        type="checkbox"
        className={cn(
          'mt-0.5 size-4 shrink-0 rounded-xxs border border-border-strong bg-surface accent-accent',
          'focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-bg',
          className,
        )}
        {...props}
      />
      <label htmlFor={checkboxId} className="text-sm text-fg select-none">
        {label}
        {hint ? <span className="block text-2xs text-subtle">{hint}</span> : null}
      </label>
    </div>
  )
}
