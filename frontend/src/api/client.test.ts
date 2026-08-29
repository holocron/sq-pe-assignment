import { describe, expect, it } from 'vitest'
import { cleanParams, emptyPage, toPage } from './client'
import type { SpringPage } from './types'

describe('toPage', () => {
  /* The shape this backend actually returns (web/dto/PageResponse.java),
     captured from GET /api/customers?page=1&size=3 against the running app.
     `page` is the zero-based index, NOT a PagedModel metadata object. */
  it('reads the numeric `page` index this backend sends', () => {
    const wire: SpringPage<string> = {
      content: ['a', 'b', 'c'],
      page: 1,
      size: 3,
      totalElements: 12,
      totalPages: 4,
    }
    expect(toPage(wire)).toEqual({
      content: ['a', 'b', 'c'],
      page: 1,
      size: 3,
      totalElements: 12,
      totalPages: 4,
      first: false,
      last: false,
      empty: false,
    })
  })

  it('marks the first and last numeric pages correctly', () => {
    const first = toPage({ content: [1], page: 0, size: 5, totalElements: 12, totalPages: 3 })
    expect(first.page).toBe(0)
    expect(first.first).toBe(true)
    expect(first.last).toBe(false)

    const last = toPage({ content: [1], page: 2, size: 5, totalElements: 12, totalPages: 3 })
    expect(last.page).toBe(2)
    expect(last.first).toBe(false)
    expect(last.last).toBe(true)
  })

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
