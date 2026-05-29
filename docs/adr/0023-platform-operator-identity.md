# 0023 — Identidade de operador (platform admin), separada do IAM por-tenant

- Status: aceito
- Data: 2026-05-28
- Decisores: Co-arquitetos (Opus) + Stratfy (PO/dono)
- Relacionado: ADR 0007 (IAM AWS-style por-tenant), ADR 0022 (banco de plataforma)

## Contexto

O control plane (ADR 0022) é operado **pelos donos da plataforma**, não por clientes. O IAM existente
(ADR 0007: Root + Users + Groups + Policies) é **por-tenant** — o JWT do NORA é tenant-scoped
(`tenantId` no claim). Operadores de plataforma **não pertencem a nenhum tenant**; encaixá-los no IAM
por-tenant seria errado conceitualmente e perigoso (mistura de planos).

Não há precedente no repo de Entra Easy Auth nem `ipSecurityRestrictions` — toda auth atual é JWT
próprio (cookies httpOnly).

Decisão de produto já fechada com o dono: console do operador é um **app Next separado**
(`apps/admin`), não a mesma imagem da API (a UI do NORA é Next/React; servir admin em Thymeleaf seria
inconsistente no pitch).

## Decisão

Identidade de operador **completamente separada** do IAM por-tenant, com **isolamento na borda** e
**autenticação por token entre serviços**:

1. **Borda (`nora-admin` Container App):** defesa em profundidade com **Entra Easy Auth** (grupo
   "NORA Platform Admins") **E** `ipSecurityRestrictions` (allowlist de IP) no ingress. Os dois.
   Apenas membros do grupo, vindo de IPs permitidos, alcançam o app.
2. **`nora-admin` (Next) é o único que lê a identidade do operador** (`X-MS-CLIENT-PRINCIPAL-*`
   injetado pelo Easy Auth). Chama a API Spring **server-side** com:
   - `X-Internal-Token: <admin token>` (autoriza `/admin/platform/**`);
   - `X-Operator-Email: <email do operador>` (auditoria — quem mudou o quê).
3. **A API Spring não lê header de Easy Auth.** Protege `/internal/platform/**` e `/admin/platform/**`
   por **token interno** (`InternalTokenAuthFilter`, comparação constant-time), em chains de segurança
   próprias (`securityMatcher` + `@Order`), com a chain JWT por-tenant intacta.
4. **Dois tokens, least-privilege:** `NORA_PLATFORM_INTERNAL_TOKEN` (worker/BFF → `/internal/*`) e
   `NORA_PLATFORM_ADMIN_TOKEN` (nora-admin → `/admin/*`). Distintos por padrão; vazamento do token do
   worker não dá acesso a mutações de admin. Secrets no Key Vault.
5. **Grupo Entra + App Registration são passo MANUAL** (Bicep não cria grupo/app registration de
   forma confiável). Documentado no runbook. Recomendação: App Registration com "assignment required"
   + atribuir **apenas** o grupo → só membros recebem token do Easy Auth.

## Consequências

**Positivas:**
- Planos separados: operador-dono nunca passa pelo IAM por-tenant; nenhum risco de cruzamento.
- Defense in depth: rede (IP) + identidade (Entra) na borda; token entre serviços.
- Spring fica simples: sem parsing de Easy Auth, sem 2ª instância só pra esconder endpoint. Auth de
  `/admin/*` é o admin token; o isolamento real está na borda do `nora-admin`.
- Browser nunca fala direto com o Spring (sem CORS extra, sem dupla Easy Auth).

**Negativas / trade-offs:**
- `/admin/platform/**` no `nora-api` público é alcançável por quem tiver o admin token (o path não é
  escondido por rede). Aceito: o token é o gate, e o isolamento de rede/identidade está na borda do
  `nora-admin`. Mitigação futura: restringir `/admin/*` à origem interna.
- Passo manual no Entra (grupo + app registration) — fora do IaC. Mitigado por runbook.
- `X-Operator-Email` é confiado quando o admin token é válido (não é verificado contra o Entra no
  Spring). Aceito: só o `nora-admin` (atrás do Easy Auth, que strippa headers do cliente) tem o admin
  token e seta esse header.

## Alternativas Consideradas

1. **`nora-admin` = mesma imagem da API (endpoints Thymeleaf/JSON gated por env)** — rejeitado: deixa
   a UI órfã (NORA é Next), inconsistente no pitch, e exigiria 2ª instância Spring só pra esconder
   endpoint.
2. **Easy Auth header trust no Spring** — rejeitado: acopla o Spring ao Easy Auth, abre risco de
   spoofing de header em qualquer ingress sem Easy Auth na frente, e duplica a borda.
3. **Operador no IAM por-tenant (tenant especial "platform")** — rejeitado: mistura planos, polui o
   modelo tenant-scoped, e expõe o control plane ao mesmo blast radius do dado do cliente.

## Histórico

| Data | Decisor | Mudança |
|---|---|---|
| 2026-05-28 | Co-arquitetos + Stratfy | Criação. Refino do dono: `nora-admin` é app Next separado; Spring por token interno (não Easy Auth). Exceção consciente ao ADR 0014 autorizada. |
