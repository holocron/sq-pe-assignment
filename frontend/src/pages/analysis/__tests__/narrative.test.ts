import { describe, expect, it } from 'vitest'
import { parseNarrative } from '../narrative'

/** Built from code points: the whole problem is that these are invisible in a source file. */
const NBSP = String.fromCharCode(0x00a0)
const NARROW_NBSP = String.fromCharCode(0x202f)
const ZERO_WIDTH = String.fromCharCode(0x200b)

/**
 * Verbatim `analysis_runs.recommendations` from a real completed run, after the
 * transcript overflowed the model's context window and the compacted retry
 * produced a degenerate answer.
 */
const DEGENERATE_RUN_OUTPUT =
  `1.${NBSP}File${NBSP}SARS${NBSP}(${NBSP}S${NBSP.repeat(100)})${NBSP.repeat(17)})${NBSP.repeat(6)}`

/** Longest stretch a browser has no opportunity to wrap. */
function longestUnbreakableRun(text: string): number {
  return Math.max(0, ...text.split(/[ \n]/).map((part) => part.length))
}

describe('parseNarrative', () => {
  it('collapses the no-break space run that stretched the recommendations panel', () => {
    // The row is already persisted, so the renderer cannot rely on the server fix alone.
    expect(longestUnbreakableRun(DEGENERATE_RUN_OUTPUT)).toBeGreaterThan(100)

    const blocks = parseNarrative(DEGENERATE_RUN_OUTPUT)

    expect(blocks).toEqual([{ kind: 'list', items: ['File SARS ( S ) )'] }])
    const [rendered] = (blocks[0] as { items: string[] }).items
    expect(rendered).not.toContain(NBSP)
    expect(longestUnbreakableRun(rendered)).toBeLessThan(10)
  })

  it('collapses the other invisible space characters too', () => {
    const blocks = parseNarrative(`File${NARROW_NBSP}a${ZERO_WIDTH}SAR    now`)

    expect(blocks).toEqual([{ kind: 'paragraph', text: 'File a SAR now' }])
  })

  it('keeps ordinary prose and its bullet structure intact', () => {
    const blocks = parseNarrative(
      'Five of twelve rules were breached.\n\n- File a SAR\n- Freeze the card',
    )

    expect(blocks).toEqual([
      { kind: 'paragraph', text: 'Five of twelve rules were breached.' },
      { kind: 'list', items: ['File a SAR', 'Freeze the card'] },
    ])
  })

  it('renders a JSON array the model sometimes emits as a bullet list', () => {
    expect(parseNarrative('["File a SAR", "Freeze the card"]')).toEqual([
      { kind: 'list', items: ['File a SAR', 'Freeze the card'] },
    ])
  })

  it('reports nothing for text that carries nothing', () => {
    expect(parseNarrative(NBSP.repeat(20))).toEqual([])
    expect(parseNarrative('')).toEqual([])
    expect(parseNarrative(null)).toEqual([])
  })
})
