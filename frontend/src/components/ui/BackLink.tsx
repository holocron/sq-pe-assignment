import { ArrowLeft } from 'lucide-react'
import { Link, type LinkProps } from 'react-router-dom'
import { cn } from '../../lib/cn'

export interface BackLinkProps extends Omit<LinkProps, 'className'> {
  className?: string
}

/**
 * The one "way back" affordance: a left arrow plus the destination's page
 * title, rendered in the page-header eyebrow (or just above the header card
 * on the customer profile). Every detail page uses this instead of inventing
 * its own link styling.
 */
export function BackLink({ className, children, ...props }: BackLinkProps) {
  return (
    <Link
      className={cn(
        'inline-flex w-fit items-center gap-1.5 rounded-xs font-medium underline-offset-4',
        'transition-colors hover:text-fg hover:underline',
        'focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none',
        className,
      )}
      {...props}
    >
      <ArrowLeft aria-hidden="true" className="size-3.5" />
      {children}
    </Link>
  )
}
