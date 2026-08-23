# `observability/` — the stack that replaces Log Analytics + Application Insights

Referenced by `otel-collector.yaml` and by ADR 0034. Everything here is mounted `:ro` by the
observability services of `infra/host/docker-compose.yml`, which is the source of
truth about ports, networks and images.

| file | service | replaces |
|---|---|---|
| `otel-collector.yaml` | `otel-collector` | the `applicationinsights-agent` as the collection point for traces/metrics |
| `prometheus.yml` | `prometheus` | Metrics Explorer / Live Metrics + the Query REST API (KQL) |
| `loki.yaml` | `loki` | the Log Analytics Workspace (`ContainerAppConsoleLogs_CL` table) |
| `config.alloy` | `alloy` | the `appLogsConfiguration` of the Container Apps Environment |
| `grafana/provisioning/dashboards/**` | `grafana` | the chart blades and the portal's saved queries |
| `grafana/provisioning/datasources/**` | `grafana` | the Metrics Explorer / Log Analytics connection |
| `grafana/provisioning/alerting/**` | `grafana` | the metric alerts and action groups |

## The two legs (one does not replace the other)

```
  api (javaagent OTel)  ──OTLP:4317──┐
  worker / web / admin  ──(none)─────┤
                                     ├──> otel-collector ──remote-write──> prometheus ──┐
                                     │         └── traces ──> discarded (no Tempo) │
                                     │                                                   ├──> grafana
  every container ──stdout──> docker.sock ──> socket-proxy ──> alloy ──push──> loki ──────┘
```

`socket-proxy` is the only container in the stack holding `/var/run/docker.sock`. It is an
haproxy with an allow-list: `GET /containers/json`, `GET /containers/{id}/json`,
`GET /containers/{id}/logs`, plus `/_ping` and `/version` for the client's API negotiation, and
every other path and every non-GET method refused. Alloy reaches it over TCP on `docker-api`,
an internal bridge whose only two members are those two containers. The reason is that the Docker API can create a
container with the host root filesystem mounted into it, so the socket is root on the host,
and Alloy — which joins user-produced log lines with a regex — is the container in this stack
most exposed to hostile input. See the service in `../docker-compose.yml`.

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

1. ~~**The Caddy scrape does not work.**~~ **Resolved.** Both preconditions are met in the tree:
   the `Caddyfile` serves the metrics from a dedicated `http://:2021 { metrics /metrics }` site
   block (the admin API stays on `admin localhost:2019`, unauthenticated and able to rewrite the
   whole proxy, which is why it is not the scrape target), and the global `servers { metrics }`
   block turns on the opt-in `caddy_http_*` families. `prometheus.yml` points the `caddy` job at
   `caddy:2021`. If the edge panels are still empty after a deploy, check the two conditions
   separately — target `up`, then presence of `caddy_http_request_duration_seconds` — because they
   fail in different ways and one of them looks like success. Detail in the `caddy` job's comment.
2. ~~**The cloudflared scrape does not work.**~~ **Resolved.** `cloudflared` is on
   `networks: [edge, internal]` in the compose, which is the one-line fix this item asked for;
   `internal` is `internal: true`, so it adds an interface on the inside without widening the
   connector's exposure. `cloudflared_tunnel_ha_connections` — the signal that detects exactly
   the 522 that took `nora.systems` down — is collected, and the
   `nora-tunnel-connections-zero` rule now alerts on it. If the tunnel panel is empty after a
   deploy, check that this service still has both networks: putting it back on `edge` alone
   fails silently in the worst place, because the tunnel keeps working and only the metric
   proving it works disappears.
3. **Alloy's WAL is volatile.** The compose passes `--storage.path=/var/lib/alloy/data` but
   does not mount a volume at that path. The argument "Alloy has a WAL, so backpressure becomes
   delay and not loss" holds for **Loki going down**, but **not** for recreating the Alloy
   container: `--force-recreate` erases the WAL and the read positions. See the header of
   `config.alloy`.

## Alerting: the rules, and the one variable that makes them arrive

Until `grafana/provisioning/alerting/` existed, three files in this directory named "Grafana
unified alerting" as the mechanism (`prometheus.yml`, `loki.yaml`, `datasources.yaml`) and
there was no rule, no contact point and no SMTP anywhere. Everything below detects things this
stack could already see and never said.

| rule | fires on | datasource |
|---|---|---|
| `nora-upstream-unhealthy` | Caddy reports `api`/`web`/`admin`/`grafana` as an unhealthy upstream for 5 min | Prometheus |
| `nora-tunnel-connections-zero` | `cloudflared_tunnel_ha_connections` at 0 for 5 min — the 522 | Prometheus |
| `nora-postgres-unreachable` | no successful Postgres scrape in 5 min | Prometheus |
| `nora-scrape-target-down` | any scrape job `up == 0` for 10 min | Prometheus |
| `nora-edge-5xx-rate` | over 5% of edge responses are 5xx for 10 min | Prometheus |
| `nora-root-disk-low` | under 5 GiB free on the host root filesystem | Prometheus |
| `nora-loki-compactor-stalled` | no successful compaction in 24 h — retention has stopped | Prometheus |
| `nora-backup-not-succeeding` | no `event=dump.ok` line in 3 h | Loki |

**The one variable:** `NORA_ALERT_WEBHOOK_URL`, in the host's secrets file (see
`../secrets.env.example`), injected into the Grafana container by the compose. Any endpoint
that accepts a JSON POST works. **With it empty the rules still evaluate and still fire** —
they are visible under *Alerting → Alert rules* with their state history — and only the
delivery is missing. That ordering is deliberate: the rule is the part that encodes what
"broken" means, and it is the part nobody can write during an incident.

Two of these rules needed a series that did not exist, and both were added to
`otel-collector.yaml` rather than by adding containers: the `hostmetrics` receiver, restricted
to the container's own root mount (**no** host filesystem is mounted into the collector —
read the note there before "improving" it), and the `postgresql` receiver, which is why the
collector is now also on the `data` network. Removing either receiver leaves its rule
evaluating against nothing.

**Host-level failures are a separate path on purpose.** A failed systemd unit —
`nora-deploy`, `nora-offsite-backup`, `nora-restore-drill` — is reported by
`../scripts/notify-failure.sh` reading `NORA_ALERT_WEBHOOK_URL` from `/etc/nora/alerting.env`,
not by Grafana. Grafana is part of the stack, and a deploy failing is a plausible reason for
it not to be running.

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
net: if the compactor does not delete, nobody deletes. You find out when the host's disk
fills up, Postgres can no longer write the WAL and the stack goes down because of old logs.

Confirm via the series, not via the file:

```bash
docker compose exec prometheus wget -qO- \
  'http://localhost:9090/api/v1/query?query=loki_boltdb_shipper_compact_tables_operation_last_successful_run_timestamp_seconds'
# the timestamp has to advance every ~10min (compaction_interval)
```

## Quick verification

```bash
# 1. is the collector up and receiving?
docker compose exec otel-collector wget -qO- http://localhost:13133/
docker compose exec prometheus wget -qO- \
  'http://localhost:9090/api/v1/query?query=otelcol_receiver_accepted_metric_points_total'

# 2. is remote-write arriving? (0 here = empty API dashboard)
docker compose exec prometheus wget -qO- \
  'http://localhost:9090/api/v1/query?query=otelcol_exporter_send_failed_metric_points_total'

# 3. which services actually emit metrics
docker compose exec prometheus wget -qO- \
  'http://localhost:9090/api/v1/label/job/values'
# expected today: prometheus, otel-collector, loki, alloy, grafana, nora-api

# 4. is Alloy dropping lines?
docker compose exec prometheus wget -qO- \
  'http://localhost:9090/api/v1/query?query=loki_write_dropped_entries_total'

# 5. are the four apps logging?
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
