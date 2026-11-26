import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.tsx'
import { initAuth } from './auth'

// Log in first (redirects to Keycloak when there is no session), then render.
initAuth()
  .then(() =>
    createRoot(document.getElementById('root')!).render(
      <StrictMode>
        <App />
      </StrictMode>,
    ),
  )
  .catch(() => {
    document.getElementById('root')!.textContent = 'Cannot reach the login server (Keycloak). Is docker compose running?'
  })
