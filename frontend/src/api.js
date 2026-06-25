/**
 * Pre-configured Axios instance for all API calls to the Spring Boot backend.
 *
 * Base URL:
 * - Reads VITE_API_URL from the environment (set in .env / deployment config).
 * - Falls back to an empty string in development so Vite's proxy handles routing.
 *
 * Request interceptor:
 * - Automatically attaches the stored Google OAuth access token as a Bearer header.
 * - Only adds the header if one is not already present, allowing individual call sites
 *   to override the token (e.g., the login request that sets the token for the first time).
 *
 * Response interceptor:
 * - On 401 responses, clears both stored tokens and redirects to the login page.
 *   This handles expired tokens without requiring every call site to check for 401.
 *   The hash-based redirect (/#/login) matches the HashRouter configuration in App.jsx.
 */
import axios from 'axios'

const api = axios.create({
  baseURL: import.meta.env.VITE_API_URL || '',
})

api.interceptors.request.use((config) => {
  const token = localStorage.getItem('id_token')
  if (token && !config.headers.Authorization) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

api.interceptors.response.use(
  (res) => res,
  (err) => {
    if (err.response?.status === 401) {
      localStorage.removeItem('id_token')
      localStorage.removeItem('access_token')
      window.location.href = '/#/login'
    }
    return Promise.reject(err)
  }
)

export default api
