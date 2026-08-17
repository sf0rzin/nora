# API Contracts — NORA

This folder contains the canonical contracts between **Web ↔ Backend ↔ NLP Worker**.

## What is where

| File | Role |
|---|---|
| [`openapi.yaml`](openapi.yaml) | **The contract.** Every HTTP operation the backend serves, described by hand. Start here. |
| `examples/` | Illustrative payloads, one file per shape. They are examples, not the contract — the schema in `openapi.yaml` wins on any disagreement. |
| `llm-schemas/` | JSON Schemas the NLP worker validates model output against. |

`openapi.yaml` is written and maintained by hand: `springdoc-openapi-starter-webmvc-ui` is on the
classpath and can be used to cross-check, but its dump is not the deliverable — the descriptions
are. To keep a hand-written file from drifting, `scripts/check-openapi-coverage.sh` runs in CI and
compares it to the controllers **in both directions**: a route added to a controller and not to the
spec fails the build, and so does an operation left in the spec after its handler was deleted.

## Principles

1. **The contract comes before the code.** If you are going to change a payload, update it here first.
2. **Camel case** across the whole public HTTP API.
3. **IDs are UUID v4** as strings.
4. **Dates are ISO-8601 UTC** (`2026-05-02T14:30:00Z`).
5. Every error response follows the standard format of `error.example.json`.
6. Every tenant-bound entity carries `tenantId` (even when implicit from the JWT on the server — the examples show it explicitly for clarity).

## Example payloads

### Web ↔ Backend (HTTP REST)

| File | Endpoint | Description |
|---|---|---|
| `auth-login-request.json` | `POST /auth/login` | Email/password login |
| `auth-login-response.json` | — | Login response body (the tokens also arrive as httpOnly cookies) |
| `meeting-upload-request.json` | `POST /meetings` (multipart) | The `metadata` part sent alongside the file |
| `meeting-upload-response.json` | — | Meeting created with `processingStatus` |
| `meeting-detail-response.json` | `GET /meetings/{id}` | Meeting + analysis + tasks |
| `meetings-list-response.json` | `GET /meetings` | Paginated list scoped to the user |
| `tenant-context-request.json` | `PUT /tenant/context` | The tenant's commercial/product context |
| `tenant-context-response.json` | `GET /tenant/context` | Current context |
| `tenant-domain-update-request.json` | `PUT /tenant/domain` | Allowed corporate email domain |
| `tenant-domain-update-response.json` | — | Domain after the update |
| `iam-invite-request.json` | `POST /iam/users/invite` | Invite a user by email |
| `iam-invite-response.json` | — | The created invitation (the token is never returned) |
| `iam-invite-list-response.json` | `GET /iam/invites` | Paginated invitation list |
| `iam-invite-accept-request.json` | `POST /iam/invites/{token}/accept` | Acceptance with password |
| `error.example.json` | — | Standard error format |

The context endpoints are **1-1 with the tenant in the JWT**: the path is `/tenant/context`, with no
tenant id in it. There is no route shaped `/tenants/{id}/...` anywhere in the API.

### Backend ↔ Worker (internal HTTP)

| File | Endpoint | Description |
|---|---|---|
| `worker-analyze-request.json` | `POST /analyze` | Text + tenant context |
| `worker-analyze-response.json` | — | Validated structured analysis |

The worker also serves `POST /split` (behind `POST /meetings/split-preview`) and
`POST /analyze-live` (behind `POST /meetings/live-analyze`), plus `/healthz` and `/readyz`. Those
have no example file here yet.

## Processing status conventions

`processingStatus` ∈ `PENDING | PROCESSING | COMPLETED | FAILED`.

## Pagination conventions

Paginated collections take `?page=0&size=20`. `page` is 0-based; `size` is clamped server-side to
`1..100`. There is **no `sort` parameter** — ordering is fixed per endpoint (`GET /meetings` sorts
by `createdAt` descending). Additional filters are per endpoint; `GET /meetings` accepts `search`,
`status`, `from` and `to`.

Response:

```json
{
  "items": [...],
  "page": 0,
  "size": 20,
  "totalItems": 137,
  "totalPages": 7
}
```

Not every collection is paginated: several endpoints (chat sessions, workflows, integrations,
tasks) return a plain array or a bare `items` wrapper. `openapi.yaml` is authoritative per
operation.
