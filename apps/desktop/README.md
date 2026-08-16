# NORA Desktop

Desktop application for real-time audio capture and meeting transcription.
Transcription runs **locally on the user's machine** (embedded Whisper). There is
no cloud STT backend and no subprocess: audio does not leave the machine.

> **Windows only.** macOS and Linux were dropped: the macOS path required the user to
> install the BlackHole virtual driver and the Linux path shelled out to PulseAudio's
> `parecord`, and neither had ever been exercised by anyone. ADR 0008 and ADR 0035 describe
> a three-platform client — they are accepted records of what was decided at the time, and
> this is what the client does now.

## Stack

- **Frontend**: React 18 + TypeScript + Tailwind CSS
- **Backend**: Tauri 2 (Rust)
- **STT**: `whisper.cpp` in-process via the [`whisper-rs`](https://crates.io/crates/whisper-rs) crate — offline, no network
- **Build**: Vite + Tauri CLI

## Prerequisites

> `whisper.cpp` is compiled from C++ source by the `whisper-rs-sys` build
> script. That puts **CMake + a C++ compiler** on the list of prerequisites.

### Windows

- [Visual Studio Build Tools](https://visualstudio.microsoft.com/downloads/) with the **"Desktop development with C++"** workload
- [CMake](https://cmake.org/download/) on the `PATH`
- [Node.js 20](https://nodejs.org/)
- [Rust](https://rustup.rs/)

> **Long path (MAX_PATH):** the MSBuild that CMake uses underneath still has
> parts limited to 260 characters. If the repository is in a deep
> directory (`C:\Users\<you>\OneDrive\Desktop\...`), the `whisper-rs-sys` build
> fails with `error MSB6003 ... cmTC_xxxx.tlog` / `DirectoryNotFoundException`.
> It is not a code error. Work around it with a short target dir:
>
> ```powershell
> $env:CARGO_TARGET_DIR = "C:\nrt"
> ```

## Development

```bash
# At the monorepo root
cd apps/desktop

# Install dependencies
npm install

# Run in dev mode. The first `start_recording` downloads the Whisper
# model (~488 MB on `small`).
npx tauri dev
```

To iterate quickly without waiting for the large model:

```bash
NORA_WHISPER_MODEL=tiny npx tauri dev
```

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
    │   ├── stt.rs                # SttBackend trait + backend selection
    │   ├── stt_local.rs          # STT: whisper.cpp in-process
    │   ├── whisper_model.rs      # Download/cache/checksum of the GGML model
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
│      backend selected at runtime    │                 │
│                                     ▼                 │
│                        ┌────────────────────────┐     │
│                        │ stt_local.rs           │     │
│                        │ whisper.cpp in-process │     │
│                        │ offline, no network    │     │
│                        └────────────────────────┘     │
└───────────────────────────────────────────────────────┘
```

### Data Flow

1. **Capture**: Rust (cpal) captures audio from the microphone and from the system
2. **Resampling**: converts to PCM 16 kHz / 16-bit / mono
3. **Routing**: one STT session per track (`mic` and `system`), both behind the `SttBackend` trait
4. **Transcription**: `whisper.cpp` in-process, one re-decode loop per track
5. **UI**: both tracks emit the **same** Tauri `transcript` event — the frontend does not distinguish them

## Speech-to-Text (STT)

### Backend selection

There is **one** backend, `local`. It is still resolved at **runtime**, in this
priority order (`src/stt.rs`):

1. env `NORA_STT_BACKEND` (only works when launching the app from a terminal)
2. env injected at build time by `build.rs` (CI/release)
3. `plugins.nora.sttBackend` in `tauri.conf.json`
4. default: `local`

The only accepted values are `local` and `whisper`. Anything else — including a
stale `azure` left in an old config or in a shell — falls back to the default
with a warning on stderr instead of failing; it never takes the app down.

The selection machinery is kept on purpose even with a single implementation:
cloud transcription is planned to come back as a second backend, and the seam is
worth more than the few lines it costs.

`stt-local` is the only default feature, so a plain build is the local backend:

```bash
cargo build                                # stt-local, CPU
cargo build --features whisper-vulkan      # opt-in GPU, needs the Vulkan SDK
```

### Speaker attribution: PER TRACK (there is no online diarization)

A deliberate product decision. Whisper **does not do diarization**, and
WhisperX/pyannote are batch by construction — there is no honest streaming
version of that. Instead of inventing unstable labels that would corrupt the
recorded transcript with name *churn*:

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

### Model

Downloaded **on demand on first use** into `<app_data_dir>/models/`, with
`sha256` verification. No model is embedded in the installer.

| Size     | Download | Approx. RAM | Note                                          |
| -------- | -------- | ----------- | --------------------------------------------- |
| `tiny`   | ~78 MB   | ~0.4 GB     | smoke test/CI only; poor quality in pt-BR     |
| `base`   | ~148 MB  | ~0.6 GB     | acceptable in pt-BR, runs on a weak CPU       |
| `small`  | ~488 MB  | ~1.2 GB     | **default** — best trade-off in pt-BR         |
| `medium` | ~1.5 GB  | ~3 GB       | best quality; requires a strong CPU or a GPU  |

The two tracks **share** a single `WhisperContext` (the weights); only the KV
cache is per track. Without that, `small` would cost ~930 MB in weights alone.

Download progress goes to the Tauri `stt-model-progress` event
(`checking` → `downloading` → `verifying` → `ready` | `error`). The frontend does not
listen to that event yet — the hook is ready, the progress bar UI is not.

### Machine requirements

- **CPU**: 4 cores is the realistic floor for `small` with two simultaneous tracks.
  Inference uses half the cores per track (capped at 4) precisely because there are two
  decoding at the same time; giving `min(4, cores)` to each causes
  *oversubscription* and worsens latency.
- **RAM**: model + KV cache + the Tauri webview.
- **Disk**: the size of the model, once, in the app data dir.
- **GPU**: none by default — whisper.cpp runs on CPU. Vulkan/CUDA are explicit opt-in
  (`--features whisper-vulkan` / `whisper-cuda`) because they require the vendor SDK
  on the **build** machine, not just the runtime one. (Metal used to be on by default,
  automatically, on macOS. It went with macOS support.)

### What this means for the user

| | |
| --- | --- |
| Network | **not used** for transcription |
| Audio leaving the machine | **never** |
| Cost per minute | zero |
| First use | downloads the model (~488 MB on `small`) |
| Diarization | per track (see above) |
| Latency | pseudo-real-time, ~1 s per partial |
| `confidence` | **not calibrated** (see below) |

### Note on `confidence`

The emitted value is `exp(mean of the tokens' ln(p))` — Whisper's `avg_logprob`
normalized. It is **not comparable** with the `NBest[0].Confidence` of the Azure
Speech backend this replaced, which was a trained score. A fluent hallucination
scores **high**, and the scale changes with the model size. It is good for ordering
segments within the same session and the same model, and nothing else. Any threshold
carried over from the old scale needs to be recalibrated.

### Streaming: how it works

Whisper has a fixed 30 s window and **has no incremental state**. Streaming
here is a re-decode loop over a sliding window with energy-based VAD: roughly every
900 ms the window is re-decoded and a `partial` comes out; when the VAD sees ~700 ms of
silence (or the window reaches 22 s), the `final` comes out and the clock advances.

The trap is the **offset regressing** between re-decodes. The `committed_ms` counter is
monotonic and is the base offset of every event; the `final`s go through a
`last_final_end_ms.max(...)` clamp. Details in `src/stt_local.rs`.

## Environment Variables

See `.env.example` for the full, commented list.

```bash
# NORA API URL (default: http://localhost:8080)
NORA_API_BASE_URL=http://localhost:8080

# STT
NORA_STT_BACKEND=local        # local | whisper — anything else falls back to local
NORA_WHISPER_MODEL=small      # tiny | base | small | medium
```

> An app opened from Explorer/Finder **does not inherit env from the shell**. In production the
> effective value comes from `tauri.conf.json` or from the env injected at build time.

## CI/CD

The `desktop-bundle` job builds one artifact, Windows (x86_64) `.msi`, and it runs only on
a push to `main` — not on pull requests. See `.github/workflows/ci.yml` for details.

> **Known pending item.** Compiling `whisper.cpp` from source changes the CI requirements
> and that **has not been validated on a runner**:
>
> - The current `timeout-minutes: 60` may not be enough on a cold *cache miss*: the
>   ggml/whisper C++ build lands on the critical path.
> - `swatinem/rust-cache` caches `target/`, which includes the C++ artifacts —
>   but the key is invalidated on every `Cargo.lock` change.
> - The updater-signature verification moved from the Ubuntu runner (where `minisign` was
>   an apt package) to the Windows one, where it installs through Chocolatey. Because the
>   job does not run on pull requests, the first push to `main` is what proves that port.

## Troubleshooting

### Windows: `error MSB6003` / `DirectoryNotFoundException` when compiling whisper.cpp

MSBuild's 260-character limit, not a code error. Use a short target dir:

```powershell
$env:CARGO_TARGET_DIR = "C:\nrt"
```

### Model download fails or hangs

The model comes from HuggingFace. On a network that blocks HF, point at a mirror
(the checksum is still verified):

```bash
NORA_WHISPER_MODEL_BASE_URL=https://mirror.interno/whisper.cpp
```

To use a local file (skips the download **and** the checksum):

```bash
NORA_WHISPER_MODEL_PATH=/path/ggml-small.bin
```

A `.bin` that fails verification is deleted and re-downloaded from scratch — there is no resume.
Cache in `<app_data_dir>/models/`; deleting that folder forces a fresh download.

### Local transcription too slow

In this order:

1. Downgrade the model: `NORA_WHISPER_MODEL=base` (or `tiny` for testing).
2. Reduce the encoder: `NORA_WHISPER_AUDIO_CTX=768` (loses quality).
3. Tune threads: `NORA_WHISPER_THREADS=N` — remember that **there are two tracks**
   decoding at the same time; raising it too much makes things worse.
4. Turn off system audio capture if you only need your microphone —
   it cuts half the inference load.

If `GAP of Nms in the audio (inference lagging)` shows up in the log, the machine is not
keeping up with real time and audio is being dropped.

## License

See the `LICENSE` file at the root of the repository (AGPL-3.0, ADR 0017).
