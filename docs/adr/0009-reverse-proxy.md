# ADR 0009: Nginx as the single entry point

**Status:** accepted

## Context
The web app, two services and Keycloak each had their own port. Clients had to know all of them, CORS was needed between them, and internal endpoints (actuator metrics and health) were reachable by anyone who could reach a service.

## Decision
One **Nginx** container (`deploy/nginx`) on `http://localhost` (port `PROXY_PORT`, default 80):

| Path | Goes to |
| --- | --- |
| `/` | the web app, built into the image (single-page app: unknown paths serve `index.html`) |
| `/api/`, `/swagger-ui`, `/v3/api-docs` | transfer API |
| `/risk/` | risk service, prefix stripped, `X-Forwarded-Prefix: /risk` |
| `/auth/` | Keycloak |
| `/actuator`, `/risk/actuator` | **404**: health and metrics are for the platform, not the internet |

- Everything is **same-origin** for the browser, so the web app needs no CORS and Keycloak's login page lives under the same host.
- **Forwarded headers** (`X-Forwarded-For/Proto/Host/Port/Prefix`) so Spring (`server.forward-headers-strategy: framework`) and Keycloak (`KC_PROXY_HEADERS=xforwarded`) build correct links and see the client's IP.
- **`X-Request-Id`** generated per request, passed to the services and returned to the client, to find one request across logs.
- **Edge rate limits per client IP:** 20 requests/s (burst 40) for the APIs, 5/s (burst 10) for Keycloak's token endpoint (password guessing). They complement, not replace, the per-account limit in the API (ADR 0004).
- Security headers (`X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy`), `server_tokens off`, 1 MB body limit, 30 s timeouts.
- Upstreams are resolved per request through Docker's DNS, so the proxy starts and keeps running when one service is stopped (e.g. while it is debugged in IntelliJ); that route answers 502.
- The services' own ports stay published in docker-compose **for local debugging only**.

## Consequences
- In Kubernetes the same job is done by an **Ingress** controller (often NGINX itself) with TLS; these manifests do not include one yet.
- No TLS locally. In any shared environment the proxy terminates HTTPS and Keycloak's hostname becomes `https://...`.
