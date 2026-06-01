import json
import logging
import signal
import sys

from nora_stt_sidecar.logging_setup import configure
from nora_stt_sidecar.protocol import (
    AudioMessage,
    ErrorMessage,
    OutboundMessage,
    RefreshTokenMessage,
    StartMessage,
    StopMessage,
    parse_inbound,
)
from nora_stt_sidecar.transcriber import LiveTranscriber
from nora_stt_sidecar.audio_pipe import decode_audio_message

logger = logging.getLogger("nora_stt_sidecar")


class SidecarApp:
    def __init__(self):
        self._transcriber: LiveTranscriber | None = None
        self._running = True
        self._expected_seq = 0
        self._audio_error_emitted = False
    
    def _emit(self, msg: OutboundMessage) -> None:
        """Emit NDJSON message to stdout."""
        print(msg.model_dump_json(by_alias=True), flush=True)
    
    def _handle_start(self, msg: StartMessage) -> None:
        """Handle start message."""
        if self._transcriber is not None:
            self._emit(
                ErrorMessage(
                    session_id=msg.session_id,
                    code="BUSY",
                    message="Another session is already active",
                )
            )
            return
        
        try:
            self._transcriber = LiveTranscriber(
                session_id=msg.session_id,
                region=msg.azure_region,
                auth_token=msg.auth_token,
                language=msg.language,
                on_event=self._emit,
            )
            self._transcriber.start()
            self._expected_seq = 0
            self._audio_error_emitted = False
        except Exception as e:
            logger.error(f"Failed to start session: {e}")
            self._transcriber = None
    
    def _handle_refresh_token(self, msg: RefreshTokenMessage) -> None:
        """Handle refresh token message."""
        if self._transcriber is None:
            self._emit(
                ErrorMessage(
                    session_id=msg.session_id,
                    code="NO_SESSION",
                    message="No active session to refresh token",
                )
            )
            return
        
        try:
            self._transcriber.update_auth_token(msg.auth_token)
        except Exception as e:
            logger.error(f"Failed to refresh token: {e}")
            self._emit(
                ErrorMessage(
                    session_id=msg.session_id,
                    code="TOKEN_REFRESH_FAILED",
                    message=str(e),
                )
            )
    
    def _handle_audio(self, msg: AudioMessage) -> None:
        """Handle audio message."""
        if self._transcriber is None:
            self._emit(
                ErrorMessage(
                    session_id=msg.session_id,
                    code="NO_SESSION",
                    message="No active session. Send 'start' first.",
                )
            )
            return
        
        # Validate sequence number
        if msg.seq != self._expected_seq:
            logger.warning(f"Sequence gap: expected {self._expected_seq}, got {msg.seq}")
        self._expected_seq = msg.seq + 1
        
        try:
            pcm_bytes = decode_audio_message(msg)
            self._transcriber.feed(pcm_bytes)
        except Exception as e:
            logger.error(f"Failed to process audio: {e}")
            # Avisa o Rust UMA vez por sessão (não floodar o IPC por frame). #123
            if not self._audio_error_emitted:
                self._audio_error_emitted = True
                self._emit(
                    ErrorMessage(
                        session_id=msg.session_id,
                        code="AUDIO_DECODE_FAILED",
                        message=str(e),
                    )
                )
    
    def _handle_stop(self, msg: StopMessage) -> None:
        """Handle stop message."""
        if self._transcriber is None:
            self._emit(
                ErrorMessage(
                    session_id=msg.session_id,
                    code="NO_SESSION",
                    message="No active session to stop",
                )
            )
            return
        
        self._transcriber.stop()
        self._transcriber = None
    
    def _handle_signal(self, signum, frame) -> None:
        """Handle SIGTERM/SIGINT — só sinaliza a parada. Chamar stop()/print() no
        contexto do signal não é async-signal-safe; o cleanup roda no finally do
        run() (stop() é idempotente). Auditoria #122."""
        self._running = False
    
    def run(self) -> None:
        """Main loop: read NDJSON from stdin and dispatch."""
        configure()
        
        # Setup signal handlers
        signal.signal(signal.SIGTERM, self._handle_signal)
        signal.signal(signal.SIGINT, self._handle_signal)
        
        logger.info("NORA STT Sidecar started")
        
        try:
            for line in sys.stdin:
                if not self._running:
                    break
                
                line = line.strip()
                if not line:
                    continue
                
                try:
                    data = json.loads(line)
                    msg = parse_inbound(data)
                    
                    if isinstance(msg, StartMessage):
                        self._handle_start(msg)
                    elif isinstance(msg, AudioMessage):
                        self._handle_audio(msg)
                    elif isinstance(msg, StopMessage):
                        self._handle_stop(msg)
                    elif isinstance(msg, RefreshTokenMessage):
                        self._handle_refresh_token(msg)
                
                except json.JSONDecodeError as e:
                    logger.error(f"Invalid JSON: {e}")
                    self._emit(
                        ErrorMessage(
                            session_id="unknown",
                            code="INVALID_JSON",
                            message=str(e),
                        )
                    )
                except ValueError as e:
                    logger.error(f"Invalid message: {e}")
                    self._emit(
                        ErrorMessage(
                            session_id="unknown",
                            code="INVALID_MESSAGE",
                            message=str(e),
                        )
                    )
                except Exception as e:
                    logger.error(f"Unexpected error: {e}")
                    self._emit(
                        ErrorMessage(
                            session_id="unknown",
                            code="UNKNOWN",
                            message=str(e),
                        )
                    )
        
        except KeyboardInterrupt:
            logger.info("Interrupted by user")
        
        finally:
            if self._transcriber:
                self._transcriber.stop()
            logger.info("NORA STT Sidecar stopped")


def main() -> int:
    """Entry point."""
    app = SidecarApp()
    app.run()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
