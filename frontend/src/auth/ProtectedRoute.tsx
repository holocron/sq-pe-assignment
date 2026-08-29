import type { ReactNode } from 'react'
import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { useAuth } from './useAuth'

export interface ProtectedRouteProps {
  /** Rendered when authenticated. Falls back to the nested `<Outlet />`. */
  children?: ReactNode
}

/**
 * Gate for every authenticated route. Unauthenticated visitors are sent to
 * /login with the attempted location in `state.from`, so LoginPage can bounce
 * them back after a successful sign-in.
 */
export function ProtectedRoute({ children }: ProtectedRouteProps) {
  const { isAuthenticated } = useAuth()
  const location = useLocation()

  if (!isAuthenticated) {
    return <Navigate to="/login" replace state={{ from: location }} />
  }

  return <>{children ?? <Outlet />}</>
}
