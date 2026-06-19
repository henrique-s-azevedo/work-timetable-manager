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
      logout()
    }).finally(() => {
      setLoading(false)
    })
  }, [credential, logout])

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
        headers: { Authorization: `Bearer ${accessToken}` }
      })
      setUser(res.data)
    } catch (err) {
      console.error('Login failed', err)
      logout()
    }
  }, [logout])

  const updateUser = useCallback((partial) => {
    setUser(prev => ({ ...prev, ...partial }))
  }, [])

  return (
    <AuthContext.Provider value={{ user, credential, loading, loginSuccess, logout, updateUser }}>
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth() {
  return useContext(AuthContext)
}
