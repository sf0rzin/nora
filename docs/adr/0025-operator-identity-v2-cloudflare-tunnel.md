# 0025 — Identidade de operador v2: Cloudflare Tunnel + Access (substitui Easy Auth do ADR 0023)

- Status: aceito
- Data: 2026-06-01
- Decisores: Arquiteto Control Plane (Opus) + Stratfy (PO/dono)
- Relacionado: ADR 0023 (identidade de operador v1 — parcialmente substituído), ADR 0022 (banco de plataforma), `docs/operations/cloudflare-access.md`, `docs/operations/control-plane-runbook.md`

## Contexto

O ADR 0023 definiu a borda do `nora-admin` como **Entra Easy Auth + `ipSecurityRestrictions`** ("os dois"). A criação do grupo Entra + App Registration é passo manual no tenant.

No go-live (2026-06-01) isso bateu num bloqueio duro: o tenant **fiap.com.br** (gerenciado pela instituição) **nega** `az ad group create` / `az ad app create` com `Authorization_RequestDenied` — a conta-dona não tem o role de diretório (Application Developer / Groups Administrator). Sem App Registration, não há Easy Auth.

Em paralelo, a lane Cloudflare já entregou (PRs #177/#178) **Cloudflare Access** protegendo `admin.nora.systems` (allowlist + OTP, log central, plano Free). Mas, do jeito que estava, a origem (`nora-admin` com `ingress: external`) ficava **alcançável diretamente pelo FQDN cru do Azure**, contornando o Access — e com Easy Auth fora + IP allowlist vazio, sem nenhuma autenticação.

## Decisão

Substituir o Easy Auth (Entra) por **Cloudflare Tunnel + Cloudflare Access**, removendo a origem pública:

1. **Sem origem pública.** `nora-admin` passa a `ingress: internal` (sem FQDN público). O acesso externo é só via **Cloudflare Tunnel**: um sidecar **cloudflared** no mesmo Container App conecta-se (outbound) ao Cloudflare e encaminha pro Next em `localhost:3002`. O DNS `admin.nora.systems` vira CNAME (proxied) pro `<tunnel-id>.cfargotunnel.com`. Não existe FQDN do Azure pra contornar.
2. **Identidade na borda de rede:** Cloudflare Access (allowlist + OTP/SSO) gateia `admin.nora.systems` antes da requisição entrar no túnel.
3. **Identidade na borda do app (Tier 2, defense-in-depth):** o `nora-admin` valida o header `Cf-Access-Jwt-Assertion` (JWKS do team domain + `aud` da Access App) num **server component** (Node runtime, `lib/access.ts`). Sem asserção válida → 403. Degrada pra edge-only se `CF_ACCESS_TEAM_DOMAIN`/`CF_ACCESS_AUD` não estiverem setados.
4. **Token entre serviços inalterado** (herdado do ADR 0023): `nora-admin` → Spring `/admin/platform/**` com o admin token; `X-Operator-Email` (agora do header `Cf-Access-Authenticated-User-Email`) pra auditoria.
5. **Automação + lanes:** o túnel é provisionado pelo workflow `cloudflare-tunnel.yml` (idempotente, API Cloudflare, **arquivo novo** — não toca o `cloudflare-setup.yml` da lane irmã). O `cloudflare-setup.yml` continua dono do Access App/Policy/IdP e **deve rodar sem `admin_hostname`** (senão sobrescreve o CNAME do túnel). A lane do túnel foi reassinada ao arquiteto Control Plane pela PO.

## Consequências

**Positivas:**
- Zero superfície pública no admin (sem FQDN do Azure exposto) — mais forte que o IP allowlist do v1.
- Sem dependência de tenant Entra (contorna o bloqueio FIAP). Operadores externos (gmail/proton) entram via Access (OTP/SSO) sem precisar de guest B2B.
- Defesa em profundidade real: rede (Access) + transporte (Tunnel, origem trancada) + app (validação de JWT) + serviço (admin token).
- Log central de acesso no painel Zero Trust. Bom argumento de pitch (arquitetura Zero Trust de verdade).

**Negativas / trade-offs:**
- `nora-admin` não escala mais a zero: o conector cloudflared precisa de ≥1 réplica sempre de pé (~US$ 3–15/mês de compute; ordem de grandeza ≤ o 2º Postgres do ADR 0022). Aceito.
- Mais peças: sidecar + túnel + workflow + secret (`CLOUDFLARE_TUNNEL_TOKEN`). Mitigado por idempotência + runbook.
- A validação de JWT roda em server component (não em middleware) porque o middleware edge inlinaria `CF_ACCESS_*` em build-time. O gate roda por render de página; `/healthz` (route handler) fica naturalmente fora, mantendo o probe do Container App.
- Dependência operacional do Cloudflare na borda. Aceito (já era a borda escolhida em 0023/cloudflare-access).

## Alternativas Consideradas

1. **Pedir a um admin do tenant FIAP criar o App Registration** — rejeitado: dependência de terceiro, atrito, frágil pra demo.
2. **Tenant Entra separado (do dono) só pro Easy Auth** — rejeitado: tenant descartável + B2B guests pros operadores externos; pior UX que OTP/SSO do Access.
3. **Cloudflare-only sem remover a origem, trancando-a nas faixas de IP da Cloudflare** (Tier 1 intermediário) — trancaria a origem por IP em vez de removê-la. Rejeitado em favor do Tunnel, que elimina a origem pública inteira (mais forte e melhor narrativa de pitch).
4. **Manter `ingress: external` + cloudflared** — rejeitado: deixaria o FQDN cru acessível; o ponto é não ter origem pública.

## Histórico

| Data | Decisor | Mudança |
|---|---|---|
| 2026-06-01 | Arquiteto Control Plane + Stratfy | Criação. Substitui o Easy Auth do ADR 0023 por Cloudflare Tunnel + Access após bloqueio do tenant FIAP (`Authorization_RequestDenied`). PO reassinou a lane do túnel ao arquiteto Control Plane. |
