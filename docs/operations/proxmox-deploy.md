---
title: "Runbook — Deploy do NORA em Proxmox (self-hosted)"
owner: Arquiteto NORA (Tech Lead)
status: approved
version: 1.1
last_reviewed: 2026-08-07
---

# Runbook — Deploy do NORA em Proxmox (self-hosted)

> **Audiência:** quem opera o deploy do NORA na VM Proxmox (Tech Lead hoje; futuros operadores).
>
> **Substitui** [`azure-deploy.md`](azure-deploy.md), que passa a ser documento histórico.
> A decisão está no [ADR 0034](../adr/0034-migracao-azure-para-proxmox.md); o desligamento do que
> ficou na Azure está em [`azure-decommission.md`](azure-decommission.md).
>
> **Pré-requisitos:** acesso ao Proxmox "beta", conta Cloudflare com a zona `nora.systems`,
> `sops` + `age` na máquina do operador e `gh` CLI. **Não é preciso trazer nada da Azure:** o
> NORA é um projeto educacional sem dado de produção, então o banco nasce vazio e o Flyway cria o
> schema do zero (ver `azure-decommission.md` §"O que este runbook NÃO precisa fazer").

> **Ambiente único, de novo.** Como no Azure, existe **um** ambiente vivo. Não há staging. A VM se
> chama `nora-prod` desde o começo — o rename cosmético que ficou pendente no Azure (`dev` = produção)
> não se repete aqui.

---

## Visão geral

Uma VM Debian no Proxmox roda toda a stack com Docker Compose (projeto `nora`), definida em
[`infra/proxmox/docker-compose.yml`](../../infra/proxmox/docker-compose.yml) — **esse arquivo é a
fonte da verdade**; este runbook é como operá-lo.

```
Internet ──> Cloudflare edge ──(tunnel, saída-only)──> cloudflared
                                                           │
                                                         caddy            <- retry buffer
                                                     ┌─────┼─────┐           do rolling update
                                                    web   api   admin
                                                           │
                                                        worker (interno)
                                                           │
                                           postgres  +  postgres-platform
```

**Nenhuma porta inbound é aberta no host.** As únicas portas publicadas são em `127.0.0.1`
(`5432` → postgres, `5433` → postgres-platform) para debug via `ssh -L`.

Mapa de substituição, para quem vem do Azure:

| Azure | Proxmox |
|---|---|
| Container Apps Environment + ingress externo + managed cert | `cloudflared` (Tunnel) + `caddy` (roteamento por Host) |
| 3 Container Apps (api/worker/web) + `nora-admin` | 4 serviços do compose, mesmas env vars (transcritas de `main.bicep:694-1540`) |
| Postgres Flexible Server B1ms | `pgvector/pgvector:pg16` (`nora`) |
| 2º Flexible Server (ADR 0022) | `postgres-platform` (`nora_platform`), profile `platform` |
| Key Vault + 3 User-Assigned Managed Identities | SOPS + age (`secrets.env.sops` + `/etc/nora/age.key`) |
| App Insights (`-javaagent`) | `opentelemetry-javaagent.jar` → `otel-collector` → `prometheus` |
| Log Analytics (`appLogsConfiguration`) | `alloy` (socket do Docker) → `loki` |
| Workbook / Metrics Explorer | `grafana` em `grafana.<dom>` |
| PITR 7 dias | `backup` (pg_dump horário, retenção 14d) + snapshot da VM no PBS |
| `deploy-infra.yml` (push, OIDC) | `scripts/deploy.sh` no host (**PULL**) |

### Bloqueantes antes de começar

- [ ] **Imagem da API republicada com o javaagent trocado.** Trocar só
      `OTEL_EXPORTER_OTLP_ENDPOINT` **não funciona**: o agent do App Insights exporta para o
      endpoint Breeze da connection string e **ignora** a env OTLP. É preciso substituir o
      `applicationinsights-agent.jar` pelo `opentelemetry-javaagent.jar` no
      `services/api/Dockerfile` (linhas 16, 22-28, 39) e publicar uma tag nova no GHCR. Sem isso a
      API sobe sem erro e **não emite telemetria nenhuma**.
- [ ] **`PrometheusHealthSource` no lugar do `AppInsightsHealthSource`.** O antigo faz `GET` em
      `api.applicationinsights.io/v1/apps/{id}/query` com **KQL**. O OTel Collector é write-only e
      não fala KQL — é reescrita de classe Java, não troca de env.
- [ ] **Dump verificado dos dois bancos**, fora do Azure (`azure-decommission.md` §1).
- [ ] Arquivos de suporte presentes em `infra/proxmox/`: `caddy/Caddyfile`, `postgres/init/*.sql`,
      `observability/{otel-collector.yaml,prometheus.yml,loki.yaml,config.alloy,grafana/provisioning/}`,
      `backup/run-backup.sh`, `scripts/deploy.sh`.

---

## As 9 armadilhas do self-hosted (CATALOGADAS)

Mesmo espírito das 8 armadilhas do Azure for Students: cada uma custou tempo, e nenhuma dá erro
óbvio. **Leia antes do primeiro deploy.**

### Armadilha 1 — `?sslmode=require` na JDBC URL derruba a API no boot

**Sintoma:** a API entra em restart loop; o log mostra o Hikari falhando antes do Flyway:

```
org.postgresql.util.PSQLException: The server does not support SSL.
HikariPool-1 - Exception during pool initialization.
```

**Causa:** a JDBC URL do Azure levava `?sslmode=require` porque o Flexible Server **exige** TLS. A
imagem oficial do Postgres sobe com **SSL OFF** — o parâmetro herdado do Bicep faz o driver abortar
o handshake.

**Fix:** a `DATASOURCE_URL` do compose **não tem** `sslmode`, de propósito:

```yaml
DATASOURCE_URL: jdbc:postgresql://postgres:5432/nora
```

Não é relaxamento de segurança: o tráfego não sai da bridge `data`, que é `internal: true`. Se
alguém colar a URL do Azure por hábito, o boot quebra. Vale para
`PLATFORM_DATASOURCE_URL`, `SPRING_FLYWAY_URL` e `NORA_TELEMETRY_DATASOURCE_URL` também.

### Armadilha 2 — `initdb` só roda em VOLUME VAZIO

**Sintoma:** os roles `nora_app` / `nora_telemetry` não existem, o painel do operador fica vazio, ou
o flip do RLS falha. Nenhum erro no boot do Postgres — ele sobe limpo.

**Causa:** o entrypoint da imagem só executa `/docker-entrypoint-initdb.d/*` quando
`PGDATA` está **vazio**. Se o volume `pgdata` já foi inicializado (um `up` anterior, um restore, um
drill), os scripts são **silenciosamente ignorados**.

**Fix:** trate `postgres/init` como bootstrap de volume novo, nunca como migração. Para volume já
existente, aplique à mão:

```bash
docker compose -p nora exec -T postgres psql -U nora_admin -d nora < infra/proxmox/postgres/init/01-roles-and-db.sql
```

**Corolário importante:** depois de um `pg_restore`, `DEFAULT PRIVILEGES` **não** alcançam as
tabelas restauradas (elas já existiam no dump). É obrigatório reaplicar os `GRANT` do R001 — ver
§Restaurar os dados, passo 4.

### Armadilha 3 — `internal: true` corta a saída do api/web/worker

**Sintoma:** a stack sobe **saudável** (todos os healthchecks verdes) e, no primeiro uso real,
tudo que fala com o mundo dá timeout: análise LLM, embeddings, envio de e-mail pelo Resend, troca de
código OAuth das integrações (ADR 0031), Telegram. O log mostra `UnknownHostException` ou timeout de
conexão — não erro de credencial.

**Causa:** as redes `internal` e `data` são `internal: true`. Isso não bloqueia só o inbound: bloqueia
**egress e resolução de DNS externo** para qualquer container que esteja **apenas** nelas. O
comentário do compose ("postgres e worker não saem sozinhos") descreve a intenção do `postgres`; o
`worker`, o `api` e o `web` **precisam** sair (OpenAI, Gemini, Resend, endpoints de token dos 7
providers OAuth).

**Fix:** dar saída — sem dar entrada — anexando `api`, `web` e `worker` também à `edge`:

```yaml
  worker:
    networks: [internal, edge]
  api:
    networks: [internal, data, edge]
  web:
    networks: [internal, edge]
```

Estar na `edge` **não** publica porta nenhuma: não há `ports:` nesses serviços e o único caminho de
entrada continua sendo `cloudflared → caddy`. `postgres`, `postgres-platform` e `backup` ficam **só**
na `data` — esses realmente não saem.

Verificação (falha antes do fix, passa depois):

```bash
docker compose -p nora exec worker python -c \
  "import urllib.request;print(urllib.request.urlopen('https://api.openai.com/v1/models',timeout=5).status)"
```

### Armadilha 4 — `NEXT_PUBLIC_*` é baked em BUILD-TIME

**Sintoma:** você muda `NEXT_PUBLIC_API_BASE_URL` no `.env`, reinicia o `web`, e o browser continua
chamando o endereço antigo. Nada no log indica o problema.

**Causa:** o Next inlina `NEXT_PUBLIC_*` no bundle do cliente durante o `next build`. A env do
runtime **não** altera JavaScript já compilado. O valor real está congelado na imagem publicada pelo
`build-images.yml` (via `--build-arg`).

**Fix:** se o domínio público mudar, **rebuildar a imagem** com o build-arg novo e publicar tag
nova. Não existe atalho por env.

> Como o domínio continua `nora.systems`, **a imagem `web` que já está no GHCR serve sem rebuild**.
> Isso só vira problema se alguém tentar validar a stack num domínio de teste.

### Armadilha 5 — `CF_ACCESS_AUD` vazio faz o admin FAIL-OPEN

**Sintoma:** nenhum. O `admin.<dom>` responde normalmente. A validação Tier 2 do ADR 0025
simplesmente **não acontece**.

**Causa:** `apps/admin/src/lib/access.ts` degrada para "edge-only" quando `CF_ACCESS_TEAM_DOMAIN` ou
`CF_ACCESS_AUD` estão vazios — **fail-OPEN, em silêncio**. Em produção na Azure isso está ativo há
meses: `CF_ACCESS_AUD` foi cadastrado como **Secret** mas é lido como `vars.` nos workflows, então
chega vazio (ver `environment-secrets.md` §5.1).

**Fix (já no compose):** as duas variáveis são obrigatórias com `:?` — sem elas o container `admin`
**não sobe**:

```yaml
CF_ACCESS_TEAM_DOMAIN: ${CF_ACCESS_TEAM_DOMAIN:?... sem ele access.ts faz fail-open}
CF_ACCESS_AUD:         ${CF_ACCESS_AUD:?... sem ele access.ts faz fail-open}
```

Trocar fail-open silencioso por fail-closed barulhento é o ponto. Conferir depois do deploy:

```bash
docker compose -p nora --profile platform exec admin printenv CF_ACCESS_AUD
```

### Armadilha 6 — retenção do Loki NÃO apaga nada sem compactor

**Sintoma:** o volume `loki_data` cresce até encher o disco da VM. Quando o disco enche, **o Postgres
para de aceitar escrita** — a perda de log vira perda de dados.

**Causa:** configurar `limits_config.retention_period` **não é suficiente**. No Loki, quem deleta é o
**compactor**, e ele precisa estar explicitamente ligado com `retention_enabled: true`. Sem isso a
retenção é só um filtro de consulta: os chunks continuam no disco para sempre.

**Fix:** em `observability/loki.yaml`:

```yaml
limits_config:
  retention_period: 720h          # 30d, alinhado ao Prometheus
compactor:
  working_directory: /loki/compactor
  retention_enabled: true          # <- sem esta linha, nada é apagado
  delete_request_store: filesystem
```

Alarme de disco (o Prometheus com `node_exporter` cobre) é obrigatório de qualquer forma — o
compactor roda em ciclos e não protege contra um pico.

### Armadilha 7 — a janela de ~45s de 502 no rolling update

Ver §[Rolling update](#rolling-update). É a consequência mais visível da migração e está registrada
como perda no ADR 0034 §Disponibilidade — **não é bug, é o custo de trocar Container Apps por
Compose.**

### Armadilha 8 — os TRÊS roles do RLS (omitir o `nora_telemetry` zera o painel EM SILÊNCIO)

**Sintoma:** o painel de telemetria do operador mostra **0 linhas**. Sem erro, sem 500, sem log.

**Causa:** o RLS dos ADRs 0026/0028 depende de **três** roles, não dois:

| Role | Flag | Uso |
|---|---|---|
| `nora_app` | `NOBYPASSRLS` | runtime da aplicação — enxerga só o tenant do GUC |
| `nora_telemetry` | **`BYPASSRLS`** | painel do operador — precisa atravessar tenants |
| admin / owner | dono do schema | Flyway / DDL |

Se `NORA_TELEMETRY_DATASOURCE_*` não estiver preenchido, a API cai no datasource de runtime
(`nora_app`), que é `NOBYPASSRLS`: as queries cross-tenant retornam **zero linhas sem erro**.
Fail-closed silencioso — o modo de falha mais caro de diagnosticar.

**Fix:** provisionar os três no bootstrap (armadilha 2) e preencher as três variáveis. Conferência:

```bash
docker compose -p nora exec postgres psql -U nora_admin -d nora -c \
  "select rolname, rolbypassrls from pg_roles where rolname in ('nora_app','nora_telemetry','nora_admin');"
```

Esperado: `nora_app` = `f`, `nora_telemetry` = `t`.

### Armadilha 9 — o `-javaagent` do App Insights ignora a env OTLP

**Sintoma:** você aponta `OTEL_EXPORTER_OTLP_ENDPOINT` para o collector local, a API sobe, o
healthcheck passa — e o Prometheus/Grafana ficam **sem nenhum dado da API**.

**Causa:** `JAVA_TOOL_OPTIONS` carrega `-javaagent:/app/applicationinsights-agent.jar`
(`services/api/Dockerfile:39`). Esse agent exporta para o endpoint **Breeze** da
`APPLICATIONINSIGHTS_CONNECTION_STRING` e **não implementa** o exporter OTLP genérico. Com a
connection string vazia, ele vira no-op silencioso — que é exatamente o que parece "estar
funcionando".

**Fix:** trocar o JAR por `opentelemetry-javaagent.jar` e **republicar a imagem**. É o primeiro item
dos bloqueantes por isso.

---

## Primeiro deploy do zero

### 1. Provisionar a VM no Proxmox

#### O host `beta`, como ele realmente é

Levantado por SSH em 2026-08-07 (`ssh beta`, entrada no `~/.ssh/config` → `142.132.199.184`,
`root`, chave `hetzner-admin-ed25519`). Não são suposições:

| | |
|---|---|
| Hypervisor | Proxmox VE **9.2.5**, kernel 7.0.14-8-pve, sobre **Debian 13 (trixie)** |
| CPU | AMD Ryzen 9 5950X — 16 cores / **32 threads** |
| RAM | **125 GB** total, ~49 GB disponíveis (a VM `windows11-beta` sozinha reserva 64 GB) |
| Storage | `local-lvm` (LVM-thin) com **6,8 TB livres** (2,6% usado); `local` (dir) com 48 GB |
| Bridge das VMs | `vmbr1` → `10.10.1.0/24`, gateway `10.10.1.1` |
| Egress | `MASQUERADE` de `10.10.1.0/24` para `enp7s0`, `ip_forward=1` |
| Inbound | **nenhum** — as VMs não têm IP público |
| VMs existentes | 100 `windows11-beta`, 101 `ayla`, 102 `yara`, 103 `anglis`, 104 `edge`, 105 `passabola` |
| Próximo VMID livre | **106** |
| Imagem já disponível | `local:iso/noble-server-cloudimg-amd64.img` (Ubuntu 24.04) |

> **A topologia valida o desenho, e não o contrário.** As VMs só têm saída NAT e zero entrada da
> internet. O Cloudflare Tunnel não foi escolhido por conveniência — é o **único** jeito de publicar
> a partir de `vmbr1` sem mexer em firewall do host nem em port-forward. Se um dia alguém propuser
> "abrir a 443 direto", isso significa alterar o NAT do hypervisor que serve outras cinco VMs.

#### Perfil da VM nova

Dimensionado pelos limites do compose (api 2 vCPU/2,5 Gi, web 2/2 Gi, worker 1/1,5 Gi, admin
0,5/0,5 Gi, mais os dois Postgres e a observabilidade):

| Item | Valor | Nota |
|---|---|---|
| VMID / Nome | `106` / `nora-prod` | sem `-dev`; o erro de nomenclatura do Azure não se repete |
| SO | Ubuntu 24.04 (cloudimg já em `local:iso`) ou Debian 13 | o `bootstrap-host.sh` detecta a distro e escolhe o repo Docker certo |
| vCPU | 6, tipo `host` | sobra folga: o host tem 32 threads |
| RAM | 16 GB, **sem ballooning** | cabe nos ~49 GB livres; ballooning + Postgres é OOM imprevisível |
| Disco | 100 GB em `local-lvm`, `virtio-scsi-single`, **Discard** + **SSD emulation** | o thin pool tem 6,8 TB; discard o mantém honesto |
| Rede | `virtio`, bridge **`vmbr1`**, IP estático **`10.10.1.30/24`**, gw `10.10.1.1` | siga o padrão das VMs existentes (`.21` yara, `.22` anglis, `.23` passabola) |
| DNS | `1.1.1.1 8.8.8.8` | mesmo das outras VMs |
| Boot | QEMU Guest Agent **ligado** | necessário para snapshot consistente |
| Proteção | `Start at boot: yes`, `Protection: yes` | evita destruição acidental |

```bash
# no host beta, como root
qm create 106 --name nora-prod --ostype l26 \
  --cores 6 --cpu host --memory 16384 --balloon 0 \
  --net0 virtio,bridge=vmbr1 --agent enabled=1 \
  --scsihw virtio-scsi-single --onboot 1 --protection 1
qm importdisk 106 /var/lib/vz/template/iso/noble-server-cloudimg-amd64.img local-lvm
qm set 106 --scsi0 local-lvm:vm-106-disk-0,discard=on,iothread=1,ssd=1
qm resize 106 scsi0 100G
qm set 106 --ide2 local-lvm:cloudinit --boot order=scsi0 --serial0 socket --vga serial0
qm set 106 --ipconfig0 ip=10.10.1.30/24,gw=10.10.1.1 --nameserver "1.1.1.1 8.8.8.8"
qm set 106 --ciuser nora --sshkeys ~/.ssh/authorized_keys
qm start 106
```

Depois adicione ao seu `~/.ssh/config` local, seguindo o padrão das outras VMs — note que elas são
alcançáveis a partir do host, não da internet:

```
Host nora-prod
  HostName 10.10.1.30
  User nora
  ProxyJump beta
  IdentityFile ~/.ssh/hetzner-admin-ed25519
```

#### Backup do hypervisor — não existe ainda, e precisa ser criado

**Não há Proxmox Backup Server neste host.** O que existe é um job `vzdump` local chamado
`ayla-daily`, e ele cobre **apenas a VM 101**:

```
vzdump: ayla-daily
  storage local · mode snapshot · schedule 03:30
  prune-backups keep-daily=7,keep-weekly=4
  vmid 101
```

Uma VM nova **não entra nesse job automaticamente**. Sem criar um equivalente, a `nora-prod` fica
sem backup de hypervisor nenhum — e aí o `pg_dump` horário do serviço `backup` vira a única linha
de defesa, o que cobre perda de *dado* mas não perda de *VM*.

Crie o job (Datacenter → Backup → Add, ou editando `/etc/pve/jobs.cfg`), com a mesma política:

```
vzdump: nora-daily
  storage local
  mode snapshot
  schedule 03:30
  prune-backups keep-daily=7,keep-weekly=4
  vmid 106
  notes-template NORA prod - backup automatico {{guestname}}
```

> Atenção ao espaço: o storage `local` tem **48 GB livres** e já guarda ~5 GB por backup da `ayla`.
> Um snapshot de 100 GB da `nora-prod` não cabe lá. Ou aponte este job para `local-lvm`/um storage
> dedicado, ou reduza o disco da VM, ou adicione um Proxmox Backup Server. **Decida isto antes do
> go-live, não depois do primeiro backup falhar em silêncio.**

### 2. Bootstrap do host

```bash
ssh nora-prod

# --- base ---
sudo apt-get update && sudo apt-get -y upgrade
sudo apt-get -y install ca-certificates curl gnupg git age unattended-upgrades \
                        postgresql-client-16 jq
sudo dpkg-reconfigure -plow unattended-upgrades   # security updates automáticos

# --- Docker (repo oficial; o docker.io do Debian é velho demais pro compose v2) ---
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/debian/gpg | \
  sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
https://download.docker.com/linux/debian $(. /etc/os-release && echo $VERSION_CODENAME) stable" | \
  sudo tee /etc/apt/sources.list.d/docker.list
sudo apt-get update
sudo apt-get -y install docker-ce docker-ce-cli containerd.io \
                        docker-buildx-plugin docker-compose-plugin
sudo usermod -aG docker "$USER" && newgrp docker

# --- diretórios ---
sudo mkdir -p /etc/nora /srv/nora/backups /opt/nora
sudo chown "$USER":"$USER" /opt/nora
sudo chmod 700 /srv/nora/backups

# --- SOPS ---
SOPS_VER=3.9.4
curl -fsSLo /tmp/sops "https://github.com/getsops/sops/releases/download/v${SOPS_VER}/sops-v${SOPS_VER}.linux.amd64"
sudo install -m 0755 /tmp/sops /usr/local/bin/sops

# --- firewall: nada de inbound além de SSH da LAN ---
sudo apt-get -y install ufw
sudo ufw default deny incoming && sudo ufw default allow outgoing
sudo ufw allow from 192.168.0.0/16 to any port 22 proto tcp
sudo ufw --force enable

# --- código ---
git clone https://github.com/sf0rzin/nora.git /opt/nora
```

> O `ufw` é redundante com "não publicar portas", e é justamente por isso que vale: protege contra o
> `-p 0.0.0.0:...` que alguém vai adicionar por engano num debug às 2h da manhã.

Limitar o journald e o log do Docker (o compose já define `max-size: 20m` / `max-file: 5` por
container, mas o daemon precisa do default também):

```bash
echo '{ "log-driver": "json-file", "log-opts": { "max-size": "20m", "max-file": "5" } }' | \
  sudo tee /etc/docker/daemon.json
sudo systemctl restart docker
```

### 3. Gerar a chave age e cifrar os segredos

**Na máquina do operador** (não no host — a chave privada é gerada uma vez e copiada):

```bash
age-keygen -o age.key
# Public key: age1xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

Guardar a **chave privada** em dois lugares offline (gerenciador de senhas + mídia física). Ela é
o único material que decifra tudo — perdê-la significa recriar **todos** os segredos.

Instalar no host:

```bash
scp age.key nora-prod:/tmp/age.key
ssh nora-prod 'sudo mv /tmp/age.key /etc/nora/age.key && \
               sudo chown root:root /etc/nora/age.key && \
               sudo chmod 400 /etc/nora/age.key'
```

Declarar a chave pública em `infra/proxmox/.sops.yaml` (versionado):

```yaml
creation_rules:
  - path_regex: secrets\.env\.sops$
    age: age1xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

Montar o arquivo de segredos a partir do `.env.example` da stack e cifrar:

```bash
cd infra/proxmox
cp secrets.env.example secrets.env      # NUNCA commitar este intermediário
$EDITOR secrets.env                     # preencher os valores
sops --encrypt secrets.env > secrets.env.sops
shred -u secrets.env
git add .sops.yaml secrets.env.sops && git commit -m "chore(infra): segredos cifrados (SOPS+age)"
```

**Dois planos, não um.** Só o que não pode vazar é cifrado; o resto fica em claro e
legível no `env.defaults`. Cifrar configuração não-secreta faz cada troca de tag virar uma
edição de arquivo cifrado com diff ilegível — e faz "perder" valores públicos (foi
exatamente assim que o `CF_ACCESS_AUD` sumiu no Azure). O molde canônico é
`infra/proxmox/secrets.env.example`; o mapa completo das variáveis do compose é o
`.env.example` ao lado.

**Inventário do `secrets.env`** — só segredo (ver ADR 0034 §Segredos: o número **aumenta**
em relação ao Key Vault, porque sem managed identity cada `secretRef` vira valor estático):

```dotenv
# Borda
CLOUDFLARE_TUNNEL_TOKEN=

# Dados
POSTGRES_ADMIN_PASSWORD=            # openssl rand -base64 32
POSTGRES_PLATFORM_ADMIN_PASSWORD=   # OUTRO valor (blast radius, ADR 0022)

# RLS (os TRÊS roles — armadilha 8). Fonte única: alimentam o ALTER ROLE e o .env da API.
NORA_APP_PASSWORD=                  # openssl rand -hex 24
RLS_TELEMETRY_PASSWORD=
NORA_TELEMETRY_DATASOURCE_PASSWORD= # mesmo valor de RLS_TELEMETRY_PASSWORD
SPRING_FLYWAY_PASSWORD=

# Auth
JWT_SECRET=                         # openssl rand -base64 48 (min 32 chars)

# Providers / e-mail
OPENAI_API_KEY=
DEEPSEEK_API_KEY=
GEMINI_API_KEY=
RESEND_API_KEY=

# Integrações (ADR 0031) — só os SECRETS; os *_OAUTH_CLIENT_ID são públicos
NORA_INTEGRATIONS_ENC_KEY=          # AES-256-GCM, 32 bytes BASE64 — ver aviso abaixo
NORA_INTEGRATIONS_STATE_SECRET=     # openssl rand -hex 32
GOOGLE_OAUTH_CLIENT_SECRET= ...     # (slack, github, notion, todoist, linear, ms)
NORA_TELEGRAM_BOT_TOKEN=
TRELLO_API_KEY=

# Control plane (ADR 0022/0023/0024) — dois valores DISTINTOS
NORA_PLATFORM_INTERNAL_TOKEN=       # openssl rand -hex 32
NORA_PLATFORM_ADMIN_TOKEN=          # OUTRO openssl rand -hex 32

# Observabilidade
GRAFANA_ADMIN_PASSWORD=             # openssl rand -base64 24
```

**Fica FORA do arquivo cifrado** (plano não-secreto, `env.defaults`): `NORA_PUBLIC_DOMAIN`,
`NORA_ENV`, `POSTGRES_ADMIN_USER`, `DATASOURCE_USERNAME`, as URLs JDBC, `NORA_RLS_ENFORCE`,
`NORA_EMAIL_FROM`, os `*_OAUTH_CLIENT_ID`, `NORA_PLATFORM_ENABLED`, **`CF_ACCESS_AUD` e
`CF_ACCESS_TEAM_DOMAIN`** (identificadores públicos — armadilha 5) e as tags de rollout
(`API_TAG`/`WORKER_TAG`/`WEB_TAG`/`ADMIN_TAG`, que o `deploy.sh --tag` sobrescreve).

> **Nenhum valor pode ser a string `unset`.** Herança do Bicep, que gravava `'unset'` no
> Key Vault quando um secret chegava vazio. Vazio é seguro; `unset` é fatal.

> **`NORA_INTEGRATIONS_ENC_KEY`:** nunca colocar a string `unset` nem valor não-base64. O
> `TokenCipher` valida base64 e **derruba o boot** — foi o incidente de 2026-06-11 com
> KV-reference. Gerar com `openssl rand -base64 32`.

Rotação de chave age (política do ADR 0016 Gap 7, agora sem Key Vault):

```bash
# adiciona a nova pública em .sops.yaml, depois:
sops updatekeys secrets.env.sops
```

### 4. Cloudflare Tunnel + Access Applications

O `TUNNEL_TOKEN` do compose implica **túnel gerenciado remotamente**: a configuração de hostnames
vive no painel/API da Cloudflare, não num `config.yml` local.

1. **Criar o túnel** (Zero Trust → Networks → Tunnels → Create a tunnel → *Cloudflared*), nome
   `nora-prod`. Copiar o **connector token** para `CLOUDFLARE_TUNNEL_TOKEN`.

2. **Public hostnames** — os quatro apontam para o **mesmo** serviço, porque quem faz o roteamento
   por Host é o Caddy:

   | Hostname | Service |
   |---|---|
   | `nora.systems` | `http://caddy:80` |
   | `www.nora.systems` | `http://caddy:80` |
   | `api.nora.systems` | `http://caddy:80` |
   | `admin.nora.systems` | `http://caddy:80` |
   | `grafana.nora.systems` | `http://caddy:80` |

   Adicionar um hostname cria automaticamente o CNAME **proxied** para
   `<tunnel-id>.cfargotunnel.com`. **Não crie os hostnames públicos ainda** se quiser verificar
   antes do cutover de DNS — ver §Verificar.

3. **Access Applications** (Zero Trust → Access → Applications):

   | Aplicação | Política | Observação |
   |---|---|---|
   | `admin.nora.systems` | allowlist de e-mail + OTP/SSO | já existe (ADR 0025); reusar o **AUD** |
   | `grafana.nora.systems` | allowlist de e-mail + OTP/SSO | **NOVA** — o Grafana passou a ter rota pública; sem Access ele fica exposto atrás de uma senha só |

   O apex, `www` e `api` ficam **públicos** (são o produto).

4. **`CF_ACCESS_AUD`** = AUD tag da Access App do admin. Se estiver reaproveitando a do Azure, o
   valor não muda. Cadastrar no `secrets.env` (armadilha 5).

> **Cuidado herdado do ADR 0025:** o workflow `cloudflare-setup.yml` é dono da Access App/Policy/IdP
> e **deve rodar sem `admin_hostname`** — com o parâmetro, ele sobrescreve o CNAME do túnel.

### 5. Primeiro deploy

```bash
cd /opt/nora/infra/proxmox
./scripts/deploy.sh --platform --tag sha-xxxxxxx
```

O `deploy.sh` faz, em ordem: decifra `secrets.env.sops` (SOPS + age) para um `.env` em
**tmpfs** (`/dev/shm` — ele **recusa** rodar se `/dev/shm` não for tmpfs, para não encostar
segredo no disco) → `docker compose pull` → `up -d --wait --no-deps` **serviço a serviço**,
na ordem de dependência, respeitando o healthcheck → em falha de health, **rollback
automático** para a tag anterior. O `.env` é sobrescrito e apagado no trap EXIT.

Flags que importam:

| Flag | Uso |
|---|---|
| `--tag sha-xxxxxxx` | tag das imagens de app a subir |
| `--service api,web` | só estes serviços (repetível ou lista) |
| `--if-changed` | só faz deploy se o digest remoto diferir — é o que o timer systemd chama |
| `--rollback` | volta os serviços selecionados para a tag anterior registrada no estado |
| `--no-rollback` | em falha de health, deixa quebrado para debug |
| `--dry-run` | mostra o que faria |

O estado de rollout (tag atual, tag anterior, digest, timestamp) fica em
`/srv/nora/state/deploy-state.env` — é ele que torna o rollback possível sem você ter
anotado nada.

Manual, quando precisar depurar o script:

```bash
sops --decrypt --input-type dotenv --output-type dotenv secrets.env.sops > /dev/shm/nora.env
chmod 600 /dev/shm/nora.env
docker compose -p nora --env-file ./env.defaults --env-file /dev/shm/nora.env \
  --profile platform up -d --wait
shred -u /dev/shm/nora.env
```

> **Dois planos de configuração, de propósito.** `env.defaults` (não-secreto: domínio, tags
> de imagem, toggles, retenção) e `secrets.env.sops` (só o que não pode vazar). O compose
> aceita `--env-file` repetido e o **último vence**. Cifrar as tags de imagem faria cada
> rollout virar uma edição de arquivo cifrado com diff ilegível — ver o cabeçalho de
> `secrets.env.example`.

> **Não** suba tudo de uma vez se for restaurar dados. Ver o passo seguinte.

### 6. Restaurar os dados vindos do Azure

**Ordem obrigatória.** Se a API subir antes do restore, o Flyway cria um schema virgem e o
`pg_restore` colide com ele.

```bash
# 1) SÓ os bancos. O initdb roda aqui (volume vazio) e cria os roles do RLS.
docker compose -p nora --env-file ./env.defaults --env-file /dev/shm/nora.env \
  up -d postgres postgres-platform
docker compose -p nora exec postgres pg_isready -U nora_admin -d nora

# 2) Copiar os dumps (formato custom, -Fc — ver azure-decommission.md §1)
docker cp nora.dump          nora-postgres:/tmp/nora.dump
docker cp nora_platform.dump nora-postgres-platform:/tmp/nora_platform.dump

# 3) Restaurar. --no-owner/--no-acl porque os roles do Azure (azure_pg_admin,
#    azuresu) não existem aqui e fariam o restore cuspir erro em cada objeto.
docker compose -p nora exec postgres \
  pg_restore -U nora_admin -d nora --no-owner --no-acl --exit-on-error -v /tmp/nora.dump
docker compose -p nora exec postgres-platform \
  pg_restore -U nora_admin -d nora_platform --no-owner --no-acl --exit-on-error -v /tmp/nora_platform.dump
```

**4) Reaplicar os GRANTs (obrigatório — armadilha 2).** `ALTER DEFAULT PRIVILEGES` só vale para
objetos criados **depois**; as tabelas restauradas já existiam no dump e chegam **sem** as permissões
do `nora_app` / `nora_telemetry`:

```bash
docker compose -p nora exec -T postgres psql -U nora_admin -d nora \
  < ../../services/api/src/main/resources/db/operational/R001__provision_app_roles.sql
```

**5) Conferir antes de subir o resto:**

```bash
docker compose -p nora exec postgres psql -U nora_admin -d nora -c "
  select count(*) as tenants from tenants;
  select count(*) as meetings from meetings;
  select count(*) as transcripts from transcripts;
  select max(installed_rank) as flyway_rank, max(version) as flyway_version
    from flyway_schema_history where success;"
```

O `flyway_schema_history` **vem no dump**. Se a versão bater com a do repo, o Flyway do boot não vai
reaplicar nada — é assim que se sabe que o restore está íntegro. Se estiver **atrás**, o Flyway vai
migrar no primeiro boot da API (esperado); se estiver **à frente**, pare: o dump é de um código mais
novo que a imagem.

```bash
# 6) Agora sim, a stack inteira.
./scripts/deploy.sh --platform --tag sha-xxxxxxx
```

> **Só faça isto se estiver recuperando de um backup.** No primeiro deploy o banco nasce vazio e
> não há nada a restaurar. Quando for o caso, os passos 1 a 5 acima estão automatizados em
> `./scripts/restore-into-proxmox.sh --from-dir <dir-de-backup> --sops` (os dumps que o serviço
> `backup` gera em `$BACKUP_DIR`), que cria os roles antes dos dados, restaura com `--no-owner
> --no-privileges` e aplica o R001 **depois**. Use o script; a sequência manual acima é o que ele
> faz, para quando algo falhar no meio.

### 7. Verificar

**Antes de tocar no DNS.** O Caddy não publica porta, então a verificação é de dentro da rede
`edge`, com o Host header certo:

```bash
for h in nora.systems api.nora.systems admin.nora.systems grafana.nora.systems; do
  printf '%-26s ' "$h"
  docker run --rm --network nora_edge curlimages/curl:8.11.1 \
    -s -o /dev/null -w '%{http_code}\n' -H "Host: $h" http://caddy/
done
```

Esperado: `200` no apex, `200` na api (ou `401`/`404` conforme a rota raiz), `302`/`403` no admin
(Access não está no caminho aqui — o gate é na borda), `200`/`302` no grafana.

Health de cada serviço:

```bash
docker compose -p nora ps --format 'table {{.Service}}\t{{.Status}}'
docker compose -p nora exec api    wget -qO- http://localhost:8080/actuator/health
docker compose -p nora exec worker python -c \
  "import urllib.request;print(urllib.request.urlopen('http://localhost:8001/healthz').read())"
```

Egress (armadilha 3) e observabilidade:

```bash
docker compose -p nora exec worker python -c \
  "import urllib.request;print(urllib.request.urlopen('https://api.openai.com/v1/models',timeout=5).status)"

# a API está mesmo emitindo? (falha se o javaagent não foi trocado — armadilha 9)
docker compose -p nora exec prometheus wget -qO- \
  'http://localhost:9090/api/v1/query?query=up{job="nora-api"}' | jq '.data.result'

# logs chegando no Loki?
docker compose -p nora exec loki wget -qO- \
  'http://localhost:3100/loki/api/v1/label/container/values' | jq
```

RLS (armadilha 8) e segredos:

```bash
docker compose -p nora exec postgres psql -U nora_admin -d nora -c \
  "select rolname, rolbypassrls from pg_roles where rolname like 'nora_%';"
docker compose -p nora --profile platform exec admin printenv CF_ACCESS_AUD   # não pode ser vazio
```

**Só depois disso** crie os public hostnames no túnel (§4.2) — esse é o cutover de DNS. A ordem
completa de corte, incluindo o que fazer na Azure antes e depois, está em
[`azure-decommission.md`](azure-decommission.md).

---

## Operações comuns

### Rollout de uma versão nova

Tags imutáveis `sha-<short>` são o mecanismo. `latest` serve para bootstrap; **não** serve para
rollout, porque não dá alvo de rollback.

```bash
cd /opt/nora && git pull
./infra/proxmox/scripts/deploy.sh --platform --tag sha-a1b2c3d
```

A tag anterior **não precisa ser anotada**: o `deploy.sh` grava `<SVC>_PREV_TAG` em
`/srv/nora/state/deploy-state.env` antes de trocar. Só um serviço:

```bash
./infra/proxmox/scripts/deploy.sh --service api --tag sha-a1b2c3d
```

<a id="rolling-update"></a>

### Rolling update — a janela de ~45s

**Não existe rolling update de verdade nesta stack.** `docker compose up -d` **derruba o container
antigo antes de subir o novo** — não há o readiness gate que o `activeRevisionsMode: Single` do
Container Apps dava. O boot do Spring com Flyway leva ~30s (o healthcheck da `api` usa
`start_period: 45s` e `retries: 12`, herdados do `failureThreshold: 12` do Bicep).

Resultado prático: **~45s por deploy da API em que a origem não responde.**

Mitigação — não eliminação — no `caddy/Caddyfile`:

```caddyfile
api.{$NORA_PUBLIC_DOMAIN} {
    reverse_proxy api:8080 {
        lb_try_duration 60s      # segura e reenvia enquanto a origem volta
        lb_try_interval 500ms
        fail_duration    0s      # não marca a única origem como down
        health_uri       /actuator/health
        health_interval  5s
    }
}
```

O que isso **não** resolve, e é preciso saber:

- requisições que estouram o `lb_try_duration` **falham** (504 em vez de 502 — ainda é erro);
- conexões **em voo** no momento do `stop` são cortadas;
- **SSE / streaming do chat** quebra: o buffer de retry não reconstrói um stream já iniciado;
- uploads longos são perdidos e precisam ser refeitos.

**Prática recomendada:** deploy da API em janela de baixo tráfego; deploy de `web`/`admin` (boot
curto) a qualquer hora. Um deploy só do `worker` não afeta o usuário — a API degrada com erro
controlado.

Deploy de um serviço só:

```bash
./infra/proxmox/scripts/deploy.sh --service web --tag sha-a1b2c3d
```

### Ver logs

```bash
docker compose -p nora logs -f --tail=200 api
# histórico e busca: Grafana → Explore → Loki
#   {container="nora-api"} |= "ERROR"
```

### Conectar no Postgres

Sem exposição de rede: as portas estão em `127.0.0.1`.

```bash
ssh -L 15432:127.0.0.1:5432 nora-prod          # primário
ssh -L 15433:127.0.0.1:5433 nora-prod          # plataforma
psql "host=127.0.0.1 port=15432 dbname=nora user=nora_admin"     # SEM sslmode (armadilha 1)
```

Direto no host: `docker compose -p nora exec postgres psql -U nora_admin -d nora`.

### Backup manual sob demanda

O serviço `backup` roda de hora em hora (`BACKUP_INTERVAL_SECONDS`, retenção
`BACKUP_RETENTION_DAYS=14`) e grava em `/srv/nora/backups`. Para forçar agora:

```bash
docker compose -p nora exec backup /usr/local/bin/run-backup.sh --once
ls -lh /srv/nora/backups | tail
```

> **Backup no mesmo host não é backup.** Sincronize `/srv/nora/backups` para fora da VM (o job do
> Proxmox Backup Server cobre o disco inteiro; um `rclone`/`rsync` para destino externo cobre o caso
> "o Proxmox pegou fogo"). Enquanto os dumps só existirem na VM, o RPO real de uma perda de host é
> **o último snapshot do PBS**, não a última hora.

### Flip do RLS enforce

Inalterado no design (ADR 0026/0028). O que muda é o endpoint: onde o
[`rls-cutover-runbook.md`](rls-cutover-runbook.md) diz
`nora-pg-dev-wgl3a3.postgres.database.azure.com`, leia `postgres` (dentro da rede `data`), e o
provisionamento dos roles roda pelo `psql` local em vez do workflow `rls-cutover.yml` — que
dependia de firewall rule do runner e OIDC, e não se aplica mais.

---

## Rollback

Três níveis. Escolha pelo que quebrou, não pelo que é mais rápido.

### Nível 1 — rollback de aplicação (imagem)

Serve para bug de código. **Segundos a um minuto.**

```bash
./infra/proxmox/scripts/deploy.sh --service api --rollback
```

O `--rollback` lê `API_PREV_TAG` do `/srv/nora/state/deploy-state.env` — gravado
automaticamente no rollout anterior. É por isso que `latest` é **proibido** no rollout: sem
tag imutável não há alvo de rollback, e o estado não teria o que registrar.

Se o próprio `up --wait` falhar no health, o `deploy.sh` **já faz esse rollback sozinho**
(a menos de `--no-rollback`).

### Nível 2 — rollback de schema (NÃO existe automático)

> **Aviso.** Flyway é **forward-only** (`standards.md` §6: "migration nunca é editada depois de
> aplicada"). Voltar a imagem **não volta o schema**. Se a migration nova for destrutiva
> (`DROP COLUMN`, mudança de tipo com perda), a imagem antiga vai encontrar um banco que ela não
> entende — e o rollback de Nível 1 **não resolve**; pode piorar.

Nesse caso o rollback é **restore de dado**:

```bash
docker compose -p nora stop api web admin worker
docker compose -p nora exec postgres \
  psql -U nora_admin -d postgres -c 'drop database nora; create database nora;'
docker compose -p nora exec postgres \
  pg_restore -U nora_admin -d nora --no-owner --no-acl --exit-on-error /backups/nora-<TS>.dump
docker compose -p nora exec -T postgres psql -U nora_admin -d nora \
  < services/api/src/main/resources/db/operational/R001__provision_app_roles.sql
# só então: API_TAG de volta para a anterior + deploy
```

Perde-se tudo que foi escrito desde o dump (**até 1 hora** — o RPO real desta stack, ADR 0034
§Disponibilidade). Toda migration destrutiva deve, por isso, ser precedida de um backup manual.

### Nível 3 — rollback de host (snapshot do Proxmox)

Só para quebra de **host** (upgrade de kernel, Docker corrompido, disco). Reverter o snapshot
**descarta todos os dados escritos desde ele** — inclusive os dumps em `/srv/nora/backups`.

```
Proxmox → nora-prod → Snapshots → selecionar → Rollback
```

**Antes de reverter, copie `/srv/nora/backups` para fora da VM.** Sem isso você troca um problema de
host por perda de dado.

---

## Restore drill

**Trimestral**, herdado do ADR 0016 Gap 3. O que muda: antes o RTO era garantido pelo PITR do
Flexible Server; agora ele é **um procedimento manual**. Um RTO nunca medido é um chute.

1. **Clonar** `nora-prod` no Proxmox (Full Clone) como `nora-drill`.
2. **Isolar antes de ligar** — dois cuidados, nessa ordem:
   - mover a NIC para uma bridge sem uplink (ou vlan isolada);
   - **remover `CLOUDFLARE_TUNNEL_TOKEN` do `.env` do clone.** Se o clone subir com o token, ele
     registra um **segundo connector no mesmo túnel** e a Cloudflare passa a balancear tráfego de
     produção entre a VM real e o drill. Este é o erro mais perigoso do procedimento.
3. **Cronometrar a partir daqui.** Apagar os volumes e restaurar do backup mais recente seguindo
   §Restaurar os dados (passos 1 a 5).
4. **Smoke** com o checklist de §Verificar: contagens de `tenants`/`meetings`/`transcripts` batendo
   com a produção, `flyway_schema_history` na versão esperada, os três roles corretos, login
   funcionando.
5. **Parar o cronômetro. Registrar** na tabela abaixo: data, tamanho do dump, RTO medido, e o que
   deu errado (sempre há algo).
6. **Destruir** o clone.

| Data | Dump | RTO medido | Achados |
|---|---|---|---|
| _(pendente — primeiro drill até 30 dias após o go-live)_ | | | |

> Se o RTO medido passar de 2h (a meta do ADR 0016 Gap 3), a meta está errada ou o procedimento
> está. **Corrija um dos dois no mesmo dia** — não deixe a divergência documentada e viva.

---

## Histórico

| Data | Mudança |
|---|---|
| 2026-08-07 | v1.0 — runbook criado com o ADR 0034. Substitui `azure-deploy.md`. Cobre provisionamento da VM, bootstrap, SOPS+age, Cloudflare Tunnel/Access, primeiro deploy, restore vindo da Azure, verificação, 9 armadilhas do self-hosted, rollback em 3 níveis e restore drill trimestral. |
| 2026-08-07 | v1.1 — reconciliação com os arquivos reais de `infra/proxmox/`: nomes corretos (`postgres/init/01-roles-and-db.sql`, `R001__provision_app_roles.sql`), flags reais do `deploy.sh` (`--platform`, `--tag`, `--service`, `--rollback`, `--if-changed`) no lugar de `--profile platform` e da edição manual de `API_TAG`, estado de rollout em `/srv/nora/state/deploy-state.env`, tmpfs em `/dev/shm`, e separação dos dois planos de configuração (`env.defaults` vs. `secrets.env.sops`) no inventário de segredos. Referência ao `restore-into-proxmox.sh`. |
