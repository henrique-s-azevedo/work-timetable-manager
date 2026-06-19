import { useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { useGoogleLogin } from '@react-oauth/google'
import { useAuth } from '../context/AuthContext'
import './Login.css'

export default function Login() {
  const { user, loginSuccess } = useAuth()
  const navigate = useNavigate()

  useEffect(() => {
    if (user) navigate('/dashboard', { replace: true })
  }, [user, navigate])

  const login = useGoogleLogin({
    flow: 'implicit',
    scope: 'openid email profile https://www.googleapis.com/auth/calendar',
    onSuccess: async (tokenResponse) => {
      // Get ID token from userinfo endpoint
      const userInfoRes = await fetch('https://www.googleapis.com/oauth2/v3/userinfo', {
        headers: { Authorization: `Bearer ${tokenResponse.access_token}` }
      })
      const userInfo = await userInfoRes.json()

      // For resource server we need the ID token — use access_token as credential for now
      // and pass access_token for calendar storage
      // Note: with implicit flow we get access_token; id_token may be in tokenResponse
      const idToken = tokenResponse.id_token || tokenResponse.access_token
      await loginSuccess(idToken, tokenResponse.access_token)
      navigate('/dashboard', { replace: true })
    },
    onError: (err) => console.error('Google login error:', err),
  })

  return (
    <div className="login-page">
      <div className="login-card">
        <div className="login-logo">
          <span className="login-logo-icon">📅</span>
        </div>
        <h1 className="login-title">Work Timetable Manager</h1>
        <p className="login-subtitle">Gere o teu horário de trabalho</p>
        <button className="btn-google" onClick={() => login()}>
          <svg width="18" height="18" viewBox="0 0 18 18" fill="none">
            <path d="M17.64 9.2c0-.637-.057-1.251-.164-1.84H9v3.481h4.844c-.209 1.125-.843 2.078-1.796 2.716v2.259h2.908c1.702-1.567 2.684-3.875 2.684-6.615z" fill="#4285F4"/>
            <path d="M9 18c2.43 0 4.467-.806 5.956-2.18l-2.908-2.259c-.806.54-1.837.86-3.048.86-2.344 0-4.328-1.584-5.036-3.711H.957v2.332A8.997 8.997 0 0 0 9 18z" fill="#34A853"/>
            <path d="M3.964 10.71A5.41 5.41 0 0 1 3.682 9c0-.593.102-1.17.282-1.71V4.958H.957A8.996 8.996 0 0 0 0 9c0 1.452.348 2.827.957 4.042l3.007-2.332z" fill="#FBBC05"/>
            <path d="M9 3.58c1.321 0 2.508.454 3.44 1.345l2.582-2.58C13.463.891 11.426 0 9 0A8.997 8.997 0 0 0 .957 4.958L3.964 7.29C4.672 5.163 6.656 3.58 9 3.58z" fill="#EA4335"/>
          </svg>
          Entrar com Google
        </button>
      </div>
    </div>
  )
}
