# IMPORTANT NOTE: build context = REPO ROOT, NOT services/nlp-worker/
# This is necessary for the COPY of the shared package packages/nlp-baseline (ADR 0010).
# Build: docker build -f services/nlp-worker/Dockerfile -t nora-worker .

FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app

RUN groupadd --system nora && useradd --system --gid nora nora

# Install shared package nlp-baseline (TF-IDF baseline, ADR 0010)
COPY packages/nlp-baseline /tmp/nlp-baseline
RUN pip install --upgrade pip && pip install /tmp/nlp-baseline && rm -rf /tmp/nlp-baseline

# Install worker
COPY services/nlp-worker/pyproject.toml ./pyproject.toml
COPY services/nlp-worker/src ./src
RUN pip install .

USER nora
EXPOSE 8001
ENV PYTHONPATH=/app/src

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8001/healthz', timeout=3)" || exit 1

# --workers 2 unblocks the scale rule (50 concurrent/replica) that with 1 worker
# python would serialize all requests. 2 workers x 3 replicas max = 6 in parallel.
CMD ["uvicorn", "nora_nlp.main:app", "--host", "0.0.0.0", "--port", "8001", "--workers", "2"]
