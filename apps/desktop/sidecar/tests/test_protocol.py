import json
import pytest
from nora_stt_sidecar.protocol import (
    StartMessage,
    AudioMessage,
    StopMessage,
    ReadyMessage,
    PartialMessage,
    FinalMessage,
    ErrorMessage,
    StoppedMessage,
    parse_inbound,
)


class TestProtocol:
    """Test protocol message parsing and serialization."""
    
    def test_parse_start_message(self):
        # ADR 0009: Token Broker — desktop nunca tem azure_key. Recebe auth_token JWT
        # do backend NORA, que faz o broker para o Azure Speech STS.
        data = {
            "v": 1,
            "type": "start",
            "session_id": "test-session",
            "azure_region": "eastus",
            "auth_token": "ey.jwt.fake",
            "language": "pt-BR",
            "sample_rate": 16000,
            "channels": 1,
            "speakers_hint": 2,
        }
        msg = parse_inbound(data)
        assert isinstance(msg, StartMessage)
        assert msg.session_id == "test-session"
        assert msg.azure_region == "eastus"
        assert msg.auth_token == "ey.jwt.fake"
        assert msg.language == "pt-BR"

    def test_parse_refresh_token_message(self):
        data = {
            "v": 1,
            "type": "refresh_token",
            "session_id": "test-session",
            "auth_token": "ey.jwt.refreshed",
        }
        msg = parse_inbound(data)
        assert msg.auth_token == "ey.jwt.refreshed"
        assert msg.type == "refresh_token"
    
    def test_parse_audio_message(self):
        data = {
            "v": 1,
            "type": "audio",
            "session_id": "test-session",
            "seq": 42,
            "pcm_b64": "dGVzdA==",
        }
        msg = parse_inbound(data)
        assert isinstance(msg, AudioMessage)
        assert msg.seq == 42
        assert msg.pcm_b64 == "dGVzdA=="
    
    def test_parse_stop_message(self):
        data = {
            "v": 1,
            "type": "stop",
            "session_id": "test-session",
        }
        msg = parse_inbound(data)
        assert isinstance(msg, StopMessage)
    
    def test_reject_invalid_version(self):
        data = {
            "v": 2,
            "type": "start",
            "session_id": "test",
        }
        with pytest.raises(ValueError, match="Unsupported protocol version"):
            parse_inbound(data)
    
    def test_reject_unknown_type(self):
        data = {
            "v": 1,
            "type": "unknown",
            "session_id": "test",
        }
        with pytest.raises(ValueError, match="Unknown message type"):
            parse_inbound(data)
    
    def test_ready_message_serialization(self):
        msg = ReadyMessage(session_id="test")
        data = json.loads(msg.model_dump_json(by_alias=True))
        assert data["type"] == "ready"
        assert data["session_id"] == "test"
        assert data["v"] == 1
    
    def test_partial_message_serialization(self):
        msg = PartialMessage(
            session_id="test",
            speaker_id="Guest-1",
            text="Hello",
            offset_ms=1000,
        )
        data = json.loads(msg.model_dump_json(by_alias=True))
        assert data["type"] == "partial"
        assert data["speaker_id"] == "Guest-1"
        assert data["text"] == "Hello"
        assert data["offset_ms"] == 1000
    
    def test_final_message_serialization(self):
        msg = FinalMessage(
            session_id="test",
            speaker_id="Guest-1",
            text="Hello world",
            offset_ms=1000,
            duration_ms=500,
            confidence=0.95,
        )
        data = json.loads(msg.model_dump_json(by_alias=True))
        assert data["type"] == "final"
        assert data["duration_ms"] == 500
        assert data["confidence"] == 0.95
    
    def test_error_message_serialization(self):
        msg = ErrorMessage(
            session_id="test",
            code="AUTH_FAILED",
            message="Invalid key",
        )
        data = json.loads(msg.model_dump_json(by_alias=True))
        assert data["type"] == "error"
        assert data["code"] == "AUTH_FAILED"
    
    def test_stopped_message_serialization(self):
        msg = StoppedMessage(session_id="test")
        data = json.loads(msg.model_dump_json(by_alias=True))
        assert data["type"] == "stopped"
