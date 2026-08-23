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

# spaCy and the pt-BR model for the PERSON_NAME backstop (services/pii_ner.py), in a layer of
# their own. Only the pinned requirements file is copied in, so this step is keyed on those two
# lines and on nothing else: it is refetched when the model version changes and never when the
# source does. That is the whole point of the split -- this is the expensive install and the layer
# below it is invalidated by every commit.
#
# Cost, measured from an installed 3.12 environment: ~13 MB downloaded for the model, ~155 MB on
# disk for the pair, of which the model is ~15 MB and the rest is spaCy and its BLAS stack. numpy
# is not part of that; scikit-learn already brought it in with nlp-baseline above.
#
# spaCy is named in the requirements file rather than left to `pip install .` below because a
# spaCy model wheel declares NO dependency on spaCy -- installing the model alone would put the
# 140 MB half of this in the source-keyed layer, which is the cache miss this split exists to
# avoid. `pip install .` then finds the `spacy>=3.8,<3.9` pin in pyproject.toml already satisfied.
COPY services/nlp-worker/requirements-ner.txt ./requirements-ner.txt
RUN pip install -r requirements-ner.txt

# Install worker
COPY services/nlp-worker/pyproject.toml ./pyproject.toml
COPY services/nlp-worker/src ./src
RUN pip install .

# The backstop degrades silently when the model will not load. That is the right behaviour for a
# process that has already started serving, and the wrong outcome for an image: this one is built
# to ship the second PII layer, and an image that does not have it should not exist rather than
# run at the higher leak rate without saying so. Loading the pipeline here turns a broken pin, a
# spaCy upgrade past the model's minor, or a mis-cached layer into a failed build.
RUN python -c "import spacy; spacy.load('pt_core_news_sm')"

USER nora
EXPOSE 8001
ENV PYTHONPATH=/app/src

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8001/healthz', timeout=3)" || exit 1

# --workers 2 unblocks the scale rule (50 concurrent/replica) that with 1 worker
# python would serialize all requests. 2 workers x 3 replicas max = 6 in parallel.
CMD ["uvicorn", "nora_nlp.main:app", "--host", "0.0.0.0", "--port", "8001", "--workers", "2"]
