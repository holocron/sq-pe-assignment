import { ShieldCheck, UserRound } from 'lucide-react'
import type { Role } from '../../api/types'
import { Badge, type BadgeSize } from './Badge'

export interface RoleBadgeProps {
  role: Role | null | undefined
  size?: BadgeSize
  className?: string
}

const ICON_SIZES: Record<BadgeSize, string> = {
  sm: 'size-2.5',
  md: 'size-3',
}

/** Signed-in role chip. Icon + label, so the tint is never the only signal. */
export function RoleBadge({ role, size = 'sm', className }: RoleBadgeProps) {
  if (!role) return null
  const admin = role === 'ADMIN'
  const Icon = admin ? ShieldCheck : UserRound

  return (
    <Badge
      tone={admin ? 'accent' : 'outline'}
      size={size}
      icon={<Icon className={ICON_SIZES[size]} />}
      className={className}
    >
      {admin ? 'Admin' : 'Operator'}
    </Badge>
  )
}
