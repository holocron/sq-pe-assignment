import { format, formatDistanceToNowStrict, isValid, parseISO } from 'date-fns'

const EM_DASH = '—'

function toDate(value: string | number | Date | null | undefined): Date | null {
  if (value === null || value === undefined || value === '') return null
  const date =
    value instanceof Date
      ? value
      : typeof value === 'number'
        ? new Date(value)
        : parseISO(value)
  return isValid(date) ? date : null
}

function toNumber(value: number | string | null | undefined): number | null {
  if (value === null || value === undefined || value === '') return null
  const parsed = typeof value === 'number' ? value : Number(value)
  return Number.isFinite(parsed) ? parsed : null
}

/** `1 234.56` style grouping with a fixed number of fraction digits. */
export function formatNumber(
  value: number | string | null | undefined,
  options: { minimumFractionDigits?: number; maximumFractionDigits?: number } = {},
): string {
  const num = toNumber(value)
  if (num === null) return EM_DASH
  return new Intl.NumberFormat(undefined, {
    minimumFractionDigits: options.minimumFractionDigits ?? 0,
    maximumFractionDigits: options.maximumFractionDigits ?? 2,
  }).format(num)
}

/** Amount only, always two decimals — pair with an explicit currency column. */
export function formatAmount(value: number | string | null | undefined): string {
  const num = toNumber(value)
  if (num === null) return EM_DASH
  return new Intl.NumberFormat(undefined, {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(num)
}

/**
 * Money with an explicit ISO currency code, e.g. `USD 12,500.00`.
 * Unknown/invalid currency codes fall back to `<amount> <code>`.
 */
export function formatMoney(
  value: number | string | null | undefined,
  currency: string | null | undefined,
): string {
  const num = toNumber(value)
  if (num === null) return EM_DASH
  const code = (currency ?? '').trim().toUpperCase()
  if (code.length !== 3) {
    return code ? `${formatAmount(num)} ${code}` : formatAmount(num)
  }
  try {
    return new Intl.NumberFormat(undefined, {
      style: 'currency',
      currency: code,
      currencyDisplay: 'code',
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    })
      .format(num)
      .replace(/[\u00a0\u202f]/g, ' ')
  } catch {
    return `${formatAmount(num)} ${code}`
  }
}

/** Large figures for stat tiles: `1.2M`. */
export function formatCompactNumber(value: number | string | null | undefined): string {
  const num = toNumber(value)
  if (num === null) return EM_DASH
  return new Intl.NumberFormat(undefined, {
    notation: 'compact',
    maximumFractionDigits: 1,
  }).format(num)
}

/** `value` is a ratio in 0..1 unless `alreadyPercent` is set. */
export function formatPercent(
  value: number | string | null | undefined,
  options: { alreadyPercent?: boolean; maximumFractionDigits?: number } = {},
): string {
  const num = toNumber(value)
  if (num === null) return EM_DASH
  const ratio = options.alreadyPercent ? num / 100 : num
  return new Intl.NumberFormat(undefined, {
    style: 'percent',
    maximumFractionDigits: options.maximumFractionDigits ?? 1,
  }).format(ratio)
}

export function formatDate(value: string | number | Date | null | undefined): string {
  const date = toDate(value)
  return date ? format(date, 'dd MMM yyyy') : EM_DASH
}

export function formatDateTime(value: string | number | Date | null | undefined): string {
  const date = toDate(value)
  return date ? format(date, 'dd MMM yyyy, HH:mm') : EM_DASH
}

export function formatDateTimeSeconds(
  value: string | number | Date | null | undefined,
): string {
  const date = toDate(value)
  return date ? format(date, 'dd MMM yyyy, HH:mm:ss') : EM_DASH
}

export function formatTime(value: string | number | Date | null | undefined): string {
  const date = toDate(value)
  return date ? format(date, 'HH:mm:ss') : EM_DASH
}

/** `3 hours ago` / `in 2 days`. */
export function formatRelativeTime(
  value: string | number | Date | null | undefined,
): string {
  const date = toDate(value)
  return date ? formatDistanceToNowStrict(date, { addSuffix: true }) : EM_DASH
}

/** Milliseconds as a compact human duration: `812ms`, `4.2s`, `1m 03s`. */
export function formatDuration(ms: number | string | null | undefined): string {
  const num = toNumber(ms)
  if (num === null) return EM_DASH
  if (num < 1000) return `${Math.round(num)}ms`
  if (num < 60_000) return `${(num / 1000).toFixed(1)}s`
  const minutes = Math.floor(num / 60_000)
  const seconds = Math.round((num % 60_000) / 1000)
  return `${minutes}m ${String(seconds).padStart(2, '0')}s`
}

export function formatBytes(value: number | string | null | undefined): string {
  const num = toNumber(value)
  if (num === null) return EM_DASH
  if (num < 1024) return `${num} B`
  const units = ['KB', 'MB', 'GB', 'TB']
  let size = num / 1024
  let unit = 0
  while (size >= 1024 && unit < units.length - 1) {
    size /= 1024
    unit += 1
  }
  return `${size.toFixed(size >= 10 || unit === 0 ? 0 : 1)} ${units[unit]}`
}

/** ISO-2 country code to a display name, e.g. `DE` -> `Germany`. */
export function formatCountry(code: string | null | undefined): string {
  const value = (code ?? '').trim().toUpperCase()
  if (value.length !== 2) return value || EM_DASH
  try {
    const names = new Intl.DisplayNames(undefined, { type: 'region' })
    return names.of(value) ?? value
  } catch {
    return value
  }
}

/** Middle-truncates long opaque strings such as wallet addresses and hashes. */
export function truncateMiddle(
  value: string | null | undefined,
  head = 8,
  tail = 6,
): string {
  if (!value) return EM_DASH
  if (value.length <= head + tail + 1) return value
  return `${value.slice(0, head)}…${value.slice(-tail)}`
}

/** Shortens a UUID for dense tables while keeping it recognisable. */
export function shortId(value: string | null | undefined): string {
  if (!value) return EM_DASH
  return value.length <= 8 ? value : value.slice(0, 8)
}

/** Never render a full PAN — keep only the last four digits. */
export function maskPan(pan: string | null | undefined): string {
  if (!pan) return EM_DASH
  const digits = pan.replace(/\D/g, '')
  if (digits.length < 4) return '••••'
  return `•••• ${digits.slice(-4)}`
}

export function fullName(
  firstName: string | null | undefined,
  lastName: string | null | undefined,
): string {
  const name = [firstName, lastName].filter(Boolean).join(' ').trim()
  return name || EM_DASH
}

export function initials(name: string | null | undefined): string {
  if (!name) return '?'
  const parts = name.trim().split(/\s+/).filter(Boolean)
  if (parts.length === 0) return '?'
  const first = parts[0]?.[0] ?? ''
  const last = parts.length > 1 ? (parts[parts.length - 1]?.[0] ?? '') : ''
  return (first + last).toUpperCase() || '?'
}

/** Turns `AGENT_JUDGED` / `tool_call` into `Agent judged` / `Tool call`. */
export function humanizeToken(value: string | null | undefined): string {
  if (!value) return EM_DASH
  const spaced = value.replace(/[_-]+/g, ' ').trim().toLowerCase()
  return spaced.charAt(0).toUpperCase() + spaced.slice(1)
}

export { EM_DASH }
