# NORA Desktop

Aplicativo desktop para captura de áudio em tempo real e transcrição de reuniões usando Azure Speech-to-Text.

## Stack

- **Frontend**: React 18 + TypeScript + Tailwind CSS
- **Backend**: Tauri 2 (Rust)
- **STT**: Python sidecar com Azure Speech SDK
- **Build**: Vite + Tauri CLI + PyInstaller

## Pré-requisitos

### Linux

```bash
# Debian/Ubuntu
sudo apt-get update
sudo apt-get install -y libwebkit2gtk-4.1-dev libappindicator3-dev librsvg2-dev patchelf

# Fedora
sudo dnf install webkit2gtk4.1-devel libappindicator-gtk3-devel librsvg2-devel patchelf
```

### Windows

- [Visual Studio 2022 Build Tools](https://visualstudio.microsoft.com/downloads/) com "C++ build tools"
- [Python 3.12](https://python.org/)
- [Node.js 20](https://nodejs.org/)
- [Rust](https://rustup.rs/)

### macOS

- Xcode Command Line Tools: `xcode-select --install`
- Python 3.12, Node.js 20, Rust

## Desenvolvimento

```bash
# Na raiz do monorepo
cd apps/desktop

# Instalar dependências
pnpm install

# Build do sidecar (necessário antes de rodar o app)
cd sidecar
pip install -r requirements.txt
python build/build_sidecar.py
cd ..

# Rodar em modo dev
pnpm tauri dev
```

## Build de Produção

```bash
# Linux
cd sidecar && python build/build_sidecar.py && cd ..
pnpm tauri build

# Windows (cross-compile não suportado)
pnpm tauri build

# macOS (Apple Silicon)
pnpm tauri build --target aarch64-apple-darwin
```

## Estrutura

```
apps/desktop/
├── src/                    # Frontend React
│   ├── pages/             # Páginas (recording, meetings, etc)
│   ├── hooks/             # Custom hooks (useRecording)
│   ├── lib/               # Utilitários e API client
│   └── components/        # Componentes reutilizáveis
├── src-tauri/             # Backend Rust
│   ├── src/
│   │   ├── audio_capture.rs      # Captura de áudio (cpal)
│   │   ├── audio_resample.rs     # Resampling (rubato)
│   │   ├── stt_sidecar.rs        # Orquestração do sidecar
│   │   ├── system_audio.rs       # Áudio do sistema (Linux/Win/macOS)
│   │   ├── http_proxy.rs         # Proxy HTTP para API
│   │   ├── secrets.rs            # Armazenamento de secrets
│   │   └── commands.rs           # Comandos Tauri
│   └── Cargo.toml
└── sidecar/               # Sidecar Python
    ├── src/
    │   ├── transcriber.py        # Azure Speech SDK
    │   ├── protocol.py           # Protocolo JSON Lines
    │   └── audio_pipe.py         # Pipe de áudio
    └── build/
        └── build_sidecar.py      # Script PyInstaller
```

## Arquitetura

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

### Fluxo de Dados

1. **Captura**: Rust (cpal) captura áudio do microfone e sistema
2. **Resampling**: Converte para 16kHz mono
3. **Envio**: Envia chunks de áudio para o sidecar via stdin
4. **Transcrição**: Sidecar usa Azure Speech SDK
5. **Resultado**: Sidecar envia transcrições via stdout (JSON Lines)
6. **UI**: Frontend exibe transcrição em tempo real

## Variáveis de Ambiente

```bash
# URL da API NORA (default: http://localhost:8080)
NORA_API_BASE_URL=http://localhost:8080
```

## CI/CD

O workflow de CI builda automaticamente para todas as plataformas:

- Ubuntu (x86_64): `.deb` e `.AppImage`
- Windows (x86_64): `.msi`
- macOS (aarch64): `.dmg`

Ver `.github/workflows/ci.yml` para detalhes.

## Troubleshooting

### Linux: Erro de áudio

Verifique se o PulseAudio está rodando:
```bash
pactl info
```

### Sidecar não encontrado

O sidecar deve estar em `src-tauri/binaries/` com o nome correto:
- Linux: `nora-stt-sidecar-x86_64-unknown-linux-gnu`
- Windows: `nora-stt-sidecar-x86_64-pc-windows-msvc.exe`
- macOS: `nora-stt-sidecar-aarch64-apple-darwin`

### Build falha no Linux

Instale as dependências do sistema:
```bash
sudo apt-get install -y libwebkit2gtk-4.1-dev libappindicator3-dev librsvg2-dev patchelf
```

## Licença

MIT - NORA Team
