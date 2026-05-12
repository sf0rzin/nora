# nlp-baseline

Package Python compartilhado com baseline TF-IDF interpretavel para PT-BR.

## Proposito

Implementacao unica do baseline NLP exigido por:

- Plano academico (`docs/PROJECT.md` secao 5 — "Baseline interpretavel do worker NLP")
- Worker NLP em producao como step do pipeline pre-LLM

Mesma logica, mesma lista de stopwords, mesmos hiperparametros — o numero
que aparece no notebook eh o mesmo que vai pra UI da NORA.

Decisao registrada em [`docs/adr/0010-nlp-baseline-package.md`](../../docs/adr/0010-nlp-baseline-package.md).

## Uso

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

## Dependencias

- `scikit-learn>=1.4`

Sem dependencia de runtime do NLTK — stopwords sao hardcoded em
`src/nlp_baseline/stopwords.py`.

## Testes

```bash
pip install -e ".[dev]"
pytest -v
```
