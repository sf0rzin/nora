# NORA Desktop

Aplicativo desktop para captura de áudio em tempo real e transcrição de reuniões.
A transcrição roda **localmente na máquina do usuário** (Whisper embarcado); o
backend Azure Speech continua disponível como legado durante a transição.

## Stack

- **Frontend**: React 18 + TypeScript + Tailwind CSS
- **Backend**: Tauri 2 (Rust)
- **STT (default)**: `whisper.cpp` in-process via crate [`whisper-rs`](https://crates.io/crates/whisper-rs) — offline, sem rede
- **STT (legado)**: sidecar Python com Azure Speech SDK, atrás da feature `stt-azure`
- **Build**: Vite + Tauri CLI (+ PyInstaller só pro sidecar legado)

## Pré-requisitos

> **Novo:** o `whisper.cpp` é compilado a partir do código-fonte C++ pelo build
> script do `whisper-rs-sys`. Isso adiciona **CMake + um compilador C++** à lista
> de pré-requisitos nos três alvos. Python passou a ser opcional (só é necessário
> pro sidecar Azure legado).

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

- [Visual Studio Build Tools](https://visualstudio.microsoft.com/downloads/) com o workload **"Desktop development with C++"**
- [CMake](https://cmake.org/download/) no `PATH`
- [Node.js 20](https://nodejs.org/)
- [Rust](https://rustup.rs/)
- Python 3.12 — **opcional**, só pro sidecar Azure legado

> **Caminho longo (MAX_PATH):** o MSBuild que o CMake usa por baixo ainda tem
> partes limitadas a 260 caracteres. Se o repositório estiver num diretório
> profundo (`C:\Users\<voce>\OneDrive\Desktop\...`), o build do `whisper-rs-sys`
> falha com `error MSB6003 ... cmTC_xxxx.tlog` / `DirectoryNotFoundException`.
> Não é erro de código. Contorne com um target dir curto:
>
> ```powershell
> $env:CARGO_TARGET_DIR = "C:\nrt"
> ```

### macOS

- Xcode Command Line Tools: `xcode-select --install` (traz clang++ e make)
- CMake: `brew install cmake`
- Node.js 20, Rust
- Python 3.12 — **opcional**, só pro sidecar Azure legado

## Desenvolvimento

```bash
# Na raiz do monorepo
cd apps/desktop

# Instalar dependências
pnpm install

# Rodar em modo dev — NÃO precisa mais buildar o sidecar Python.
# O primeiro `start_recording` baixa o modelo Whisper (~488 MB no `small`).
pnpm tauri dev
```

Pra iterar rápido sem esperar o modelo grande:

```bash
NORA_WHISPER_MODEL=tiny pnpm tauri dev
```

## Build de Produção

```bash
# Linux / Windows / macOS — backend local (default), sem Python
pnpm tauri build

# macOS (Apple Silicon)
pnpm tauri build --target aarch64-apple-darwin
```

Backend Azure legado (precisa do sidecar Python e do overlay de config que
declara o `externalBin`):

```bash
cd sidecar && pip install -e ".[dev]" && python build_sidecar.py && cd ..
pnpm tauri build -- --config src-tauri/tauri.azure.conf.json
```

> O `tauri.conf.json` base **não** declara mais `externalBin`. Com ele no base,
> o `tauri-build` exigia o binário PyInstaller já em *build script* — ou seja,
> nem `cargo check` rodava sem antes empacotar Python. Como o backend local é o
> default, o sidecar não pode ser pré-requisito de compilação.

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
│   │   ├── stt.rs                # Trait SttBackend + escolha do backend
│   │   ├── stt_local.rs          # STT local: whisper.cpp in-process (DEFAULT)
│   │   ├── whisper_model.rs      # Download/cache/checksum do modelo GGML
│   │   ├── stt_sidecar.rs        # STT legado: sidecar Azure (feature stt-azure)
│   │   ├── speech_token.rs       # Token do Azure (só na feature stt-azure)
│   │   ├── system_audio.rs       # Áudio do sistema (Linux/Win/macOS)
│   │   ├── http_proxy.rs         # Proxy HTTP para API
│   │   ├── secrets.rs            # Armazenamento de secrets
│   │   └── commands.rs           # Comandos Tauri
│   ├── tauri.conf.json           # Config base (backend local, sem externalBin)
│   ├── tauri.azure.conf.json     # Overlay do bundle legado (declara externalBin)
│   └── Cargo.toml
└── sidecar/               # Sidecar Python (LEGADO — só com o backend azure)
    ├── src/
    │   └── nora_stt_sidecar/
    │       ├── transcriber.py    # Azure Speech SDK
    │       ├── protocol.py       # Protocolo JSON Lines
    │       └── audio_pipe.py     # Pipe de áudio
    ├── tests/                    # pytest
    ├── build_sidecar.py          # Script PyInstaller (cross-platform)
    ├── sidecar-linux.spec        # PyInstaller spec (Linux)
    ├── sidecar-macos.spec        # PyInstaller spec (macOS)
    └── sidecar-windows.spec      # PyInstaller spec (Windows)
```

## Arquitetura

```
┌───────────────────────────────────────────────────────┐
│                   NORA Desktop App                    │
│  ┌─────────────┐         ┌────────────────────────┐  │
│  │  Frontend   │◄───────►│     Rust Backend       │  │
│  │  (React)    │ evento  │       (Tauri)          │  │
│  └─────────────┘"transcript"└──────────┬───────────┘  │
│                                        │              │
│              backend selecionado em runtime           │
│                    ┌───────────────────┴──────┐       │
│                    ▼                          ▼       │
│      ┌─────────────────────────┐   ┌──────────────┐  │
│      │  stt_local.rs (DEFAULT) │   │Sidecar Python│  │
│      │  whisper.cpp in-process │   │(Azure Speech)│  │
│      │  offline, sem rede      │   │   LEGADO     │  │
│      └─────────────────────────┘   └──────────────┘  │
└───────────────────────────────────────────────────────┘
```

### Fluxo de Dados

1. **Captura**: Rust (cpal) captura áudio do microfone e do sistema
2. **Resampling**: converte para PCM 16 kHz / 16-bit / mono
3. **Roteamento**: um backend de STT por track (`mic` e `system`), ambos por trás do trait `SttBackend`
4. **Transcrição**: `whisper.cpp` in-process (default) ou sidecar Azure (legado)
5. **UI**: os dois emitem o **mesmo** evento Tauri `transcript` — o frontend não distingue

## Speech-to-Text (STT)

### Escolha do backend

Resolvido em **runtime**, nesta ordem de prioridade (`src/stt.rs`):

1. env `NORA_STT_BACKEND` (só funciona lançando o app de um terminal)
2. env injetada em build-time pelo `build.rs` (CI/release)
3. `plugins.nora.sttBackend` no `tauri.conf.json`
4. default: `local`

Valor desconhecido cai no default com aviso — nunca derruba o app. Pedir um
backend que não foi compilado degrada pro que existe.

```bash
cargo build                                          # os dois backends no binário
cargo build --no-default-features --features stt-local   # local puro, sem Python
```

### Atribuição de falante: POR TRACK (não há diarização online)

Decisão de produto consciente. O Whisper **não faz diarização**, e
WhisperX/pyannote são batch por construção — não existe versão streaming
honesta disso. Em vez de inventar rótulos instáveis que corromperiam a
transcrição gravada com *churn* de nome:

| Track    | Quem é                     | `speakerId` emitido |
| -------- | -------------------------- | ------------------- |
| `mic`    | o usuário local            | `null`              |
| `system` | participantes remotos      | `"Participantes"`   |

O `mic` manda `null` porque o frontend já trata `track === "mic"` como "Eu"/"Você"
antes de olhar o `speakerId`. O `system` manda um id **estável e não-vazio**
porque `overlay.tsx` pula linhas sem id (`if (!id) continue`) — com `null` ali a
UI de renomear falante ficaria permanentemente vazia e `participants` nunca seria
preenchido no upload. Renomear na overlay continua funcionando normalmente.

**Consequência real:** numa chamada com 3 pessoas remotas, as três aparecem
agrupadas sob um único rótulo. Isso é uma regressão frente à diarização do Azure
(`Guest-1`/`Guest-2`) e é intencional.

### Modelo

Baixado **sob demanda no primeiro uso** para `<app_data_dir>/models/`, com
verificação de `sha256`. Nada de modelo embarcado no instalador.

| Tamanho  | Download | RAM aprox. | Observação                                    |
| -------- | -------- | ---------- | --------------------------------------------- |
| `tiny`   | ~78 MB   | ~0.4 GB    | só smoke test/CI; qualidade ruim em pt-BR     |
| `base`   | ~148 MB  | ~0.6 GB    | aceitável em pt-BR, roda em CPU fraca         |
| `small`  | ~488 MB  | ~1.2 GB    | **default** — melhor troca em pt-BR           |
| `medium` | ~1.5 GB  | ~3 GB      | melhor qualidade; exige CPU forte ou GPU      |

Os dois tracks **compartilham** um único `WhisperContext` (os pesos); só o KV
cache é por track. Sem isso o `small` custaria ~930 MB só de pesos.

O progresso do download vai pro evento Tauri `stt-model-progress`
(`checking` → `downloading` → `verifying` → `ready` | `error`). O frontend ainda
não escuta esse evento — o gancho está pronto, a UI de barra de progresso não.

### Requisitos de máquina

- **CPU**: 4 cores é o piso realista pro `small` com dois tracks simultâneos.
  A inferência usa metade dos cores por track (teto 4) justamente porque há dois
  decodificando ao mesmo tempo; dar `min(4, cores)` a cada um causa
  *oversubscribe* e piora a latência.
- **RAM**: modelo + KV cache + o webview do Tauri.
- **Disco**: o tamanho do modelo, uma vez, no app data dir.
- **GPU**: Metal ligado por default no macOS. Vulkan/CUDA são opt-in explícito
  (`--features whisper-vulkan` / `whisper-cuda`) porque exigem SDK do fabricante
  na máquina de **build**, não só na de execução.
- **macOS 11+**, e isto é *obrigatório*: `bundle.macOS.minimumSystemVersion` está
  fixado em `"11.0"` no `tauri.conf.json`.

#### Por que o piso do macOS está no `tauri.conf.json`, e não no CI

O `ggml` do whisper.cpp usa `std::filesystem`, que a libc++ da Apple só expõe a
partir do deployment target **10.15**. Com o default do Tauri (**10.13**) o clang
aborta com `'path' is unavailable: introduced in macOS 10.15` em
`ggml-backend-reg.cpp`, e o build script do `whisper-rs-sys` entra em pânico.

**Setar `MACOSX_DEPLOYMENT_TARGET` no workflow não resolve** — foi tentado no
[#358](https://github.com/sf0rzin/nora/pull/358) e falhou. O `tauri build`
*exporta* essa variável a partir de `bundle.macOS.minimumSystemVersion` e
sobrescreve o que estiver no ambiente. O valor precisa estar na config do Tauri,
que é também onde vale para build local.

`11.0` em vez de `10.15` porque o alvo é `aarch64-apple-darwin`: Apple Silicon
não existe antes do macOS 11, então não há compatibilidade real sendo descartada.

> Não tente documentar isso com uma chave `"//"` dentro de `bundle.macOS`. O
> schema do Tauri rejeita campo desconhecido ali e derruba o build com
> `unknown field '//'` — aconteceu no
> [#359](https://github.com/sf0rzin/nora/pull/359). É por isso que a explicação
> vive aqui.

### O que muda pro usuário

| | Azure (antes) | Local (agora) |
| --- | --- | --- |
| Rede | obrigatória | **não usa** |
| `/speech/token` | a cada gravação | **não chama** |
| Áudio sai da máquina | sim | **não** |
| Custo por minuto | sim | zero |
| Primeiro uso | imediato | baixa o modelo (~488 MB) |
| Diarização | `Guest-1`/`Guest-2` | por track (ver acima) |
| Latência | ~200-400 ms | pseudo-real-time, ~1 s por parcial |
| `confidence` | calibrada (`NBest[0]`) | **não calibrada** (ver abaixo) |

### Nota sobre `confidence`

O valor emitido é `exp(média dos ln(p) dos tokens)` — o `avg_logprob` do Whisper
normalizado. **Não é comparável** com o `NBest[0].Confidence` do Azure, que era um
score treinado. Uma alucinação fluente pontua **alto**, e a escala muda com o
tamanho do modelo. Serve pra ordenar segmentos dentro da mesma sessão e do mesmo
modelo, e só. Qualquer threshold em cima disso precisa ser recalibrado.

### Streaming: como funciona

O Whisper tem janela fixa de 30 s e **não tem estado incremental**. O streaming
aqui é um loop de re-decode sobre janela deslizante com VAD por energia: a cada
~900 ms a janela é redecodificada e sai um `partial`; quando o VAD vê ~700 ms de
silêncio (ou a janela chega a 22 s), sai o `final` e o relógio avança.

A armadilha é o **offset regredir** entre re-decodes. O contador `committed_ms` é
monotônico e é o offset base de todo evento; os `final` passam por um clamp
`last_final_end_ms.max(...)`. Detalhes em `src/stt_local.rs`.

## Variáveis de Ambiente

Ver `.env.example` para a lista completa e comentada.

```bash
# URL da API NORA (default: http://localhost:8080)
NORA_API_BASE_URL=http://localhost:8080

# STT
NORA_STT_BACKEND=local        # local | azure
NORA_WHISPER_MODEL=small      # tiny | base | small | medium
```

> Um app aberto pelo Explorer/Finder **não herda env do shell**. Em produção o
> valor efetivo vem do `tauri.conf.json` ou da env injetada em build-time.

## CI/CD

O workflow de CI builda automaticamente para todas as plataformas:

- Ubuntu (x86_64): `.deb` e `.AppImage`
- Windows (x86_64): `.msi`
- macOS (aarch64): `.dmg`

Ver `.github/workflows/ci.yml` para detalhes.

> **Pendência conhecida — `desktop-release.yml` ainda não foi ajustado.**
> Compilar o `whisper.cpp` a partir do fonte muda os requisitos do CI e isso
> **não foi validado em runner**:
>
> - O `timeout-minutes: 60` atual pode não bastar num *cache miss* frio: o build
>   C++ do ggml/whisper entra no caminho crítico dos dois runners.
> - A matriz hoje é só `windows-latest` + `ubuntu-latest` — **não há runner
>   macOS**, então o caminho Metal nunca é exercitado pelo CI.
> - Os passos de `setup-python` / `build_sidecar.py` viraram opcionais pro bundle
>   default, mas continuam no workflow.
> - O `swatinem/rust-cache` cacheia o `target/`, o que inclui os artefatos C++ —
>   mas a chave invalida a cada mudança de `Cargo.lock`.

## Troubleshooting

### Linux: Erro de áudio

Verifique se o PulseAudio está rodando:
```bash
pactl info
```

### macOS: Captura de áudio do sistema

A captura do áudio do sistema (vozes de outros participantes em chamadas) atualmente requer
o driver virtual **BlackHole**:

1. Instale o BlackHole 2ch: https://existential.audio/blackhole/
2. Em **Audio MIDI Setup → Multi-Output Device**, crie um device combinando seus alto-falantes
   e o BlackHole 2ch para continuar ouvindo o áudio enquanto o NORA captura.
3. Selecione esse Multi-Output Device como saída do sistema durante a reunião.
4. Na primeira execução, o macOS pedirá permissão de **Microphone** e (futuro) **Screen Recording**
   em *Privacy & Security*. Aprove ambas.

> **Roadmap (Issue #15):** suporte nativo via ScreenCaptureKit em macOS 13+ (sem driver virtual)
> está planejado. As entitlements (`NSScreenCaptureUsageDescription`) e a detecção de versão
> já estão no código; apenas a integração com a crate `screencapturekit` falta — requer
> validação em hardware Apple.

### Windows: `error MSB6003` / `DirectoryNotFoundException` ao compilar whisper.cpp

Limite de 260 caracteres do MSBuild, não erro de código. Use um target dir curto:

```powershell
$env:CARGO_TARGET_DIR = "C:\nrt"
```

### Download do modelo falha ou trava

O modelo vem do HuggingFace. Numa rede que bloqueia HF, aponte um mirror
(o checksum continua sendo verificado):

```bash
NORA_WHISPER_MODEL_BASE_URL=https://mirror.interno/whisper.cpp
```

Para usar um arquivo local (pula download **e** checksum):

```bash
NORA_WHISPER_MODEL_PATH=/caminho/ggml-small.bin
```

Um `.bin` que falha na verificação é apagado e rebaixado do zero — não há resume.
Cache em `<app_data_dir>/models/`; apagar essa pasta força novo download.

### Transcrição local muito lenta

Nesta ordem:

1. Baixe o modelo: `NORA_WHISPER_MODEL=base` (ou `tiny` pra testar).
2. Reduza o encoder: `NORA_WHISPER_AUDIO_CTX=768` (perde qualidade).
3. Ajuste threads: `NORA_WHISPER_THREADS=N` — lembre que **há dois tracks**
   decodificando ao mesmo tempo; subir demais piora.
4. Desligue a captura de áudio do sistema se só precisa do seu microfone —
   corta metade da carga de inferência.

Se aparecer `BURACO de Nms no audio (inferencia atrasada)` no log, a máquina não
está acompanhando o tempo real e áudio está sendo descartado.

### Sidecar não encontrado

> Só se aplica ao backend **azure** (legado). No backend local não há sidecar.
> Se estiver vendo isso com `sttBackend: "local"`, algo forçou `NORA_STT_BACKEND=azure`.

O sidecar deve estar em `src-tauri/binaries/` com o nome correto:
- Linux x86_64: `nora-stt-sidecar-x86_64-unknown-linux-gnu`
- Linux ARM64: `nora-stt-sidecar-aarch64-unknown-linux-gnu`
- Windows x86_64: `nora-stt-sidecar-x86_64-pc-windows-msvc.exe`
- macOS Intel: `nora-stt-sidecar-x86_64-apple-darwin`
- macOS Apple Silicon: `nora-stt-sidecar-aarch64-apple-darwin`

O `build_sidecar.py` detecta a plataforma automaticamente e gera o binário com o nome
correto baseado em `platform.system()` + `platform.machine()`. Os specs PyInstaller
correspondentes são `sidecar-linux.spec`, `sidecar-macos.spec` e `sidecar-windows.spec`.

### Build falha no Linux

Instale as dependências do sistema:
```bash
sudo apt-get install -y libwebkit2gtk-4.1-dev libappindicator3-dev librsvg2-dev patchelf
```

## Licença

MIT - NORA Team
