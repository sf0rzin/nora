# Error Codes — Convenção

O backend NORA retorna erros no formato:

```json
{
  "code": "STRING_SNAKE_UPPER",
  "message": "Human-readable",
  "traceId": "uuid",
  "timestamp": "ISO-8601",
  "details": [ { "field": "x", "message": "y" } ]
}
```

`code` é a fonte da verdade pra UI tomar decisões (renderizar mensagem específica, disparar fluxo de refresh, etc.). `message` é fallback se o cliente não conhecer o code.

## HTTP Status × code (atual)

### 400 BAD_REQUEST
- `VALIDATION_FAILED` — payload inválido (Bean Validation, conversão de tipo)
- `MALFORMED_REQUEST` — body JSON inválido
- `TOKEN_INVALID` — one-time token de email-verify/password-reset inválido/usado/expirado

### 401 UNAUTHORIZED
- `UNAUTHENTICATED` — sem credencial (cliente deve fazer login)
- `INVALID_CREDENTIALS` — email/senha errados
- `EMAIL_NOT_VERIFIED` — login bloqueado até verificação
- `REFRESH_TOKEN_INVALID` — refresh expirado/revogado → cliente faz logout local + redirect login

### 403 FORBIDDEN
- `FORBIDDEN` — Spring Security access denied
- `USER_DISABLED` — conta desativada
- `IAM_FORBIDDEN` — policy IAM nega operação

### 404 NOT_FOUND
- `NOT_FOUND` — endpoint inexistente
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
- `CONFLICT` (genérico — `DataIntegrityViolationException`)

### 410 GONE
- `INVITE_EXPIRED`

### 413 PAYLOAD_TOO_LARGE
- `PAYLOAD_TOO_LARGE` — upload excede 10MB
- `TRANSCRIPT_TOO_LARGE` — texto excede limite por meeting

### 415 UNSUPPORTED_MEDIA_TYPE
- `UNSUPPORTED_MEDIA_TYPE`

### 422 UNPROCESSABLE_ENTITY
- `EMAIL_DOMAIN_NOT_ALLOWED` — invite recusado pelo allowed_email_domain
- `TENANT_DOMAIN_INVALID`

### 429 TOO_MANY_REQUESTS
- `RATE_LIMIT_EXCEEDED` — Speech token broker
- `RATE_LIMITED` — auth endpoints (login/signup/reset)

### 500 INTERNAL_ERROR
- `INTERNAL_ERROR` — fallback (qualquer Exception não tratada)

### 502 BAD_GATEWAY
- `ANALYSIS_WORKER_UNAVAILABLE` — worker NLP down/timeout
- `ANALYSIS_INVALID_RESPONSE` — worker retornou JSON inválido
- `BROKER_ERROR` — Azure Speech STS retornou erro

## Convenções
- `code` é UPPER_SNAKE_CASE, máximo 32 chars
- Granularidade: códigos específicos vencem genéricos (`MEETING_NOT_FOUND` em vez de `NOT_FOUND` quando aplicável)
- Quebra de contrato em `code` exige PR documentado (clientes podem switch nele)
- `details[].field` usa caminho Bean Validation (ex.: `email`, `participants[0].email`)
