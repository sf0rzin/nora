# Error Codes — Convention

The NORA backend returns errors in the format:

```json
{
  "code": "STRING_SNAKE_UPPER",
  "message": "Human-readable",
  "traceId": "uuid",
  "timestamp": "ISO-8601",
  "details": [ { "field": "x", "message": "y" } ]
}
```

`code` is the source of truth for the UI to make decisions (render a specific message, trigger a refresh flow, etc.). `message` is the fallback if the client does not know the code.

## HTTP Status × code (current)

### 400 BAD_REQUEST
- `VALIDATION_FAILED` — invalid payload (Bean Validation, type conversion)
- `MALFORMED_REQUEST` — invalid JSON body
- `TOKEN_INVALID` — email-verify/password-reset one-time token invalid/used/expired

### 401 UNAUTHORIZED
- `UNAUTHENTICATED` — no credential (the client must log in)
- `INVALID_CREDENTIALS` — wrong email/password
- `EMAIL_NOT_VERIFIED` — login blocked until verification
- `REFRESH_TOKEN_INVALID` — refresh expired/revoked → the client does a local logout + redirect to login

### 403 FORBIDDEN
- `FORBIDDEN` — Spring Security access denied
- `USER_DISABLED` — deactivated account
- `IAM_FORBIDDEN` — an IAM policy denies the operation

### 404 NOT_FOUND
- `NOT_FOUND` — non-existent endpoint
- `MEETING_NOT_FOUND`
- `ANALYSIS_MEETING_NOT_FOUND`
- `TENANT_NOT_FOUND`
- `TENANT_CONTEXT_NOT_FOUND`
- `IAM_GROUP_NOT_FOUND`
- `IAM_POLICY_NOT_FOUND`
- `INVITE_NOT_FOUND`
- `TASK_NOT_FOUND`

### 405 METHOD_NOT_ALLOWED
- `METHOD_NOT_ALLOWED`

### 409 CONFLICT
- `EMAIL_ALREADY_TAKEN`
- `IAM_NAME_TAKEN`
- `INVITE_ALREADY_ACCEPTED`
- `INVITE_DUPLICATE_PENDING`
- `CONFLICT` (generic — `DataIntegrityViolationException`)

### 410 GONE
- `INVITE_EXPIRED`

### 413 PAYLOAD_TOO_LARGE
- `PAYLOAD_TOO_LARGE` — upload exceeds 10MB
- `TRANSCRIPT_TOO_LARGE` — text exceeds the per-meeting limit

### 415 UNSUPPORTED_MEDIA_TYPE
- `UNSUPPORTED_MEDIA_TYPE`

### 422 UNPROCESSABLE_ENTITY
- `EMAIL_DOMAIN_NOT_ALLOWED` — invite refused by the allowed_email_domain
- `TENANT_DOMAIN_INVALID`

### 429 TOO_MANY_REQUESTS
- `RATE_LIMIT_EXCEEDED` — Speech token broker
- `RATE_LIMITED` — auth endpoints (login/signup/reset)

### 500 INTERNAL_ERROR
- `INTERNAL_ERROR` — fallback (any unhandled Exception)

### 502 BAD_GATEWAY
- `ANALYSIS_WORKER_UNAVAILABLE` — NLP worker down/timeout
- `ANALYSIS_INVALID_RESPONSE` — the worker returned invalid JSON
- `BROKER_ERROR` — Azure Speech STS returned an error

## Conventions
- `code` is UPPER_SNAKE_CASE, maximum 32 chars
- Granularity: specific codes beat generic ones (`MEETING_NOT_FOUND` instead of `NOT_FOUND` when applicable)
- Breaking the `code` contract requires a documented PR (clients may switch on it)
- `details[].field` uses the Bean Validation path (e.g. `email`, `participants[0].email`)
