import { describe, expect, it } from 'vitest'
import {
  EM_DASH,
  formatBytes,
  formatDuration,
  formatMoney,
  formatPercent,
  initials,
  maskPan,
  truncateMiddle,
} from './format'

describe('formatMoney', () => {
  it('always shows an explicit currency code', () => {
    expect(formatMoney(12500, 'USD')).toContain('USD')
    expect(formatMoney(12500, 'USD')).toContain('12,500.00')
  })

  it('falls back gracefully for unknown codes', () => {
    expect(formatMoney(10, 'XYZZY')).toBe('10.00 XYZZY')
  })

  it('renders a dash for missing amounts', () => {
    expect(formatMoney(null, 'USD')).toBe(EM_DASH)
  })
})

describe('misc formatters', () => {
  it('formats durations the way the trace shows them', () => {
    expect(formatDuration(812)).toBe('812ms')
    expect(formatDuration(4200)).toBe('4.2s')
    expect(formatDuration(63_000)).toBe('1m 03s')
  })

  it('never leaks a full PAN', () => {
    const masked = maskPan('4111111111111234')
    expect(masked).toContain('1234')
    expect(masked).not.toContain('4111')
  })

  it('truncates long hashes in the middle', () => {
    expect(truncateMiddle('0x1234567890abcdef1234567890', 6, 4)).toBe('0x1234…7890')
  })

  it('formats ratios as percentages', () => {
    expect(formatPercent(0.125)).toBe('12.5%')
  })

  it('formats byte sizes', () => {
    expect(formatBytes(512)).toBe('512 B')
    expect(formatBytes(2048)).toBe('2 KB')
  })

  it('derives initials', () => {
    expect(initials('Ada Lovelace')).toBe('AL')
    expect(initials(null)).toBe('?')
  })
})
