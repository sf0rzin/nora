# `observability/` — a stack que substitui Log Analytics + Application Insights

Referenciada por `otel-collector.yaml` e pelo ADR 0034. Tudo aqui é montado `:ro` pelos
serviços de observabilidade do `infra/proxmox/docker-compose.yml`, que é a fonte da
verdade sobre portas, redes e imagens.

| arquivo | serviço | substitui |
|---|---|---|
| `otel-collector.yaml` | `otel-collector` | o `applicationinsights-agent` como ponto de coleta de traces/métricas |
| `prometheus.yml` | `prometheus` | Metrics Explorer / Live Metrics + a Query REST API (KQL) |
| `loki.yaml` | `loki` | o Log Analytics Workspace (tabela `ContainerAppConsoleLogs_CL`) |
| `config.alloy` | `alloy` | o `appLogsConfiguration` do Container Apps Environment |
| `grafana/provisioning/**` | `grafana` | os blades de gráfico e as consultas salvas do portal |

## As duas pernas (uma não substitui a outra)

```
  api (javaagent OTel)  ──OTLP:4317──┐
  worker / web / admin  ──(nada)─────┤
                                     ├──> otel-collector ──remote-write──> prometheus ──┐
                                     │         └── traces ──> descartados (não há Tempo) │
                                     │                                                   ├──> grafana
  TODO container ──stdout──> docker.sock ──> alloy ──push──> loki ────────────────────────┘
```

O Collector é **write-only e não faz tail de stdout**. Sem o Alloy, três dos quatro apps
não teriam sinal nenhum. Por isso são dois serviços, não um.

## Cobertura real, por app

Esta é a parte honesta do documento. **Não há paridade com o App Insights.**

| | logs | métricas | traces |
|---|---|---|---|
| **api** (Java) | sim (Alloy) | sim — `opentelemetry-javaagent` 2.30.0 | emitidos e **descartados** |
| **worker** (Python) | sim (Alloy) | **não** | **não** |
| **web** (Next.js) | sim (Alloy) | **não** | **não** |
| **admin** (Next.js) | sim (Alloy) | **não** | **não** |
| infra (caddy, cloudflared, pg, ...) | sim (Alloy) | parcial — ver pendências | n/a |

**A API é o único serviço instrumentado.** O `docker-compose.yml` seta
`OTEL_SERVICE_NAME` e `OTEL_EXPORTER_OTLP_ENDPOINT` nos quatro apps, mas **env var sozinha
não instrumenta nada**: worker, web e admin não têm SDK OTel instalado. As variáveis estão
lá para que instalar o SDK um dia seja só adicionar a dependência — hoje elas são inertes.

Consequência prática: para worker, web e admin não existe taxa de requisição, latência p95
nem taxa de erro *de verdade* em lugar nenhum desta stack. O que existe é (a) volume de
log como sinal de "está vivo", (b) `caddy_reverse_proxy_upstreams_healthy` como sinal de
"está aceitando conexão" — e (b) depende de uma pendência aberta, ver abaixo.

### O que se perdeu do App Insights e não voltou

- **Transaction Search / Application Map / end-to-end transaction details.** Não há backend
  de traces no compose (nem Tempo nem Jaeger). Os spans chegam no Collector e vão para o
  exporter `debug`. A pipeline existe só para os apps não encherem o log com erro de
  export. Isso é **regressão de funcionalidade**, não paridade.
- **Correlação log ↔ trace.** Exigiria `trace_id` no MDC do logback, um datasource de
  traces e `derivedFields` no datasource Loki. Nenhum dos três existe.
- **CPU / memória / restarts por container** (o painel "Metrics" do Container Apps). Não há
  cAdvisor nem o receiver `docker_stats` ligado. O dashboard **não inventa** essas queries
  de propósito: `container_cpu_usage_seconds_total` não existe aqui e o painel ficaria
  vazio. Ver a nota no fim do `prometheus.yml`.
- **Consultas KQL salvas.** A linguagem passa a ser PromQL e LogQL. Nada é portável
  automaticamente; toda consulta é reescrita à mão.

### O que já foi migrado (não está pendente)

- O `services/api/Dockerfile` **já** troca o `applicationinsights-agent` pelo
  `opentelemetry-javaagent.jar` (`ARG OTEL_AGENT_VERSION=2.30.0`, linhas 30-33 e 49). Isso
  era obrigatório: o agent do App Insights exporta para o endpoint Breeze da connection
  string e **ignora** `OTEL_EXPORTER_OTLP_ENDPOINT`.
- O caminho de **leitura** de telemetria já foi reescrito: `PrometheusHealthSource.java`
  substitui o `AppInsightsHealthSource`, que fazia `GET api.applicationinsights.io/v1/apps/{id}/query`
  com KQL e header `x-api-key`. O compose aponta `NORA_PLATFORM_HEALTH_PROMETHEUS_URL`
  para `http://prometheus:9090`.

> Os dois estão feitos **no fonte**. O que roda é a imagem do GHCR: se `API_TAG` apontar
> para um build anterior à troca, o container ainda carrega o agent antigo e o dashboard
> da API fica em "No data" sem um único erro. Confirme com
> `docker compose exec api sh -c 'echo $JAVA_TOOL_OPTIONS'`.

## Pendências que fazem a stack mentir em silêncio

Nenhuma destas gera erro. Todas produzem painel vazio ou perda de dado sem aviso.

1. **Scrape do Caddy não funciona.** O `Caddyfile` tem `admin localhost:2019` (inalcançável
   de fora do container) e não tem `servers { metrics }` (as `caddy_http_*` são opt-in
   desde o Caddy 2.7). Com as duas pendentes, seis painéis de borda ficam em "No data" —
   justamente os únicos que enxergam web e admin. Tornar o endpoint alcançável já acende
   os dois painéis de `caddy_reverse_proxy_upstreams_healthy` (esse gauge não depende do
   opt-in), que são a **única** métrica por serviço que cobre web/admin. É o maior ganho
   por linha alterada da stack. Detalhe e correção sugerida — sem expor a admin API — no
   job `caddy` do `prometheus.yml`.
2. **Scrape do cloudflared não funciona.** O `cloudflared` está só na rede `edge` e o
   `prometheus` só na `internal`; não há rota. Correção de uma linha no compose
   (`networks: [edge, internal]`). Sem isso, `cloudflared_tunnel_ha_connections` — o sinal
   que detecta exatamente o 522 que derrubou `nora.systems` — nunca é coletado.
3. **O WAL do Alloy é volátil.** O compose passa `--storage.path=/var/lib/alloy/data` mas
   não monta volume nesse caminho. O argumento "Alloy tem WAL, logo backpressure vira
   atraso e não perda" vale para o **Loki cair**, mas **não** para recriação do container
   do Alloy: `--force-recreate` apaga WAL e posições de leitura. Ver o cabeçalho do
   `config.alloy`.

## Retenção: três números que têm que andar juntos

O `retentionInDays: 30` do Log Analytics vira **três** configurações em lugares diferentes:

| onde | como | arquivo |
|---|---|---|
| Prometheus | `--storage.tsdb.retention.time=30d` | `docker-compose.yml` (flag, **não** o `prometheus.yml`) |
| Loki | `limits_config.retention_period: 720h` | `loki.yaml` |
| Loki (executor) | `compactor.retention_enabled: true` + `delete_request_store: filesystem` | `loki.yaml` |

**A terceira linha é a que ninguém lembra.** `retention_period` sozinho é uma declaração
de política — quem executa é o compactor, e sem `retention_enabled: true` ele ignora a
política inteira. O Loki sobe, responde `/ready`, ingere e consulta normalmente; só que
nada nunca é apagado. Em `filesystem` não há lifecycle policy de bucket como rede de
segurança: se o compactor não apagar, ninguém apaga. Você descobre quando o disco da VM
enche, o Postgres não consegue mais escrever WAL e a stack cai por causa de log velho.

Confirme pela série, não pelo arquivo:

```bash
docker compose exec prometheus wget -qO- \
  'http://localhost:9090/api/v1/query?query=loki_boltdb_shipper_compact_tables_operation_last_successful_run_timestamp_seconds'
# o timestamp tem que avançar a cada ~10min (compaction_interval)
```

## Verificação rápida

```bash
# 1. o collector está de pé e recebendo?
docker compose exec otel-collector wget -qO- http://localhost:13133/
docker compose exec prometheus wget -qO- \
  'http://localhost:9090/api/v1/query?query=otelcol_receiver_accepted_metric_points_total'

# 2. o remote-write está chegando? (0 aqui = dashboard da API vazio)
docker compose exec prometheus wget -qO- \
  'http://localhost:9090/api/v1/query?query=otelcol_exporter_send_failed_metric_points_total'

# 3. quais serviços realmente emitem métrica
docker compose exec prometheus wget -qO- \
  'http://localhost:9090/api/v1/label/job/values'
# esperado hoje: prometheus, otel-collector, loki, alloy, grafana, nora-api

# 4. o Alloy está perdendo linha?
docker compose exec prometheus wget -qO- \
  'http://localhost:9090/api/v1/query?query=loki_write_dropped_entries_total'

# 5. os quatro apps estão logando?
docker compose exec loki wget -qO- \
  'http://localhost:3100/loki/api/v1/label/service/values'
```

Se **todos** os painéis do dashboard estiverem vazios, o problema não é app: é coleta.
Comece pelo passo 1.

## Convenções que amarram os arquivos

- **`service.name` vira o label `job`.** O exporter `prometheusremotewrite` traduz o
  resource attribute `service.name` para `job` — por isso as queries do dashboard filtram
  por `job=~"$job"` e não por `service_name`.
- **No Loki o label equivalente é `service`**, derivado do nome do container pelo
  `config.alloy` e escolhido para bater exatamente com `OTEL_SERVICE_NAME` (`nora-api`,
  `nora-worker`, `nora-web`, `nora-admin`). É o que permite pular de métrica para log sem
  tradução mental. Todo stream leva também `project="nora"`.
- **Os `uid` dos datasources são fixos** (`nora-prometheus`, `nora-loki`). O
  `nora-overview.json` os referencia literalmente; trocar quebra todo painel.
- **Dashboard é read-only na UI** (`allowUiUpdates: false`). Para mudar: editar o JSON e
  redeployar. Edição pela interface viveria só no volume `grafana_data`, fora do git.
