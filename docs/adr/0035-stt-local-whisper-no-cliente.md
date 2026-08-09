# ADR 0035 — Local STT: Whisper embedded in Tauri (Rust), running on the client machine

- **Status:** accepted
- **Date:** 2026-08-07
- **Deciders:** sys0xFF (PO/owner) + NORA Architect (Tech Lead, migration audit)
- **Supersedes:** ADR 0009 (Speech Token Broker — entirely: the broker, the endpoint, the rate limit and
  the credential cease to exist)
- **Partially supersedes:** ADR 0008 (Desktop Tauri 2 + Python Sidecar — **Tauri 2 remains**;
  the **Python sidecar goes**, and with it PyInstaller and the second runtime)
- **Related:** ADR 0034 (the migration erases the Azure Speech resource and is what forces this
  decision), ADR 0012 (PII), ADR 0029 (operational LGPD), ADR 0033 (PII on the chat path),
  ADR 0005/0006/0015 (consumers of the speaker signal)

## Context

ADR 0034 shuts down the Azure subscription. With it dies the **Azure Speech (Cognitive Services
SpeechServices S0)** resource, and with it the substrate of ADRs 0008 (Python sidecar) and 0009 (token broker).
You cannot "just remove it": the desktop needs STT.

### What exists today

| Piece | Where | What it does |
|---|---|---|
| Python sidecar | `apps/desktop/sidecar/` (`azure-cognitiveservices-speech>=1.40`) | `ConversationTranscriber` over a 16 kHz/16-bit/mono push stream; NDJSON v1 protocol over stdin/stdout; packaged with PyInstaller |
| **Two** simultaneous sidecars | `src-tauri/src/commands.rs:135-180` | one per track: `track_label = "mic"` and `track_label = "system"` (system loopback) |
| Online diarization | `transcriber.py:94-97` (`SpeechServiceResponse_DiarizeIntermediateResults`) | `speaker_id` (`Guest-1`, …) per speaker, **within each track** |
| Confidence | `transcriber.py:152-164` | `NBest[0].Confidence` from `OutputFormat.Detailed` |
| Resilience | `transcriber.py:166-222` | `CancellationErrorCode` → `AUTH_FAILED`/`BAD_REQUEST`/`QUOTA`/`NETWORK`/`SERVICE_UNAVAILABLE`/`RUNTIME_ERROR` map, with auto-restart and exponential backoff (3 attempts) **only** on `NETWORK`/`SERVICE_UNAVAILABLE` |
| Broker | `POST /speech/token`, `application.yml:175-183` | 540s token issued by the **regional** STS (`https://%s.api.cognitive.microsoft.com/sts/v1.0/issueToken`, `Ocp-Apim-Subscription-Key` header), Bucket4j rate limit 6/min/user |

### The constraint that reorders the calculation

**Raw meeting audio is the product's most sensitive PII** — and today it leaves the user's machine
in real time, to a third party, **with no gate at all**.

ADR 0012 and ADR 0033 deal with text. ADR 0029 points to `transcripts.raw_text` as PII at
rest. But **audio is upstream of all of that**: the PII Shield runs in the worker, afterwards, over the
already transcribed text. Between the microphone and the text, the entire speech — names, numbers, whatever is said — has
already crossed the internet to an external provider. That stretch of the path never had an ADR.

ADR 0009 treated that point as solved because the *credential* was protected ("the subscription
key never leaves the server", "limited blast radius", "compliance: smaller LGPD attack surface").
Protecting the credential does not protect the audio. The forced migration is the opportunity to fix that, not
merely to swap vendors.

## Decision

**Whisper embedded in the Tauri binary (Rust), transcribing on the client machine.**

1. **`whisper.cpp` via a Rust binding (`whisper-rs`), compiled into the Tauri binary.** No
   separate process, no Python, no PyInstaller, no IPC over stdin/stdout.
2. **Quantized GGML model, downloaded on first use** — not embedded in the installer (see
   §Model download strategy).
3. **Two in-process instances**, one per track — the two-transcriber design of
   `commands.rs` is kept, no longer being two processes.
4. **Attribution by TRACK, not by speaker:** `track: "mic"` = local user; `track: "system"` =
   remote participants. `speaker_id` stays **null** within the track.
5. **The broker goes entirely:** `SpeechController`, `SpeechTokenService`, `AzureSpeechTokenBroker`, the
   Bucket4j rate limit, `AZURE_SPEECH_KEY`/`AZURE_SPEECH_REGION`, the `nora.speech.*` block of
   `application.yml`, the NDJSON protocol's `refresh_token` message and the 8-minute renewal timer
   in Rust.
6. **Offline-first:** with the model present, recording and transcription work without a network. Only the
   meeting upload and the LLM analysis require connectivity.

### Why on the CLIENT MACHINE, and not self-hosted on the server

The obvious alternative after ADR 0034 would be to stand up a Whisper in the worker and keep sending audio
there. The gains below come from **local on the client**; self-hosting on the server **delivers
none of them**:

**a) Raw audio with PII never leaves the machine.** Self-hosting would move the PII from a third party (Azure)
to ourselves. That improves contractual governance and **does not reduce exposure**: the audio
would keep traveling, would keep sitting in a buffer somewhere, and would make us controllers of one more
sensitive piece of data — more surface for ADR 0029's right to be forgotten and retention to cover.
Local **reinforces** ADR 0012 (the strongest gate is the data not existing outside) and **reinforces** ADR 0029
(there is no audio to retain, nor to delete) instead of making both worse.

**b) The entire token broker disappears.** Authenticated endpoint, rate limit, secret, region, renewal every
8 min, the `refresh_token` message in the protocol, the `TOKEN_REFRESH_FAILED` error and the failure class
"the token expired in the middle of the meeting": all of it ceases to exist. Self-hosting would **not** make that
disappear — it would still be necessary to authenticate the client against the STT service, that is, an
equivalent broker under another name.

**c) It works offline.** In-person meeting, hotel wifi, restrictive corporate VPN: STT
keeps going. Server self-hosting does not give that in any configuration.

**d) It does not reintroduce the topology that ADR 0009 itself rejected.** "Alternative A — Server-Side
Proxy (Rejected): all audio goes through the NORA backend", with the verdict *"high latency, bandwidth
cost, single point of failure, complex LGPD — not suitable for real-time STT"*. Self-hosting
Whisper on the server **is literally that alternative**, now aggravated: after ADR 0034 the
"server" is **a single home VM, with no GPU**. Streaming audio from N clients to a residential
host is the worst possible variant of a topology that had already been discarded with the good
infra.

**e) The marginal cost of STT goes to zero for NORA.** ADR 0009 listed "centralized cost" as a
*positive* — in practice it means NORA paying per minute transcribed for everybody. It stops being a
unit-economics item.

<a id="trade-off"></a>

## Trade-off — what is lost, explicitly

Nothing here is mitigated by configuration. These are losses.

### 1. Online per-speaker diarization

**Whisper does not do diarization.** It is a transcription encoder-decoder; it has no notion of a speaker. The
usual solutions — **WhisperX + pyannote.audio** — are **batch by construction**: they cluster
speaker embeddings over the **complete** audio. There is no online variant of them that fits into a
real-time loop; this is not an implementation limitation, it is the design of the method.

Concrete consequences:

- Attribution becomes **per track**: `mic` = local user, `system` = remote participants.
- `speaker_id` stays **null** within the track. The contract **does not break**: the NDJSON already declares
  `speaker_id: str | None` (`protocol.py:52,59`) and Rust's `TranscriptEvent` already carries
  `track: String` (`stt_sidecar.rs:17`). The contract holds; the **content gets poorer**.
- On a call with 4 remote participants, the 4 become **a single block**. The speaker-renaming UI in
  `overlay.tsx` (`detectedSpeakers`, `nora://rename-speaker` event) degrades from N detected
  speakers to exactly **two** fixed labels.
- **What is NOT lost:** the me-vs-them separation. It is what sustains talk-ratio and much of
  the Productivity Score (ADR 0005) and Customer Confidence (ADR 0006/0015). The most valuable signal
  survives; the granularity **inside** the remote side does not.

Possible recovery — **declared, not promised**: **post-meeting** diarization, in batch,
locally, at the end of the recording, rewriting `speaker_id` in the already saved transcript. It is not in scope now and
is not a prerequisite of this decision.

### 2. Calibrated confidence

`NBest[0].Confidence` is a probability **calibrated by the service**. Whisper exposes logprob per
token and `no_speech_prob`; an average of logprob **is not calibrated** and is not comparable to the previous
number.

Decision: **`confidence` becomes `null`**, and not a logprob average disguised as a
probability. Publishing an uncalibrated number in a field that used to be calibrated is worse than
publishing nothing — any consumer applying a threshold would inherit a meaningless threshold. The
types are already optional (`Option<f32>` in `stt_sidecar.rs:23`, `confidence?: number` in
`lib/types.ts:98`), so nothing breaks by type. If some consumer needs a quality
signal, it will be a **new and explicitly uncalibrated** field, not this one.

### 3. The `CancellationErrorCode` taxonomy and the auto-restart it sustained

The error map came from the SDK, and the auto-restart with exponential backoff fired **only** on `NETWORK`
and `SERVICE_UNAVAILABLE`. With no network in the path, **those two categories cease to exist** — and with
them the auto-restart trigger. `AUTH_FAILED` and `QUOTA` also disappear (there is no credential and no quota) and
so does `TOKEN_REFRESH_FAILED` (there is no token).

Local failures are of another nature and require a new taxonomy, in which **backoff is not the answer**:

| New code | Nature | Handling |
|---|---|---|
| `MODEL_MISSING` | model not downloaded | fail-fast; UI gate with a "download model" action |
| `MODEL_CHECKSUM_MISMATCH` | corrupted/tampered download | fail-fast; delete and re-download |
| `MODEL_LOAD_FAILED` | invalid file / insufficient RAM at load | fail-fast with an actionable message |
| `OUT_OF_MEMORY` | overflow during decode | degrade to a smaller model or single-track |
| `DECODE_FAILED` | inference error | 1 immediate retry; then fail-fast |
| `AUDIO_DEVICE_LOST` | device disappeared | recoverable — it is the **only** case that keeps a retry |

That is: resilience is not "ported", it is **redesigned**. Recording this matters because the
auto-restart of `transcriber.py:193-222` was born from two audits (#113, #116) and disappears entirely.

### 4. Whisper is not streaming

It is a 30s-window encoder-decoder. Real time is **emulated** by a sliding window with VAD.
Consequences:

- `partial` stops being word-by-word and becomes **chunk-by-chunk**.
- The latency of the first text goes from hundreds of milliseconds to the window size (target: ~5s
  with a silence flush, in place of the 800 ms of `Speech_SegmentationSilenceTimeoutMs`).
- There is **edge rewriting**: a chunk's text can change when the window advances. The overlay UI
  needs to tolerate partial-line rewriting — today it assumes append.

## Cost on the client's hardware

This cost does not disappear: it **leaves our billing and enters the user's laptop**. It needs to be
written down.

- **Two simultaneous instances** (mic + system) running throughout the entire meeting. It doubles CPU and RAM
  relative to any single-instance benchmark.
- **Default model: quantized `small`** (`ggml-small-q5_0`, ~190 MB on disk, ~600 MB resident per
  instance). It is the practical floor for pt-BR with usable quality.
  - `base` q5 (~60 MB) as **automatic degradation** on a weak machine — faster, worse in pt-BR.
  - `medium` q5 (~540 MB) **opt-in** for those with headroom.
- **CPU:** `small` with ~4 threads runs close to real time on modern x86-64 with AVX2. Two
  instances take up ~4-8 effective threads **throughout the whole meeting** — on a laptop that means
  the fan on and the battery draining, at a moment when the machine **is already on a video call**.
- **Declared floor:** 4 physical cores + 8 GB RAM for `small` dual-track. Below that: degrade
  to `base`; if it still does not fit, to **single-track** (only `system`, which is the side that matters
  most in a commercial context).
- **Acceleration:** Metal (macOS) and Vulkan/CUDA (Windows/Linux) via whisper.cpp feature flags, on a
  best-effort basis. **It is not a requirement** — the decision has to close on CPU.
- **Product consequence, without makeup:** the desktop stops being "light" in the sense of ADR 0008.
  The bundle argument (~5-15 MB vs ~150 MB for Electron) **remains valid** — the binary does not
  grow. What grows is the **runtime footprint**, which is a different metric and one that ADR 0008
  never discussed.

## Model download strategy

**Do not embed it in the installer.** `small` q5 would add ~190 MB to a bundle whose central argument in ADR
0008 was precisely size.

- **Download on first use**, with a progress bar, **resumable** (HTTP Range), **atomic**
  writing (downloads to `.part`, `fsync`, renames).
- **Mandatory SHA-256 verification** against a manifest embedded in the binary. A different checksum =
  delete and fail (`MODEL_CHECKSUM_MISMATCH`); it never uses an unverified file.
- **Destination:** the app's data directory — `%APPDATA%\NORA\models` (Windows),
  `~/Library/Application Support/NORA/models` (macOS), `$XDG_DATA_HOME/nora/models` (Linux).
  **Never** the installation directory: on Windows that would require elevation.
- **Origin: our own mirror** (`models.<dominio>`, static behind Cloudflare) with the upstream Hugging
  Face (`ggerganov/whisper.cpp`) as a fallback. Two reasons: not depending on a third party's availability or
  rate limit policy **on first use** — the most fragile moment of onboarding — and,
  being static content cached at the edge, **not going through the ADR 0034 VM**: the host going down
  does not stop someone from installing the desktop.
- **Offline installation:** `NORA_WHISPER_MODEL_PATH` points to an already present file; the app validates
  the checksum and uses it. It covers a corporate environment with no internet access — which is, not by chance,
  exactly the audience that most wants local STT.
- **Gate:** without a valid model, recording is **disabled with an actionable message**, not silently
  broken.
- **Update:** a new version of the app can declare a new model in the manifest. The old one is
  kept until the new one is downloaded **and verified**; only then is it removed.

## Consequences

**Positive**

- Raw audio with PII **never leaves the user's machine** — it closes a stretch of the path that never
  had a gate, reinforcing ADR 0012 and ADR 0029.
- One runtime fewer: Python goes, PyInstaller goes, the sidecar's matrix build across the 3
  platforms goes, the IPC over stdin/stdout goes. ADR 0008's "two runtimes to maintain" negative is
  resolved.
- Smaller server surface: one authenticated endpoint, one rate limit, one credential and one region
  fewer to operate, rotate and audit.
- The per-minute STT cost disappears from unit economics.
- It works offline.

**Negative / debts**

- Per-speaker diarization, calibrated confidence and the SDK's error taxonomy: lost (see
  §Trade-off).
- Quality in pt-BR now depends on the local model and on the user's hardware — **variable across
  machines**, unlike a managed service that delivers the same result to everyone.
- First use gains a ~190 MB download step.
- A real rewrite, not a port: new engine, new error taxonomy, new windowing strategy, new progress
  and model-gate UI. The `test_transcriber_fake.py` / `test_protocol.py` tests are
  replaced, not adapted.
- The hardware floor excludes weak machines from dual-track. It is a new product constraint.

## Alternatives Considered

1. **Whisper self-hosted on the NORA server** (an endpoint in the worker, the client sends audio). Rejected: it is
   ADR 0009's Alternative A (server-side proxy) back again, now on a single home VM with no
   GPU (ADR 0034). It keeps the broker, keeps the audio leaving the machine, does not work offline and
   concentrates audio streaming from all clients on a residential host.
2. **Another cloud STT** (Deepgram, AssemblyAI, OpenAI `gpt-4o-transcribe`). Less effort,
   preserves diarization and confidence. Rejected: it pays per minute, keeps the broker (or worse: the key on the
   client), keeps the raw audio leaving and **reintroduces the third-party dependency that ADR 0034
   has just cost us**. It remains as a **declared plan B** if the local quality in pt-BR is
   unacceptable. *Trigger:* WER of `small` q5 in pt-BR worse than the acceptance baseline measured against
   the transcriptions in `data/synthetic/`.
3. **Keeping the Python sidecar and swapping only the SDK** (`faster-whisper` in place of the Azure SDK). Smaller
   diff, NDJSON protocol and PyInstaller intact. Rejected: it preserves the two runtimes that
   ADR 0008 itself already listed as a negative, and taking Python out of the bundle is precisely the gain that
   pays for the rewrite. **Acceptable as an intermediate step** if the Rust binding causes problems on some
   platform.
4. **WhisperX / pyannote embedded to recover diarization.** Rejected by construction: it is batch,
   it requires the complete audio, and it brings Python back (pyannote is PyTorch — bundle and packaging
   complexity far above what the current sidecar costs). Reopened only as offline
   post-processing (§Trade-off, item 1).
5. **User BYO-key.** Already rejected by ADR 0009 (alternatives B and C) and nothing has changed in the
   reasoning.

## Code impact (map of what goes)

| Goes | Where |
|---|---|
| The entire Python sidecar | `apps/desktop/sidecar/` (`transcriber.py`, `protocol.py`, `build_sidecar.py`, tests) |
| The sidecar's `externalBin` + the PyInstaller build job | Tauri config + `.github/workflows/ci.yml` |
| Broker and rate limit | `SpeechController`, `SpeechTokenService`, `AzureSpeechTokenBroker`, the speech Bucket4j |
| Broker config | `nora.speech.*` block (`application.yml:175-183`) |
| Credentials | `AZURE_SPEECH_KEY`, `AZURE_SPEECH_REGION`, secret `azure-speech-key`, `AZURE_SPEECH_ENDPOINT` (this one was already unconsumed — see ADR 0034) |
| Token renewal | 8-min timer in `speech_token.rs`, the NDJSON's `refresh_token` message |

| Stays | Note |
|---|---|
| Tauri 2 + Rust | ADR 0008 kept in this part |
| Audio capture (`cpal`, WASAPI loopback, PulseAudio/PipeWire, ScreenCaptureKit) | unchanged |
| The `track` field in `TranscriptEvent` | becomes the **only** source of attribution |
| `speaker_id` and `confidence` in the contract | kept as optional, now **always null** |

## History

| Date | Decider | Change |
|---|---|---|
| 2026-08-07 | sys0xFF + NORA Architect | Creation and acceptance. Forced by ADR 0034 (end of the Azure Speech resource). Supersedes ADR 0009 entirely and ADR 0008 in the Python sidecar part. Trade-off of diarization, confidence and error taxonomy recorded as an explicit, unmitigated loss. |
