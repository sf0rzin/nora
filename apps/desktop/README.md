# NORA Desktop

Desktop application for real-time audio capture and meeting transcription.
Transcription is the **transcription provider's realtime API**, reached over a WebSocket
with a short-lived credential minted by the NORA backend (ADR 0039, ADR 0045). The
provider account key never reaches this application, and the audio goes from here
straight to the provider — it does not pass through NORA's infrastructure.

> **The audio does leave the machine.** This replaced an on-device engine that kept it
> local (ADR 0035), and that reversal was deliberate; ADR 0040 is where the privacy
> consequence is recorded rather than glossed. Do not describe this client as offline.

> **Windows only.** macOS and Linux were dropped: the macOS path required the user to
> install the BlackHole virtual driver and the Linux path shelled out to PulseAudio's
> `parecord`, and neither had ever been exercised by anyone. ADR 0008 and ADR 0035 describe
> a three-platform client with on-device Whisper — they are accepted records of what was
> decided at the time, and this is what the client does now.

## Stack

- **Frontend**: React 18 + TypeScript + Tailwind CSS
- **Backend**: Tauri 2 (Rust)
- **STT**: the provider's realtime streaming API over a WebSocket (`tokio-tungstenite`, rustls)
- **Build**: Vite + Tauri CLI

## Prerequisites

- [Node.js 20](https://nodejs.org/)
- [Rust](https://rustup.rs/)

> There is **no C++ toolchain requirement** any more. The old on-device engine vendored
> whisper.cpp and built it from source, which put CMake, the MSVC C++ workload and a
> `MAX_PATH` workaround on this list, and made `cargo check` need `libclang` for bindgen.
> All of it went with ADR 0039/0045.

## Development

```bash
# At the monorepo root
cd apps/desktop

# Install dependencies
npm install

# Run in dev mode.
npx tauri dev
```

Recording needs a reachable NORA API with a transcription credential configured
(`NORA_STT_OPENAI_API_KEY` — see `services/api/.env.example`), and a logged-in session in
the `main` window. **Do not put a provider key in this app's `.env`**: a key in a
distributed desktop binary is a published key, which is the whole reason the backend mints
session credentials.

## Production Build

```bash
npx tauri build
```

> `tauri.conf.json` declares no `externalBin`, so nothing has to be packaged
> before the Rust compiles — `cargo check` on a clean checkout is enough.

## Structure

> The app declares three windows (`tauri.conf.json` → `app.windows`), and only
> two of them are built from this directory. The `main` window loads the
> product's web UI from `https://nora.systems/dashboard`; `overlay` and `dock`
> are the local React surfaces. There is no local page tree — the router and its
> five screens were compiled into every bundle and rendered by nothing, and were
> deleted.

```
apps/desktop/
├── overlay.html            # Entry point of the `overlay` window
├── dock.html               # Entry point of the `dock` window
├── src/                    # React frontend — overlay and dock ONLY
│   ├── hooks/             # Custom hooks (use-recording, use-live-transcript)
│   ├── lib/               # Utilities and API client
│   └── components/        # The overlay, the dock and their shared primitives
└── src-tauri/             # Rust backend
    ├── src/
    │   ├── audio_capture.rs      # Audio capture (cpal)
    │   ├── audio_resample.rs     # Resampling (rubato)
    │   ├── stt.rs                # SttBackend trait, TranscriptEvent, TARGET_SAMPLE_RATE
    │   ├── stt_token.rs          # Fetches the ephemeral session from POST /stt/sessions
    │   ├── stt_cloud.rs          # STT: streaming WebSocket client, one per track
    │   ├── system_audio.rs       # System audio (WASAPI loopback)
    │   ├── live_analysis.rs      # Live highlights + overlay toggle
    │   ├── stealth_mode.rs       # Hide the windows from screen capture (Windows only)
    │   ├── windows.rs            # Window management (dock, overlay, focus)
    │   ├── auth_bridge.rs        # Reads the session JWT out of the main webview
    │   ├── http_proxy.rs         # HTTP proxy for the API
    │   ├── secrets.rs            # Secrets storage
    │   └── commands.rs           # Tauri commands
    ├── tauri.conf.json           # Tauri config
    └── Cargo.toml
```

## Architecture

```
┌───────────────────────────────────────────────────────┐
│                   NORA Desktop App                    │
│                                                       │
│   ┌─────────────┐                ┌────────────────┐   │
│   │  Frontend   │◄──────────────►│  Rust Backend  │   │
│   │  (React)    │  "transcript"  │    (Tauri)     │   │
│   └─────────────┘     event      └──┬─────────────┘   │
│                                     │                 │
│                                     ▼                 │
│                        ┌────────────────────────┐     │
│                        │ stt_cloud.rs           │     │
│                        │ one WebSocket per track│     │
│                        └────────┬───────────────┘     │
└─────────────────────────────────┼─────────────────────┘
                                  │
      POST /stt/sessions          │  PCM16 + transcript events
      (session credential)        │
              │                   │
              ▼                   ▼
      ┌───────────────┐   ┌──────────────────────┐
      │   NORA API    │   │ Transcription        │
      │ holds the key │   │ provider (realtime)  │
      └───────────────┘   └──────────────────────┘
```

### Data Flow

1. **Capture**: Rust (cpal) captures audio from the microphone and from the system
2. **Resampling**: converts to mono PCM16 at `stt::TARGET_SAMPLE_RATE`
3. **Session**: one credential per track from `POST /stt/sessions`, minted by the backend
4. **Streaming**: one WebSocket per track (`mic` and `system`), both behind the `SttBackend` trait
5. **UI**: both tracks emit the **same** Tauri `transcript` event — the frontend does not distinguish them

## Speech-to-Text (STT)

### There is one backend, and no selection machinery

`SttBackendKind`, `configured_backend()` and `NORA_STT_BACKEND` are gone. They existed so
cloud transcription could arrive as a second backend without disturbing `commands.rs`; it
arrived, the local one left, and a one-variant enum resolved from three configuration
sources decides nothing. The `SttBackend` trait stays — it is what lets `commands.rs` hold
one handle per track and stop them uniformly.

The Cargo manifest has no `[features]` section at all, so a plain `cargo build` is the
whole product.

### The session credential

`stt_token.rs` calls `POST /stt/sessions` with the web session JWT
(`auth_bridge::web_session_jwt`, the cookie from the `main` window). The response carries a
short-lived client secret plus the endpoint, model, audio format and sample rate the server
chose — the client hardcodes none of them, so a provider rename is a server variable rather
than a desktop release.

**There is no renewal loop, and that is deliberate.** The credential's expiry governs how
long it can *open* a connection; a session already open outlives it. What the client keeps
from the deleted Azure broker is the 60-second slack, used for a narrower job: refusing a
credential that would be rejected between here and the handshake.

A dropped connection asks for a **new** session — the credential is not renewable — which
means a fresh authorization check and a fresh telemetry row on the backend each time.

### Reconnection leaves a hole, and says so

While the socket is down, captured audio is discarded. That follows the rule the pipeline
has always had (real time beats completeness) but the result is a gap in the transcript
rather than a dropped sample, so it goes out on `stt-error` with code `STT_SESSION_LOST`
and the overlay renders it as a toast. Reconnection is bounded at five consecutive
failures, after which the track emits `STT_SESSION_ENDED` and stops; the recording itself
continues.

The offset clock survives the gap. It counts audio **received from capture**, not audio
successfully sent, so it advances through the outage and the transcript resumes at the
right position instead of the length of the outage behind.

### Error codes on `stt-error`

| Code | Means |
| --- | --- |
| `STT_AUTH_REJECTED` | the NORA API refused the caller (401/403), or there is no web session |
| `STT_QUOTA_EXCEEDED` | the per-user session budget is spent (429) |
| `STT_NOT_CONFIGURED` | the deployment has no provider credential (503) |
| `STT_SERVICE_UNAVAILABLE` | the API could not reach the provider (502/504) |
| `STT_TRANSPORT_FAILED` | the NORA API itself was unreachable |
| `STT_SESSION_LOST` | the stream dropped and is reconnecting; audio in the gap is lost |
| `STT_SESSION_ENDED` | reconnection gave up; no more text for this track |
| `STT_PROVIDER_ERROR` | the provider sent an error frame on an open session |

### Speaker attribution: PER TRACK (there is no online diarization)

A deliberate product decision, carried forward unchanged from ADR 0035 §Decision 4. The
provider's realtime transcription is per stream and carries no notion of a speaker, so
nothing is recovered by the vendor change and nothing is promised.

| Track    | Who it is                  | `speakerId` emitted |
| -------- | -------------------------- | ------------------- |
| `mic`    | the local user             | `null`              |
| `system` | remote participants        | `"Participantes"`   |

`mic` sends `null` because the frontend already treats `track === "mic"` as "Me"/"You"
before looking at `speakerId`. `system` sends a **stable, non-empty** id
because `overlay.tsx` skips lines without an id (`if (!id) continue`) — with `null` there the
speaker-rename UI would be permanently empty and `participants` would never be
filled in on upload. Renaming in the overlay keeps working normally.

**Real consequence:** on a call with 3 remote people, all three appear
grouped under a single label. That is intentional — it is the price of having no
online diarization, not a bug.

### What this means for the user

| | |
| --- | --- |
| Network | **required** — no network, no transcription |
| Audio leaving the machine | **yes**, straight to the provider (not through NORA) |
| Cost per minute | real, billed to NORA; small at demonstration volume |
| First use | nothing to download, no hardware floor |
| Diarization | per track (see above) |
| Latency | real streaming; partials are incremental |
| `confidence` | always `null` (see below) |

### Note on `confidence`

Always `null`, and it stays that way. ADR 0035 refused to publish an uncalibrated logprob
average in a field that used to carry a trained score, and ADR 0039 carried that refusal
forward: token logprobs are obtainable from this provider too and are still not calibrated.
Re-populating the field would silently give every downstream threshold a new meaning.

### Sample rate

One constant, `stt::TARGET_SAMPLE_RATE`, is the rate every capture path resamples to and
the rate the client declares to the provider on connect. It must agree with
`nora.stt.openai.sample-rate` on the backend, which is what sessions are minted with.

A disagreement fails nowhere: it plays the audio to the provider at the wrong speed and
comes back as confident nonsense. The client therefore warns when the two differ and always
declares the rate it actually sends, so what is streamed and what is claimed cannot
diverge. `stt.rs` carries a unit test asserting the number, so changing it is a decision
rather than an edit.

## Environment Variables

See `.env.example` for the full, commented list.

```bash
# NORA API URL (default: http://localhost:8080)
NORA_API_BASE_URL=http://localhost:8080
```

That is the whole list on this side. Everything about transcription — provider, model,
audio format, VAD, session lifetime — is resolved by the backend that pays for it.

> An app opened from Explorer **does not inherit env from the shell**. In production the
> effective value comes from `tauri.conf.json` or from the env injected at build time by
> `build.rs`.

## CI/CD

The `desktop-bundle` job builds one artifact, Windows (x86_64) `.msi`, and it runs only on
a push to `main` — not on pull requests. See `.github/workflows/ci.yml` for details.

> The `desktop-rust` job used to take about ten minutes per merge, almost all of it
> compiling whisper.cpp through a build script, and both workflows carried a `MAX_PATH`
> workaround for CMake's scratch directories. Both are gone (ADR 0039/0045).

## Troubleshooting

### "Session not found — please log in in the Nora main window"

The desktop authenticates with the web session cookie of the `main` window. Open it and log
in; there is no separate desktop login, and the keyring is not a source of credentials for
these paths.

### Recording refuses to start with `STT_NOT_CONFIGURED`

The API you are pointing at has no transcription credential. Set
`NORA_STT_OPENAI_API_KEY` (or `OPENAI_API_KEY`) on the **API**, never here.

### The transcript has a gap and a "reconnecting" toast appeared

Expected behaviour on a dropped connection, not a bug: audio captured while the socket was
down is not transcribed. The offsets after the gap are still correct. Repeated drops that
end in `STT_SESSION_ENDED` mean five consecutive reconnection attempts failed — check the
network and the API.

### Transcription is out of sync or reads as nonsense

Check that `stt::TARGET_SAMPLE_RATE` and the API's `nora.stt.openai.sample-rate` are the
same number. A mismatch is the one failure mode in this path that produces confident
garbage instead of an error, and the desktop log carries a `[stt_cloud]` warning naming
both values.

## License

See the `LICENSE` file at the root of the repository (AGPL-3.0, ADR 0017).
