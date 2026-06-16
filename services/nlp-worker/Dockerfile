# NOTA IMPORTANTE: build context = REPO ROOT, NÃO services/nlp-worker/
# Isso é necessário pra COPY do package compartilhado packages/nlp-baseline (ADR 0010).
# Build: docker build -f services/nlp-worker/Dockerfile -t nora-worker .

FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app

RUN groupadd --system nora && useradd --system --gid nora nora

# Install package compartilhado nlp-baseline (TF-IDF baseline, ADR 0010)
COPY packages/nlp-baseline /tmp/nlp-baseline
RUN pip install --upgrade pip && pip install /tmp/nlp-baseline && rm -rf /tmp/nlp-baseline

# Install worker (puxa spacy como dependencia, ver pyproject.toml)
COPY services/nlp-worker/pyproject.toml ./pyproject.toml
COPY services/nlp-worker/src ./src
RUN pip install .

# Modelo NER pt-BR do PII Shield (PR #3 / ADR 0012). Baixado NO BUILD, nunca em
# runtime — runtime offline e startup deterministico. Impacto:
#   - pesos do modelo `pt_core_news_sm`: ~13 MB (wheel ~15-20 MB).
#   - spacy + numpy/thinc no runtime: ~algumas centenas de MB de imagem.
# Aceitavel pelo ganho de recall de PERSON_NAME. Se este passo falhar, o worker
# ainda sobe: o shield degrada gracioso para so as heuristicas (pii_shield._load_ner).
RUN python -m spacy download pt_core_news_sm

USER nora
EXPOSE 8001
ENV PYTHONPATH=/app/src

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8001/healthz', timeout=3)" || exit 1

# --workers 2 desbloqueia a scale rule (50 concurrent/replica) que com 1 worker
# python serializaria todas as requisicoes. 2 workers x 3 replicas max = 6 paralelos.
CMD ["uvicorn", "nora_nlp.main:app", "--host", "0.0.0.0", "--port", "8001", "--workers", "2"]
