# 0008 — Desktop App com Tauri 2 + Sidecar Python

- Status: aceito (sidecar Python substituído por ADR 0035 — Whisper embarcado no Tauri/Rust; a decisão por Tauri 2 e a captura de áudio permanecem)
- Data: 2026-05-07
- Decisores: NORA Team
- Relacionado: ADR 0035 (substitui a parte do sidecar Python desta decisão)

## Contexto

O MVP do NORA precisa capturar áudio de reuniões em tempo real no desktop dos usuários. A arquitetura deve:

- Funcionar em Windows, macOS e Linux
- Capturar áudio do microfone e do sistema (loopback)
- Transcrever em tempo real usando Azure Speech-to-Text
- Empacotar tudo em um instalador nativo

## Decisão

Usar **Tauri 2** (Rust + WebView) como framework desktop, com um **sidecar Python** para o STT (Speech-to-Text).

### Arquitetura

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

### Componentes

1. **Frontend**: React + TypeScript + Tailwind
2. **Backend Rust**: Tauri commands para captura de áudio e proxy HTTP
3. **Sidecar Python**: Processo separado que roda o Azure Speech SDK
4. **Empacotamento**: PyInstaller para o sidecar + Tauri bundler para o app

## Consequências

### Positivas

- **Tamanho reduzido**: App nativo ~5-15MB vs Electron ~150MB
- **Performance**: Rust para captura de áudio, Python para STT
- **Segurança**: Chaves Azure não ficam no código-fonte JS
- **Multi-plataforma**: Windows, macOS, Linux com mesmo codebase

### Negativas

- **Complexidade**: Dois runtimes (Rust + Python) para manter
- **Sidecar**: Processo separado adiciona overhead de IPC
- **Build**: Pipeline de build mais complexo (Rust + Python + Node)

## Alternativas Consideradas

### 1. Electron + Node.js

- **Prós**: Ecossistema maduro, mais simples
- **Contras**: Tamanho grande, performance inferior, segurança fraca
- **Veredicto**: Descartado por tamanho e performance

### 2. Flutter Desktop

- **Prós**: UI consistente, bom desempenho
- **Contras**: Curva de aprendizado, ecossistema menor para desktop
- **Veredicto**: Descartado por não termos expertise no time

### 3. Tauri + Rust puro (sem sidecar)

- **Prós**: Um runtime só, mais simples
- **Contras**: Azure Speech SDK não tem crate Rust oficial madura
- **Veredicto**: Descartado por falta de SDK Rust oficial

### 4. Tauri + WASM

- **Prós**: Sem sidecar, tudo no processo principal
- **Contras**: Azure Speech SDK não suporta WASM
- **Veredicto**: Descartado por incompatibilidade técnica

## Notas de Implementação

### Sidecar Python

- Usa `azure-cognitiveservices-speech` (SDK oficial Microsoft)
- Comunicação via stdin/stdout com protocolo NDJSON (JSON Lines)
- Empacotado com PyInstaller para binário standalone
- Binário incluído no bundle Tauri via `externalBin`
- **Entry point**: `nora_stt_sidecar_main.py` é um wrapper de 1 linha que delega para `nora_stt_sidecar.__main__:main()`

### Captura de Áudio

- **Linux**: `cpal` + `parecord` (PulseAudio/PipeWire monitor)
- **Windows**: `cpal` + WASAPI loopback
- **macOS**: `cpal` + ScreenCaptureKit (placeholder)

### CI/CD

- GitHub Actions com matrix: Ubuntu, Windows, macOS
- Build do sidecar antes do build Tauri
- Upload de artifacts para cada plataforma
- **pytest** do sidecar roda no job desktop antes do build

## Nota sobre Escopo do MVP

O Desktop foi antecipado em relação ao boundary original do MVP (que previa apenas Web + Backend + Worker). A decisão de incluir o Desktop no Sprint 1+2 foi tomada para:

1. **Diferenciação competitiva**: Captura em tempo real é um diferencial chave vs concorrentes
2. **Validação técnica**: Provar que a arquitetura sidecar funciona end-to-end
3. **Demo FIAP**: Ter uma demonstração visual forte para o Challenge

O Desktop continua sendo **pós-MVP** em termos de maturidade (SSO, áudio/vídeo upload, MCPs completos), mas a fundação técnica foi estabelecida antecipadamente.

## Referências

- [Tauri Documentation](https://tauri.app/)
- [Azure Speech SDK Python](https://docs.microsoft.com/azure/cognitive-services/speech-service/)
- [PyInstaller](https://pyinstaller.org/)
- Issue #8: Desktop Tauri Scaffold
