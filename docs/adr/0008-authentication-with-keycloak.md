# ADR 0008: Authentication with Keycloak, authorization in the services

**Status:** accepted

## Context
Until now anyone who could reach the API could open accounts, read any balance and move money out of any account. A payments API needs to know **who** is calling (authentication) and decide **what they may do** (authorization), without each service storing passwords.

## Decision
- **Keycloak** (OpenID Connect / OAuth2) issues tokens; it runs in docker-compose with a development realm imported from `deploy/keycloak/kirim-realm.json` (users `alice`, `bob`, `carol`, `dave` with role `customer`, `ops` with role `ops`; fixed user ids so account ownership survives a Keycloak restart).
- Both services are **OAuth2 resource servers** (Spring Security). Every `/api` request carries `Authorization: Bearer <access token>`; the service checks the signature against Keycloak's public keys (JWKS), the expiry and the issuer. No sessions, no cookies, so CSRF protection is off.
- **Issuer vs keys:** tokens name the public URL people log in through (`http://localhost/auth/realms/kirim`, via the reverse proxy), while services fetch keys from `http://keycloak:8080` inside the Docker network. Keycloak's `KC_HOSTNAME` pins the issuer so both paths agree.
- **Roles** come from Keycloak's `realm_access.roles` claim and become `ROLE_CUSTOMER` / `ROLE_OPS`.
- **Rules:**

| Action | customer | ops |
| --- | --- | --- |
| Open an account | yes, owned by the caller (`owner_id` = token `sub`; never taken from the body) | yes |
| List / read accounts, statements, notifications | own accounts only; others are **404** (ids cannot be probed) | all |
| Send money | **from own accounts only**; to any account | **no** (403 `ACCOUNT_NOT_OWNED`): separation of duties |
| Read a transfer | if sender or receiver | all |
| Reconciliation, risk alerts | 403 | yes |

- Coarse checks (role per URL) are in `SecurityConfig`; data checks ("is this your account?") in `AccountAccess`, called by the controllers before the services run. Ownership is checked **before** the idempotency key is looked at, so another user's key can never replay someone else's transfer.
- 401 and 403 are Problem Details with `code` (`UNAUTHENTICATED`, `FORBIDDEN`, `ACCOUNT_NOT_OWNED`), like every other error.
- **Clients:** the web app uses the Authorization Code flow with **PKCE** (`kirim-web`, public client, keycloak-js); demo scripts use the password grant (`kirim-cli`), acceptable only for scripts on a development realm.
- **Tests** need no Keycloak: `spring-security-test` puts a ready-made JWT on each MockMvc request (`support/Auth.kt`).

## Consequences
- Accounts created before this change have no owner: only OPS can read them, nobody can send from them through the API. A real migration would backfill owners.
- Keycloak in dev mode keeps its data in memory; the realm is re-imported on a fresh start. Production: Keycloak with its own database, HTTPS, and no password grant.
- Not done yet: audience (`aud`) checks per service, service-to-service tokens (not needed: services talk through Kafka), step-up authentication for large transfers.
