# ADR 0031 — Integrações OAuth (Google) e armazenamento de tokens

- **Status:** aceito
- **Data:** 2026-06-11
- **Decisores:** Arquiteto NORA (run do pitch) + Stratfy (PO, via GOAL.md)
- **Relacionados:** ADR 0030 (workflow engine — as ações consomem as conexões), ADR 0002/0028
  (tenant isolation + RLS), ADR 0020 (precedente de rotação de tokens)

## Contexto

As ações estrela do NORA Flows (enviar e-mail via Gmail do usuário, criar evento no Google
Calendar, postar no Slack) exigem OAuth REAL com contas externas. Precisamos de: fluxo
authorization code completo, armazenamento seguro de tokens por tenant, refresh automático em
runtime (as ações rodam em listener assíncrono, sem usuário presente) e um hub de status
("Conectado"/"Conectar") na página de integrações.

Particularidades: o callback OAuth chega por redirect do navegador no domínio da API (sem garantia
de cookie de sessão); o Core é individual (1 usuário root por tenant); as ações executam fora de
request (thread do engine), então o token precisa ser resolvível só com o tenant_id do evento.

## Decisão

1. **Sem SDK do Google**: o fluxo são 2 POSTs (token endpoint) e 1 GET (userinfo) + 2 chamadas de
   API (Gmail send, Calendar events) — `WebClient` direto em adapters infrastructure
   (`GoogleOAuthHttpClient`, `GoogleWorkspaceClient`), atrás das portas `GoogleOAuthClient` (fluxo
   OAuth) e ações `ActionExecutor` (`gmail_send_email`, `calendar_create_event`). Menos
   dependências, payloads visíveis, stub trivial nos testes.
2. **State auto-contido assinado (HMAC-SHA256)** em vez de state persistido: carrega
   tenantId+userId+provider+exp(10min)+nonce, assinado com `NORA_INTEGRATIONS_STATE_SECRET`
   (`OAuthStateCodec`). O callback é rota PÚBLICA (`/integrations/*/oauth/callback`) — o state É a
   credencial: forge quebra na assinatura, replay tardio na expiração. Callback sempre REDIRECIONA
   pro front (`/integracoes?connected=…` ou `?error=…`), nunca responde JSON ao navegador.
3. **Conexão tenant-level** (`integration_connections`, V024): UNIQUE (tenant_id, provider) — o
   Core é individual; `user_id` registra quem conectou (auditoria). RLS enforced padrão V022/V023.
   Reconectar = upsert (ON CONFLICT) trocando tokens/conta.
4. **Tokens cifrados em repouso**: AES-256-GCM com IV aleatório por valor (`TokenCipher`), chave
   de 32 bytes em `NORA_INTEGRATIONS_ENC_KEY`; formato `enc:v1:iv:ciphertext`. SEM chave (dev
   local), grava `plain:…` com WARN no boot — degradação honesta e visível, nunca silenciosa. O
   adapter cifra/decifra; porta e domínio falam token em claro.
5. **Refresh em runtime**: `IntegrationService.validGoogleAccessToken(tenantId)` renova quando o
   access token está a <60s do vencimento, persiste a rotation (mantém o refresh token atual quando
   o Google não envia um novo — comportamento padrão do Google) e devolve token pronto pra ação.
   Token expirado SEM refresh token → erro claro pedindo reconexão (vai pro log da execução).
6. **Escopos mínimos**: `openid email` (identificar a conta no hub) + `gmail.send` (só envio, sem
   leitura de caixa) + `calendar.events` (criar eventos, sem acesso total ao calendário).
   `access_type=offline&prompt=consent` para garantir refresh token na primeira conexão.

## Alternativas rejeitadas

- **SDK oficial google-api-client**: ~10 MB de dependências transitivas para 4 chamadas HTTP;
  esconde o fluxo que queremos auditável.
- **State persistido em tabela**: exige tabela + limpeza; o state assinado é stateless e cobre os
  mesmos ataques no nosso fluxo (não usamos PKCE porque o client é confidencial — secret no
  servidor).
- **Token por usuário (não por tenant)**: correto num multi-seat, mas o Core é individual; a
  decisão Stratfy "Core sem IAM" torna a conexão tenant-level mais simples e suficiente. Trigger
  de upgrade: tenants multi-usuário no Enterprise.
- **Key Vault para tokens**: latência+custo por execução de workflow; AES-GCM com chave em env
  (que JÁ vem do GitHub Secrets/KV no deploy) dá cifra em repouso com acesso O(1).

## Consequências

- Slack seguirá o mesmo molde (provider novo no enum + client próprio + ação) — o hub
  (`GET /integrations`) já lista todos os providers com configured/connected.
- Revogação no Google (usuário remove o app) aparece como falha clara no log da execução
  (`ProviderError 400 invalid_grant`) — o hub continua mostrando "Conectado" até reconexão ou
  disconnect manual; aceitável no MVP.
- Prova: `IntegrationFlowIntegrationTest` (start → callback assinado → status conectado → ação
  gmail num workflow real → disconnect → ação falha com mensagem clara; state forjado rejeitado;
  isolamento cross-tenant), `IntegrationServiceTest` (refresh/rotation), `OAuthStateCodecTest`,
  `TokenCipherTest`.
- Handoff humano necessário para ativar de verdade: projeto no Google Cloud Console, consent
  screen, Client ID/Secret e redirect URIs (dev + api.nora.systems) — ver `.env.example`.
