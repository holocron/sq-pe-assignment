import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { isApiError } from './api/errors'
import { AuthProvider } from './auth/AuthContext'
import { ProtectedRoute } from './auth/ProtectedRoute'
import { RoleGate } from './auth/RoleGate'
import { AccessDenied } from './components/AccessDenied'
import { ErrorBoundary, RouteErrorBoundary } from './components/ErrorBoundary'
import { AppShell } from './components/layout/AppShell'
import { ToastProvider } from './components/ui/Toast'
import { ThemeProvider } from './lib/theme'
import { AnalysisHistoryPage } from './pages/AnalysisHistoryPage'
import { AnalysisPage } from './pages/AnalysisPage'
import { CustomerPage } from './pages/CustomerPage'
import { DashboardPage } from './pages/DashboardPage'
import { KnowledgeSearchPage } from './pages/KnowledgeSearchPage'
import { LoginPage } from './pages/LoginPage'
import { NotFoundPage } from './pages/NotFoundPage'
import { KnowledgePage } from './pages/admin/KnowledgePage'
import { RulesPage } from './pages/admin/RulesPage'
import { UsersPage } from './pages/admin/UsersPage'

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      refetchOnWindowFocus: false,
      // Client errors are never worth retrying; transient server/network ones are.
      retry: (failureCount, error) => {
        const status = isApiError(error) ? error.status : 0
        if (status >= 400 && status < 500) return false
        return failureCount < 2
      },
    },
    mutations: { retry: false },
  },
})

/** Wraps an admin-only route so operators get a clear 403 surface. */
function AdminRoute({ resource, children }: { resource: string; children: ReactNode }) {
  return (
    <RoleGate allow="ADMIN" fallback={<AccessDenied resource={resource} />}>
      {children}
    </RoleGate>
  )
}

export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <ThemeProvider>
        <ToastProvider>
          <BrowserRouter>
            <AuthProvider>
              {/* Last resort inside the router: a crash in the shell, the login
                  screen or a provider renders the full-page panel instead of
                  unmounting the root to a blank page. */}
              <ErrorBoundary>
                <Routes>
                  <Route path="/login" element={<LoginPage />} />

                  <Route element={<ProtectedRoute />}>
                    <Route element={<AppShell />}>
                      {/* Screen-level boundary: keeps the chrome alive and
                          resets when the operator navigates away. */}
                      <Route element={<RouteErrorBoundary />}>
                        <Route index element={<Navigate to="/dashboard" replace />} />
                        <Route path="dashboard" element={<DashboardPage />} />
                        <Route path="customers/:customerId" element={<CustomerPage />} />
                        <Route
                          path="customers/:customerId/analyses"
                          element={<AnalysisHistoryPage />}
                        />
                        <Route path="analyses" element={<AnalysisHistoryPage />} />
                        <Route path="analyses/:assessmentId" element={<AnalysisPage />} />
                        <Route path="knowledge-search" element={<KnowledgeSearchPage />} />

                        <Route
                          path="admin/rules"
                          element={
                            <AdminRoute resource="risk rules">
                              <RulesPage />
                            </AdminRoute>
                          }
                        />
                        <Route
                          path="admin/knowledge"
                          element={
                            <AdminRoute resource="the knowledge base">
                              <KnowledgePage />
                            </AdminRoute>
                          }
                        />
                        <Route
                          path="admin/users"
                          element={
                            <AdminRoute resource="user administration">
                              <UsersPage />
                            </AdminRoute>
                          }
                        />

                        <Route path="*" element={<NotFoundPage />} />
                      </Route>
                    </Route>
                  </Route>
                </Routes>
              </ErrorBoundary>
            </AuthProvider>
          </BrowserRouter>
        </ToastProvider>
      </ThemeProvider>
    </QueryClientProvider>
  )
}
