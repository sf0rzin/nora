import json
import logging
import time
from typing import Callable

import azure.cognitiveservices.speech as speechsdk
from azure.cognitiveservices.speech import (
    AudioConfig,
    SpeechConfig,
    PropertyId,
    CancellationReason,
)
from azure.cognitiveservices.speech.audio import (
    AudioStreamFormat,
    PushAudioInputStream,
)
from azure.cognitiveservices.speech.transcription import (
    ConversationTranscriber,
    ConversationTranscriptionEventArgs,
)

from .protocol import (
    ErrorMessage,
    FinalMessage,
    OutboundMessage,
    PartialMessage,
    ReadyMessage,
    StoppedMessage,
)

logger = logging.getLogger("nora_stt_sidecar")

# Audio/segmentation constants (were magic numbers scattered around). Audit #117.
_TICKS_PER_MS = 10_000  # the SDK reports times in 100ns units
_SAMPLE_RATE_HZ = 16_000
_BITS_PER_SAMPLE = 16
_CHANNELS = 1
_SEGMENTATION_SILENCE_MS = "800"


class LiveTranscriber:
    def __init__(
        self,
        session_id: str,
        region: str,
        auth_token: str,
        language: str = "pt-BR",
        on_event: Callable[[OutboundMessage], None] | None = None,
    ):
        self.session_id = session_id
        self.region = region
        self.auth_token = auth_token
        self.language = language
        self.on_event = on_event
        
        self._push_stream: PushAudioInputStream | None = None
        self._transcriber: ConversationTranscriber | None = None
        self._started = False
        self._stopped = False
        self._restart_count = 0
        self._max_restarts = 3
    
    def _emit(self, msg: OutboundMessage) -> None:
        if self.on_event:
            self.on_event(msg)
    
    def start(self) -> None:
        """Start the transcriber and emit ready when connected."""
        try:
            self._setup_transcriber()
            self._started = True
            self._emit(ReadyMessage(session_id=self.session_id))
            logger.info(f"Transcriber ready for session {self.session_id}")
        except Exception as e:
            logger.error(f"Failed to start transcriber: {e}")
            self._emit(
                ErrorMessage(
                    session_id=self.session_id,
                    code="START_FAILED",
                    message=str(e),
                )
            )
            raise
    
    def _setup_transcriber(self) -> None:
        """Setup the ConversationTranscriber with push audio stream."""
        speech_config = SpeechConfig(auth_token=self.auth_token, region=self.region)
        speech_config.speech_recognition_language = self.language

        # Detailed output → NBest[0].Confidence (the SDK does not expose .confidence directly).
        speech_config.output_format = speechsdk.OutputFormat.Detailed

        # Enable diarization for intermediate results
        speech_config.set_property(
            PropertyId.SpeechServiceResponse_DiarizeIntermediateResults,
            "true",
        )
        
        # Silence timeout for faster segmentation
        speech_config.set_property(
            PropertyId.Speech_SegmentationSilenceTimeoutMs,
            _SEGMENTATION_SILENCE_MS,
        )
        
        # Create push audio stream with 16kHz, 16-bit, mono
        stream_format = AudioStreamFormat(
            samples_per_second=_SAMPLE_RATE_HZ,
            bits_per_sample=_BITS_PER_SAMPLE,
            channels=_CHANNELS,
        )
        self._push_stream = PushAudioInputStream(stream_format)
        
        audio_config = AudioConfig(stream=self._push_stream)
        self._transcriber = ConversationTranscriber(speech_config, audio_config)
        
        # Connect callbacks
        self._transcriber.transcribing.connect(self._on_transcribing)
        self._transcriber.transcribed.connect(self._on_transcribed)
        self._transcriber.canceled.connect(self._on_canceled)
        
        # Start transcribing
        self._transcriber.start_transcribing_async().get()
    
    def _on_transcribing(self, evt: ConversationTranscriptionEventArgs) -> None:
        """Handle partial transcription events."""
        result = evt.result
        if result.text:
            self._emit(
                PartialMessage(
                    session_id=self.session_id,
                    speaker_id=result.speaker_id or None,
                    text=result.text,
                    offset_ms=result.offset // _TICKS_PER_MS,  # Convert to milliseconds
                )
            )
    
    def _on_transcribed(self, evt: ConversationTranscriptionEventArgs) -> None:
        """Handle final transcription events."""
        result = evt.result
        if result.text:
            self._emit(
                FinalMessage(
                    session_id=self.session_id,
                    speaker_id=result.speaker_id or None,
                    text=result.text,
                    offset_ms=result.offset // _TICKS_PER_MS,
                    duration_ms=result.duration // _TICKS_PER_MS if result.duration else None,
                    confidence=self._parse_confidence(result),
                )
            )
    
    def _parse_confidence(self, result) -> float | None:
        """Confidence comes from the detailed JSON (NBest[0].Confidence); the SDK does
        not expose a direct .confidence attribute. Degrades to None if absent."""
        try:
            data = json.loads(result.json)
        except (AttributeError, ValueError, TypeError):
            return None
        nbest = data.get("NBest") if isinstance(data, dict) else None
        if isinstance(nbest, list) and nbest:
            conf = nbest[0].get("Confidence")
            if isinstance(conf, (int, float)):
                return float(conf)
        return None

    def _on_canceled(self, evt: ConversationTranscriptionEventArgs) -> None:
        """Handle cancellation events."""
        result = evt.result
        cancellation_details = result.cancellation_details

        # EndOfStream (normal end) is NOT an error: do not emit ErrorMessage nor restart.
        if cancellation_details.reason != CancellationReason.Error:
            return

        error_code_map = {
            speechsdk.CancellationErrorCode.AuthenticationFailure: "AUTH_FAILED",
            speechsdk.CancellationErrorCode.BadRequest: "BAD_REQUEST",
            speechsdk.CancellationErrorCode.TooManyRequests: "QUOTA",
            speechsdk.CancellationErrorCode.ConnectionFailure: "NETWORK",
            speechsdk.CancellationErrorCode.ServiceUnavailable: "SERVICE_UNAVAILABLE",
            speechsdk.CancellationErrorCode.RuntimeError: "RUNTIME_ERROR",
        }
        error_code = error_code_map.get(cancellation_details.error_code, "UNKNOWN")

        self._emit(
            ErrorMessage(
                session_id=self.session_id,
                code=error_code,
                message=cancellation_details.error_message or "Unknown error",
            )
        )

        # Restart only on network/service error — and never after stop() (avoids
        # resurrecting the session). Runs on the SDK callback thread. Audit #113.
        if error_code not in ("NETWORK", "SERVICE_UNAVAILABLE"):
            return
        if self._stopped or self._restart_count >= self._max_restarts:
            return

        self._restart_count += 1
        backoff = 2 ** (self._restart_count - 1)
        logger.warning(
            f"Restarting transcriber (attempt {self._restart_count}/{self._max_restarts}) after {backoff}s"
        )
        time.sleep(backoff)
        if self._stopped:
            return  # stop() during the backoff — do not resurrect

        try:
            self._cleanup()
            self._setup_transcriber()
            self._restart_count = 0  # reset after recovery (audit #116)
            logger.info("Transcriber restarted successfully")
        except Exception as e:
            logger.error(f"Failed to restart transcriber: {e}")
            self._emit(
                ErrorMessage(
                    session_id=self.session_id,
                    code="RESTART_FAILED",
                    message=f"Failed to restart after {self._restart_count} attempts: {e}",
                )
            )
    
    def feed(self, pcm_bytes: bytes) -> None:
        """Feed PCM16LE audio data to the transcriber."""
        # Capture the local ref: _cleanup() (restart/stop) can null out _push_stream
        # between the test and the write, running on the callback thread. Audit #115.
        stream = self._push_stream
        if stream and self._started:
            stream.write(pcm_bytes)
    
    def stop(self) -> None:
        """Stop the transcriber gracefully."""
        if self._stopped:
            return
        
        self._stopped = True
        self._cleanup()
        self._emit(StoppedMessage(session_id=self.session_id))
        logger.info(f"Transcriber stopped for session {self.session_id}")
    
    def update_auth_token(self, new_token: str) -> None:
        """Update the authorization token without restarting the session."""
        self.auth_token = new_token
        if self._transcriber is not None:
            try:
                self._transcriber.authorization_token = new_token
                logger.info(f"Updated auth token for session {self.session_id}")
            except Exception as e:
                logger.error(f"Failed to update auth token: {e}")
                self._emit(
                    ErrorMessage(
                        session_id=self.session_id,
                        code="TOKEN_REFRESH_FAILED",
                        message=f"Failed to update auth token: {e}",
                    )
                )

    def _cleanup(self) -> None:
        """Clean up resources."""
        try:
            if self._transcriber:
                self._transcriber.stop_transcribing_async().get()
        except Exception as e:
            logger.warning(f"Error stopping transcriber: {e}")
        
        try:
            if self._push_stream:
                self._push_stream.close()
        except Exception as e:
            logger.warning(f"Error closing push stream: {e}")
        
        self._transcriber = None
        self._push_stream = None
