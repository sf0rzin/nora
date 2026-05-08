# NORA STT Sidecar

Sidecar Python para transcrição de fala em tempo real com diarização usando Microsoft Speech SDK.

## Protocolo NDJSON

O sidecar comunica via stdin/stdout usando NDJSON (uma linha JSON por mensagem, terminada por `\n`).

### Entrada (Rust → Sidecar)

```json
{"v":1,"type":"start","session_id":"<uuid>","azure_region":"eastus","azure_key":"<key>","language":"pt-BR","sample_rate":16000,"channels":1,"speakers_hint":2}
{"v":1,"type":"audio","session_id":"<uuid>","seq":42,"pcm_b64":"<base64 PCM16LE>"}
{"v":1,"type":"stop","session_id":"<uuid>"}
```

### Saída (Sidecar → Rust)

```json
{"v":1,"type":"ready","session_id":"<uuid>"}
{"v":1,"type":"partial","session_id":"<uuid>","speaker_id":"Guest-1","text":"...","offset_ms":1234}
{"v":1,"type":"final","session_id":"<uuid>","speaker_id":"Guest-1","text":"...","offset_ms":1234,"duration_ms":820,"confidence":0.93}
{"v":1,"type":"error","session_id":"<uuid>","code":"AUTH_FAILED|QUOTA|NETWORK|UNKNOWN","message":"..."}
{"v":1,"type":"stopped","session_id":"<uuid>"}
```

## Instalação

```bash
cd apps/desktop/sidecar
pip install -e ".[dev]"
```

## Uso

```bash
python -m nora_stt_sidecar
```

O sidecar lê NDJSON do stdin e escreve NDJSON no stdout. Logs vão para stderr.

## Testes

```bash
cd apps/desktop/sidecar
pytest
```

## Build

```bash
python build/build_sidecar.py
```

Gera binário standalone em `apps/desktop/src-tauri/binaries/`.
