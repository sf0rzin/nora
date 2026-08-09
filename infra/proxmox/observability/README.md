# `observability/` — the stack that replaces Log Analytics + Application Insights

Referenced by `otel-collector.yaml` and by ADR 0034. Everything here is mounted `:ro` by the
observability services of `infra/proxmox/docker-compose.yml`, which is the source of
truth about ports, networks and images.

| file | service | replaces |
|---|---|---|
| `otel-collector.yaml` | `otel-collector` | the `applicationinsights-agent` as the collection point for traces/metrics |
| `prometheus.yml` | `prometheus` | Metrics Explorer / Live Metrics + the Query REST API (KQL) |
| `loki.yaml` | `loki` | the Log Analytics Workspace (`ContainerAppConsoleLogs_CL` table) |
| `config.alloy` | `alloy` | the `appLogsConfiguration` of the Container Apps Environment |
| `grafana/provisioning/**` | `grafana` | the chart blades and the portal's saved queries |

## The two legs (one does not replace the other)

```
  api (javaagent OTel)  ──OTLP:4317──┐
  worker / web / admin  ──(nada)─────┤
                                     ├──> otel-collector ──remote-write──> prometheus ──┐
                                     │         └── traces ──> descartados (não há Tempo) │
                                     │                                                   ├──> grafana
  TODO container ──stdout──> docker.sock ──> alloy ──push──> loki ────────────────────────┘
```

The Collector is **write-only and does not tail stdout**. Without Alloy, three of the four apps
would have no signal at all. That is why there are two services, not one.

## Real coverage, per app

This is the honest part of the document. **There is no parity with App Insights.**

| | logs | metrics | traces |
|---|---|---|---|
| **api** (Java) | yes (Alloy) | yes — `opentelemetry-javaagent` 2.30.0 | emitted and **discarded** |
| **worker** (Python) | yes (Alloy) | **no** | **no** |
| **web** (Next.js) | yes (Alloy) | **no** | **no** |
| **admin** (Next.js) | yes (Alloy) | **no** | **no** |
| infra (caddy, cloudflared, pg, ...) | yes (Alloy) | partial — see open items | n/a |

**The API is the only instrumented service.** The `docker-compose.yml` sets
`OTEL_SERVICE_NAME` and `OTEL_EXPORTER_OTLP_ENDPOINT` on all four apps, but **an env var alone
does not instrument anything**: worker, web and admin have no OTel SDK installed. The variables are
there so that installing the SDK one day is just a matter of adding the dependency — today they are inert.

Practical consequence: for worker, web and admin there is no *real* request rate, p95 latency
or error rate anywhere in this stack. What does exist is (a) log volume as an "it is alive"
signal, (b) `caddy_reverse_proxy_upstreams_healthy` as an "it is accepting connections" signal
— and (b) depends on an open item, see below.

### What was lost from App Insights and did not come back

- **Transaction Search / Application Map / end-to-end transaction details.** There is no traces
  backend in the compose (neither Tempo nor Jaeger). The spans arrive at the Collector and go to the
  `debug` exporter. The pipeline exists only so the apps do not fill the log with export
  errors. This is a **functionality regression**, not parity.
- **Log ↔ trace correlation.** It would require `trace_id` in the logback MDC, a traces
  datasource and `derivedFields` in the Loki datasource. None of the three exists.
- **CPU / memory / restarts per container** (the Container Apps "Metrics" panel). There is
  neither cAdvisor nor the `docker_stats` receiver enabled. The dashboard **deliberately does not invent**
  those queries: `container_cpu_usage_seconds_total` does not exist here and the panel would be
  empty. See the note at the end of `prometheus.yml`.
- **Saved KQL queries.** The language becomes PromQL and LogQL. Nothing is portable
  automatically; every query is rewritten by hand.

### What has already been migrated (it is not pending)

- The `services/api/Dockerfile` **already** swaps the `applicationinsights-agent` for the
  `opentelemetry-javaagent.jar` (`ARG OTEL_AGENT_VERSION=2.30.0`, lines 30-33 and 49). This
  was mandatory: the App Insights agent exports to the Breeze endpoint of the connection
  string and **ignores** `OTEL_EXPORTER_OTLP_ENDPOINT`.
- The telemetry **read** path has already been rewritten: `PrometheusHealthSource.java`
  replaces `AppInsightsHealthSource`, which did `GET api.applicationinsights.io/v1/apps/{id}/query`
  with KQL and an `x-api-key` header. The compose points `NORA_PLATFORM_HEALTH_PROMETHEUS_URL`
  at `http://prometheus:9090`.

> Both are done **in the source**. What runs is the GHCR image: if `API_TAG` points
> to a build earlier than the swap, the container still loads the old agent and the API's
> dashboard sits at "No data" without a single error. Confirm with
> `docker compose exec api sh -c 'echo $JAVA_TOOL_OPTIONS'`.

## Open items that make the stack lie silently

None of these raises an error. All of them produce an empty panel or data loss without warning.

1. **The Caddy scrape does not work.** The `Caddyfile` has `admin localhost:2019` (unreachable
   from outside the container) and does not have `servers { metrics }` (the `caddy_http_*` are opt-in
   since Caddy 2.7). With both pending, six edge panels sit at "No data" —
   precisely the only ones that see web and admin. Making the endpoint reachable already lights up
   the two `caddy_reverse_proxy_upstreams_healthy` panels (that gauge does not depend on the
   opt-in), which are the **only** per-service metric that covers web/admin. It is the biggest gain
   per changed line in the stack. Detail and suggested fix — without exposing the admin API — in the
   `caddy` job of `prometheus.yml`.
2. **The cloudflared scrape does not work.** `cloudflared` is only on the `edge` network and
   `prometheus` only on `internal`; there is no route. A one-line fix in the compose
   (`networks: [edge, internal]`). Without it, `cloudflared_tunnel_ha_connections` — the signal
   that detects exactly the 522 that took `nora.systems` down — is never collected.
3. **Alloy's WAL is volatile.** The compose passes `--storage.path=/var/lib/alloy/data` but
   does not mount a volume at that path. The argument "Alloy has a WAL, so backpressure becomes
   delay and not loss" holds for **Loki going down**, but **not** for recreating the Alloy
   container: `--force-recreate` erases the WAL and the read positions. See the header of
   `config.alloy`.

## Retention: three numbers that have to move together

Log Analytics' `retentionInDays: 30` becomes **three** settings in different places:

| where | how | file |
|---|---|---|
| Prometheus | `--storage.tsdb.retention.time=30d` | `docker-compose.yml` (flag, **not** `prometheus.yml`) |
| Loki | `limits_config.retention_period: 720h` | `loki.yaml` |
| Loki (executor) | `compactor.retention_enabled: true` + `delete_request_store: filesystem` | `loki.yaml` |

**The third line is the one nobody remembers.** `retention_period` on its own is a policy
declaration — the one that executes it is the compactor, and without `retention_enabled: true` it ignores the
whole policy. Loki starts up, answers `/ready`, ingests and queries normally; it is just that
nothing is ever deleted. On `filesystem` there is no bucket lifecycle policy as a safety
net: if the compactor does not delete, nobody deletes. You find out when the VM's disk
fills up, Postgres can no longer write the WAL and the stack goes down because of old logs.

Confirm via the series, not via the file:

```bash
docker compose exec prometheus wget -qO- \
  'http://localhost:9090/api/v1/query?query=loki_boltdb_shipper_compact_tables_operation_last_successful_run_timestamp_seconds'
# o timestamp tem que avançar a cada ~10min (compaction_interval)
```

## Quick verification

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

If **all** the dashboard panels are empty, the problem is not the app: it is collection.
Start at step 1.

## Conventions that tie the files together

- **`service.name` becomes the `job` label.** The `prometheusremotewrite` exporter translates the
  `service.name` resource attribute into `job` — which is why the dashboard queries filter
  by `job=~"$job"` and not by `service_name`.
- **In Loki the equivalent label is `service`**, derived from the container name by
  `config.alloy` and chosen to match `OTEL_SERVICE_NAME` exactly (`nora-api`,
  `nora-worker`, `nora-web`, `nora-admin`). It is what allows jumping from metric to log without
  mental translation. Every stream also carries `project="nora"`.
- **The datasource `uid`s are fixed** (`nora-prometheus`, `nora-loki`). The
  `nora-overview.json` references them literally; changing them breaks every panel.
- **The dashboard is read-only in the UI** (`allowUiUpdates: false`). To change it: edit the JSON and
  redeploy. Editing through the interface would live only in the `grafana_data` volume, outside git.
