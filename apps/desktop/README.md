# NORA Desktop

Desktop application for real-time audio capture and meeting transcription.
Transcription runs **locally on the user's machine** (embedded Whisper); the
Azure Speech backend remains available as legacy during the transition.

## Stack

- **Frontend**: React 18 + TypeScript + Tailwind CSS
- **Backend**: Tauri 2 (Rust)
- **STT (default)**: `whisper.cpp` in-process via the [`whisper-rs`](https://crates.io/crates/whisper-rs) crate — offline, no network
- **STT (legacy)**: Python sidecar with the Azure Speech SDK, behind the `stt-azure` feature
- **Build**: Vite + Tauri CLI (+ PyInstaller only for the legacy sidecar)

## Prerequisites

> **New:** `whisper.cpp` is compiled from C++ source by the `whisper-rs-sys` build
> script. That adds **CMake + a C++ compiler** to the list of
> prerequisites on all three targets. Python became optional (it is only needed
> for the legacy Azure sidecar).

### Linux

```bash
# Debian/Ubuntu
sudo apt-get update
sudo apt-get install -y libwebkit2gtk-4.1-dev libappindicator3-dev librsvg2-dev patchelf \
  build-essential cmake libasound2-dev

# Fedora
sudo dnf install webkit2gtk4.1-devel libappindicator-gtk3-devel librsvg2-devel patchelf \
  gcc-c++ cmake alsa-lib-devel
```

### Windows

- [Visual Studio Build Tools](https://visualstudio.microsoft.com/downloads/) with the **"Desktop development with C++"** workload
- [CMake](https://cmake.org/download/) on the `PATH`
- [Node.js 20](https://nodejs.org/)
- [Rust](https://rustup.rs/)
- Python 3.12 — **optional**, only for the legacy Azure sidecar

> **Long path (MAX_PATH):** the MSBuild that CMake uses underneath still has
> parts limited to 260 characters. If the repository is in a deep
> directory (`C:\Users\<you>\OneDrive\Desktop\...`), the `whisper-rs-sys` build
> fails with `error MSB6003 ... cmTC_xxxx.tlog` / `DirectoryNotFoundException`.
> It is not a code error. Work around it with a short target dir:
>
> ```powershell
> $env:CARGO_TARGET_DIR = "C:\nrt"
> ```

### macOS

- Xcode Command Line Tools: `xcode-select --install` (brings clang++ and make)
- CMake: `brew install cmake`
- Node.js 20, Rust
- Python 3.12 — **optional**, only for the legacy Azure sidecar

## Development

```bash
# At the monorepo root
cd apps/desktop

# Install dependencies
pnpm install

# Run in dev mode — no longer need to build the Python sidecar.
# The first `start_recording` downloads the Whisper model (~488 MB on `small`).
pnpm tauri dev
```

To iterate quickly without waiting for the large model:

```bash
NORA_WHISPER_MODEL=tiny pnpm tauri dev
```

## Production Build

```bash
# Linux / Windows / macOS — local backend (default), no Python
pnpm tauri build

# macOS (Apple Silicon)
pnpm tauri build --target aarch64-apple-darwin
```

Legacy Azure backend (requires the Python sidecar and the config overlay that
declares `externalBin`):

```bash
cd sidecar && pip install -e ".[dev]" && python build_sidecar.py && cd ..
pnpm tauri build -- --config src-tauri/tauri.azure.conf.json
```

> The base `tauri.conf.json` no longer declares `externalBin`. With it in the base,
> `tauri-build` required the PyInstaller binary to exist already at *build script*
> time — meaning not even `cargo check` would run without packaging Python
> first. Since the local backend is the default, the sidecar cannot be a compilation
> prerequisite.

## Structure

```
apps/desktop/
├── src/                    # React frontend
│   ├── pages/             # Pages (recording, meetings, etc)
│   ├── hooks/             # Custom hooks (useRecording)
│   ├── lib/               # Utilities and API client
│   └── components/        # Reusable components
├── src-tauri/             # Rust backend
│   ├── src/
│   │   ├── audio_capture.rs      # Audio capture (cpal)
│   │   ├── audio_resample.rs     # Resampling (rubato)
│   │   ├── stt.rs                # SttBackend trait + backend selection
│   │   ├── stt_local.rs          # Local STT: whisper.cpp in-process (DEFAULT)
│   │   ├── whisper_model.rs      # Download/cache/checksum of the GGML model
│   │   ├── stt_sidecar.rs        # Legacy STT: Azure sidecar (feature stt-azure)
│   │   ├── speech_token.rs       # Azure token (only in the stt-azure feature)
│   │   ├── system_audio.rs       # System audio (Linux/Win/macOS)
│   │   ├── http_proxy.rs         # HTTP proxy for the API
│   │   ├── secrets.rs            # Secrets storage
│   │   └── commands.rs           # Tauri commands
│   ├── tauri.conf.json           # Base config (local backend, no externalBin)
│   ├── tauri.azure.conf.json     # Legacy bundle overlay (declares externalBin)
│   └── Cargo.toml
└── sidecar/               # Python sidecar (LEGACY — only with the azure backend)
    ├── src/
    │   └── nora_stt_sidecar/
    │       ├── transcriber.py    # Azure Speech SDK
    │       ├── protocol.py       # JSON Lines protocol
    │       └── audio_pipe.py     # Audio pipe
    ├── tests/                    # pytest
    ├── build_sidecar.py          # PyInstaller script (cross-platform)
    ├── sidecar-linux.spec        # PyInstaller spec (Linux)
    ├── sidecar-macos.spec        # PyInstaller spec (macOS)
    └── sidecar-windows.spec      # PyInstaller spec (Windows)
```

## Architecture

```
┌───────────────────────────────────────────────────────┐
│                   NORA Desktop App                    │
│  ┌─────────────┐         ┌────────────────────────┐  │
│  │  Frontend   │◄───────►│     Rust Backend       │  │
│  │  (React)    │  event  │       (Tauri)          │  │
│  └─────────────┘"transcript"└──────────┬───────────┘  │
│                                        │              │
│              backend selected at runtime               │
│                    ┌───────────────────┴──────┐       │
│                    ▼                          ▼       │
│      ┌─────────────────────────┐   ┌──────────────┐  │
│      │  stt_local.rs (DEFAULT) │   │Sidecar Python│  │
│      │  whisper.cpp in-process │   │(Azure Speech)│  │
│      │  offline, no network    │   │   LEGACY     │  │
│      └─────────────────────────┘   └──────────────┘  │
└───────────────────────────────────────────────────────┘
```

### Data Flow

1. **Capture**: Rust (cpal) captures audio from the microphone and from the system
2. **Resampling**: converts to PCM 16 kHz / 16-bit / mono
3. **Routing**: one STT backend per track (`mic` and `system`), both behind the `SttBackend` trait
4. **Transcription**: `whisper.cpp` in-process (default) or the Azure sidecar (legacy)
5. **UI**: both emit the **same** Tauri `transcript` event — the frontend does not distinguish them

## Speech-to-Text (STT)

### Backend selection

Resolved at **runtime**, in this priority order (`src/stt.rs`):

1. env `NORA_STT_BACKEND` (only works when launching the app from a terminal)
2. env injected at build time by `build.rs` (CI/release)
3. `plugins.nora.sttBackend` in `tauri.conf.json`
4. default: `local`

An unknown value falls back to the default with a warning — it never takes the app down. Asking for a
backend that was not compiled degrades to whichever one exists.

```bash
cargo build                                          # both backends in the binary
cargo build --no-default-features --features stt-local   # pure local, no Python
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
grouped under a single label. This is a regression compared with Azure's diarization
(`Guest-1`/`Guest-2`) and it is intentional.

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
- **GPU**: Metal is on by default on macOS. Vulkan/CUDA are explicit opt-in
  (`--features whisper-vulkan` / `whisper-cuda`) because they require the vendor SDK
  on the **build** machine, not just the runtime one.
- **macOS 11+**, and this is *mandatory*: `bundle.macOS.minimumSystemVersion` is
  pinned to `"11.0"` in `tauri.conf.json`.

#### Why the macOS floor lives in `tauri.conf.json`, and not in CI

whisper.cpp's `ggml` uses `std::filesystem`, which Apple's libc++ only exposes from
deployment target **10.15** onward. With Tauri's default (**10.13**), clang
aborts with `'path' is unavailable: introduced in macOS 10.15` in
`ggml-backend-reg.cpp`, and the `whisper-rs-sys` build script panics.

**Setting `MACOSX_DEPLOYMENT_TARGET` in the workflow does not fix it** — that was tried in
[#358](https://github.com/sf0rzin/nora/pull/358) and failed. `tauri build`
*exports* that variable from `bundle.macOS.minimumSystemVersion` and
overwrites whatever is in the environment. The value has to be in the Tauri config,
which is also where it applies for local builds.

`11.0` instead of `10.15` because the target is `aarch64-apple-darwin`: Apple Silicon
does not exist before macOS 11, so no real compatibility is being discarded.

> Do not try to document this with a `"//"` key inside `bundle.macOS`. The
> Tauri schema rejects an unknown field there and takes the build down with
> `unknown field '//'` — that happened in
> [#359](https://github.com/sf0rzin/nora/pull/359). That is why the explanation
> lives here.

### What changes for the user

| | Azure (before) | Local (now) |
| --- | --- | --- |
| Network | required | **not used** |
| `/speech/token` | on every recording | **not called** |
| Audio leaves the machine | yes | **no** |
| Cost per minute | yes | zero |
| First use | immediate | downloads the model (~488 MB) |
| Diarization | `Guest-1`/`Guest-2` | per track (see above) |
| Latency | ~200-400 ms | pseudo-real-time, ~1 s per partial |
| `confidence` | calibrated (`NBest[0]`) | **not calibrated** (see below) |

### Note on `confidence`

The emitted value is `exp(mean of the tokens' ln(p))` — Whisper's `avg_logprob`
normalized. It is **not comparable** with Azure's `NBest[0].Confidence`, which was a
trained score. A fluent hallucination scores **high**, and the scale changes with the
model size. It is good for ordering segments within the same session and the same
model, and nothing else. Any threshold on top of it needs to be recalibrated.

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
NORA_STT_BACKEND=local        # local | azure
NORA_WHISPER_MODEL=small      # tiny | base | small | medium
```

> An app opened from Explorer/Finder **does not inherit env from the shell**. In production the
> effective value comes from `tauri.conf.json` or from the env injected at build time.

## CI/CD

The CI workflow builds automatically for all platforms:

- Ubuntu (x86_64): `.deb` and `.AppImage`
- Windows (x86_64): `.msi`
- macOS (aarch64): `.dmg`

See `.github/workflows/ci.yml` for details.

> **Known pending item — `desktop-release.yml` has not been adjusted yet.**
> Compiling `whisper.cpp` from source changes the CI requirements and that
> **has not been validated on a runner**:
>
> - The current `timeout-minutes: 60` may not be enough on a cold *cache miss*: the
>   ggml/whisper C++ build lands on the critical path of both runners.
> - The matrix today is only `windows-latest` + `ubuntu-latest` — **there is no macOS
>   runner**, so the Metal path is never exercised by CI.
> - The `setup-python` / `build_sidecar.py` steps became optional for the default
>   bundle, but they are still in the workflow.
> - `swatinem/rust-cache` caches `target/`, which includes the C++ artifacts —
>   but the key is invalidated on every `Cargo.lock` change.

## Troubleshooting

### Linux: Audio error

Check that PulseAudio is running:
```bash
pactl info
```

### macOS: System audio capture

Capturing system audio (the voices of other participants on calls) currently requires
the **BlackHole** virtual driver:

1. Install BlackHole 2ch: https://existential.audio/blackhole/
2. In **Audio MIDI Setup → Multi-Output Device**, create a device combining your speakers
   and BlackHole 2ch so you keep hearing the audio while NORA captures it.
3. Select that Multi-Output Device as the system output during the meeting.
4. On the first run, macOS will ask for **Microphone** and (in the future) **Screen Recording**
   permission in *Privacy & Security*. Approve both.

> **Roadmap (Issue #15):** native support via ScreenCaptureKit on macOS 13+ (no virtual driver)
> is planned. The entitlements (`NSScreenCaptureUsageDescription`) and version detection
> are already in the code; only the integration with the `screencapturekit` crate is missing — it requires
> validation on Apple hardware.

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

### Sidecar not found

> Only applies to the **azure** (legacy) backend. There is no sidecar in the local backend.
> If you are seeing this with `sttBackend: "local"`, something forced `NORA_STT_BACKEND=azure`.

The sidecar must be in `src-tauri/binaries/` with the correct name:
- Linux x86_64: `nora-stt-sidecar-x86_64-unknown-linux-gnu`
- Linux ARM64: `nora-stt-sidecar-aarch64-unknown-linux-gnu`
- Windows x86_64: `nora-stt-sidecar-x86_64-pc-windows-msvc.exe`
- macOS Intel: `nora-stt-sidecar-x86_64-apple-darwin`
- macOS Apple Silicon: `nora-stt-sidecar-aarch64-apple-darwin`

`build_sidecar.py` detects the platform automatically and generates the binary with the
correct name based on `platform.system()` + `platform.machine()`. The corresponding
PyInstaller specs are `sidecar-linux.spec`, `sidecar-macos.spec` and `sidecar-windows.spec`.

### Build fails on Linux

Install the system dependencies:
```bash
sudo apt-get install -y libwebkit2gtk-4.1-dev libappindicator3-dev librsvg2-dev patchelf
```

## License

MIT - NORA Team
