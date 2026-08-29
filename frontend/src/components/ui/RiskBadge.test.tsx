import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { RISK_LEVELS } from '../../api/types'
import { RiskBadge } from './RiskBadge'

describe('RiskBadge', () => {
  it('always renders the level as text, never colour alone', () => {
    for (const level of RISK_LEVELS) {
      const { unmount } = render(<RiskBadge level={level} />)
      expect(screen.getByText(level)).toBeInTheDocument()
      unmount()
    }
  })

  it('uses the shared risk colour scale', () => {
    const { container } = render(<RiskBadge level="CRITICAL" />)
    expect(container.firstElementChild?.className).toContain('risk-critical')
  })

  it('handles a missing level', () => {
    render(<RiskBadge level={null} />)
    expect(screen.getByText('NOT ASSESSED')).toBeInTheDocument()
  })

  it('can show the numeric score alongside the level', () => {
    render(<RiskBadge level="HIGH" score={61.5} showScore />)
    expect(screen.getByText('HIGH')).toBeInTheDocument()
    expect(screen.getByText('61.5')).toBeInTheDocument()
  })
})
