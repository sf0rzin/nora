import logging
import threading
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

# Restart counter reseta após este intervalo de "boa saúde" (sem erros) —
# evita que 3 erros acumulados ao longo de horas desabilitem recovery pra sempre.
_RESTART_RESET_WINDOW_SECS = 60.0


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
        # Lock para serializar setup/cleanup/feed durante restart.
        # Antes o callback do SDK Azure (`_on_canceled`) reatribuía `_transcriber` /
        # `_push_stream` enquanto o thread principal podia estar em `feed()` ou `stop()` —
        # use-after-free no SDK nativo → potencial segfault.
        self._lock = threading.RLock()
        # Última transcrição saudável: usado pra resetar o contador de restarts
        # quando o sistema ficou estável por mais de _RESTART_RESET_WINDOW_SECS.
        self._last_healthy_at: float | None = None
        # Thread de restart spawnada quando o callback do SDK pede recovery.
        # Não usamos `time.sleep()` no callback do SDK porque ele bloqueia o thread
        # de eventos do Azure SDK por até 4s — congelando a máquina de eventos.
        self._restart_thread: threading.Thread | None = None
    
    def _emit(self, msg: OutboundMessage) -> None:
        if self.on_event:
            self.on_event(msg)
    
    def start(self) -> None:
        """Start the transcriber and emit ready when connected."""
        try:
            with self._lock:
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
    
    def _mark_healthy(self) -> None:
        """Sinaliza que recebemos transcrição válida — se a janela saudável já estourou,
        reseta o contador de restarts. Sem isso, três erros transitórios acumulados
        ao longo de uma reunião de 1h desabilitavam recovery permanentemente."""
        now = time.monotonic()
        if (
            self._restart_count > 0
            and self._last_healthy_at is not None
            and now - self._last_healthy_at >= _RESTART_RESET_WINDOW_SECS
        ):
            logger.info(f"Restart counter reset after {now - self._last_healthy_at:.0f}s healthy")
            self._restart_count = 0
        self._last_healthy_at = now

    def _on_transcribing(self, evt: ConversationTranscriptionEventArgs) -> None:
        """Handle partial transcription events."""
        result = evt.result
        if result.text:
            self._mark_healthy()
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
            self._mark_healthy()
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
        """Handle cancellation events.

        IMPORTANTE: este callback roda no thread interno do Azure SDK. Não pode
        bloquear (time.sleep) nem reentrar no SDK enquanto o estado está sendo
        reconstruído. Por isso o restart é delegado a uma thread separada.
        """
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

        # Delega restart pra thread separada — não bloqueia o thread de eventos do SDK.
        if (
            not self._stopped
            and error_code in ("NETWORK", "SERVICE_UNAVAILABLE")
            and self._restart_count < self._max_restarts
        ):
            self._restart_count += 1
            backoff = 2 ** (self._restart_count - 1)
            logger.warning(
                f"Scheduling restart (attempt {self._restart_count}/{self._max_restarts}) after {backoff}s"
            )
            attempt = self._restart_count
            # daemon=True garante que o processo possa morrer mesmo se o restart estiver
            # pendente. Threadname facilita debugging.
            self._restart_thread = threading.Thread(
                target=self._restart_async,
                args=(backoff, attempt),
                name=f"nora-stt-restart-{self.session_id[:8]}-{attempt}",
                daemon=True,
            )
            self._restart_thread.start()

    def _restart_async(self, backoff_secs: float, attempt: int) -> None:
        """Roda em thread separada — pode dormir e bloquear no setup do SDK sem
        congelar o thread de eventos do Azure."""
        time.sleep(backoff_secs)
        if self._stopped:
            return
        with self._lock:
            if self._stopped:
                return
            try:
                self._cleanup()
                self._setup_transcriber()
                logger.info(f"Transcriber restarted successfully (attempt {attempt})")
                # Emite Ready novamente — sem isso o frontend acha que a sessão morreu.
                self._emit(ReadyMessage(session_id=self.session_id))
                self._last_healthy_at = time.monotonic()
            except Exception as e:
                logger.error(f"Failed to restart transcriber (attempt {attempt}): {e}")
                self._emit(
                    ErrorMessage(
                        session_id=self.session_id,
                        code="RESTART_FAILED",
                        message=f"Failed to restart after {attempt} attempts: {e}",
                    )
                )
    
    def feed(self, pcm_bytes: bytes) -> None:
        """Feed PCM16LE audio data to the transcriber.

        O lock protege o intervalo em que o restart pode estar trocando
        `_push_stream` — sem ele, gravava no stream antigo já fechado (segfault no SDK nativo).
        """
        with self._lock:
            stream = self._push_stream
            if stream is not None and self._started and not self._stopped:
                try:
                    stream.write(pcm_bytes)
                except Exception as e:
                    # write() pode lançar se stream foi fechado entre o check e a chamada
                    logger.warning(f"feed() write failed: {e}")

    def stop(self) -> None:
        """Stop the transcriber gracefully."""
        if self._stopped:
            return

        # Sinaliza primeiro pra que o restart_thread (se rodando) saiba que deve abortar.
        self._stopped = True
        with self._lock:
            self._cleanup()
        self._emit(StoppedMessage(session_id=self.session_id))
        logger.info(f"Transcriber stopped for session {self.session_id}")

    def update_auth_token(self, new_token: str) -> None:
        """Update the authorization token without restarting the session."""
        with self._lock:
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
        """Clean up resources. CHAMAR COM `self._lock` SEGURADO."""
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
