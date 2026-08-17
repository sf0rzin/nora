# 0039 — Cloud STT: OpenAI transcription reached with an ephemeral session token

- Status: accepted
- Date: 2026-08-16
- Supersedes: ADR 0035 (local Whisper on the client machine — entirely: the engine, the model
  download strategy, the hardware floor and the local error taxonomy)
- Related: ADR 0040 (this decision is what forces it — raw audio leaves the machine before any
  redaction), ADR 0038 (the destination that changes the arithmetic), ADR 0009 (the ephemeral-token
  pattern this rebuilds), ADR 0036 (the substrate that still rules out a server-side audio proxy),
  ADR 0004 (provider-agnostic LLM), ADR 0024 (cost telemetry in the operator console),
  ADR 0005/0006/0015 (consumers of the speaker signal)

## Context

ADR 0035 was decided on 2026-08-07, nine days before this one, and it was decided under duress:
ADR 0034 shut down the Azure subscription and took the Azure Speech resource with it, so the
desktop needed an STT engine that week. It chose Whisper compiled into the Tauri binary, running on
the client's machine, and it was unusually honest about the bill:

- a ~190 MB quantized model downloaded on first use (0035 §Model download strategy);
- **a declared floor of 4 physical cores + 8 GB of RAM** for `small` dual-track, degrading to
  `base` and then to single-track below that (0035 §Cost on the client's hardware);
- two in-process instances running for the whole meeting, on a laptop that is already on a video
  call — "the fan on and the battery draining", in its own words.

Two things changed since.

**The destination is now written down.** ADR 0038 declares NORA a FIAP deliverable plus portfolio,
with no real users. ADR 0035 optimized for a user who runs NORA on their own machine all day, on
possibly hostile networks — a user who does not exist and is not planned. For the audience that
does exist, a first run that downloads 190 MB before the product does anything, and a hardware floor
a borrowed laptop may not meet, is a materially worse demonstration than an API call. The cost of
the local engine lands precisely on the moment the project is being judged.

**The maintainer has OpenAI Platform credit.** The per-minute cost that ADR 0035 counted as a win
(§e — "the marginal cost of STT goes to zero for NORA") is, at demonstration volume, near zero
either way. It was a real argument for a commercial product with N tenants. ADR 0038 says there are
no tenants.

What did **not** change is ADR 0035's privacy argument. It identified a stretch of the path that
had never had an ADR — between the microphone and the text, the entire speech has already crossed
the internet — and it closed that stretch by keeping the audio on the machine. That argument was
right when it was made and is still right. This ADR reopens the hole deliberately, and ADR 0040 is
where the consequence is dealt with rather than glossed.

## Decision

**Transcription moves to OpenAI's API. The desktop streams audio to OpenAI directly, using a
short-lived session credential minted by the NORA backend.**

1. **The engine is OpenAI's transcription API.** `whisper-rs`, `stt_local.rs`, `whisper_model.rs`,
   the `stt-local` / `whisper-vulkan` / `whisper-cuda` / `whisper-openblas` Cargo features, the
   `sha2` dependency they pull in, the `whisperModel` plugin key in `tauri.conf.json`, and the whole
   model-manifest / mirror / `NORA_WHISPER_MODEL_PATH` apparatus of ADR 0035 all go.
2. **Live transcription survives, over the provider's real-time streaming.** The overlay, the live
   highlights and `POST /meetings/live-analyze` (`MeetingsController.java:545`, called from
   `apps/desktop/src-tauri/src/live_analysis.rs:100`) stay in the product. Of the three options on
   the table this was the most expensive — it requires a real WebSocket streaming integration in
   Rust, which the sidecar-era code never had — and it was chosen because deleting live
   transcription deletes the desktop's reason to exist.
3. **The OpenAI key stays on the server.** It lives in the API's environment. It is never compiled
   into the desktop binary, never written to disk on the client, and never sent to the client in any
   form.
4. **The desktop receives an ephemeral session credential and connects to OpenAI directly.** A
   single authenticated endpoint on the API (working name `POST /stt/realtime-session`) calls OpenAI
   with the server-held key, creates one transcription session, and returns only the short-lived
   client secret for it. The credential is scoped to one session, expires in minutes — the exact
   lifetime is the provider's to set, not ours — and is not renewable into a second session; a new
   session means a new call to the endpoint, which means a new authorization check.
5. **Attribution stays per track.** `track: "mic"` is the local user, `track: "system"` is the
   remote participants, and `speaker_id` stays null. This part of ADR 0035 (§Decision 4) is carried
   forward unchanged, for the reason given in §What comes back below.

### The audio does not pass through NORA's infrastructure

This section is the honest correction to the shape of the decision, and it must not be dropped in
any summary of it.

With an ephemeral credential, the media path is **desktop → OpenAI**. NORA's servers see the request
that mints the session. They do not see the audio, and they do not see the transcript in flight.
(Text does reach NORA afterwards, when the desktop posts chunks to `/meetings/live-analyze` and
uploads the finished transcript — but that is text, after the fact, and it is a different path.)

The consequences follow directly and are accepted:

- **Per-tenant attribution happens at session issuance, not over content.** NORA can state which
  tenant requested how many sessions, when, and for how long the client says each ran. It cannot
  meter bytes or seconds of audio it never carried.
- **The operator console's cost telemetry (ADR 0024) measures issued sessions and estimated
  minutes, not measured usage.** Those figures must be labelled as estimates wherever they are
  displayed. A number presented as measurement, that is arithmetic on a client-reported duration, is
  the kind of quiet lie this repository spends whole passes removing.
- **The provider's invoice is authoritative; NORA's telemetry is an estimate.** A divergence between
  them is expected behaviour, not a defect, and should not be chased as one.

The decision to keep the key on the server was made for two reasons: protecting the credential and
attributing usage per tenant. The ephemeral-token design delivers the first completely and the
second only at the point of issuance. That trade was put on the table explicitly and accepted; it is
written here so that nobody later reads "usage is attributable per tenant" as a claim about content.

### This rebuilds a broker that was just deleted, and that is coherent

Days before this ADR, the Azure Speech token broker was removed from both sides of the product:
`SpeechController`, `SpeechTokenService`, `AzureSpeechTokenBroker`, `POST /speech/token`, the
Bucket4j limit that protected it, the desktop's 8-minute renewal timer and the `refresh_token`
message in the sidecar protocol. That deletion executed exactly what ADR 0035 §Decision 5 decided.

This ADR builds a broker of the same shape, for a different vendor. That is not a reversal, and the
next session reading these two ADRs in order should not treat it as one.

What ADR 0035 killed was **Azure**, the Python sidecar and the second runtime. What it did not kill,
and could not, is the *pattern*: the long-lived credential stays on the server, the client gets a
short-lived one, and the media path stays off our infrastructure. That pattern was the good part of
ADR 0009, and it is still the reason ADR 0009's own Alternative A — a server-side proxy with all
audio flowing through the NORA backend — is rejected again here. ADR 0035 §d already re-rejected it
on the grounds that the "server" is a single home machine with no GPU; ADR 0036 has since confirmed
the substrate is one bare-metal host with no hypervisor under it. Streaming every client's audio
through that host is the worst variant of a topology that was discarded when the infrastructure was
better.

The pattern was right. The vendor was not.

### What ADR 0035 recorded as lost, and what actually comes back

ADR 0035 wrote its losses down instead of mitigating them in prose. The same discipline applies to
reversing it: the table below says what changes and, more importantly, what does not.

| Item | ADR 0035's position | Now |
|---|---|---|
| **Per-speaker diarization** | Lost. Attribution degrades to two track labels | **Still lost.** The provider's real-time transcription is per stream and carries no notion of a speaker. Track attribution stays the only source, and `speaker_id` stays null. Nothing is recovered and nothing is promised |
| **Calibrated `confidence`** | Set to null, deliberately, rather than publish an uncalibrated logprob average in a field that used to be calibrated | **Stays null.** Token logprobs are obtainable and are still not calibrated. Swapping the vendor does not change the reasoning, and re-populating the field would silently give every downstream threshold a new meaning |
| **Streaming behaviour** | Whisper is not streaming; real time was emulated with a sliding window, ~5 s to first text, with edge rewriting | **Real streaming returns.** Partials become incremental again and first text drops back to the sub-second range. The overlay's tolerance for partial-line rewriting stays useful — a streaming transcriber also revises what it already emitted |
| **Error taxonomy** | Network classes deleted; replaced by `MODEL_MISSING`, `MODEL_CHECKSUM_MISMATCH`, `MODEL_LOAD_FAILED`, `OUT_OF_MEMORY`, `DECODE_FAILED`, `AUDIO_DEVICE_LOST` | **The network classes come back** — transport failure, service unavailable, quota, auth rejection — **and with them the failure mode ADR 0035 §b specifically celebrated killing: the session credential expiring mid-meeting.** The model-related codes go. `AUDIO_DEVICE_LOST` stays, and stays the case that warrants a retry |
| **Offline operation** | Gained. With the model present, recording and transcription work with no network (0035 §c) | **Lost.** No network, no transcription. This is a real regression against the "corporate environment with no internet access" audience ADR 0035 named, and it is not mitigated — it is traded for §Context |
| **Hardware floor** | 4 physical cores + 8 GB RAM for dual-track; degrade or drop to single-track below | **Removed.** The client resamples and ships audio. This is the gain that pays for the ADR |
| **Per-minute STT cost to NORA** | Zero — it left our billing and entered the user's laptop (0035 §e) | **Returns.** Small at demonstration volume, real nonetheless. It should not be described as free |

### Where the privacy problem went

Raw audio — names, document numbers and figures said out loud — now leaves the user's machine
before any redaction exists. That is the exact stretch of path ADR 0035 §The constraint that
reorders the calculation identified as ungated, and closing it was that ADR's strongest argument.

**ADR 0040 handles it.** Do not read this ADR as a claim that the problem stopped existing: it
records that the problem was reopened knowingly, and 0040 records what is still promised as a
result and what is not.

One boundary, so a later reader does not over-apply this: **only the live streaming path is decided
here.** File and batch transcription of uploaded audio (US08) remains out of scope. If it is ever
built, the audio would pass through NORA's own infrastructure, which is a different privacy
statement and would need its own paragraph in ADR 0040.

## Consequences

**Positive**

- First run needs no download and no hardware floor. The demonstration starts working immediately on
  whatever machine is in the room, which is the audience ADR 0038 declared.
- Real streaming returns; the overlay's latency stops being a design compromise.
- One heavyweight native dependency leaves the desktop build. `whisper-rs` vendors whisper.cpp and
  is the reason `desktop-rust` takes about ten minutes per merge and the reason a full local
  `cargo check` needs `libclang` installed. Both costs go.
- Transcription quality in pt-BR stops varying by machine.

**Negative / debts**

- Offline transcription is gone.
- NORA pays per minute again.
- A WebSocket streaming client in Rust is new code with a new failure surface, including
  reconnection and mid-meeting credential expiry — a failure class that had been eliminated.
- Usage attribution is at session issuance only (§The audio does not pass through NORA's
  infrastructure). Cost telemetry becomes an estimate.
- Raw audio leaves the machine unredacted. ADR 0040.
- A second external provider dependency enters the live path: a NORA outage no longer stops a
  meeting, but an OpenAI outage does.

## Alternatives Considered

1. **Keep local Whisper and absorb the demonstration cost.** The zero-diff option, and the one that
   keeps ADR 0040 unnecessary. Rejected: the 190 MB first-use download and the 4-core/8 GB floor
   land exactly on the audience ADR 0038 declared, at exactly the moment the project is evaluated.
   ADR 0035's costs were acceptable for a product with users; the product has none.
2. **Route the audio through the NORA backend, which is DEC-14 read literally.** It would give
   byte-accurate per-tenant metering and would put the content under our own controls. Rejected: it
   is ADR 0009's Alternative A, re-rejected by ADR 0035 §d, and the substrate has since been
   confirmed as a single bare-metal host (ADR 0036). Streaming every client's audio through one home
   machine to gain a metering number is not a trade worth making, and it would also make NORA a
   controller of raw audio at rest — which ADR 0035 §a argued enlarges ADR 0029's job rather than
   shrinking it.
3. **Put a key on the client** — either shipped in the binary or brought by the user. Rejected on
   both halves. A shipped key in a desktop binary built from a public repository (ADR 0017) is a
   published key. Bring-your-own-key was already rejected by ADR 0009 (alternatives B and C) and
   nothing in that reasoning has changed.
4. **A different cloud vendor** (Deepgram, AssemblyAI). Real-time diarization would come back, which
   is the one loss ADR 0035 could not mitigate. Rejected: the credit is on OpenAI, ADR 0004's
   provider posture already defaults there, and a second vendor account is operational surface owned
   by one person — for a signal ADR 0035 already established the product survives without (the
   me-vs-them split is what feeds the Productivity Score and Customer Confidence, and track
   attribution preserves it). Recorded as the reopen path if per-speaker granularity ever becomes a
   requirement rather than a nicety.
5. **Batch transcription at the end of the meeting instead of streaming.** The cheapest and simplest
   integration by a wide margin: upload the recording, get a transcript. Rejected because it deletes
   the overlay, the live highlights and `live-analyze` — that is, it deletes what the desktop is
   for. This is why DEC-13 chose the expensive option knowingly.

## Code impact (map of what changes)

| Goes | Where |
|---|---|
| The local engine | `apps/desktop/src-tauri/src/stt_local.rs`, `whisper_model.rs` |
| The dependency and its features | `whisper-rs = "=0.16.0"`, `sha2`, and the `stt-local` / `whisper-vulkan` / `whisper-cuda` / `whisper-openblas` features in `apps/desktop/src-tauri/Cargo.toml` |
| The model configuration | `plugins.nora.whisperModel` and `sttBackend` in `apps/desktop/src-tauri/tauri.conf.json`; `NORA_WHISPER_MODEL_PATH` |
| The download strategy | The manifest, the mirror, the checksum gate and the resumable download of ADR 0035 §Model download strategy — none of it has anything left to fetch |
| The model-related error codes | `MODEL_MISSING`, `MODEL_CHECKSUM_MISMATCH`, `MODEL_LOAD_FAILED`, `OUT_OF_MEMORY`, `DECODE_FAILED` |

| Arrives | Where |
|---|---|
| Session-minting endpoint | A new controller on `services/api`, authenticated, tenant-scoped, holding the OpenAI key server-side |
| Streaming client | A WebSocket transcription client in `apps/desktop/src-tauri/src/`, replacing `stt_local.rs` behind the existing `stt.rs` seam |
| Network error taxonomy | Transport, service-unavailable, quota, auth-rejected and session-expired, with reconnection |
| Session telemetry | Issued sessions and estimated minutes per tenant, into the ADR 0024 cost surface, labelled as estimates |

| Stays | Note |
|---|---|
| Tauri 2 + Rust, Windows-only | ADR 0008 in this part; ADR 0038 §2 for the platform |
| Audio capture and resampling | `audio_capture.rs`, `audio_resample.rs`, WASAPI loopback — unchanged, and the 48k→16k regression tests stay the guard they already are |
| `track` as the only attribution | ADR 0035 §Decision 4, carried forward |
| `speaker_id` and `confidence` in the contract | Optional, and still always null |
| `POST /meetings/live-analyze` | The live analysis path is untouched; only what feeds it changes |

## History

| Date | Decider | Change |
|---|---|---|
| 2026-08-16 | sys0xFF | Created and accepted. Supersedes ADR 0035 nine days after it was accepted, on the change of destination recorded in ADR 0038 and on available OpenAI credit. Records that the ephemeral-token design keeps the audio off NORA's infrastructure, so per-tenant attribution is at session issuance and cost telemetry is an estimate. Records that the Speech Token Broker pattern deleted days earlier is rebuilt here on purpose — the vendor died, not the pattern. Points the reopened privacy question at ADR 0040 |
