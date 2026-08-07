# ADR 0034 — Migração de Azure Container Apps para Proxmox self-hosted (VM única + Docker Compose)

- **Status:** aceito
- **Data:** 2026-08-07
- **Decisores:** sys0xFF (PO/dono) + Arquiteto NORA (Tech Lead, auditoria de migração)
- **Substitui:** ADR 0009 (Speech Token Broker — o recurso Azure Speech deixa de existir; a
  substituição funcional é o **ADR 0035**)
- **Substitui parcialmente:** ADR 0016 (production-readiness checklist — todas as premissas
  ancoradas em Azure caem: Gap 1 `prod.bicepparam`/SP separado, Gap 3 RPO/RTO apoiado no PITR do
  Flexible Server, Gap 4 alertas via Azure Monitor + workbook do App Insights, Gap 7 rotação via
  Key Vault. Gap 2 e Gap 6 continuam válidos com outro substrato; Gap 5 já foi entregue pelo
  ADR 0029)
- **Altera:** ADR 0023 (a borda do operador deixa de ser ingress de Container App; a separação de
  planos e os dois tokens internos continuam), ADR 0022 (o 2º Postgres continua sendo **server
  separado**, mas o isolamento físico cai — mesma VM), ADR 0026/0028 (o design de RLS não muda; o
  **endpoint** do cutover muda e o FQDN Azure hardcoded precisa ser reapontado), ADR 0029 (o local
  de repouso do PII sai de datacenter gerenciado para hardware próprio)
- **Estende:** ADR 0025 (o Cloudflare Tunnel deixa de ser só do `nora-admin` e vira o **único**
  ingress de toda a stack)
- **Relacionados:** ADR 0017 (repo público — é o que veta o deploy por push), ADR 0027 (branch
  protection), ADR 0024 (control plane), `infra/proxmox/docker-compose.yml` (contrato da stack),
  `docs/operations/proxmox-deploy.md` (runbook sucessor), `docs/operations/azure-decommission.md`

## Contexto

### Produção está fora do ar

`nora.systems` e `api.nora.systems` devolvem **522** (Cloudflare alcança o DNS, a origem não
responde). O FQDN cru do Container App —
`nora-api-dev.salmonbeach-349d395f.centralus.azurecontainerapps.io` — **também não conecta**, o que
elimina a Cloudflare da lista de suspeitos: a origem sumiu.

Linha do tempo apurada:

| Data | Evento |
|---|---|
| 2026-06-13 | Último deploy de infra (`deploy-infra.yml`) bem-sucedido |
| 2026-07-06 | Último `build-images` em `main` com `deploy-apps` OK — ou seja, o OIDC federado ainda funcionava nessa data |
| 2026-08-07 | 522 no domínio público e no FQDN direto |

Causa provável: **a subscription Azure for Students foi desativada**. O benefício estudantil expira
ou esgota crédito sem aviso operacional; recursos são parados e, depois de um prazo de retenção,
excluídos. Não é falha de código nem de deploy — o `deploy-apps` funcionava em 06/07.

### Os dados estão em risco

`nora-pg-dev-wgl3a3` guarda:

- `transcripts.raw_text` — transcrição **bruta**, PII em repouso (o ADR 0029 é explícito sobre
  isso: o PII Shield redige o que vai pra LLM, não o que fica no banco);
- as análises (`meeting_analyses` + filhos), o Productivity Score (V012), o Customer Confidence
  (V017), os embeddings da V021;
- os tokens OAuth cifrados das integrações (ADR 0031) — cifrados em repouso, mas não recuperáveis
  sem o banco.

**Não existe backup fora do Azure.** O PITR de 7 dias do Flexible Server é interno à subscription:
morre junto com ela. Isso torna o resgate dos dados a tarefa de maior prioridade, acima de qualquer
decisão de arquitetura — e é por isso que a ordem do
[`azure-decommission.md`](../operations/azure-decommission.md) começa por dump verificado, não por
migração.

### Nunca houve um ADR declarando o Azure

Vale registrar sem eufemismo: a plataforma de hospedagem **nunca foi decidida** — foi herdada de um
crédito estudantil. O que existe é um runbook (`docs/operations/azure-deploy.md`) e um catálogo de
**8 armadilhas do Azure for Students** descobertas empiricamente na Sub-fase 1.9. O ADR 0016
assumiu Azure como dado, não como escolha. Este ADR é o **primeiro registro formal de decisão de
plataforma de hospedagem** do projeto.

### A auditoria: sair do Azure é mais barato do que o inventário sugeria

O levantamento de acoplamento encontrou o oposto do esperado:

- **Zero SDK Azure em qualquer app.** Nenhum `com.azure:*` no `pom.xml`, nenhum `azure-*` no worker
  Python, nenhum `@azure/*` nos dois Next. A "nuvem" estava no IaC e nas env vars, não no código.
- **O acoplamento profundo em código são DOIS pontos:**
  1. o `-javaagent` do Application Insights (`services/api/Dockerfile:16,22-28,39`);
  2. o sidecar Python do desktop (`azure-cognitiveservices-speech`) — endereçado pelo **ADR 0035**,
     não por este.

E um terceiro que o inventário quase perdeu:

- **`AppInsightsHealthSource.java` é um caminho de LEITURA de telemetria.** Faz `GET` em
  `api.applicationinsights.io/v1/apps/{id}/query` com **KQL** e header `x-api-key`. Um OTel
  Collector é **write-only e não fala KQL** — não há substituição por configuração. Isso é
  reescrita de classe Java, e ficou fora do inventário inicial porque a busca por "SDK" não pega um
  cliente HTTP cru.

Duas armadilhas de execução, apuradas antes de qualquer commit:

- **Trocar só a env `OTEL_EXPORTER_OTLP_ENDPOINT` NÃO funciona.** O agent do App Insights exporta
  para o endpoint **Breeze** da connection string e **ignora** `OTEL_EXPORTER_OTLP_ENDPOINT`. É
  obrigatório **trocar o JAR** pelo `opentelemetry-javaagent.jar` e **republicar a imagem**. Sem
  isso, a API sobe "sem erro" e não emite nada.
- **`AZURE_SPEECH_ENDPOINT` é peso morto.** É injetada pelo Bicep e **não tem um consumidor no
  repo**; o output `speechEndpoint` também é inconsumido. O broker usa o endpoint **regional de
  STS** (`application.yml:179`,
  `https://%s.api.cognitive.microsoft.com/sts/v1.0/issueToken`) com header
  `Ocp-Apim-Subscription-Key`.

### O repositório é público — e isso decide o modelo de deploy

O repo é público (ADR 0017) e `deploy-infra.yml:60-64` tem trigger `pull_request`. Um **runner
self-hosted persistente na rede doméstica** executaria, portanto, **código de fork arbitrário** com
acesso ao socket do Docker e à LAN. Isso não é uma preferência de estilo: é o que **elimina** o
deploy por push antes de qualquer comparação de conveniência.

### Restrição nova disponível

Existe um Proxmox doméstico ("beta") ocioso, com energia e link já pagos. Custo marginal de
hospedar a stack lá: ~zero em dinheiro.

## Decisão

**Migrar a produção do NORA para uma VM Debian única em Proxmox, orquestrada por Docker Compose,
com Cloudflare Tunnel como único ingress, segredos em SOPS+age e deploy por PULL.**

O contrato completo da stack é o `infra/proxmox/docker-compose.yml` (projeto compose `nora`) — ele
é a fonte da verdade; o que segue são as decisões que o justificam.

### 1. Substrato: VM Debian única + Docker Compose

Uma VM, três redes bridge: `edge` (só cloudflared e caddy), `internal` (`internal: true` — sem rota
pra internet) e `data` (`internal: true`). **Nenhuma porta publicada no host** além de `127.0.0.1`
para debug via `ssh -L`. Cada serviço é o par 1:1 de um módulo `container-app.bicep`; as env vars
foram transcritas do range `main.bicep:694-1540`.

Sem scale-to-zero: numa VM única o custo de manter uma réplica de pé é irrelevante e elimina o cold
start que o Consumption profile impunha.

### 2. Ingress: Cloudflare Tunnel como o único caminho de entrada

Estende o ADR 0025 do `nora-admin` para a stack inteira. `cloudflared` conecta **outbound** ao
Cloudflare; não existe porta inbound, não existe FQDN de origem para contornar o Access. O TLS do
visitante é o Universal SSL da Cloudflare (SSL mode Full, como já era).

O roteamento por Host é do **Caddy**: apex e `www` → `web`; `api.<dom>` → `api`;
`admin.<dom>` → `admin`; `grafana.<dom>` → `grafana`.

**`CF_ACCESS_AUD` passa a ser obrigatório** (`:?` no compose). Hoje, em produção, ele chega **vazio**
— está cadastrado como Secret e lido como `vars.` nos workflows — e `apps/admin/src/lib/access.ts`
faz **fail-OPEN**: o Tier 2 do ADR 0025 está desligado em produção **em silêncio**. A migração
fecha isso por construção: sem a variável, o container `admin` não sobe.

### 3. Caddy: um componente novo, sem par no Azure, e o motivo dele

O Container Apps tinha `activeRevisionsMode: Single` com **readiness gate**: só trocava o tráfego
depois do probe da revisão nova passar. `docker compose up -d` faz o contrário — **derruba o
container antigo antes de subir o novo**. O boot do Spring com Flyway leva ~30s (o healthcheck usa
`start_period: 45s` e `retries: 12`, calibrado no Bicep para isso).

O Caddy existe para transformar esse buraco em latência: `lb_try_duration` segura a requisição e
reenvia quando a origem volta. É **mitigação, não eliminação** — ver §Consequências.

### 4. Segredos: SOPS + age

`infra/proxmox/secrets.env.sops` é **versionado cifrado**; a chave privada age vive **só no host**,
em `/etc/nora/age.key` (`chmod 400`, dono `root`). O deploy decifra para um `.env` em **tmpfs**, que
nunca toca o disco.

Substitui Key Vault + 3 User-Assigned Managed Identities + os role assignments. As consequências
disso não são boas — estão registradas abaixo, sem maquiagem.

### 5. Deploy por PULL, nunca por push

Um agente no próprio host puxa a tag desejada do GHCR e aplica. **Nenhum runner do GitHub toca a
rede doméstica.** O CI continua sendo o dono do build e da publicação das imagens; o host é o dono
do rollout.

Imagens continuam vindo do GHCR já existente
(`ghcr.io/sys0xff/nora-{api,worker,web,admin}`). O mecanismo de rollout são as **tags imutáveis
`sha-<short>`** — `latest` serve para bootstrap, não para rollout, porque não dá alvo de rollback.

### 6. Observabilidade: duas pernas, não uma

Cobertura equivalente ao que existia exige **duas** pernas distintas:

| O que fazia no Azure | Substituto |
|---|---|
| `-javaagent` do App Insights (traces/métricas) | `opentelemetry-javaagent.jar` → `otel-collector` (OTLP gRPC 4317) → Prometheus (retenção 30d) |
| `appLogsConfiguration` → Log Analytics (stdout dos apps) | Alloy lendo o socket do Docker → Loki |
| Workbook / Metrics Explorer | Grafana em `grafana.<dom>`, atrás do mesmo Tunnel |

Um OTel Collector sozinho **não** cobre log shipping — daí o Alloy. Ele foi escolhido em vez do log
driver `loki` porque o plugin quebra `docker logs` e, em `mode=non-blocking`, descarta linhas em
silêncio sob backpressure; Alloy tem WAL, então backpressure vira atraso, não perda.

**A troca do JAR é obrigatória e é mudança de imagem**, não de env (ver §Contexto).

### 7. `AppInsightsHealthSource` → `PrometheusHealthSource`

A leitura de telemetria de saúde do control plane (ADR 0024) é reescrita: a Query REST API do App
Insights (KQL, `x-api-key`) é trocada pela **Query API do Prometheus**
(`NORA_PLATFORM_HEALTH_PROMETHEUS_URL: http://prometheus:9090`, janela em
`NORA_PLATFORM_HEALTH_WINDOW`).

Efeito colateral positivo: `NORA_PLATFORM_HEALTH_APP_ID` / `NORA_PLATFORM_HEALTH_API_KEY` nunca
foram provisionados em lugar nenhum, então esse painel está "unavailable" desde sempre. Passa a
funcionar pela primeira vez.

### 8. Dados: dois Postgres, três roles, sem `sslmode`

- Imagem `pgvector/pgvector:pg16`, **dois servers separados** (`nora` e `nora_platform`),
  preservando o contrato `PLATFORM_DATASOURCE_URL` e o blast radius do ADR 0022 — com a ressalva de
  que "separado" agora é processo, não máquina.
- **A JDBC URL não leva `?sslmode=require`.** A imagem oficial sobe com SSL OFF e o Hikari falha no
  boot. O tráfego não sai da bridge `data`, que é `internal: true`.
- Os **três** roles do RLS (ADR 0026/0028) são provisionados: `nora_app` (NOBYPASSRLS, runtime),
  `nora_telemetry` (BYPASSRLS, painel do operador) e o admin/owner (Flyway/DDL). **Omitir o
  `nora_telemetry` zera o painel em silêncio** — fail-closed sem erro, o modo de falha mais caro de
  diagnosticar. Está no runbook como item bloqueante.
- O FQDN Azure `nora-pg-dev-wgl3a3.postgres.database.azure.com` está hardcoded em **quatro**
  lugares e precisa ser reapontado ou aposentado: `infra/bicep/main.dev.bicepparam:140`,
  `.github/workflows/rls-cutover.yml:40`, `docs/operations/rls-cutover-runbook.md:69`,
  `docs/operations/azure-deploy.md:398`.

### 9. Backup

`pg_dump` lógico por hora dos dois bancos, retenção de 14 dias, mais snapshot da VM no Proxmox
Backup Server (fora do compose). Substitui o PITR de 7 dias do Flexible Server — **com perda de
garantia**, quantificada abaixo.

<a id="escopo-excluido"></a>

## Escopo excluído — o que é DELETADO, não portado

Migrar isto seria portar dívida. Nada aqui tem consumidor:

| Recurso / variável | Por que sai |
|---|---|
| **Storage Account** (LRS, blob soft-delete 7d) | **Zero consumidores** no código. Provisionado e nunca usado. |
| **Azure AI Search** (Basic, opcional) | Desligado por default. A busca semântica é a V021 (`meeting_embeddings`) + HTTP embedding client — nunca dependeu dele. |
| **Easy Auth / App Registration Entra** | Abandonado pelo **ADR 0025**; o Bicep ainda provisionava o secret. `EASYAUTH_CLIENT_ID` / `EASYAUTH_CLIENT_SECRET` são órfãos referenciados no workflow e inexistentes como Secret. |
| **`daprAIConnectionString`** | **Nenhum app habilita Dapr.** |
| **`AZURE_SPEECH_ENDPOINT`** + output `speechEndpoint` | Injetada e inconsumida. O broker usa o endpoint regional de STS (`application.yml:179`). |
| **`AZURE_SEARCH_ENDPOINT` / `AZURE_SEARCH_INDEX`** | Dependentes do AI Search desligado. |
| **`APPLICATIONINSIGHTS_CONNECTION_STRING`** | Morre com a troca do javaagent. |
| **`NORA_PLATFORM_HEALTH_APP_ID` / `_API_KEY`** | Nunca provisionados; substituídos pelo Prometheus. |
| **Key Vault + 3 UAIs + role assignments** | Substituídos por SOPS+age (com as consequências abaixo). |
| **Azure Speech (Cognitive Services S0)** | Ver **ADR 0035**. |

**Explicitamente NÃO no escopo:** ligar `pgvector` de verdade. A V021 evitou a extensão **de
propósito**, por causa da allow-list do Azure — guarda embeddings como JSON em coluna `TEXT` e
computa cosseno em Java. Sair do Azure destrava a extensão, mas trocar isso é **refactor de RAG**,
não migração de infra. A imagem `pgvector/pgvector:pg16` deixa a extensão *disponível* e **não a
cria**.

## Consequências

<a id="disponibilidade"></a>

### Disponibilidade — piora, e o quanto

**Perde-se o readiness gate e o rolling update.** O `activeRevisionsMode: Single` só cortava
tráfego depois do probe passar. Compose não tem esse conceito. Resultado: **janela de ~45s por
deploy da API** em que a origem não responde (boot do Spring + Flyway ~30s; healthcheck com
`start_period: 45s`). Mitigada — não eliminada — por `lb_try_duration` no Caddy: requisições que
estouram o try duration ainda falham, e conexões em voo caem. Web e admin sofrem menos (boot mais
curto), mas sofrem.

**Perde-se o PITR gerenciado.** RPO sai de ~5 min (ADR 0016 Gap 3, apoiado no PITR do Flexible
Server) para **até 1 hora** — o intervalo do `pg_dump`. RTO sai de **minutos** (restore PITR era um
comando) para **horas**: provisionar/restaurar a VM, restaurar o dump lógico, subir a stack,
verificar. A meta de RTO de 2h do ADR 0016 continua *alcançável*, mas por procedimento manual
ensaiado — não por botão gerenciado. **Se o restore drill não for feito, a meta é ficção.**

**Host único = ponto único de falha.** Sem multi-AZ, sem failover, sem hipervisor redundante. Queda
de energia ou de link doméstico derruba produção inteira, incluindo a observabilidade que diria que
ela caiu. O cenário C do ADR 0016 Gap 6 ("região Azure indisponível — MVP single-region aceita
downtime") vira "single-**host** aceita downtime": mesmo veredito, blast radius maior,
probabilidade maior.

**O SLO de 99,0% mensal (ADR 0016 Gap 4) fica aritmeticamente possível e materialmente
insustentado.** São ~7h de budget por mês; ~45s por deploy da API mais qualquer evento de energia o
consomem rápido. Nada nesta stack *garante* o número — apenas não o impede.

### Segredos: o número AUMENTA (o resultado contraintuitivo)

No Azure, a API **não tinha a senha do Postgres numa env var**: vinha do Key Vault por `secretRef` +
User-Assigned Managed Identity. A **identidade era a credencial**, e quem a rotacionava era a
plataforma.

Sem managed identity, cada uma dessas referências vira **valor estático** no
`secrets.env.sops`: `POSTGRES_ADMIN_PASSWORD`, `POSTGRES_PLATFORM_ADMIN_PASSWORD`, `JWT_SECRET`,
`NORA_PLATFORM_INTERNAL_TOKEN`, `NORA_PLATFORM_ADMIN_TOKEN`, `NORA_INTEGRATIONS_ENC_KEY`,
`NORA_INTEGRATIONS_STATE_SECRET`, as chaves dos providers de LLM/embeddings, `RESEND_API_KEY`, os
pares OAuth das integrações, `GRAFANA_ADMIN_PASSWORD`, `CLOUDFLARE_TUNNEL_TOKEN`, as senhas dos
roles de RLS.

Saem 1 Key Vault e 3 UAIs. Entram **~20 valores estáticos cifrados por UMA chave age que vive num
arquivo no host**. Quem lê `/etc/nora/age.key` decifra tudo — o blast radius de comprometer o host
cresce materialmente. Mitigação honesta e **parcial**: permissão 400/root, disco da VM cifrado em
repouso, e a política de rotação do ADR 0016 Gap 7 continua valendo com `sops updatekeys` no lugar
do KV. **Não é equivalente a managed identity. É pior.** Aceito pelo preço e pela urgência.

### Operação e custo

- **Custo em dinheiro cai a ~zero** (energia e link já existem, o Proxmox está ocioso). **Custo em
  atenção humana sobe**: patch do host e do Docker, disco, snapshots, rotação de chave, e o drill de
  restore — que agora é a única coisa entre nós e a perda de dados.
- **Perde-se o `az`.** Todo o `docs/operations/azure-deploy.md` e as 8 armadilhas do Azure for
  Students viram história. Em troca ganha-se uma **classe nova de armadilhas** (`sslmode` na JDBC
  URL, retenção do Loki sem compactor, `NEXT_PUBLIC` baked em build-time, `CF_ACCESS_AUD`
  fail-open, `initdb` que só roda em volume vazio, a janela de 502) — catalogadas em
  `docs/operations/proxmox-deploy.md`.
- **Perde-se o OIDC federado sem segredo.** O deploy PULL não usa credencial de nuvem alguma, mas o
  host precisará de credencial de pull do GHCR se as imagens deixarem de ser públicas.
- O ADR 0022 continua valendo no contrato (2º datasource, dump independente), mas o **isolamento
  físico** que ele comprava some: os dois Postgres passam a compartilhar host, kernel e disco.

### O que efetivamente melhora

- **A stack fica reproduzível localmente.** `docker compose up` sobe o que roda em produção; o
  Bicep nunca permitiu isso.
- **`pgvector` fica disponível.** A allow-list do Azure some, destravando o refactor da V021 para
  quem quiser fazê-lo (não agora — ver §Escopo excluído).
- **Zero superfície pública, para a stack inteira.** Nenhuma porta inbound, nenhum FQDN cru de
  origem. O ADR 0025 queria isso só pro admin.
- **O painel de saúde do control plane passa a funcionar** (nunca funcionou por falta de
  provisionamento).
- **Fim da dependência de uma subscription estudantil que pode ser desativada sem aviso** — que é,
  literalmente, o incidente que gerou este ADR.

## Alternativas Consideradas

1. **Reativar / migrar para Pay-As-You-Go no Azure.** Custo real de ~R$110-180/mês numa stack sem
   receita, e mantém a dependência de um provedor cujo desligamento já provou ser silencioso.
   **Rejeitado como destino** — mas continua sendo o caminho mais rápido para **resgatar os dados**
   (ver `docs/operations/azure-decommission.md`). Reativar para dar `pg_dump` não é o mesmo que
   continuar hospedado.
2. **Outro PaaS (Fly.io / Render / Railway).** Preservaria readiness gate e backup gerenciado, e é
   honestamente a melhor alternativa se o Proxmox se mostrar caro em atenção humana. Rejeitado
   agora por custo em dinheiro e por não resolver a lição (continuar refém de uma plataforma).
   **Trigger de reavaliação declarado:** dois incidentes de indisponibilidade por causa do host em
   um trimestre, ou o primeiro tenant pagante.
3. **k3s no Proxmox.** Resolveria o rolling update com readiness probe de verdade — o item mais
   caro que estamos perdendo. Rejeitado: para ~12 containers em **um** host, operar k3s (etcd,
   ingress controller, CNI, storage class, ciclo de upgrade) custa mais do que o único gate que o
   Caddy já mitiga em boa parte. **Trigger de upgrade:** mais de um host, ou requisito real de
   zero-downtime.
4. **Docker Swarm.** Tem `update_config` com rolling update nativo, que resolveria a janela de 502.
   Rejeitado: praticamente sem manutenção upstream — seria colocar produção sobre um orquestrador
   em fim de vida para ganhar um recurso.
5. **Deploy por PUSH com runner self-hosted.** Rejeitado **por segurança, não por gosto**: o repo é
   público (ADR 0017) e `deploy-infra.yml:60-64` dispara em `pull_request`. Um runner persistente na
   rede doméstica executaria código de fork arbitrário com acesso ao socket do Docker e à LAN. Não
   existe configuração de runner que torne isso aceitável num repo público.
6. **Migrar só o compute e manter o App Insights apontando pro Azure.** Rejeitado: a connection
   string morre com a subscription, e o App Insights é justamente um dos componentes cujo
   desligamento estamos fugindo.
7. **Manter o `-javaagent` do App Insights e só trocar a env OTLP.** Não é alternativa — é um bug.
   O agent ignora `OTEL_EXPORTER_OTLP_ENDPOINT`. Registrado aqui porque foi a primeira hipótese e
   teria falhado em silêncio.

## Plano de Aplicação

Ordem obrigatória, detalhada em [`azure-decommission.md`](../operations/azure-decommission.md) e
[`proxmox-deploy.md`](../operations/proxmox-deploy.md):

1. **Resgatar os dados primeiro** (dump verificado dos dois bancos, fora do Azure).
2. Republicar as imagens `api` (troca do javaagent + `PrometheusHealthSource`) e as demais.
3. Provisionar a VM, subir a stack, restaurar os dumps, verificar com tráfego real via hostnames de
   teste.
4. **Só então** apontar o DNS.
5. **Só depois** desligar o resource group.

## Histórico

| Data | Decisor | Mudança |
|---|---|---|
| 2026-08-07 | sys0xFF + Arquiteto NORA | Criação e aceite. Migração motivada por indisponibilidade de produção (522 desde ~julho/2026, subscription Azure for Students provavelmente desativada) e risco de perda de dados. Substitui 0009, substitui parcialmente 0016, altera 0022/0023/0026/0028/0029, estende 0025. |
