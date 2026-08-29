import { describe, expect, it } from 'vitest'
import { cleanParams, emptyPage, toPage } from './client'
import type { SpringPage } from './types'

describe('toPage', () => {
  it('normalises the classic Spring Page shape', () => {
    const wire: SpringPage<number> = {
      content: [1, 2, 3],
      totalElements: 42,
      totalPages: 3,
      size: 20,
      number: 1,
      first: false,
      last: false,
      empty: false,
    }
    expect(toPage(wire)).toEqual({
      content: [1, 2, 3],
      page: 1,
      size: 20,
      totalElements: 42,
      totalPages: 3,
      first: false,
      last: false,
      empty: false,
    })
  })

  it('normalises the Boot 4 PagedModel shape', () => {
    const wire: SpringPage<string> = {
      content: ['a'],
      page: { size: 10, number: 0, totalElements: 1, totalPages: 1 },
    }
    const page = toPage(wire)
    expect(page.page).toBe(0)
    expect(page.size).toBe(10)
    expect(page.totalElements).toBe(1)
    expect(page.first).toBe(true)
    expect(page.last).toBe(true)
  })

  it('survives a missing payload', () => {
    expect(toPage(undefined).content).toEqual([])
    expect(emptyPage<string>().empty).toBe(true)
  })
})

describe('cleanParams', () => {
  it('drops empty values so the query string stays clean', () => {
    expect(cleanParams({ query: '', page: 0, size: 20, type: null, status: undefined })).toEqual({
      page: 0,
      size: 20,
    })
  })
})
