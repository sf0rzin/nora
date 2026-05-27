# Azure provisioning — features atrás de flag (ADR 0022)

> Guia operacional do que a **Stratfy (PO)** precisa provisionar/configurar na Azure
> (conta **Azure for Students**) pra ligar as features que estão sendo implementadas
> atrás de flag. Cada feature funciona desligada por default; ao configurar o que está
> aqui + setar a flag, ela acende. Nada aqui é obrigatório pro fluxo atual rodar.
>
> Onde setar variáveis: nos **Container Apps** (`nora-api-dev`, `nora-web-dev`) como env
> vars, idealmente via **Key Vault references** pros segredos (mesmo padrão de
> `JWT_SECRET`/`OPENAI_API_KEY`). Ver `docs/operations/azure-deploy.md`.

---

## 1. SSO Microsoft (Entra ID, OIDC) — US05

**Custo:** zero (Entra ID free tier cobre app registration + OIDC).

**Passos:**
1. Portal Azure → **Microsoft Entra ID** → **App registrations** → **New registration**.
   - Name: `NORA SSO (dev)`.
   - Supported account types: *Accounts in any organizational directory* (multi-tenant) — ou single-tenant se a demo for só pro tenant da empresa.
   - Redirect URI (Web): `https://<api-host>/auth/sso/microsoft/callback` (dev:
     `https://nora-api-dev.<...>.azurecontainerapps.io/auth/sso/microsoft/callback`;
     local: `http://localhost:8080/auth/sso/microsoft/callback`).
2. Em **Certificates & secrets** → **New client secret** → copie o **Value**.
3. Anote **Application (client) ID** e **Directory (tenant) ID**.
4. Setar no `nora-api-dev` (segredo via Key Vault):

   | Var | Valor |
   |---|---|
   | `NORA_SSO_MICROSOFT_ENABLED` | `true` |
   | `NORA_SSO_MICROSOFT_TENANT_ID` | Directory (tenant) ID (ou `common` p/ multi-tenant) |
   | `NORA_SSO_MICROSOFT_CLIENT_ID` | Application (client) ID |
   | `NORA_SSO_MICROSOFT_CLIENT_SECRET` | client secret (Key Vault) |
   | `NORA_SSO_MICROSOFT_REDIRECT_URI` | a redirect URI acima |

5. Setar no `nora-web-dev`: `NEXT_PUBLIC_SSO_MICROSOFT_ENABLED=true` (faz o botão
   "Continuar com Microsoft" aparecer no login/signup).

**Comportamento:** com a flag off (default), o backend não expõe o fluxo e o botão não
aparece. Com on, login com Microsoft faz JIT provisioning do usuário no tenant.

---

## 2. Busca semântica (pgvector + embeddings) — US15

**Custo:** zero adicional (usa o Postgres Flexible existente + a OpenAI key já
configurada). **NÃO** usa Azure AI Search (que custaria ~R$400/mês) — decisão do ADR 0022.

**Passos:**
1. Habilitar a extensão `vector` no Postgres Flexible Server:
   - Portal → `nora-pg-dev-*` → **Server parameters** → `azure.extensions` → adicionar
     **`VECTOR`** à lista de allowlist → Save (reinicia rápido).
   - A migration Flyway roda `CREATE EXTENSION IF NOT EXISTS vector;` (só funciona após o
     allowlist acima — senão a migration falha com "extension not allow-listed").
2. Embeddings: reusa a `OPENAI_API_KEY` já provisionada (modelo
   `text-embedding-3-small`, barato). Sem nova credencial.
3. Ligar a flag no `nora-api-dev`: `NORA_SEARCH_ENABLED=true` (default `false`).

**Comportamento:** off = busca por keyword (atual). On = re-rank/expansão semântica via
embeddings em `pgvector`.

---

## 3. Upload de áudio (Azure Speech batch) — US08

**Custo:** dentro do free/standard tier do recurso **Azure Speech** já provisionado
(PR #71). Sem recurso novo.

**Passos:**
1. Reusa o recurso Speech existente (`AZURE_SPEECH_KEY` + região já no Key Vault, usados
   hoje pelo Speech Token Broker).
2. A transcrição batch grava/lê o áudio temporário no **Storage Account** já existente
   (`norastdevwgl3a3mz`) — criar um container `audio-uploads` (TTL curto / lifecycle
   rule de expiração, alinhado à retenção declarada). Sem custo relevante em dev.
3. Ligar a flag no `nora-api-dev`: `NORA_AUDIO_UPLOAD_ENABLED=true` (default `false`).

**Comportamento:** off = só `.txt/.vtt/.srt` (atual). On = aceita `.mp3/.mp4/.wav`,
transcreve via Speech batch e segue o pipeline NLP normal.

---

## Resumo das flags

| Feature | Backend flag (default off) | Web flag | Provisionamento Azure |
|---|---|---|---|
| SSO Microsoft | `NORA_SSO_MICROSOFT_ENABLED` | `NEXT_PUBLIC_SSO_MICROSOFT_ENABLED` | App registration (Entra ID, grátis) |
| Busca semântica | `NORA_SEARCH_ENABLED` | — | `azure.extensions += VECTOR` no Postgres |
| Upload de áudio | `NORA_AUDIO_UPLOAD_ENABLED` | — | container `audio-uploads` no Storage |

> Implementação rastreada na branch `claude/dev-solidify` (ADR 0022). Quando uma feature
> estiver no código mas a flag off, ela é inerte — seguro mergear sem provisionar.
