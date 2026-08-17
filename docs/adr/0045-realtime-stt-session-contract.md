# 0045 — The realtime STT session contract: one endpoint, one credential, no renewal loop

- Status: accepted
- Date: 2026-08-17
- Implements: ADR 0039 (which decided the vendor, the protocol and the ephemeral-token shape,
  and explicitly left the endpoint's real path, its payload and the credential lifetime open).
  It does not supersede it — ADR 0039 already supersedes ADR 0035 and remains the decision
- Related: ADR 0040 (the privacy consequence that lands with this code), ADR 0024 (the cost
  surface these telemetry rows arrive on), ADR 0009 (the ephemeral-token pattern this rebuilds),
  ADR 0007 (the IAM gate on the endpoint), ADR 0036 (the substrate that still rules out a
  server-side audio proxy), ADR 0038 §2 (Windows-only desktop)

## Context

ADR 0039 decided the shape of cloud transcription and stopped where the shape stopped. It named
`POST /stt/realtime-session` "a working name", said the credential lifetime "is the provider's to
set, not ours", and left everything below that line to whoever built it. This ADR is that line
being drawn, written down where the next person will look for it rather than left implicit in a
diff.

Four things had to be decided before any of it compiled, and each of them is hard to reverse once
a desktop binary is in the field: the endpoint's path and payload, what the credential's expiry
actually governs, what sample rate the pipeline targets, and which of the client's remaining
configuration knobs survive.

The provider surface was checked against live documentation while writing this, not recalled.
That matters because ADR 0039's own instructions said to: this API has already renamed its
session endpoint once (`/realtime/sessions` and `/realtime/transcription_sessions` became
`/realtime/client_secrets`), and its model names have moved with it.

## Decision

### 1. The endpoint is `POST /stt/sessions`

Not `POST /stt/realtime-session`. "Realtime" carries no information: realtime streaming is the
only kind of session this API mints, and ADR 0039 §"Where the privacy problem went" puts file and
batch transcription explicitly out of scope. Every other collection in this API is plural with the
create verb on the collection (`/chat/sessions`, `/iam/policies`, `/workflows`), and a singular
resource name would have been the odd one out for no gain.

The request body is `{ "language": "pt-BR" }`, optional. That is the whole client input.

The response is the session, and nothing else the client could not already have:

```json
{
  "clientSecret": "ek_…",
  "expiresAt": "2026-08-17T12:34:56Z",
  "websocketUrl": "wss://api.openai.com/v1/realtime?intent=transcription",
  "provider": "openai",
  "model": "gpt-live-transcribe",
  "language": "pt",
  "audioFormat": "audio/pcm",
  "sampleRate": 24000
}
```

**The endpoint, the model, the audio format and the sample rate are in the response on purpose.**
The alternative — the desktop hardcoding them — makes a provider rename a desktop release, and
the release path for this client is a signed MSI with an updater. Those four values are also cost
and quality decisions, and they belong on the side that pays the invoice, not in a binary the
user is running.

The endpoint is gated `@RequiresPermission(action = "stt:session:create", resource =
ResourceType.TENANT)`, which is what the deleted `SpeechController` did and for the reason its
Javadoc gave: the credential reaches a paid external provider billed to the tenant, so it is a
tenant capability, not a "self" endpoint — even though the rate limit keys on the caller.

### 2. The provider key is never in a response, a log, or an exception

Stated as a decision because it is the one property the whole design exists for, and because
it fails silently when it is broken.

- One class in the API reads `nora.stt.openai.api-key`. It sends it in an `Authorization` header
  and nowhere else.
- The provider's error body is never echoed into ours. Only its status and its `error.message`
  field are logged. An upstream body copied wholesale is how a credential reaches a support
  ticket.
- `toString` is overridden on both the application value and the response DTO, and `Debug` is
  hand-written on the desktop's session struct. A record's generated `toString` and a derived
  `Debug` print every field, so one interpolated log line would publish a live credential. Each of
  the three has a test, because a component added later would otherwise restore the generated
  form silently.

### 3. The credential's expiry does not need a renewal loop

ADR 0039 flagged this as an open question and warned against "a loop that re-fetches a token every
N seconds on an already-open connection". Checked against the provider's documentation: the
expiry governs how long the secret may be used to **open** a session. A session already open is
not terminated when it lapses.

So there is no renewal timer, and the deleted Azure broker's 8-minute one does not come back. Its
60-second slack does, for a narrower job: refusing a credential that would be rejected between the
mint call and the handshake.

The lifetime is requested rather than merely observed. The provider accepts 10..7200 seconds and
defaults to 600; NORA requests 600 explicitly, so the number is in configuration where it can be
read, and the response's own `expires_at` is what the client is told.

**A drop mid-meeting is the normal case.** The credential is not renewable into a second session,
so a reconnection is a new call to the endpoint — a new authorization check and a new telemetry
row. Bounded at five consecutive failures per track: an unbounded retry against a provider that
is refusing us spends the user's session budget and tells them nothing new.

### 4. Attribution is a session issued, never a minute transcribed

ADR 0039 §"The audio does not pass through NORA's infrastructure" required this to be written with
all the letters. Here it is, as code rather than as prose:

`UsageRecorder.recordExternal` is called at issuance with `service = "stt"`, the provider, the
model, the caller's tenant, **zero prompt tokens, zero completion tokens and a null cost hint**.
The nulls are the honest part. NORA can say which tenant asked for how many sessions and when; it
cannot meter audio it never carried. A cost estimated from a client-reported duration would be a
fabricated number in the operator console, which is exactly the class of claim this repository has
spent whole passes removing.

A unit test asserts those zeros and that null. It exists so that a later "improvement" that
computes a plausible cost has to delete an assertion that says why not.

Failures are recorded too, with `status = "error"`. A tenant whose sessions are all being refused
is precisely what the console should be able to see.

### 5. One backend, therefore no backend switch — on either side

`SttBackendKind`, `configured_backend()`, `NORA_STT_BACKEND` and the `plugins.nora.sttBackend`
config key are deleted from the desktop. So are the Cargo `[features]` section, the
`plugins.nora.whisperModel` key and `build.rs`'s injection of both env vars.

Symmetrically, the API's broker has **no** `@ConditionalOnProperty`, unlike the Azure adapter it
is modelled on, and `SttProperties` has no `provider` field. That annotation existed to keep a
rollback lever between two vendors that both existed. There is one realtime STT provider and
nothing to roll back to.

What survives on each side is the part that was load-bearing rather than optional: the
`SttBackend` trait, which is why `commands.rs` can hold one handle per track without knowing what
is behind them; and the `RealtimeSttBroker` port, which is what keeps the account key out of the
application layer.

The `stt.rs` seam did its job. Keeping a one-variant enum as a monument to it would be a mechanism
that decides nothing.

### 6. The capture pipeline targets 24 kHz, in one constant

The provider's realtime transcription session takes `audio/pcm` at 24000 Hz. The pipeline
previously produced 16 kHz, spelled as the literal `16000` at seven sites across
`audio_capture.rs` and `system_audio.rs`.

It is now `stt::TARGET_SAMPLE_RATE`, one constant, set to 24000. The alternative — keeping 16 kHz
and upsampling inside the streaming client — costs a second resample that invents nothing and
throws away the 8–12 kHz band on the way. Retargeting the capture is one resample from the
device's native rate, which is what it always was.

ADR 0039's code-impact table listed audio capture and resampling as unchanged, and said the
48k→16k regression tests "stay the guard they already are". That table was written before the
provider's input format was checked. **The tests do stay, literally unchanged**: they exercise
`MonoResampler` directly, not the pipeline constant, and what they guard is `rubato::Fft::new`'s
all-`usize` argument list, where a wrong order compiles and feeds silence into transcription. A
third test was added at 48k→24k, the 2:1 ratio the product now actually runs at.

A mismatch between this constant and the API's `nora.stt.openai.sample-rate` fails nowhere: it
plays the audio to the provider at the wrong speed and returns confident nonsense. Three things
guard it — the constant is asserted in a unit test so changing it is a decision rather than an
edit, the client warns when the server's advertised rate differs, and the client declares the rate
it actually sends on every connect, so what is streamed and what is claimed cannot diverge.

### 7. The desktop authenticates with the web session, not with the keyring

`stt_token.rs` uses `auth_bridge::web_session_jwt` — the session cookie read out of the remote
`main` window — like `commands.rs::upload_meeting` and `live_analysis.rs` already do.

It deliberately does not use `http_proxy.rs`, which signs with the keyring's `access-token`.
Measured on this tree, that path has neither end: the login form that wrote the secret was deleted
with the local UI (PR #465), and nothing reads it either — `apiClient.request` has no live caller,
the three `meetings.ts` wrappers have no callers, and `uploadTranscript` goes through
`invoke("upload_meeting")`.

The path is left in place rather than deleted, because removing it means removing a registered
Tauri command and the secret store around it, which is a separate change. What this ADR decides is
that **nothing new is built on it**. Building the STT credential fetch there would have made a
dead branch look alive, and the next person to touch desktop auth needs to know it is dead.

## Consequences

**Positive**

- The provider endpoint, model and audio format become server configuration. A rename is an
  environment variable, not a signed release.
- The desktop crate has no `[features]`, no optional native dependency, no C++ toolchain
  requirement and no `libclang` requirement. `cargo check` and `cargo test` now run on a plain
  developer machine, which they did not before — the local verification story for this client
  stops being "open a PR and read CI".
- Both CI workflows lose their CMake/`MAX_PATH` workarounds, and `desktop-rust` loses the
  ten-minute whisper.cpp build.
- Seven hardcoded sample rates become one constant with a test on it.

**Negative / debts**

- Starting a recording now depends on the API being reachable, not merely on being logged in.
  `commands.rs` said the opposite in a comment; it now says this.
- A dropped session leaves a hole in the transcript. It is announced on `stt-error` and rendered
  by the overlay, which makes it visible but does not make it recoverable.
- The per-user rate limit is in-memory and per-instance, like every other limiter in this API.
  Honest for a single-instance deployment (ADR 0036) and wrong the day there are two.
- Cost telemetry is an estimate, permanently. See §4 — this is a property of the design, not a
  gap to close.
- `http_proxy.rs` and the keyring path stay in the tree with no producer and no live consumer.
  Recorded here rather than fixed, because the fix is a different change.

## Alternatives Considered

1. **`POST /stt/realtime-session`, ADR 0039's working name, taken literally.** Rejected: the
   qualifier distinguishes nothing, and the singular resource would be the only one of its kind in
   this API. Recorded because a reader of ADR 0039 will look for that path and should find out
   here where it went.
2. **Return only the client secret and let the desktop hold the endpoint, model and format.** A
   smaller payload, and the shape most examples show. Rejected: it moves four cost-and-quality
   decisions into a distributed binary, and this provider has already renamed the very endpoint
   those decisions point at.
3. **Keep 16 kHz and resample 16 → 24 inside the streaming client.** One file changes instead of
   three. Rejected: two resamples where one suffices, spending CPU to interpolate information that
   was discarded by the first one. The 8–12 kHz band that a 24 kHz capture keeps is not decisive
   for speech, but throwing it away to avoid touching a constant is the wrong trade.
4. **Keep `SttBackendKind` with one variant as an extension point.** Rejected for the reason
   ADR 0039's own reasoning implies: the seam that mattered is the `SttBackend` trait, and it is
   kept. An enum with one variant, resolved from an env var, a build-time injection and a JSON
   config key, is three configuration surfaces that cannot produce two outcomes.
5. **Renew the credential on a timer, as the Azure broker did.** Rejected on the documentation:
   the expiry gates opening a session, not holding one. A renewal loop would have spent session
   budget and rate limit on connections that did not need it, and ADR 0039 specifically warned
   against writing it from memory.
6. **Estimate cost per session from the client-reported duration.** Rejected, and asserted against
   in a test. It would make the operator console show a measurement-shaped number that is
   arithmetic on an unverifiable client claim.

## History

| Date | Decider | Change |
|---|---|---|
| 2026-08-17 | sys0xFF | Created and accepted alongside the implementation of ADR 0039. Fixes the endpoint at `POST /stt/sessions` with a server-resolved session payload; records that the credential expiry gates opening a connection rather than holding one, so there is no renewal loop; states the attribution limit as zeros and a null in `UsageRecorder`, with a test; collapses the backend selector on both sides; moves the capture pipeline to a single 24 kHz constant; and decides that the desktop's dead keyring auth path is not built upon |
