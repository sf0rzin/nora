---
title: "API Contracts — NORA"
owner: NORA Architect (Tech Lead)
status: approved
version: 1.0
last_reviewed: 2026-06-06
---

# API Contracts — NORA

This folder contains the canonical contracts between **Web ↔ Backend ↔ NLP Worker**.

## Principles

1. **The contract comes before the code.** If you are going to change a payload, update it here first.
2. **Camel case** across the whole public HTTP API.
3. **IDs are UUID v4** as strings.
4. **Dates are ISO-8601 UTC** (`2026-05-02T14:30:00Z`).
5. Every error response follows the standard format of `error.example.json`.
6. Every tenant-bound entity carries `tenantId` (even when implicit from the JWT on the server — the examples show it explicitly for clarity).

## Index

### Web ↔ Backend (HTTP REST)

| File | Endpoint | Description |
|---|---|---|
| `auth-login-request.json` | `POST /auth/login` | Email/password login |
| `auth-login-response.json` | — | Response with JWT |
| `meeting-upload-request.json` | `POST /meetings` (multipart) | Metadata sent alongside the file |
| `meeting-upload-response.json` | — | Meeting created with `processingStatus` |
| `meeting-detail-response.json` | `GET /meetings/{id}` | Meeting + analysis + tasks |
| `meetings-list-response.json` | `GET /meetings` | Paginated list scoped to the user |
| `tenant-context-request.json` | `PUT /tenants/{id}/context` | The tenant's commercial/product context |
| `tenant-context-response.json` | `GET /tenants/{id}/context` | Current context |
| `error.example.json` | — | Standard error format |

### Backend ↔ Worker (internal HTTP)

| File | Endpoint | Description |
|---|---|---|
| `worker-analyze-request.json` | `POST /analyze` | Text + tenant context |
| `worker-analyze-response.json` | — | Validated structured analysis |

## Processing status conventions

`processingStatus` ∈ `PENDING | PROCESSING | COMPLETED | FAILED`.

## Pagination conventions

Query: `?page=0&size=20&sort=startedAt,desc`
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
