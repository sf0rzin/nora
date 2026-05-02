# Contratos de API — NORA

Esta pasta contém os contratos canônicos entre **Web ↔ Backend ↔ Worker NLP**.

## Princípios

1. **Contrato vem antes do código.** Se vai mudar payload, atualize aqui primeiro.
2. **Camel case** em toda API HTTP pública.
3. **IDs são UUID v4** em string.
4. **Datas são ISO-8601 UTC** (`2026-05-02T14:30:00Z`).
5. Toda resposta de erro segue o formato padrão de `error.example.json`.
6. Toda entidade tenant-bound carrega `tenantId` (mesmo que implícito pelo JWT no servidor — exemplos mostram explicitamente para clareza).

## Índice

### Web ↔ Backend (HTTP REST)

| Arquivo | Endpoint | Descrição |
|---|---|---|
| `auth-login-request.json` | `POST /auth/login` | Login e-mail/senha |
| `auth-login-response.json` | — | Resposta com JWT |
| `meeting-upload-request.json` | `POST /meetings` (multipart) | Metadata enviado junto do arquivo |
| `meeting-upload-response.json` | — | Reunião criada com `processingStatus` |
| `meeting-detail-response.json` | `GET /meetings/{id}` | Reunião + análise + tarefas |
| `meetings-list-response.json` | `GET /meetings` | Lista paginada com escopo do usuário |
| `tenant-context-request.json` | `PUT /tenants/{id}/context` | Contexto comercial/produto do tenant |
| `tenant-context-response.json` | `GET /tenants/{id}/context` | Contexto atual |
| `error.example.json` | — | Formato padrão de erro |

### Backend ↔ Worker (HTTP interno)

| Arquivo | Endpoint | Descrição |
|---|---|---|
| `worker-analyze-request.json` | `POST /analyze` | Texto + contexto do tenant |
| `worker-analyze-response.json` | — | Análise estruturada validada |

## Convenções de status de processamento

`processingStatus` ∈ `PENDING | PROCESSING | COMPLETED | FAILED`.

## Convenções de paginação

Query: `?page=0&size=20&sort=startedAt,desc`
Resposta:

```json
{
  "items": [...],
  "page": 0,
  "size": 20,
  "totalItems": 137,
  "totalPages": 7
}
```
