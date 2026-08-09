# 0010 — Shared `nlp-baseline` package for PT-BR TF-IDF

- Status: accepted
- Date: 2026-05-11
- Deciders: Stratfy (PO) + Claude Opus 4.7 (Tech Lead)

## Context

The academic plan (`docs/PROJECT.md` section 5) promises TF-IDF as an
**interpretable baseline** deliverable on two fronts:

- **Academic:** Colab notebook (Data Science Sprint 1+2) with EDA, top terms,
  visualizations and validation of the hypotheses about the transcript dataset.
- **Product:** a step of the NLP worker pipeline, running before/alongside the LLM, with
  the same terms made available in the response as an interpretable pre-LLM
  signal (auditing, validation, degraded fallback).

The design question is: **how to share this implementation without
duplicating logic or coupling the notebook to the worker deployment**.

Options considered:

1. **(a) TF-IDF only in the notebook, the product baseline goes without.** This loses an
   interpretable pre-LLM feature in the worker (auditing, fallback). It also
   distances the academic deliverable from the product, contradicting the strategy in
   `docs/PROJECT.md` section 5 ("Nothing is throw-away").
2. **(b) TF-IDF only in the worker.** This couples the notebook to the worker code
   (relative imports, service paths). Colab becomes fragile --- touching the
   worker breaks the notebook and vice versa.
3. **(c) Simple duplication.** The notebook has its implementation, the worker has its own.
   Drift is inevitable: different stopwords, different ngram, different
   scores. The number in the academic report does not match the number in the
   UI.
4. **(d) Shared package in `packages/nlp-baseline/`.** A single implementation,
   installed in editable mode in the worker and importable in the notebook
   (relative path in Colab or via `pip install` pointing at the monorepo).

## Decision

Adopt option **(d): a shared Python package** in
`packages/nlp-baseline/` with:

- Pure Python >=3.12, single dependency: `scikit-learn>=1.4`.
- Stable API: `TfidfBaseline(ngram_range, max_features, min_df, max_df,
  sublinear_tf)` with methods `.fit`, `.top_terms`, `.top_terms_per_doc`.
- Exposed helper functions: `normalize_text`, `tokenize_ptbr`,
  `get_ptbr_stopwords`.
- PT-BR stopwords **hardcoded** in the package (~200 terms): zero runtime dependency
  on NLTK; faster install/setup both in the worker and in Colab.
- No global state. Each `TfidfBaseline` instance is isolated.

Integration in the worker (`services/nlp-worker`):

- The worker depends on it via a local path. In `pyproject.toml` we document the dependency
  as a comment + `[tool.uv.sources]` (uv resolves it directly). For plain pip,
  the root `Makefile` installs `packages/nlp-baseline` in editable mode
  before the worker (`make worker-setup`).
- The TF-IDF step runs **after** the PII Shield and before/alongside the LLM. Redacted
  text is the input --- PII does not leak into the term ranking.
- The result is attached to the response in the optional `baselineTerms` field of the
  `MeetingAnalysisV1` schema. An empty default preserves the existing contract
  (additive field, does not break old clients).

Integration in the notebook (in parallel, another agent):

- The notebook imports `from nlp_baseline import TfidfBaseline` in Colab via
  `pip install -e .` on the path of the cloned repository.
- Alternatively, the notebook keeps using `sklearn.TfidfVectorizer`
  directly (Colab-only mode), with the package documentation serving as a
  reference to guarantee equivalent parameters.

## Consequences

**Positive**

- An interpretable baseline available in **production and in the notebook** with the
  same logic --- what comes out in the academic report matches what comes out in
  NORA's UI.
- Centralized maintenance: changing the PT-BR stopword list or the ngram
  range is a change in a single place.
- The worker remains testable without the notebook, the notebook remains runnable without
  the worker deployment.
- No additional runtime cost: TF-IDF is deterministic, fast, and has no
  external dependency beyond sklearn.

**Negative / trade-offs**

- +1 package in the monorepo --- a larger configuration surface
  (`pyproject.toml`, `tests/`, lint).
- A local path dependency is not portable in plain pip --- it requires `make worker-setup`
  or a manual two-step install. Mitigated by the root `Makefile` and
  `[tool.uv.sources]` for uv users.
- The package will not go to PyPI (a conscious decision). Anyone wanting to use it outside the
  monorepo will have to clone it.

## Alternatives Considered

- **(a) TF-IDF only in the notebook.** Rejected: the product loses the interpretable
  and auditable pre-LLM feature; the academic baseline becomes a disconnected artifact.
- **(b) TF-IDF only in the worker.** Rejected: the notebook becomes coupled to service
  code --- fragile, hard to run in Colab.
- **(c) Notebook x worker duplication.** Rejected: implementation drift
  guarantees that the academic report will tell a different story from the product's
  UI. The worst-case scenario for an academic deliverable that intends to show
  engineering rigor.
