/**
 * `main.tsx` must mount the boundary above everything, including the providers
 * and the router themselves. Isolated in its own file because it replaces the
 * whole `<App />` module with one that throws on render.
 */
import { act, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../../App', () => ({
  default: () => {
    throw new TypeError('provider blew up before the router mounted')
  },
}))

beforeEach(() => {
  vi.spyOn(console, 'error').mockImplementation(() => {})
})

describe('application root', () => {
  it('renders the crash panel instead of unmounting the root to a blank page', async () => {
    const root = document.createElement('div')
    root.id = 'root'
    document.body.appendChild(root)

    await act(async () => {
      await import('../../main')
    })

    const alert = within(root).getByRole('alert')
    expect(alert).toHaveTextContent('The application stopped unexpectedly')
    expect(alert).toHaveTextContent('provider blew up before the router mounted')
    expect(within(root).getByRole('button', { name: /Reload the application/ })).toBeInTheDocument()

    root.remove()
  })
})
