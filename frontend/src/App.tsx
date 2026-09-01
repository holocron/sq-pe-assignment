import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { lazy, Suspense, type ReactNode } from 'react'
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { isApiError } from './api/errors'
import { AuthProvider } from './auth/AuthContext'
import { ProtectedRoute } from './auth/ProtectedRoute'
import { RoleGate } from './auth/RoleGate'
import { AccessDenied } from './components/AccessDenied'
import { ErrorBoundary, RouteErrorBoundary } from './components/ErrorBoundary'
import { AppShell } from './components/layout/AppShell'
import { Spinner } from './components/ui/Spinner'
import { ToastProvider } from './components/ui/Toast'
import { ThemeProvider } from './lib/theme'
import { LoginPage } from './pages/LoginPage'

/* Every page behind the login is code-split, so the sign-in route ships neither
   the dashboard nor the chart library (recharts rides the customer-page chunk).
   Each page module keeps its named export; the mapping to a default export
   lives here. */
const AnalysisHistoryPage = lazy(() =>
  import('./pages/AnalysisHistoryPage').then((m) => ({ default: m.AnalysisHistoryPage })),
)
const AnalysisPage = lazy(() =>
  import('./pages/AnalysisPage').then((m) => ({ default: m.AnalysisPage })),
)
const CustomerPage = lazy(() =>
  import('./pages/CustomerPage').then((m) => ({ default: m.CustomerPage })),
)
const DashboardPage = lazy(() =>
  import('./pages/DashboardPage').then((m) => ({ default: m.DashboardPage })),
)
const KnowledgeSearchPage = lazy(() =>
  import('./pages/KnowledgeSearchPage').then((m) => ({ default: m.KnowledgeSearchPage })),
)
const NotFoundPage = lazy(() =>
  import('./pages/NotFoundPage').then((m) => ({ default: m.NotFoundPage })),
)
const KnowledgePage = lazy(() =>
  import('./pages/admin/KnowledgePage').then((m) => ({ default: m.KnowledgePage })),
)
const RulesPage = lazy(() => import('./pages/admin/RulesPage').then((m) => ({ default: m.RulesPage })))
const UsersPage = lazy(() => import('./pages/admin/UsersPage').then((m) => ({ default: m.UsersPage })))
const LlmSettingsPage = lazy(() =>
  import('./pages/admin/LlmSettingsPage').then((m) => ({ default: m.LlmSettingsPage })),
)
const AgentTracePage = lazy(() =>
  import('./pages/admin/AgentTracePage').then((m) => ({ default: m.AgentTracePage })),
)

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
                <Suspense
                  fallback={
                    <div className="flex min-h-40 items-center justify-center p-8">
                      <Spinner label="Loading the page" />
                    </div>
                  }
                >
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
                        <Route
                          path="admin/llm-settings"
                          element={
                            <AdminRoute resource="LLM settings">
                              <LlmSettingsPage />
                            </AdminRoute>
                          }
                        />
                        <Route
                          path="admin/agent-trace"
                          element={
                            <AdminRoute resource="the agent trace">
                              <AgentTracePage />
                            </AdminRoute>
                          }
                        />

                        <Route path="*" element={<NotFoundPage />} />
                      </Route>
                    </Route>
                  </Route>
                  </Routes>
                </Suspense>
              </ErrorBoundary>
            </AuthProvider>
          </BrowserRouter>
        </ToastProvider>
      </ThemeProvider>
    </QueryClientProvider>
  )
}
