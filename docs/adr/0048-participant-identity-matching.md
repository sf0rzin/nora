# 0048 — Participant identity: deterministic matching over the declared roster

- Status: accepted
- Date: 2026-08-17
- Related: ADR 0046 (reactivates US13), ADR 0012 (PII Shield), ADR 0040 (PII scope is text and
  analysis), ADR 0043 (measured leak rate), ADR 0029 (per-meeting erasure), ADR 0002 (tenant
  isolation), ADR 0007 (AWS-style IAM)

## Context

US13 shipped half of itself. A meeting carries a participant roster and a meeting analysis carries a
`participants` array, but nothing anywhere decides that two entries are **the same person**. The
backlog states the consequence plainly: the same person named two ways is two participants, in a
product whose central claim is understanding a conversation.

Deciding that two names denote one person is not a formatting problem. It is a **person-identity**
feature, and this repository has a PII posture that constrains where such a thing may run. Three
facts about the code decide most of this ADR, and all three were read out of the tree rather than
assumed.

### Fact 1 — the worker never sees a name

`routers/analyze.py` redacts **first**: `pii_shield.redact(req.transcript)` runs, and the analyzers
receive `safe_req`, whose transcript is the redacted text. Both `llm_analyzer.analyze` and
`stub_analyzer.analyze` therefore extract `participants` from a string in which every person name
has already become a `[[PERSON_NAME_n]]` placeholder. The same is true of `/analyze-live` and
`/split`.

### Fact 2 — placeholders cannot be matched, even to themselves

`_redact_person_names` assigns a fresh number to **every occurrence**, and says so: *"Each
occurrence gets a new number (no dedup — explicit scope decision)"*. Two mentions of the identical
string are `[[PERSON_NAME_3]]` and `[[PERSON_NAME_7]]`. So on the worker side of the shield,
participant identity is not merely hard, it is **impossible by construction** — no algorithm,
deterministic or model-based, can join two placeholders it is given no key for. The
`redactions` list does carry an `originalHash` per placeholder, which would support exact-string
grouping, but it is not passed to the analyzers, and a hash of the surface form still cannot tell
that "Ana Paula" and "Ana Paula Silva" are one person.

### Fact 3 — the extracted participants are not persisted

`WorkerDtos.AnalyzeResponse` in `services/api` has no `participants` field. The worker emits the
array, the backend never reads it, and nothing is written. The only participant data NORA stores is
`meeting_participants` (V004): the roster the **user typed into the upload form** — `display_name`,
`email`, `is_internal`.

So the story's premise, "the `Participant` model exists and its persistence exists", is true of two
different models that were never connected. What exists in the database is a declared roster. What
exists in the worker is a placeholder count that goes nowhere.

## Decision

### 1. Matching is deterministic, not model-based

Normalisation plus exact and structural key comparison. No LLM call is made to decide whether two
names denote one person.

The model-based option is better at exactly one thing — nicknames and diminutives, "Zé" for "José",
"Bia" for "Beatriz" — and worse at everything else that matters here. It costs a call per
comparison; it is **not reproducible between runs**, so the same roster can group differently on two
consecutive reads with nothing having changed; and it would send the tenant's real participant names
to a model provider, which is a new PII egress on data that today never leaves the database. A
deterministic rule can be read, tested, and explained to the person it merged.

Reproducibility is what pays for the rest of this ADR: because the same input always yields the same
partition, the result **does not need to be stored** (§4).

### 2. Matching runs on the API side, over the declared roster, and indexes no transcript name

Given facts 1–3, the worker side is not a place where this can be built. Matching runs in
`services/api`, over `meeting_participants`.

**This is a privacy decision and is stated as one, not left as a detail.** NORA builds a
person-identity index over **names its users typed into a form**, and over no other names. It does
not build one over names spoken in a transcript: those are redacted before any component that could
group them ever sees them, and this ADR does not change that ordering, does not move the shield, and
does not add a path that reads a name from raw transcript text. The PII Shield keeps being the first
thing that touches a transcript.

The declared roster was already stored in the clear, already returned by `GET /meetings/{id}`, and
already covered by the tenant isolation and erasure rules that apply to a meeting. Grouping rows
that are already there adds no new class of data — it adds a **relationship** between rows, which is
why §4 is about lifetime rather than about storage.

### 3. Scope: within a meeting and across meetings, within one tenant, never across tenants

Both halves, because the within-meeting half alone would not close the story ("matching across
meetings") and the across-meetings half is where the feature is actually useful: which meetings has
this person been in.

**Matching never crosses tenants.** The property is structural, not a filter that has to be
remembered: the repository query is scoped by `tenant_id`, and the matcher is a pure function of the
list it is handed, with no cache, no static state and no second source. Two tenants that each
declare "Ana Paula Silva" produce two identities that never meet.
`ParticipantIdentityServiceTest.identities_never_cross_the_tenant_boundary` pins it.

The matching rules, in the order they apply:

- **An e-mail is an identifier and outranks every name rule.** Two rows carrying the same e-mail are
  one person even when the names differ.
- **Two rows whose normalised full names are equal are one person.** Normalisation folds case and
  accents, collapses whitespace, drops a leading honorific and drops the pt-BR genitive particles,
  so `Sr. JOSÉ DA SILVA` and `Jose Silva` meet.
- **Two rows sharing a first token and a last token are one person**, when both names have at least
  two tokens: `Ana Paula Silva` and `Ana Silva`. This is the rule the story is named after.
- **A differing e-mail vetoes a name-based merge.** `Ana Silva <ana@a.com>` and `Ana Silva
  <ana@b.com>` stay two identities.
- **A lone first name never absorbs a full name.** `Ana` does not join `Ana Silva`: a bare given
  name identifies nobody, and merging on it would collapse every Ana in the tenant.
- **Initials are not expanded.** `A.P. Silva` does not join `Ana Paula Silva`. Letting one letter
  decide who somebody is has the same shape as the previous rule and the same answer.

**The default is to split, not to merge**, and that is the mirror image of the shield's own default.
`_COMMON_PHRASE_HEADS` documents why an unknown case there fails **towards** redacting: the cost of
over-redacting is one degraded analysis, and the cost the other way is a name reaching a provider.
Here the asymmetry runs the other way. Over-splitting shows one person twice, which is the defect
this story is fixing and is visible and harmless; over-merging **attributes one person's meetings to
another**, which is a privacy failure that looks like a feature working. So an uncertain case is
left as two identities.

Nothing is silently lost to a merge: an identity carries the full sorted list of the declared
`variants` that produced it, so any grouping this makes can be inspected in the response that
returns it.

### 4. Retention: there is no identity table, and that is the answer

**No new table, no new column, no migration.** A participant identity is a **projection** computed
on read from `meeting_participants` rows, not a record with a lifetime of its own.

The reserved migration number V033 is therefore deliberately **not used**. It is a consequence of
this decision, not an omission.

This is the strongest available answer to the retention question, and it is why it was chosen over
the more obvious design of a per-tenant `participant_identities` table with a foreign key on
`meeting_participants`:

- `DELETE /privacy/meetings/{id}` (ADR 0029) hard-deletes a meeting, and the V004 FK cascade already
  purges its `meeting_participants`. The same cascade carries the retention sweep
  (`RetentionSweeper`). An identity that exists only as a grouping of those rows **cannot outlive
  them**: erase the last meeting a person appeared in and the identity ceases to be computable, with
  nothing to wire and nothing that can be forgotten.
- A persisted identity table would have needed an explicit orphan cleanup on both erasure paths,
  because an identity is one-to-many with participants and a row FK cascade does not remove the
  parent. An identity surviving the erasure of every meeting that produced it is a privacy defect,
  and it is the kind that shows up months later.
- It also cannot be stale. There is no reconciliation job, no backfill, and no window in which the
  stored grouping disagrees with the rows.

The identity's public id is stable without being stored: it is `sha256(anchor)` truncated to 16 hex
characters, where the anchor is the identity's e-mail when it has one and its canonical normalised
name otherwise — the same `hashlib.sha256(...)[:16]` idiom `pii_shield._hash` uses. It is stable
across requests, it does not put a name into an identifier, and it changes only when the person's
own canonical form changes.

**What this design gives up, named rather than discovered later:** an identity cannot be corrected.
There is nowhere to record "these two are not the same person" or "these two are", because there is
nothing persisted to record it on. A tenant that hits a wrong merge can only fix the declared names.
Adding a corrections table is a real future story; it would reintroduce exactly the lifetime problem
this section avoids, and it should be decided when someone has actually been merged wrongly rather
than in advance.

### 5. The declared roster is a record and is not rewritten

`GET /meetings/{id}` keeps returning the participants exactly as they were typed. Identity is
derived and is exposed where it helps — the new `GET /meetings/participants`, and the meeting
listing's avatar stack, where two entries for one person are pure noise. The upload path is not
changed and nothing is merged at write time: a fuzzy rule must never be the reason a tenant's own
input comes back different from what they entered.

## Consequences

**Positive**

- The story's stated defect is closed where it is actually fixable, and the reason it is not
  fixable on the other side is now written down instead of being rediscovered.
- No new PII is stored, no new PII egress is created, and the erasure guarantee of ADR 0029 extends
  to participant identity for free.
- The matching rule is one pure function with unit tests, so a disputed merge has an answer.

**Negative / debts**

- `services/nlp-worker` keeps emitting a `participants` array that nothing consumes and that counts
  placeholders. This ADR does not delete it — the field is in
  `meeting-analysis-v1.schema.json` and in the strict JSON Schema sent to the provider, and removing
  it is a contract change that belongs to whoever next versions that schema. Its docstring now says
  what it is worth.
- Matching runs on every read of the identities endpoint rather than once at write time. At the size
  of a tenant's roster this is not measurable; at a size where it were, the fix would be a cache and
  not a table, because §4's guarantee comes from not persisting.
- Nicknames and diminutives are not matched. "Bia" and "Beatriz Souza" stay two identities. This is
  the price of §1 and it is paid knowingly.

## Alternatives Considered

1. **Ask an LLM whether two names denote one person.** Rejected in §1: non-reproducible, priced per
   comparison, and a new egress of real participant names to a provider — on a feature whose entire
   subject matter is identifying people.
2. **Persist a per-tenant `participant_identities` table (migration V033).** The obvious design, and
   the one the task reserved a migration number for. Rejected in §4: it buys correctable identities
   and a stable UUID, and it costs an orphan-cleanup path on both erasure routes, a reconciliation
   problem, and a table of people that can outlive the meetings that produced it. The projection has
   the same output and none of the lifetime.
3. **Match inside the worker, on the redacted text.** Rejected on fact 2: it cannot be done. Every
   occurrence carries a distinct placeholder, so even two identical strings are unjoinable there.
4. **Move participant extraction before the PII Shield so the worker sees real names.** Rejected
   without much deliberation, and recorded because it is the move somebody will propose. It would
   invert ADR 0012's ordering — the shield being first is the whole guarantee — to obtain names that
   §2 shows are already available, in the clear, in the database.
5. **Merge duplicates at upload time.** Rejected in §5: it makes a fuzzy rule destructive and
   unreviewable, and the roster stops being a record of what the user entered.

## History

| Date | Decider | Change |
|---|---|---|
| 2026-08-17 | sys0xFF | Created and accepted. Decides US13's matching strategy: deterministic, on the API side over the declared roster, within a tenant and never across, with the identity as a read-time projection rather than a stored table so that ADR 0029 erasure carries it for free. Records the three facts that constrain it — the worker analyzes already-redacted text, every placeholder occurrence is uniquely numbered so worker-side matching is impossible, and the worker's `participants` array was never consumed by the backend |
