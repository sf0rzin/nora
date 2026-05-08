import logging
import sys


def configure(level: int = logging.INFO) -> None:
    """Configure logging to stderr with consistent format."""
    handler = logging.StreamHandler(sys.stderr)
    handler.setFormatter(
        logging.Formatter("[sidecar] %(asctime)s %(levelname)s %(message)s")
    )
    
    logger = logging.getLogger("nora_stt_sidecar")
    logger.setLevel(level)
    logger.addHandler(handler)
    
    # Remove default handlers to avoid duplicates
    logger.propagate = False
