FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app

RUN groupadd --system nora && useradd --system --gid nora nora

COPY pyproject.toml ./
RUN pip install --upgrade pip && pip install .

COPY src ./src

USER nora
EXPOSE 8001
ENV PYTHONPATH=/app/src

CMD ["uvicorn", "nora_nlp.main:app", "--host", "0.0.0.0", "--port", "8001"]
