/**
 * Root application component.
 *
 * Responsibilities:
 * - Wraps the entire tree with AuthProvider so authentication state is available globally.
 * - Uses HashRouter (hash-based routing) so the app can be hosted on static file servers
 *   (e.g., Vercel, GitHub Pages) without server-side URL rewriting.
 * - Declares all top-level routes and enforces authentication via ProtectedRoute.
 *
 * Route structure:
 * - /login       — public; redirects authenticated users to /dashboard.
 * - /dashboard   — protected; weekly session calendar and metrics overview.
 * - /upload      — protected; three-step timetable upload → preview → export flow.
 * - /profile     — protected; instructor profile and initials management.
 * - * (catch-all) — redirects to /dashboard.
 */
import { HashRouter, Routes, Route, Navigate } from 'react-router-dom'
import { AuthProvider, useAuth } from './context/AuthContext'
import Login from './pages/Login'
import Dashboard from './pages/Dashboard'
import Profile from './pages/Profile'
import UploadPanel from './components/UploadPanel'

/**
 * Route guard that blocks unauthenticated access to protected pages.
 *
 * Renders a loading screen while the auth state is being resolved (i.e., while the
 * /api/auth/me call is in-flight on first load). Once resolved, unauthenticated users
 * are redirected to /login.
 *
 * @param {React.ReactNode} children - the protected page to render when authenticated
 */
function ProtectedRoute({ children }) {
  const { user, loading } = useAuth()
  if (loading) return <div className="loading-screen">A carregar...</div>
  if (!user) return <Navigate to="/login" replace />
  return children
}

export default function App() {
  return (
    <AuthProvider>
      <HashRouter>
        <Routes>
          <Route path="/login" element={<Login />} />
          <Route path="/dashboard" element={
            <ProtectedRoute><Dashboard /></ProtectedRoute>
          } />
          <Route path="/upload" element={
            <ProtectedRoute><UploadPanel /></ProtectedRoute>
          } />
          <Route path="/profile" element={
            <ProtectedRoute><Profile /></ProtectedRoute>
          } />
          <Route path="*" element={<Navigate to="/dashboard" replace />} />
        </Routes>
      </HashRouter>
    </AuthProvider>
  )
}
