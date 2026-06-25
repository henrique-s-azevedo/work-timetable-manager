/**
 * Authentication context provider and hook for the Work Timetable Manager.
 *
 * Responsibilities:
 * - Persists the Google OAuth access token in localStorage under the key 'id_token'.
 * - On mount, attempts to restore an existing session by calling GET /api/auth/me
 *   with the stored token. If the call fails (expired token, network error), it
 *   forces a logout to clear stale state.
 * - Exposes loginSuccess() to be called after a successful Google OAuth flow, which
 *   stores the token, calls POST /api/auth/login to upsert the backend record, and
 *   sets the user object in state.
 * - Exposes logout() to clear all tokens and user state.
 * - Exposes updateUser() for optimistic partial updates (e.g., after saving the profile).
 *
 * Context shape:
 * - user: { id, email, name, initials, profilePhotoUrl } | null
 * - credential: the raw access token string currently in use | null
 * - loading: true while the initial /api/auth/me call is in-flight
 * - loginSuccess(accessToken, userInfo): call after Google OAuth returns
 * - logout(): clears tokens and user state
 * - updateUser(partial): merges partial fields into the current user object
 *
 * Token storage note:
 * Both 'id_token' and 'access_token' are stored as the same Google access token.
 * The naming is a legacy artefact — the application uses the access token for both
 * API authentication and Google Calendar calls.
 */
import { createContext, useContext, useState, useEffect, useCallback } from 'react'
import api from '../api'

const AuthContext = createContext(null)

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null)
  const [credential, setCredential] = useState(localStorage.getItem('id_token'))
  const [loading, setLoading] = useState(true)

  const logout = useCallback(() => {
    localStorage.removeItem('id_token')
    localStorage.removeItem('access_token')
    setCredential(null)
    setUser(null)
  }, [])

  // On mount (or when credential changes), attempt to restore the session from the
  // stored token. The loading flag gates ProtectedRoute rendering in App.jsx.
  useEffect(() => {
    if (!credential) {
      setLoading(false)
      return
    }
    api.get('/api/auth/me', {
      headers: { Authorization: `Bearer ${credential}` }
    }).then(res => {
      setUser(res.data)
    }).catch(() => {
      // Token is invalid or expired; clear state so the user is redirected to login.
      logout()
    }).finally(() => {
      setLoading(false)
    })
  }, [credential, logout])

  /**
   * Completes the login flow after a successful Google OAuth implicit-flow response.
   *
   * Stores the token, calls the backend login endpoint (which upserts the instructor
   * record and stores the access token for Calendar API use), and sets the user state.
   * If the backend call fails, performs a full logout to prevent partial state.
   *
   * @param {string} accessToken - the Google OAuth 2.0 access token
   * @param {object} userInfo    - Google user profile: { name, email, picture }
   */
  const loginSuccess = useCallback(async (accessToken, userInfo) => {
    localStorage.setItem('id_token', accessToken)
    localStorage.setItem('access_token', accessToken)
    setCredential(accessToken)

    try {
      const res = await api.post('/api/auth/login', {
        accessToken,
        name: userInfo.name,
        email: userInfo.email,
        picture: userInfo.picture,
      }, {
        // The request interceptor in api.js hasn't seen the new credential yet
        // because setCredential is async; pass the token explicitly here.
        headers: { Authorization: `Bearer ${accessToken}` }
      })
      setUser(res.data)
    } catch (err) {
      console.error('Login failed', err)
      logout()
    }
  }, [logout])

  /**
   * Merges partial fields into the current user object without a round-trip to the server.
   * Used by the Profile page after a successful PATCH to keep the context in sync.
   *
   * @param {object} partial - fields to merge into the current user (e.g., { name, initials })
   */
  const updateUser = useCallback((partial) => {
    setUser(prev => ({ ...prev, ...partial }))
  }, [])

  return (
    <AuthContext.Provider value={{ user, credential, loading, loginSuccess, logout, updateUser }}>
      {children}
    </AuthContext.Provider>
  )
}

/**
 * Hook to consume the authentication context.
 * Must be used inside an AuthProvider subtree.
 *
 * @returns {{ user, credential, loading, loginSuccess, logout, updateUser }}
 */
export function useAuth() {
  return useContext(AuthContext)
}
