# 0040 — The PII non-negotiable is scoped to analysis; transcription becomes a declared subprocessor

- Status: accepted
- Date: 2026-08-16
- Related: ADR 0012 (PERSON_NAME strategy in the worker) and ADR 0033 (PII on the chat path) —
  **this ADR supersedes neither**: both controls stay exactly as they are, and what changes is the
  sentence that describes their reach. Also ADR 0039 (the decision that forces this), ADR 0029
  (erasure and retention), ADR 0035 (the argument this ADR is the price of reversing)

## Context

The rule as written today is one line, and it is the strongest sentence the product makes about
itself:

> **PII redaction**: PII never reaches the LLM raw. PIIShield in the worker is the last gate.
> ADR 0012 — `AGENTS.md:80`

The same claim is repeated outward. `docs/product/vision.md:98` promises "PII is detected and
redacted before any external LLM"; `:108` promises redaction "before any submission to the external
AI".

Both ADRs behind that line are about **text**. ADR 0012 covers the worker's `PIIShield` on the
analysis pipeline. ADR 0033 covers the chat path, where structured PII is redacted in the BFF and
PERSON_NAME is explicitly recorded as **accepted residue** until the worker routing lands. The
second one already means the sentence is not literally true today, and ADR 0033 said so in writing
rather than hiding it.

ADR 0035 named the larger hole with precision, and it is worth restating because it is the reason
this ADR exists:

> audio is upstream of all of that: the PII Shield runs in the worker, afterwards, over the already
> transcribed text. Between the microphone and the text, the entire speech — names, numbers,
> whatever is said — has already crossed the internet to an external provider.

ADR 0035 closed that stretch by keeping transcription on the client's machine. **ADR 0039 reopens
it, knowingly**, for the reasons recorded there. From the moment ADR 0039 is implemented, raw
audio — with names, CPFs and figures said out loud — leaves the user's machine before any redaction
exists anywhere in the system.

So the sentence in `AGENTS.md:80` is about to be visibly false. There are two ways forward: change
the rule, or keep the rule and let the product claim something the code does not do. This
repository has spent entire passes removing claims of the second kind. This ADR takes the first.

## Decision

### 1. The non-negotiable is scoped to text and analysis

The rule becomes:

> **PII does not reach the analysis LLM raw. The worker's `PIIShield` is the last gate before
> analysis.**

The claim of universal coverage over every byte the product touches is withdrawn. It is withdrawn
rather than narrowed quietly, because it was never fully true — ADR 0033's declared residue is the
proof that predates ADR 0039 — and because a rule everybody knows to be false stops functioning as
a rule.

### 2. No control is weakened

Nothing in this ADR removes, relaxes or defers a gate:

- `PIIShield` remains the last gate before analysis, with ADR 0012's coverage unchanged
  (EMAIL, CPF, CNPJ, PHONE, CREDIT_CARD, PERSON_NAME by the BR strategy).
- ADR 0033's split stands: structured PII redacted in the BFF, on both the chat and the RAG-query
  paths, and PERSON_NAME on the chat path still carried as declared residue with the worker-routing
  follow-up still owed.
- ADR 0029's erasure and retention paths are untouched.

This ADR changes a sentence, not a pipeline.

### 3. Transcription is a declared external subprocessor

The transcription provider chosen by ADR 0039 processes **raw audio, before any redaction**. It is
named as an external subprocessor in the documents that describe the product — `AGENTS.md` and
`docs/product/vision.md` — and not left to be discovered inside an ADR by a reader who went looking.

"Declared" is the operative word: the exposure is stated where the promise is stated, at the same
volume.

### 4. What is still promised

- Text sent to the analysis LLM has passed the shield (ADR 0012).
- Structured PII on the chat path — email, phone, CPF, CNPJ, card, with check-digit and Luhn
  validation — is redacted in the BFF before both the chat provider and the embeddings provider
  (ADR 0033 §1).
- PII at rest has an on-demand physical erasure path per meeting, and an opt-in retention sweeper
  (ADR 0029).
- Tenant isolation over everything above (ADR 0002, ADR 0019, ADR 0028).

### 5. What is no longer promised, stated plainly

- **Audio is not redacted, and cannot be.** Redaction operates on text; text is what the
  transcriber produces. There is no gate that can sit between the microphone and the transcriber in
  the design ADR 0039 chose. This is not a missing feature — it is a property of the architecture,
  and it is the price of ADR 0039.
- **PERSON_NAME on the chat path is still accepted residue**, per ADR 0033, until the worker
  routing lands. That was true before this ADR and remains true after it.
- **There is no data processing agreement with the transcription provider.** ADR 0038 §4 closed the
  Enterprise DPA as scope, because there is no customer to sign one with. What governs the
  relationship is the provider's public terms. The honest description of the resulting position is
  that this is a **demonstration posture, not a compliance posture** — and the product's outward
  language must stop implying otherwise.

### 6. Scope boundary

This ADR covers the live streaming path decided by ADR 0039. Batch or file transcription of
uploaded audio (US08) is out of scope and remains unbuilt. If it is ever built, the audio would pass
through NORA's own infrastructure, which is a different exposure and a different statement: NORA
would hold raw audio at rest and would owe it the erasure and retention treatment of ADR 0029. That
would need its own paragraph here, or its own ADR.

## Consequences

**Positive**

- The non-negotiable becomes true again, which is what makes it enforceable. A reader — human or
  agent — can now act on it without first discovering the exceptions.
- The exposure is visible where the promise is made, instead of being reachable only by reading two
  ADRs and a Rust module in the right order.
- The boundary is drawn where it actually is: NORA controls its text pipeline and does not control
  what a caller says out loud into a microphone that is streaming to a third party.

**Negative / debts**

- The product's strongest privacy sentence gets weaker, and it was a differentiator against the
  competitors named in `docs/product/vision.md:188`. That loss is real and is paid here rather than
  hidden.
- `AGENTS.md` §Non-Negotiables and `docs/product/vision.md` (§Nature and §PII Shield rows) both
  carry the old wording and must be reconciled with this ADR. Until they are, they overclaim.
- The landing page carries the strongest version of the claim, and this ADR makes it worse rather
  than better. DEC-04 froze the landing pending a separate decision, and the specific claims are
  catalogued in issue #456. Leaving it untouched is a decision, recorded here so it is not read as
  an oversight.
- `docs/product/vision.md:108` already declares ADDRESS as uncovered PII debt. That debt is
  unaffected by this ADR and still owed.

## Alternatives Considered

1. **Keep the sentence and say nothing.** Let `AGENTS.md:80` go on claiming universal coverage
   while raw audio streams to a third party. Rejected, and it is the alternative this ADR exists to
   reject. It fails in the worst possible direction: the reader who trusts the sentence is exactly
   the reader who would have cared about the exception. It also has a mechanical cost specific to
   this repository — the non-negotiables are read by agents as constraints, so a false one produces
   work defending an invariant that no longer holds, and reviews that block correct changes for
   violating it.
2. **Keep local Whisper so the sentence stays true.** That is ADR 0035, and it is the strongest
   argument against ADR 0039. If the sentence were the only consideration, ADR 0035 wins outright.
   What outweighs it is ADR 0038's declared destination — see ADR 0039 §Context. Recorded here
   because a future reader should know this ADR is a consequence, not a preference.
3. **Redact the audio before sending it.** Rejected as impossible in this order: redaction needs
   text, and the text is what we are sending the audio away to obtain. Any version of this requires
   a local transcriber, which is alternative 2.
4. **Route the audio through the NORA backend so that we at least observe what we hand over.**
   Rejected in ADR 0039 §Alternatives 2 on substrate grounds, and rejected again here on privacy
   grounds: it would make NORA a controller of raw audio at rest, which ADR 0035 §a argued enlarges
   ADR 0029's obligations instead of shrinking them. Seeing the data is not the same as protecting
   it.
5. **Replace the single non-negotiable with a per-surface matrix of gates** — a table in
   `AGENTS.md` giving analysis, chat, embeddings and audio each their own row. Partly adopted: §4
   and §5 above *are* that matrix, and this ADR is where it belongs. What was rejected is putting it
   in the non-negotiables list. A non-negotiable that takes a table to state is not a
   non-negotiable; it is documentation, and it stops being the one line somebody remembers.

## History

| Date | Decider | Change |
|---|---|---|
| 2026-08-16 | sys0xFF | Created and accepted. Forced by ADR 0039, which sends raw audio to an external transcriber before any redaction. Scopes the PII non-negotiable to text and analysis, declares the transcription provider an external subprocessor, and lists explicitly what is still promised and what is not. Supersedes nothing: ADR 0012 and ADR 0033 keep every control they defined |
