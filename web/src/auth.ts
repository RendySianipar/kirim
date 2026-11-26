import Keycloak from 'keycloak-js'

/**
 * Login through Keycloak with the Authorization Code flow + PKCE: the browser
 * is sent to Keycloak's login page and comes back with a short-lived access
 * token. No password ever reaches this app or the Kirim API.
 *
 * Served by the reverse proxy, Keycloak lives on the same origin under /auth.
 * In `npm run dev` (port 5173) set VITE_KEYCLOAK_URL=http://localhost/auth.
 */
const keycloak = new Keycloak({
  url: import.meta.env.VITE_KEYCLOAK_URL ?? `${window.location.origin}/auth`,
  realm: 'kirim',
  clientId: 'kirim-web',
})

export async function initAuth(): Promise<void> {
  await keycloak.init({
    onLoad: 'login-required',
    pkceMethod: 'S256',
    checkLoginIframe: false,
  })
}

export function currentUser(): { username: string; isOps: boolean } {
  const token = keycloak.tokenParsed
  return {
    username: (token?.preferred_username as string | undefined) ?? 'unknown',
    isOps: keycloak.hasRealmRole('ops'),
  }
}

export function logout(): Promise<void> {
  return keycloak.logout({ redirectUri: window.location.origin })
}

/** fetch() with a fresh bearer token: refreshed when it expires within 30 seconds. */
export async function authFetch(input: string, init: RequestInit = {}): Promise<Response> {
  try {
    await keycloak.updateToken(30)
  } catch {
    // The refresh token expired too: log in again.
    await keycloak.login()
  }
  const headers = new Headers(init.headers)
  headers.set('Authorization', `Bearer ${keycloak.token}`)
  return fetch(input, { ...init, headers })
}
