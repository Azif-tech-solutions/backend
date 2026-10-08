# ZifIQ Backend — Frontend Integration Guide

Everything needed to start wiring the frontend against the live dev API.

## Base URL

```
http://16.192.105.205:8080
```

Dev EC2 box. Not HTTPS yet, no custom domain — plain HTTP on port 8080.

## API Docs (Swagger / OpenAPI)

- **Swagger UI** (interactive, browsable): `http://16.192.105.205:8080/swagger-ui.html`
- **Raw OpenAPI JSON** (for codegen, Postman import, etc.): `http://16.192.105.205:8080/v3/api-docs`

## Postman collection

The fastest way to see every endpoint with real request/response examples is the repo's own Postman collection, already verified against this exact deployment:

- `backend/postman/ZifIQ-API.postman_collection.json`
- `backend/postman/ZifIQ-Local.postman_environment.json` — update the `baseUrl` variable in this environment from `http://localhost:8080` to `http://16.192.105.205:8080` before running against the dev server.

## Test tenant (already registered on the dev server)

This tenant already exists on the dev box — log in with it directly, don't re-register (re-running `register-tenant` with this exact email will fail with `409 USER_EXISTS`).

```json
{
  "companyName": "Apex Industries",
  "adminEmail": "admin-962ed412-ca84-43cc-809b-4e6bec156dbe@apex.com",
  "password": "password123"
}
```

Admin display name: `Alice Admin`

## Getting an access token

```
POST /api/v1/auth/login
Content-Type: application/json

{
  "email": "admin-962ed412-ca84-43cc-809b-4e6bec156dbe@apex.com",
  "password": "password123"
}
```

Response:

```json
{
  "data": {
    "accessToken": "...",
    "refreshToken": "...",
    "tokenType": "Bearer",
    "expiresInSeconds": 3600,
    "activeTenantId": "...",
    "userId": "..."
  }
}
```

Every other endpoint needs the access token on every request:

```
Authorization: Bearer <accessToken>
```

Access tokens expire after 1 hour (3600s). When one expires, get a new pair via:

```
POST /api/v1/auth/refresh
Content-Type: application/json

{ "refreshToken": "..." }
```

Refresh tokens are single-use — the old one is revoked the moment this succeeds, so always store the new `refreshToken` the response gives back.

## Response shape (every endpoint, no exceptions)

**Success** — the payload is always under `data`:

```json
{ "data": { "...": "..." } }
```

For list endpoints, `data` is an array directly (`{ "data": [...] }`), not a nested `items`/`results` field.

**Error** — same shape every time, never a bare error string or a different structure per endpoint:

```json
{
  "error": {
    "code": "SOME_ERROR_CODE",
    "message": "Human-readable description",
    "details": {}
  }
}
```

`code` is a fixed enum value (e.g. `INVALID_CREDENTIALS`, `VALIDATION_FAILED`, `STOCK_ITEM_NOT_FOUND`) — safe to switch/match on in frontend error handling, it will never change wording under you. `details` is sometimes empty, sometimes carries extra structured context (e.g. which fields failed validation).

## Notes

- CORS: ask backend to confirm the frontend's dev origin is allowed before you start making cross-origin requests from a local dev server — not yet confirmed configured for arbitrary origins.
- This is a **dev** server (small EC2 instance, no HTTPS, restarts/rebuilds whenever `main` gets pushed) — don't treat any data stored here as durable long-term.
