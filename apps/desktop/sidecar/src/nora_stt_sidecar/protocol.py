from typing import Literal
from pydantic import BaseModel, Field


class InboundMessage(BaseModel):
    v: Literal[1] = 1
    type: str
    session_id: str


class StartMessage(InboundMessage):
    type: Literal["start"] = "start"
    azure_region: str = Field(alias="azure_region")
    auth_token: str = Field(alias="auth_token")
    language: str = "pt-BR"
    sample_rate: int = Field(alias="sample_rate", default=16000)
    channels: int = 1
    speakers_hint: int = Field(alias="speakers_hint", default=2)


class RefreshTokenMessage(InboundMessage):
    type: Literal["refresh_token"] = "refresh_token"
    auth_token: str = Field(alias="auth_token")


class AudioMessage(InboundMessage):
    type: Literal["audio"] = "audio"
    seq: int
    pcm_b64: str = Field(alias="pcm_b64")


class StopMessage(InboundMessage):
    type: Literal["stop"] = "stop"


class OutboundMessage(BaseModel):
    v: Literal[1] = 1
    type: str
    session_id: str


class ReadyMessage(OutboundMessage):
    type: Literal["ready"] = "ready"


class PartialMessage(OutboundMessage):
    type: Literal["partial"] = "partial"
    speaker_id: str | None = Field(alias="speaker_id", default=None)
    text: str
    offset_ms: int = Field(alias="offset_ms")


class FinalMessage(OutboundMessage):
    type: Literal["final"] = "final"
    speaker_id: str | None = Field(alias="speaker_id", default=None)
    text: str
    offset_ms: int = Field(alias="offset_ms")
    duration_ms: int | None = Field(alias="duration_ms", default=None)
    confidence: float | None = None


class ErrorMessage(OutboundMessage):
    type: Literal["error"] = "error"
    code: str
    message: str


class StoppedMessage(OutboundMessage):
    type: Literal["stopped"] = "stopped"


def parse_inbound(data: dict) -> StartMessage | AudioMessage | StopMessage | RefreshTokenMessage:
    if data.get("v") != 1:
        raise ValueError(f"Unsupported protocol version: {data.get('v')}")
    
    msg_type = data.get("type")
    if msg_type == "start":
        return StartMessage.model_validate(data)
    elif msg_type == "audio":
        return AudioMessage.model_validate(data)
    elif msg_type == "stop":
        return StopMessage.model_validate(data)
    elif msg_type == "refresh_token":
        return RefreshTokenMessage.model_validate(data)
    else:
        raise ValueError(f"Unknown message type: {msg_type}")
