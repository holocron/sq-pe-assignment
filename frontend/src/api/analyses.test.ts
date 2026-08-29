import { describe, expect, it } from 'vitest'
import { analysisStreamUrl, normalizeAnalysisResult, normalizeTraceStep } from './analyses'
import type { AnalysisResultWire } from './types'

describe('normalizeTraceStep', () => {
  it('maps the snake_case trace keys from the spec', () => {
    expect(
      normalizeTraceStep({
        n: 1,
        type: 'tool_call',
        tool: 'list_risk_rules',
        args: {},
        result_preview: '10 rules',
        ms: 812,
      }),
    ).toEqual({
      type: 'tool_call',
      n: 1,
      ms: 812,
      tool: 'list_risk_rules',
      args: {},
      resultPreview: '10 rules',
      subject: null,
      outcome: null,
    })
  })

  /* Twelve verdict steps have to read as twelve different rules. The backend
     labels each step where the meaning was known, so the labels have to survive
     normalisation rather than being re-derived from a truncated preview. */
  it('forwards the recorded step labels', () => {
    expect(
      normalizeTraceStep({
        n: 6,
        type: 'tool_call',
        tool: 'submit_rule_evaluation',
        args: { rule_id: 'r-2' },
        result_preview: '{"ruleName":"Structuring',
        ms: 1421,
        subject: 'Structuring - repeated payments just below the reporting threshold',
        outcome: 'triggered +30.00 (rule 3 of 12)',
      }),
    ).toMatchObject({
      subject: 'Structuring - repeated payments just below the reporting threshold',
      outcome: 'triggered +30.00 (rule 3 of 12)',
    })
  })

  it('maps coverage reprompts and final steps', () => {
    expect(normalizeTraceStep({ n: 3, type: 'coverage_reprompt', missing: ['rule-1'] })).toEqual({
      type: 'coverage_reprompt',
      n: 3,
      ms: null,
      missing: ['rule-1'],
    })
    expect(normalizeTraceStep({ n: 4, type: 'final', risk_level: 'HIGH' })).toEqual({
      type: 'final',
      n: 4,
      ms: null,
      riskLevel: 'HIGH',
    })
  })

  it('keeps unknown step types renderable', () => {
    const step = normalizeTraceStep({ n: 9, type: 'thinking', text: 'hmm' })
    expect(step.type).toBe('unknown')
  })
})

describe('normalizeAnalysisResult', () => {
  it('accepts the trace as an object, an array or a JSON string', () => {
    const base: AnalysisResultWire = {
      assessmentId: 'a',
      customerId: 'c',
      status: 'COMPLETED',
      riskLevel: 'HIGH',
      totalScore: 61,
      createdAt: '2026-08-29T10:00:00Z',
    }
    const steps = [{ n: 1, type: 'assistant', text: 'hello' }]
    expect(normalizeAnalysisResult({ ...base, trace: { steps } }).trace).toHaveLength(1)
    expect(normalizeAnalysisResult({ ...base, trace: steps }).trace).toHaveLength(1)
    expect(normalizeAnalysisResult({ ...base, trace: JSON.stringify({ steps }) }).trace).toHaveLength(1)
    expect(normalizeAnalysisResult(base).trace).toEqual([])
    expect(normalizeAnalysisResult(base).ruleEvaluations).toEqual([])
  })
})

describe('analysisStreamUrl', () => {
  it('passes the JWT as a query parameter because EventSource cannot set headers', () => {
    expect(analysisStreamUrl('abc', 'tok en')).toBe('/api/analyses/abc/stream?token=tok%20en')
  })

  it('omits the parameter when there is no token', () => {
    expect(analysisStreamUrl('abc', null)).toBe('/api/analyses/abc/stream')
  })
})
