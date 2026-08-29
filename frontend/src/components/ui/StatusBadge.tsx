import {
  CircleCheck,
  CircleSlash,
  CircleX,
  Clock,
  Undo2,
  type LucideIcon,
} from 'lucide-react'
import type { TransactionStatus } from '../../api/types'
import { statusLabel, statusTone } from '../../lib/activity'
import { Badge, type BadgeSize } from './Badge'

export interface StatusBadgeProps {
  status: TransactionStatus | string | null | undefined
  size?: BadgeSize
  className?: string
}

/**
 * Glyph per status, so the chip carries three signals — words, shape and
 * colour — and never depends on colour alone.
 */
const STATUS_ICONS: Record<string, LucideIcon> = {
  completed: CircleCheck,
  pending: Clock,
  failed: CircleX,
  reversed: Undo2,
  declined: CircleSlash,
}

const ICON_SIZES: Record<BadgeSize, string> = {
  sm: 'size-2.5',
  md: 'size-3',
}

/** Transaction status chip. Only genuine failures use the danger colour. */
export function StatusBadge({ status, size = 'sm', className }: StatusBadgeProps) {
  const key = typeof status === 'string' ? status.trim().toLowerCase() : ''
  const Icon = STATUS_ICONS[key]

  return (
    <Badge
      tone={statusTone(status)}
      size={size}
      icon={Icon ? <Icon className={ICON_SIZES[size]} /> : undefined}
      dot={!Icon}
      className={className}
    >
      {statusLabel(status)}
    </Badge>
  )
}
