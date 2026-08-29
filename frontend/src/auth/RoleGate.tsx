import type { ReactNode } from 'react'
import type { Role } from '../api/types'
import { useAuth } from './useAuth'

export interface RoleGateProps {
  /** Roles allowed to see the children. */
  allow: Role | Role[]
  children: ReactNode
  /** Rendered instead of the children when the role does not match. */
  fallback?: ReactNode
}

/**
 * Renders `children` only for the allowed roles. Used both inline (hiding an
 * admin action) and as a route wrapper (with an `<AccessDenied />` fallback).
 * Role checks are always enforced server-side as well.
 */
export function RoleGate({ allow, children, fallback = null }: RoleGateProps) {
  const { role } = useAuth()
  const allowed = Array.isArray(allow) ? allow : [allow]
  if (!role || !allowed.includes(role)) {
    return <>{fallback}</>
  }
  return <>{children}</>
}
