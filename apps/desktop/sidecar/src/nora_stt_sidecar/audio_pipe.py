import base64
import binascii

from .protocol import AudioMessage

# Limite de tamanho por chunk: 16 kHz × 16-bit × 1 canal × 1 segundo = 32 KB.
# Aceitamos até 5s (160 KB) com margem.
_MAX_PCM_BYTES = 160 * 1024


def decode_audio_message(msg: AudioMessage) -> bytes:
    """Decode base64 PCM16LE audio data.

    `validate=True` rejeita base64 com caracteres extras (antes ignorados silenciosamente,
    resultando em PCM corrompido enviado ao Azure).
    """
    try:
        pcm_bytes = base64.b64decode(msg.pcm_b64, validate=True)
    except binascii.Error as e:
        raise ValueError(f"Invalid base64 PCM data: {e}") from e

    if not pcm_bytes:
        raise ValueError("Empty PCM payload")

    if len(pcm_bytes) % 2 != 0:
        raise ValueError(
            f"Invalid PCM16 data: length {len(pcm_bytes)} is not a multiple of 2"
        )

    if len(pcm_bytes) > _MAX_PCM_BYTES:
        raise ValueError(
            f"PCM chunk too large: {len(pcm_bytes)} bytes (max {_MAX_PCM_BYTES})"
        )

    return pcm_bytes
