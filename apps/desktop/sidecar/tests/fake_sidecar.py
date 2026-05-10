#!/usr/bin/env python3
"""
Sidecar fake para testes E2E do NORA Desktop.
Não chama a Azure — emite transcrições pré-definidas via NDJSON stdout.
"""

import json
import sys
import time


def emit(msg_type: str, **kwargs):
    payload = {"v": 1, "type": msg_type, **kwargs}
    print(json.dumps(payload), flush=True)


def main():
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            msg = json.loads(line)
        except json.JSONDecodeError:
            continue

        msg_type = msg.get("type")

        if msg_type == "start":
            emit("ready", session_id=msg.get("session_id", "test"))

            # Emit some fake transcripts after a short delay
            time.sleep(0.5)
            emit(
                "partial",
                session_id=msg.get("session_id", "test"),
                speaker_id="Guest-1",
                text="Olá, tudo bem",
            )
            time.sleep(0.5)
            emit(
                "final",
                session_id=msg.get("session_id", "test"),
                speaker_id="Guest-1",
                text="Olá, tudo bem?",
                duration_ms=1200,
                confidence=0.95,
            )
            time.sleep(0.5)
            emit(
                "final",
                session_id=msg.get("session_id", "test"),
                speaker_id="Eu",
                text="Tudo ótimo, obrigado.",
                duration_ms=1500,
                confidence=0.92,
            )

        elif msg_type == "stop":
            emit("stopped", session_id=msg.get("session_id", "test"))
            break

        elif msg_type == "refresh_token":
            # Ignore in tests
            pass


if __name__ == "__main__":
    main()
