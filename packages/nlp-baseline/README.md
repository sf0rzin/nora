# nlp-baseline

Shared Python package with an interpretable TF-IDF baseline for PT-BR.

## Purpose

Single implementation of the NLP baseline required by:

- Academic plan (FIAP Challenge — `docs/challenge/fiap-challenge-2026.md` + ADR 0010)
- NLP Worker in production as a pre-LLM pipeline step

Same logic, same stopword list, same hyperparameters — the number
that appears in the notebook is the same one that goes to NORA's UI.

Decision recorded in [`docs/adr/0010-nlp-baseline-package.md`](../../docs/adr/0010-nlp-baseline-package.md).

## Usage

```python
from nlp_baseline import TfidfBaseline

baseline = TfidfBaseline(ngram_range=(1, 2), max_features=500)
baseline.fit([
    "Cliente aprovou a proposta de renovacao do contrato.",
    "Vamos enviar proposta comercial nova com desconto.",
])
print(baseline.top_terms(top_n=10))
```

## API

- `TfidfBaseline(ngram_range, max_features, min_df, max_df, sublinear_tf)`
- `.fit(texts: list[str]) -> TfidfBaseline`
- `.top_terms(top_n: int = 20) -> list[tuple[str, float]]`
- `.top_terms_per_doc(text: str, top_n: int = 10) -> list[tuple[str, float]]`
- `normalize_text(...)`, `tokenize_ptbr(...)`, `get_ptbr_stopwords()`

## Dependencies

- `scikit-learn>=1.4`

No NLTK runtime dependency — the stopwords are hardcoded in
`src/nlp_baseline/stopwords.py`.

## Tests

```bash
pip install -e ".[dev]"
pytest -v
```
