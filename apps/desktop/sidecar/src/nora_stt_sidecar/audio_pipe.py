import base64
from .protocol import AudioMessage


def decode_audio_message(msg: AudioMessage) -> bytes:
    """Decode base64 PCM16LE audio data."""
    pcm_bytes = base64.b64decode(msg.pcm_b64)
    
    if len(pcm_bytes) % 2 != 0:
        raise ValueError(f"Invalid PCM16 data: length {len(pcm_bytes)} is not a multiple of 2")
    
    return pcm_bytes
