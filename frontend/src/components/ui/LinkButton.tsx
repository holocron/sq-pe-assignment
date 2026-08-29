import type { ReactNode } from 'react'
import { Link, type LinkProps } from 'react-router-dom'
import { cn } from '../../lib/cn'
import { buttonClasses, type ButtonSize, type ButtonVariant } from './buttonStyles'

export interface LinkButtonProps extends Omit<LinkProps, 'className'> {
  variant?: ButtonVariant
  size?: ButtonSize
  iconLeft?: ReactNode
  iconRight?: ReactNode
  fullWidth?: boolean
  className?: string
}

/**
 * A router `<Link>` that looks exactly like a `<Button>`.
 *
 * Navigation stays a real anchor — middle-click, copy-link and the browser's
 * own affordances keep working — while the styling comes from one place, so a
 * "Back to dashboard" link cannot drift away from the buttons beside it.
 */
export function LinkButton({
  variant = 'secondary',
  size = 'md',
  iconLeft,
  iconRight,
  fullWidth = false,
  className,
  children,
  ...props
}: LinkButtonProps) {
  return (
    <Link className={cn(buttonClasses({ variant, size, fullWidth }), className)} {...props}>
      {iconLeft}
      {children}
      {iconRight}
    </Link>
  )
}
