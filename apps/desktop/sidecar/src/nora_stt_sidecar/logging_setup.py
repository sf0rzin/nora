import logging
import sys

_HANDLER_TAG = "nora-sidecar-stderr-handler"


def configure(level: int = logging.INFO) -> None:
    """Configure logging to stderr with consistent format.

    Idempotente: chamadas múltiplas (em testes ou re-import) não duplicam handlers.
    """
    logger = logging.getLogger("nora_stt_sidecar")
    logger.setLevel(level)
    logger.propagate = False

    # Evita handlers duplicados: detecta o nosso pela tag.
    for existing in logger.handlers:
        if getattr(existing, "_nora_tag", None) == _HANDLER_TAG:
            return

    handler = logging.StreamHandler(sys.stderr)
    handler.setFormatter(
        logging.Formatter("[sidecar] %(asctime)s %(levelname)s %(message)s")
    )
    setattr(handler, "_nora_tag", _HANDLER_TAG)
    logger.addHandler(handler)
