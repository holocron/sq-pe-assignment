import { cn } from '../../lib/cn'

export type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger' | 'link'
export type ButtonSize = 'sm' | 'md' | 'lg' | 'icon'

/**
 * Swissquote control base: 4px radius (`xs` on the brand radius scale), a
 * hairline border and a brand-orange focus ring offset from the surface.
 */
const BASE =
  'inline-flex items-center justify-center gap-2 rounded-xs border whitespace-nowrap ' +
  'transition-colors outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 ' +
  'focus-visible:ring-offset-bg disabled:pointer-events-none disabled:opacity-50'

/* The font weight lives on the variant, not on BASE: `cn()` has no
   tailwind-merge, so two weight utilities on one element would resolve by CSS
   source order rather than by intent. */
const VARIANTS: Record<ButtonVariant, string> = {
  /* The brand CTA: Swissquote orange, white label, primary-400 on hover and
     primary-600 while pressed.

     Contrast note — white on the brand orange is 3.2:1. That is the brand's
     own CTA pairing, and it clears the 3:1 required of the control itself, so
     it is kept deliberately, set semibold and confined to 14px+ labels. Every
     smaller brand-marked control (segmented controls, chips, active nav) uses
     fg-on-surface text with an orange rail instead of white-on-orange. */
  primary:
    'border-transparent bg-accent font-semibold text-accent-fg hover:bg-accent-hover active:bg-accent-active',
  /* Neutral bordered surface — the default control in a dense operator tool. */
  secondary: 'border-border bg-surface font-medium text-fg hover:bg-surface-2 active:bg-surface-3',
  ghost:
    'border-transparent bg-transparent font-medium text-muted hover:bg-surface-2 hover:text-fg active:bg-surface-3',
  /* Destructive. A pure red, never the rose-800 of the CRITICAL risk badge,
     and never pill-shaped, so it cannot be read as a verdict. */
  danger:
    'border-transparent bg-danger font-semibold text-danger-on hover:bg-danger-hover active:bg-danger-active',
  /* `accent-strong` rather than `accent` so link text clears 4.5:1 on white. */
  link: 'border-transparent bg-transparent font-medium text-accent-strong underline-offset-4 hover:underline p-0 h-auto',
}

/** Dense by Swissquote standards — this is a working tool, not a landing page. */
const SIZES: Record<ButtonSize, string> = {
  sm: 'h-7 px-2.5 text-xs',
  md: 'h-8 px-3 text-sm',
  lg: 'h-9 px-4 text-sm',
  icon: 'size-8 p-0',
}

export interface ButtonClassOptions {
  variant?: ButtonVariant
  size?: ButtonSize
  fullWidth?: boolean
}

/**
 * The button look as a class string.
 *
 * Lives apart from `<Button />` so an anchor or a router `<Link>` can render as
 * a button without duplicating the styling — see `<LinkButton />`. Controls are
 * never coloured by risk; the risk ramp belongs to `<RiskBadge />` alone.
 */
export function buttonClasses({
  variant = 'secondary',
  size = 'md',
  fullWidth = false,
}: ButtonClassOptions = {}): string {
  return cn(
    BASE,
    VARIANTS[variant],
    variant === 'link' ? undefined : SIZES[size],
    fullWidth && 'w-full',
  )
}
