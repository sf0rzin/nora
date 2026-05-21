import io
import json
import logging
import os
import queue
import signal
import sys
import threading

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


def _isolate_protocol_stdout() -> io.TextIOWrapper:
    """Captura o fd 1 (stdout real) num wrapper dedicado pro protocolo NDJSON, e
    redireciona `sys.stdout` para stderr. Isso impede que warnings do Azure SDK,
    Pydantic ou bibliotecas terceiras contaminem o protocolo NDJSON com prints
    acidentais — antes, qualquer `print()` em libs externas quebrava o parser do Tauri.
    """
    protocol_fd = os.dup(1)
    protocol_stream = os.fdopen(protocol_fd, "w", buffering=1, encoding="utf-8")
    # Qualquer print acidental vai pro stderr (que é o que o Rust pai usa pra logs).
    sys.stdout = sys.stderr
    return protocol_stream


class SidecarApp:
    def __init__(self) -> None:
        self._transcriber: LiveTranscriber | None = None
        self._running = True
        self._expected_seq = 0
        # Stream dedicado pro protocolo NDJSON (não compartilhado com sys.stdout).
        self._protocol_stream = _isolate_protocol_stdout()
        # Protege _emit contra escritas concorrentes do callback do SDK Azure
        # e do main thread — sem isso, duas linhas NDJSON podiam ser intercaladas.
        self._stdout_lock = threading.Lock()
        # Fila usada pra desacoplar o thread leitor de stdin do main loop. Antes
        # o `for line in sys.stdin` bloqueava no I/O nativo, ignorando SIGTERM/SIGINT
        # até a próxima linha chegar — se o Tauri fechar stdin, processo zumbi.
        self._inbound_queue: queue.Queue[str | None] = queue.Queue(maxsize=256)

    def _emit(self, msg: OutboundMessage) -> None:
        """Emit NDJSON message to stdout (thread-safe)."""
        line = msg.model_dump_json(by_alias=True)
        with self._stdout_lock:
            try:
                self._protocol_stream.write(line + "\n")
                self._protocol_stream.flush()
            except (BrokenPipeError, ValueError):
                # Tauri fechou o pipe — sinaliza shutdown gracioso.
                self._running = False
    
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
        except Exception as e:
            logger.error(f"Failed to start session: {e}")
            self._transcriber = None
            # Antes a falha era apenas logada — o cliente Tauri ficava esperando
            # `ready` que nunca chegava. Agora emitimos um erro explícito.
            self._emit(
                ErrorMessage(
                    session_id=msg.session_id,
                    code="START_FAILED",
                    message=str(e),
                )
            )
    
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

        # Detecta lacunas no stream. Não temos NACK/janela de reordenação no MVP,
        # mas o gap é exposto via ErrorMessage(SEQUENCE_GAP) para que o cliente
        # possa decidir reiniciar a sessão — antes o gap virava só warning silencioso.
        if msg.seq != self._expected_seq:
            gap = msg.seq - self._expected_seq
            logger.warning(f"Sequence gap: expected {self._expected_seq}, got {msg.seq} (gap={gap})")
            self._emit(
                ErrorMessage(
                    session_id=msg.session_id,
                    code="SEQUENCE_GAP",
                    message=f"Expected seq {self._expected_seq}, got {msg.seq} (gap={gap})",
                )
            )
        self._expected_seq = msg.seq + 1

        try:
            pcm_bytes = decode_audio_message(msg)
            self._transcriber.feed(pcm_bytes)
        except Exception as e:
            logger.error(f"Failed to process audio: {e}")
    
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
        """Handle SIGTERM/SIGINT."""
        logger.info(f"Received signal {signum}, shutting down...")
        self._running = False
        # Sentinela acorda o consumer pra fazer o for queue.get() retornar imediatamente.
        try:
            self._inbound_queue.put_nowait(None)
        except queue.Full:
            pass
        if self._transcriber:
            self._transcriber.stop()
            self._transcriber = None

    def _stdin_reader(self) -> None:
        """Thread daemon: lê linhas do stdin e empilha na queue. Sair do `for` em EOF
        sinaliza shutdown também (Tauri fechou o pipe)."""
        try:
            for raw in sys.stdin:
                if not self._running:
                    break
                try:
                    self._inbound_queue.put(raw, timeout=1.0)
                except queue.Full:
                    # Em caso de backlog extremo (mais de 256 linhas pendentes),
                    # drop pra evitar OOM. Linha PCM = ~3.5 KB.
                    logger.warning("inbound queue full; dropping line")
        except Exception as e:
            logger.error(f"stdin reader crashed: {e}")
        finally:
            # Sentinela None acorda o main loop pra terminar.
            try:
                self._inbound_queue.put_nowait(None)
            except queue.Full:
                pass

    def _try_extract_session_id(self, raw: str) -> str:
        """Best-effort: extrai session_id mesmo de JSON malformado."""
        try:
            data = json.loads(raw)
            sid = data.get("session_id")
            if isinstance(sid, str):
                return sid
        except Exception:
            pass
        return "unknown"

    def run(self) -> None:
        """Main loop: consome NDJSON da queue e despacha. Stdin lido em thread separada
        para que SIGTERM/SIGINT possam interromper o loop mesmo se nada chegar do Tauri."""
        configure()

        # Setup signal handlers (no main thread só)
        signal.signal(signal.SIGTERM, self._handle_signal)
        signal.signal(signal.SIGINT, self._handle_signal)

        logger.info("NORA STT Sidecar started")

        reader_thread = threading.Thread(
            target=self._stdin_reader,
            name="nora-stt-stdin-reader",
            daemon=True,
        )
        reader_thread.start()

        try:
            while self._running:
                try:
                    raw = self._inbound_queue.get(timeout=0.5)
                except queue.Empty:
                    continue

                if raw is None:  # sentinela de shutdown
                    break

                line = raw.strip()
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
                            session_id=self._try_extract_session_id(line),
                            code="INVALID_MESSAGE",
                            message=str(e),
                        )
                    )
                except Exception as e:
                    logger.error(f"Unexpected error: {e}")
                    self._emit(
                        ErrorMessage(
                            session_id=self._try_extract_session_id(line),
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
