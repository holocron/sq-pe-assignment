import { describe, expect, it } from 'vitest'
import { RISK_LEVELS, type RiskLevel } from '../api/types'
import {
  RISK_LEVEL_STYLES,
  compareRiskLevel,
  parseRiskLevel,
  riskLevelFromScore,
} from './risk'

describe('riskLevelFromScore', () => {
  it('applies the banding from the spec', () => {
    expect(riskLevelFromScore(0)).toBe('LOW')
    expect(riskLevelFromScore(24.99)).toBe('LOW')
    expect(riskLevelFromScore(25)).toBe('MEDIUM')
    expect(riskLevelFromScore(49.99)).toBe('MEDIUM')
    expect(riskLevelFromScore(50)).toBe('HIGH')
    expect(riskLevelFromScore(74.99)).toBe('HIGH')
    expect(riskLevelFromScore(75)).toBe('CRITICAL')
    expect(riskLevelFromScore(1000)).toBe('CRITICAL')
  })

  it('returns null for missing scores', () => {
    expect(riskLevelFromScore(null)).toBeNull()
    expect(riskLevelFromScore(undefined)).toBeNull()
    expect(riskLevelFromScore(Number.NaN)).toBeNull()
  })
})

describe('risk colour mapping', () => {
  it('covers every level exactly once', () => {
    for (const level of RISK_LEVELS) {
      expect(RISK_LEVEL_STYLES[level].level).toBe(level)
    }
  })

  it('uses the agreed hue per level', () => {
    expect(RISK_LEVEL_STYLES.LOW.dot).toContain('risk-low')
    expect(RISK_LEVEL_STYLES.MEDIUM.dot).toContain('risk-medium')
    expect(RISK_LEVEL_STYLES.HIGH.dot).toContain('risk-high')
    expect(RISK_LEVEL_STYLES.CRITICAL.dot).toContain('risk-critical')
  })
})

describe('parseRiskLevel', () => {
  it('normalises casing and rejects unknown values', () => {
    expect(parseRiskLevel(' high ')).toBe('HIGH')
    expect(parseRiskLevel('EXTREME')).toBeNull()
    expect(parseRiskLevel(7)).toBeNull()
  })
})

describe('compareRiskLevel', () => {
  it('sorts most severe first', () => {
    const sorted = ([...RISK_LEVELS] as RiskLevel[]).sort(compareRiskLevel)
    expect(sorted).toEqual(['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'])
  })
})
