import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import type { ReactNode } from 'react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import type { Role, User } from '../api/types'
import { AuthProvider } from './AuthContext'
import { RoleGate } from './RoleGate'
import { writeStoredAuth } from './storage'

vi.mock('../api/auth', () => ({
  fetchCurrentUser: vi.fn(async () => ({
    username: 'admin',
    fullName: 'Ada Admin',
    role: 'ADMIN',
  })),
  login: vi.fn(),
}))

function signIn(role: Role): User {
  const user: User = { username: role.toLowerCase(), fullName: `Test ${role}`, role }
  writeStoredAuth({ token: 'test-token', expiresAt: null, user })
  return user
}

function wrap(children: ReactNode) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return (
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <AuthProvider>{children}</AuthProvider>
      </MemoryRouter>
    </QueryClientProvider>
  )
}

describe('RoleGate', () => {
  it('renders children for an allowed role', () => {
    signIn('ADMIN')
    render(wrap(<RoleGate allow="ADMIN">admin area</RoleGate>))
    expect(screen.getByText('admin area')).toBeInTheDocument()
  })

  it('hides children for a disallowed role', () => {
    signIn('OPERATOR')
    render(wrap(<RoleGate allow="ADMIN">admin area</RoleGate>))
    expect(screen.queryByText('admin area')).not.toBeInTheDocument()
  })

  it('renders the fallback for a disallowed role', () => {
    signIn('OPERATOR')
    render(
      wrap(
        <RoleGate allow={['ADMIN']} fallback={<span>not permitted</span>}>
          admin area
        </RoleGate>,
      ),
    )
    expect(screen.getByText('not permitted')).toBeInTheDocument()
  })

  it('hides everything when signed out', () => {
    render(wrap(<RoleGate allow={['ADMIN', 'OPERATOR']}>any area</RoleGate>))
    expect(screen.queryByText('any area')).not.toBeInTheDocument()
  })
})
