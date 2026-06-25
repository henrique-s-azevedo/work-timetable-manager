/**
 * Application entry point.
 *
 * Mounts the React tree into the DOM root element and wraps the entire app with:
 * - React.StrictMode: activates additional development-time checks and warnings.
 * - GoogleOAuthProvider: supplies the Google OAuth client ID to all @react-oauth/google
 *   hooks via context, enabling useGoogleLogin() in descendant components.
 *
 * The client ID is read from the VITE_GOOGLE_CLIENT_ID environment variable, which
 * must be set in .env (or the deployment environment) before building.
 */
import React from 'react'
import ReactDOM from 'react-dom/client'
import { GoogleOAuthProvider } from '@react-oauth/google'
import App from './App.jsx'
import './index.css'

const clientId = import.meta.env.VITE_GOOGLE_CLIENT_ID

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <GoogleOAuthProvider clientId={clientId}>
      <App />
    </GoogleOAuthProvider>
  </React.StrictMode>,
)
