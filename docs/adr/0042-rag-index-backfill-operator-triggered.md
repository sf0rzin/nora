# 0042 — The RAG index gets a backfill path, triggered by an operator

- Status: accepted
- Date: 2026-08-17
- Related: ADR 0024 (control plane: model catalog, cost telemetry, feature flags — the surface this
  lands on), ADR 0012 (why the embedded text is the summary and not the transcript), ADR 0028 (RLS
  enforcement and explicit tenant propagation), ADR 0004 (provider-agnostic embeddings), ADR 0023
  (operator identity and audit), ADR 0038 §5 (reactivates US21, which depends on this)

## Context

Semantic search shipped in PR #206 with migration `V021__create_meeting_embeddings.sql`. One vector
per meeting, generated from the summary snippet, compared with cosine in Java. `GET /meetings/search`
serves it and the chat grounds its answers on it.

`EmbeddingService.index` is called from exactly one place: the end of a successful analysis in
`AnalysisService`. The call is best-effort by design — an embedding failure must never fail an
analysis — so it returns silently when there is no credential and swallows provider errors.

Best-effort at the only write site means the miss is permanent. A meeting analysed before an
embedding credential existed, or while the provider was rate limiting, or before V021 at all, ends
up with a summary in the database and no row in `meeting_embeddings`. Nothing ever comes back for
it. It is invisible to semantic search and to the chat's grounding, with no signal to anyone: the
endpoint returns fewer results, not an error.

The only existing remedy was `POST /meetings/{id}/reprocess`, which re-runs the entire analysis
pipeline — a full LLM call, per meeting — to obtain one embedding that costs a fraction of a cent.

There is a second shape of the same defect, and it is not hypothetical. `EmbeddingRepository`
`findByTenantAndModel` filters by `provider:model`, because vectors from different models are not
comparable. Changing the embedding model therefore empties the index without emptying the table.
`V021`'s own comment says "switching provider requires a re-backfill" — describing a procedure that
did not exist.

## Decision

**A backfill path exists, it is triggered by an operator, and it is bounded.**

### 1. It reads the summary that is already stored; it never re-analyses

The input is `meetings.summary_snippet`, which is byte-for-byte what the live indexing path
consumes. That is the summary produced from the redacted transcript — not the raw transcript and
not the meeting title, which comes unredacted from whoever typed it at upload (ADR 0012). A backfill
is one embedding call per meeting and zero model calls. Changing this input would be a PII decision,
not an implementation detail.

### 2. One query covers both failure shapes

The pending predicate is: the meeting has a summary snippet, and either there is no row in
`meeting_embeddings` or the row's `model` differs from the current one. A missing vector and a
stale-model vector are equally invisible to the search, so they are equally candidates.

### 3. The trigger is an operator endpoint, not a startup hook or a schedule

Four triggers were on the table. The choice is driven by cost, because embedding calls are billed
and a catch-up on a whole deployment is a burst of them.

- **A startup catch-up** — rejected. The first boot after a credential is configured would fire N
  external calls that nobody asked for, at the worst possible moment: a deploy.
- **A scheduled sweep** — rejected for the same reason with less visibility. On a single-host
  deployment with no real users, a cron that spends money while nobody is watching is a bill that
  arrives without an author.
- **A per-meeting endpoint** — rejected as insufficient. The real case is "this whole tenant is
  outside the index", and repairing it one meeting at a time is not a remedy.
- **An operator endpoint — chosen.** It sits in the control plane of ADR 0024, which is where
  operations that cost money already live, and it runs only when a human asks.

### 4. It says what it will do before it does it

`GET /admin/platform/embeddings/backfill` is a preview: per tenant, how many meetings are analysed,
indexed, missing a vector, or carrying a stale model. It is plain SQL — no provider call, nothing
billed, safe to poll — and it does not require the platform database.

`POST` runs it. `tenantId` is required: a run is never implicitly every tenant. `limit` defaults to
25 and is clamped to 100. A run also stops on a 60-second budget or after three consecutive provider
failures, and reports which; a provider that is rate limiting should end the batch, not be hammered
through it. The response reports `remaining` re-measured after the run rather than subtracted, so
"run it again" is a decision made on a fact.

It is idempotent: a meeting already carrying a vector from the current model is not a candidate.

### 5. The cost goes through the telemetry that already exists

`UsageRecorder` (ADR 0024) records it, like every other AI call — no second reporting path. The
`service` dimension separates the two: `embedding` for ordinary indexing and search, and
`embedding-backfill` for an operator run, so a deliberate bulk spend is visible on its own in
`telemetry/cost?groupBy=service`.

The live indexing and search paths were **not** emitting usage events before this change. They are
now. A cost report that only ever showed embeddings during backfills would have been a new false
statement, not a fix.

`promptTokens` is what the provider reported: OpenAI's embeddings endpoint returns
`usage.prompt_tokens` and Gemini's `embedContent` returns nothing, so on Gemini the field is 0 and
means *unknown*, not *free*. We do not estimate tokens from character counts — an invented number in
a cost report is worse than an honest gap, and the event still carries provider, model, tenant,
latency and outcome.

### 6. Two database roles, because the two halves ask different questions

The `POST` writes `meeting_embeddings` through the primary datasource as `nora_app`, which is
NOBYPASSRLS, so it sets the tenant GUC explicitly through `TenantRlsContext` — the operator request
thread never carried one. This is the same mechanism the async analysis pipeline uses (ADR 0028).

The `GET` is cross-tenant by nature and reads through the `nora_telemetry` datasource (BYPASSRLS,
read-only) when it is configured, exactly like the business metrics of ADR 0024. The response
carries which role answered, because under RLS enforce the primary role would return all-zero
counters — a fail-closed result that reads as "there is nothing to do".

### 7. `service.search-embeddings` is deleted, not repurposed

The flag was seeded by `V001` of the platform database and has never had a consumer.
`LlmConfigResolver` reads `service.{service}` only for services with an `llm_config` binding: chat,
analysis, multimodal. Embeddings have no binding and no catalog row — their provider, model and
credential come from `nora.embedding.*` in the environment.

Giving it to the backfill was considered and rejected. A flag the operator must flip before an
operation the operator just requested is friction with no safety gain, and it fails open when the
platform database is unreachable, which makes it not a gate. Extending it over the whole embedding
path was also rejected: the real off-switch already exists and is unambiguous — with no credential,
`EmbeddingClient.isEnabled()` is false and indexing, search and backfill are all no-ops. A second
switch, in a second database, contradicting the first, is not a safety net.

Its seeded value was also false. It sat at `FALSE`, described as "off in the MVP", while the feature
it named had been serving production traffic since V021. Platform migration `V002` deletes the row.

## Consequences

- A meeting that missed indexing is now recoverable without paying for an LLM analysis. Changing
  the embedding model is recoverable by the same mechanism.
- **Nothing happens by itself.** An index only becomes complete because someone ran the backfill.
  This is deliberate, and it means "US15 is merged" still does not imply "the index is complete" —
  US21's trends panel has to state which it is looking at rather than draw a flat line.
- A large catch-up is several runs. With a ceiling of 100 per run, a tenant with 1,000 unindexed
  meetings takes ten calls. That is the intended shape: bounded, observable, interruptible.
- The cost report gains two new `service` values. Any consumer that enumerates services must
  tolerate them; the column is free text and `groupBy=service` already aggregates whatever is there.
- The operator console has no UI for this. It is a `curl` behind Cloudflare Access, like the rest of
  `/admin/platform/**` that `apps/admin` has not surfaced yet.
- Deployments that already ran platform `V001` lose the `service.search-embeddings` row on the next
  boot. Nothing read it, so nothing changes behaviour.

## Alternatives Considered

- **Make indexing not best-effort.** Fail the analysis when the embedding fails, so the miss cannot
  happen. Rejected: it trades a degraded search for a lost analysis, which is the more expensive of
  the two by orders of magnitude, and it does not repair the meetings already missing.
- **Enable `pgvector` and index in the database.** The image is `pgvector/pgvector:pg16` and the
  extension is deliberately never created (ADR 0034 records why the JSON-in-`TEXT` design outlived
  its original Azure reason). It would not have helped: the missing rows are missing whatever the
  column type is. This is a RAG refactor, filed separately.
- **A `--backfill` CLI flag on the API process.** Rejected: it needs a shell on the host, it has no
  audit trail, and it cannot tell the operator what it will do before doing it. The endpoint gets
  the operator's e-mail from `X-Operator-Email` and writes it to `platform_audit_log`.
- **Backfill every tenant in one call.** Rejected: the blast radius of a mistyped request would be
  the entire deployment's embedding bill.
