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


class LiveTranscriber:
    def __init__(
        self,
        session_id: str,
        region: str,
        key: str,
        language: str = "pt-BR",
        on_event: Callable[[OutboundMessage], None] | None = None,
    ):
        self.session_id = session_id
        self.region = region
        self.key = key
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
        speech_config = SpeechConfig(subscription=self.key, region=self.region)
        speech_config.speech_recognition_language = self.language
        
        # Enable diarization for intermediate results
        speech_config.set_property(
            PropertyId.SpeechServiceResponse_DiarizeIntermediateResults,
            "true",
        )
        
        # Silence timeout for faster segmentation
        speech_config.set_property(
            PropertyId.Speech_SegmentationSilenceTimeoutMs,
            "800",
        )
        
        # Create push audio stream with 16kHz, 16-bit, mono
        stream_format = AudioStreamFormat(
            samples_per_second=16000,
            bits_per_sample=16,
            channels=1,
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
                    speaker_id=getattr(result, "speaker_id", None),
                    text=result.text,
                    offset_ms=result.offset // 10000,  # Convert to milliseconds
                )
            )
    
    def _on_transcribed(self, evt: ConversationTranscriptionEventArgs) -> None:
        """Handle final transcription events."""
        result = evt.result
        if result.text:
            self._emit(
                FinalMessage(
                    session_id=self.session_id,
                    speaker_id=getattr(result, "speaker_id", None),
                    text=result.text,
                    offset_ms=result.offset // 10000,
                    duration_ms=result.duration // 10000 if result.duration else None,
                    confidence=getattr(result, "confidence", None),
                )
            )
    
    def _on_canceled(self, evt: ConversationTranscriptionEventArgs) -> None:
        """Handle cancellation events."""
        result = evt.result
        cancellation_details = result.cancellation_details
        
        error_code = "UNKNOWN"
        if cancellation_details.reason == CancellationReason.Error:
            error_code_map = {
                speechsdk.CancellationErrorCode.AuthenticationFailure: "AUTH_FAILED",
                speechsdk.CancellationErrorCode.BadRequestParameters: "BAD_REQUEST",
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
        
        # Try to restart if it's a network/service error
        if error_code in ("NETWORK", "SERVICE_UNAVAILABLE") and self._restart_count < self._max_restarts:
            self._restart_count += 1
            backoff = 2 ** (self._restart_count - 1)
            logger.warning(f"Restarting transcriber (attempt {self._restart_count}/{self._max_restarts}) after {backoff}s")
            time.sleep(backoff)
            try:
                self._cleanup()
                self._setup_transcriber()
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
        if self._push_stream and self._started:
            self._push_stream.write(pcm_bytes)
    
    def stop(self) -> None:
        """Stop the transcriber gracefully."""
        if self._stopped:
            return
        
        self._stopped = True
        self._cleanup()
        self._emit(StoppedMessage(session_id=self.session_id))
        logger.info(f"Transcriber stopped for session {self.session_id}")
    
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
