# 0008 — Desktop App with Tauri 2 + Python Sidecar

- Status: accepted (Python sidecar superseded by ADR 0035 — Whisper embedded in Tauri/Rust; the decision for Tauri 2 and the audio capture remain)
- Date: 2026-05-07
- Related: ADR 0035 (supersedes the Python sidecar part of this decision)

## Context

The NORA MVP needs to capture meeting audio in real time on users' desktops. The architecture must:

- Work on Windows, macOS and Linux
- Capture audio from the microphone and from the system (loopback)
- Transcribe in real time using Azure Speech-to-Text
- Package everything into a native installer

## Decision

Use **Tauri 2** (Rust + WebView) as the desktop framework, with a **Python sidecar** for STT (Speech-to-Text).

### Architecture

```
┌─────────────────────────────────────────┐
│           NORA Desktop App              │
│  ┌─────────────┐    ┌───────────────┐  │
│  │  Frontend   │    │  Rust Backend │  │
│  │  (React)    │◄──►│  (Tauri)      │  │
│  └─────────────┘    └───────┬───────┘  │
│                             │           │
│  ┌──────────────────────────┴───────┐  │
│  │      Sidecar Python              │  │
│  │  (Azure Speech SDK)              │  │
│  └──────────────────────────────────┘  │
└─────────────────────────────────────────┘
```

### Components

1. **Frontend**: React + TypeScript + Tailwind
2. **Rust backend**: Tauri commands for audio capture and HTTP proxying
3. **Python sidecar**: separate process that runs the Azure Speech SDK
4. **Packaging**: PyInstaller for the sidecar + Tauri bundler for the app

## Consequences

### Positive

- **Reduced size**: native app ~5-15MB vs Electron ~150MB
- **Performance**: Rust for audio capture, Python for STT
- **Security**: Azure keys are not in the JS source code
- **Cross-platform**: Windows, macOS, Linux with the same codebase

### Negative

- **Complexity**: two runtimes (Rust + Python) to maintain
- **Sidecar**: a separate process adds IPC overhead
- **Build**: a more complex build pipeline (Rust + Python + Node)

## Alternatives Considered

### 1. Electron + Node.js

- **Pros**: mature ecosystem, simpler
- **Cons**: large size, inferior performance, weak security
- **Verdict**: discarded because of size and performance

### 2. Flutter Desktop

- **Pros**: consistent UI, good performance
- **Cons**: learning curve, smaller ecosystem for desktop
- **Verdict**: discarded because we have no expertise on the team

### 3. Tauri + pure Rust (no sidecar)

- **Pros**: a single runtime, simpler
- **Cons**: the Azure Speech SDK has no mature official Rust crate
- **Verdict**: discarded due to the lack of an official Rust SDK

### 4. Tauri + WASM

- **Pros**: no sidecar, everything in the main process
- **Cons**: the Azure Speech SDK does not support WASM
- **Verdict**: discarded due to technical incompatibility

## Implementation Notes

### Python Sidecar

- Uses `azure-cognitiveservices-speech` (official Microsoft SDK)
- Communication via stdin/stdout with the NDJSON protocol (JSON Lines)
- Packaged with PyInstaller into a standalone binary
- Binary included in the Tauri bundle via `externalBin`
- **Entry point**: `nora_stt_sidecar_main.py` is a 1-line wrapper that delegates to `nora_stt_sidecar.__main__:main()`

### Audio Capture

- **Linux**: `cpal` + `parecord` (PulseAudio/PipeWire monitor)
- **Windows**: `cpal` + WASAPI loopback
- **macOS**: `cpal` + ScreenCaptureKit (placeholder)

### CI/CD

- GitHub Actions with a matrix: Ubuntu, Windows, macOS
- Sidecar build before the Tauri build
- Artifact upload for each platform
- The sidecar's **pytest** runs in the desktop job before the build

## Note on MVP Scope

The Desktop was brought forward relative to the original MVP boundary (which foresaw only Web + Backend + Worker). The decision to include the Desktop in Sprint 1+2 was taken in order to:

1. **Competitive differentiation**: real-time capture is a key differentiator vs competitors
2. **Technical validation**: prove that the sidecar architecture works end-to-end
3. **FIAP demo**: have a strong visual demonstration for the Challenge

The Desktop remains **post-MVP** in terms of maturity (SSO, audio/video upload, complete MCPs), but the technical foundation was established ahead of time.

## References

- [Tauri Documentation](https://tauri.app/)
- [Azure Speech SDK Python](https://docs.microsoft.com/azure/cognitive-services/speech-service/)
- [PyInstaller](https://pyinstaller.org/)
- Issue #8: Desktop Tauri Scaffold
