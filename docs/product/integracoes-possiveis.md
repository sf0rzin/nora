# Integrações possíveis no NORA Flows — catálogo + tutoriais de credenciais

> **Para quem é este doc:** Stratfy (PO). Cada seção diz o que a integração habilita no
> Flows, se é grátis e multi-usuário de verdade, e o passo a passo pra obter as
> credenciais. **Fluxo de trabalho combinado:** você obtém a credencial, salva nas suas
> env vars de usuário do Windows com o nome indicado, e o arquiteto cuida do resto
> (GitHub Secrets, Bicep, backend, bloco no canvas).
>
> Critério de entrada no catálogo: **grátis** (sem cobrança por usuário/uso no nosso
> volume) e **multi-usuário** (cada usuário do NORA conecta a própria conta — não é uma
> conta nossa hardcoded).

## Estado atual (2026-06-12)

| Integração | Status |
|---|---|
| E-mail transacional (Resend) | ✅ em produção (ação `send_email`) |
| Google Gmail + Calendar (OAuth) | ✅ em produção (modo Testing — test users; reconectar a cada 7 dias) |
| Slack (OAuth) | 🟡 backend + Bicep prontos; **falta criar o app** (tutorial abaixo) |

## Ranking de recomendação (esforço × impacto na demo)

1. **Webhook genérico** — zero credencial, impacto enorme ("integra com qualquer coisa")
2. **Discord** — zero credencial global (webhook por canal), demo visual
3. **Slack** — backend já pronto, só falta o app (15 min)
4. **Telegram** — bot grátis em 2 min, notificação no celular ao vivo no palco
5. **GitHub** — OAuth App sem review, "action item vira issue"
6. **Notion** — OAuth grátis, "resumo vira página no workspace"
7. **Trello / Todoist / Linear** — OAuth grátis, "action item vira card/tarefa"
8. **Microsoft Outlook/Teams** — possível e grátis, mas o setup Azure AD é mais chato

---

## 1. Webhook genérico (HTTP POST) — ⭐ esforço zero de credencial

**O que habilita:** ação "Chamar webhook" — o NORA faz POST com o JSON do evento
(resumo, action items, riscos) pra qualquer URL que o usuário colar. É o que torna o
Flows "integrável com qualquer coisa" (n8n, Zapier, Make, sistemas internos).

**Custo / multi-usuário:** grátis por definição; cada usuário cola a própria URL no nó.

**Credencial necessária: NENHUMA.** Não tem env var — o campo URL fica no próprio nó do
canvas. É só pedir pro arquiteto implementar a ação.

---

## 2. Discord — webhook por canal (sem app, sem OAuth)

**O que habilita:** ação "Postar no Discord" — resumo/alertas da reunião num canal.

**Custo / multi-usuário:** grátis. Cada usuário gera o webhook do PRÓPRIO servidor/canal
e cola a URL no nó (mesmo modelo do webhook genérico, com payload formatado bonito —
embeds com cor NORA, campos de resumo/ações).

**Credencial necessária: NENHUMA global.** Tutorial que faremos aparecer no produto:

1. No Discord, abra o servidor → canal desejado → ⚙️ **Editar canal** → **Integrações**.
2. **Webhooks** → **Novo webhook** → dê um nome (ex.: "NORA") → **Copiar URL do webhook**.
3. Colar a URL no nó "Postar no Discord" do canvas. Pronto.

---

## 3. Slack — OAuth v2 (backend já pronto!)

**O que habilita:** ação `slack_post_message` (já implementada) — postar resumo/alerta
num canal escolhido. O hub de integrações já tem o conector esperando as credenciais.

**Custo / multi-usuário:** grátis (plano free do Slack basta). Com **distribuição
pública ativada** (grátis, sem review pra escopos de bot básicos), qualquer workspace
conecta via OAuth.

**Tutorial (≈15 min):**

1. Acesse <https://api.slack.com/apps> → **Create New App** → **From scratch**.
2. Nome: `NORA` · Workspace: o seu (é só o workspace "dono" do app).
3. Menu **OAuth & Permissions**:
   - Em **Redirect URLs**, adicione: `https://api.nora.systems/integrations/slack/oauth/callback`
   - Em **Scopes → Bot Token Scopes**, adicione: `chat:write` e `channels:read`.
4. Menu **Manage Distribution** → **Distribute App** → ative a distribuição pública
   (checklist: remover hard-coded secrets etc. — é só marcar, nosso app já cumpre).
5. Menu **Basic Information** → seção **App Credentials**: copie **Client ID** e
   **Client Secret**.
6. Salve nas env vars de usuário:
   - `SLACK_OAUTH_CLIENT_ID`
   - `SLACK_OAUTH_CLIENT_SECRET`

---

## 4. Telegram — bot (token único, sem OAuth)

**O que habilita:** ação "Enviar no Telegram" — notificação no privado ou em grupo
quando a análise termina / risco alto detectado. Demo forte: o celular apita no palco.

**Custo / multi-usuário:** grátis e sem review. Modelo: UM bot do NORA; cada usuário
conecta mandando `/start` pro bot (o NORA captura o `chat_id` via deep-link
`t.me/<bot>?start=<código>` — o backend faz o pareamento).

**Tutorial (≈2 min):**

1. No Telegram, fale com **@BotFather** → `/newbot`.
2. Nome de exibição: `NORA` · username: algo como `nora_flows_bot` (precisa terminar em `bot`).
3. O BotFather responde com o **token** (formato `123456:ABC-DEF...`).
4. Salve na env var de usuário: `NORA_TELEGRAM_BOT_TOKEN`

---

## 5. GitHub — OAuth App (action item vira issue)

**O que habilita:** ação "Criar issue no GitHub" — cada action item da reunião vira uma
issue no repositório escolhido. Ótimo pra narrativa "da reunião direto pro backlog".

**Custo / multi-usuário:** grátis, sem processo de review — qualquer usuário GitHub
autoriza o app na hora.

**Tutorial (≈5 min):**

1. GitHub → **Settings** (do seu perfil) → **Developer settings** → **OAuth Apps** →
   **New OAuth App**.
2. Application name: `NORA` · Homepage: `https://nora.systems`
   · Authorization callback URL: `https://api.nora.systems/integrations/github/oauth/callback`
3. **Register application** → copie o **Client ID** → **Generate a new client secret** → copie.
4. Salve nas env vars de usuário:
   - `GITHUB_OAUTH_CLIENT_ID`
   - `GITHUB_OAUTH_CLIENT_SECRET`

---

## 6. Notion — public integration OAuth (resumo vira página)

**O que habilita:** ação "Criar página no Notion" — o relatório da reunião (resumo,
decisões, action items) vira uma página no workspace do usuário.

**Custo / multi-usuário:** grátis. Uma "public integration" do Notion pode ser
autorizada por qualquer workspace via OAuth **sem precisar estar listada na galeria**
(listagem na galeria é que exige review — não precisamos dela).

**Tutorial (≈10 min):**

1. Acesse <https://www.notion.so/my-integrations> → **New integration**.
2. Tipo: **Public** · Nome: `NORA` · workspace dono: o seu.
3. Em **OAuth Domain & URIs**, redirect URI:
   `https://api.nora.systems/integrations/notion/oauth/callback`
   (vai pedir também site/política de privacidade — pode usar `https://nora.systems`).
4. Capabilities: **Insert content** e **Read content** bastam.
5. Copie **OAuth client ID** e **OAuth client secret**.
6. Salve nas env vars de usuário:
   - `NOTION_OAUTH_CLIENT_ID`
   - `NOTION_OAUTH_CLIENT_SECRET`

---

## 7. Trello — API key (action item vira card)

**O que habilita:** ação "Criar card no Trello" — action items viram cards numa lista.

**Custo / multi-usuário:** grátis. O Trello usa API key do app + token autorizado pelo
usuário (fluxo de autorização próprio da Atlassian, sem review).

**Tutorial (≈5 min):**

1. Logado no Trello, acesse <https://trello.com/power-ups/admin> → **New** (criar um
   Power-Up "NORA" — é o contêiner administrativo, não precisa publicar).
2. Na página do Power-Up → **API key** → **Generate a new API key**.
3. Em "Allowed origins", adicione `https://nora.systems`.
4. Copie a **API key** (e o **Secret**, se exibido).
5. Salve nas env vars de usuário:
   - `TRELLO_API_KEY`
   - `TRELLO_API_SECRET` (se houver)

---

## 8. Todoist — OAuth (action item vira tarefa)

**O que habilita:** ação "Criar tarefa no Todoist" — com data de vencimento vinda do
action item (`dueDate`).

**Custo / multi-usuário:** grátis, sem review.

**Tutorial (≈5 min):**

1. Acesse <https://developer.todoist.com/appconsole.html> → **Create a new app**.
2. Nome: `NORA` · OAuth redirect URL:
   `https://api.nora.systems/integrations/todoist/oauth/callback`
3. Copie **Client ID** e **Client Secret**.
4. Salve nas env vars de usuário:
   - `TODOIST_OAUTH_CLIENT_ID`
   - `TODOIST_OAUTH_CLIENT_SECRET`

---

## 9. Linear — OAuth (action item vira issue de produto)

**O que habilita:** ação "Criar issue no Linear" — pro público startup/produto.

**Custo / multi-usuário:** grátis, sem review.

**Tutorial (≈5 min):**

1. No Linear: **Settings** → **API** → **OAuth applications** → **New OAuth application**.
2. Nome: `NORA` · Callback URL: `https://api.nora.systems/integrations/linear/oauth/callback`
3. Copie **Client ID** e **Client Secret**.
4. Salve nas env vars de usuário:
   - `LINEAR_OAUTH_CLIENT_ID`
   - `LINEAR_OAUTH_CLIENT_SECRET`

---

## 10. Microsoft Outlook / Teams / Calendar (Graph) — possível, médio esforço

**O que habilita:** paridade com o Google pra usuários corporativos Microsoft: enviar
e-mail pelo Outlook, criar evento no Calendar, postar no Teams.

**Custo / multi-usuário:** grátis (App Registration no Entra ID multi-tenant). O usuário
final autoriza via OAuth normal. *Porém*: tenants corporativos costumam exigir
consentimento de admin, e a Microsoft recomenda "publisher verification" pra apps
multi-tenant — sem ela aparece aviso de "unverified" (similar ao Google Testing).

**Recomendação:** deixar pra depois do pitch — o Google já cobre a história de
e-mail/agenda na demo. Se quiser preparar:

1. <https://portal.azure.com> → **Microsoft Entra ID** → **App registrations** → **New registration**.
2. Supported account types: **Accounts in any organizational directory and personal
   Microsoft accounts** (multi-tenant + pessoal).
3. Redirect URI (Web): `https://api.nora.systems/integrations/microsoft/oauth/callback`
4. **Certificates & secrets** → **New client secret** → copie o **Value** na hora (some depois).
5. **API permissions** (delegated, Microsoft Graph): `Mail.Send`, `Calendars.ReadWrite`,
   `offline_access`, `openid`, `email`.
6. Env vars: `MS_OAUTH_CLIENT_ID` · `MS_OAUTH_CLIENT_SECRET`

---

## Fora do catálogo (e por quê)

- **WhatsApp (Meta Cloud API):** exige Business verification da Meta — semanas, não é
  "qualquer um conecta". Inviável pré-pitch.
- **Jira:** OAuth 2.0 (3LO) da Atlassian funciona grátis, mas o app precisa passar por
  aprovação pra produção multi-usuário; o modo dev limita os usuários. Mesma vibe do
  Google Testing — possível, mas sem vantagem sobre GitHub/Linear pra demo.
- **Zoom / Google Meet (importar gravação):** APIs gratuitas existem, mas são *fonte* de
  transcrição (outro épico), não ação do Flows.
- **Google Sheets ("logar reuniões numa planilha"):** tecnicamente é só somar o escopo
  `spreadsheets` ao OAuth Google existente — mas escopo novo = consent screen de novo a
  cada reconexão e mais um motivo de review futuro. Avaliar pós-pitch.

## Lembretes de segurança (processo combinado)

- Salvar env var no Windows: PowerShell →
  `[Environment]::SetEnvironmentVariable('NOME', 'valor', 'User')` (ou Configurações →
  variáveis de ambiente). **Nunca** colar a credencial no chat, em commit ou em arquivo
  do repo.
- O arquiteto propaga pra GitHub Secrets **sempre via `gh secret set NOME --body ...`**
  (nunca pipe — BOM do PowerShell corrompe; ver memória do projeto) e pro Bicep/app.
- Cada integração nova segue o padrão ADR 0031: state HMAC, token cifrado AES-GCM no
  banco, refresh server-side.

## Histórico

| Data | Mudança |
|---|---|
| 2026-06-12 | Criado a pedido do PO (catálogo + tutoriais; fluxo env var local → arquiteto configura) |
