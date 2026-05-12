# 0010 — Package compartilhado `nlp-baseline` para TF-IDF PT-BR

- Status: aceito
- Data: 2026-05-11
- Decisores: Anthony Sforzin (PO) + Claude Opus 4.7 (Tech Lead)

## Contexto

O plano academico (`docs/PROJECT.md` secao 5) promete TF-IDF como
**baseline interpretavel** entregavel em duas frentes:

- **Acadêmico:** notebook Colab (Data Science Sprint 1+2) com EDA, top termos,
  visualizacoes e validacao das hipoteses sobre o dataset de transcricoes.
- **Produto:** step do pipeline do worker NLP, rodando antes/junto ao LLM, com
  os mesmos termos disponibilizados no response como sinal interpretavel
  pre-LLM (auditoria, validacao, fallback degradado).

A pergunta de design eh: **como compartilhar essa implementacao sem
duplicar logica nem acoplar o notebook ao deploy do worker**.

Opcoes consideradas:

1. **(a) TF-IDF soh no notebook, baseline em produto fica sem.** Perde uma
   feature interpretavel pre-LLM no worker (auditoria, fallback). Tambem
   distancia entrega academica e produto, contrariando a estrategia do
   `docs/PROJECT.md` secao 5 ("Nada eh throw-away").
2. **(b) TF-IDF soh no worker.** Acopla o notebook ao codigo do worker
   (imports relativos, paths de servico). Colab fica fragil --- mexer no
   worker quebra o notebook e vice-versa.
3. **(c) Duplicacao simples.** Notebook tem sua impl, worker tem a dele.
   Drift inevitavel: stopwords diferentes, ngram diferente, scores
   diferentes. O numero do relatorio academico nao confere com o numero da
   UI.
4. **(d) Package compartilhado em `packages/nlp-baseline/`.** Implementacao
   unica, instalada em modo editable no worker e importavel no notebook
   (path relativo no Colab ou via `pip install` apontando para o monorepo).

## Decisão

Adotar a opcao **(d): package Python compartilhado** em
`packages/nlp-baseline/` com:

- Python puro >=3.12, dep unica: `scikit-learn>=1.4`.
- API estavel: `TfidfBaseline(ngram_range, max_features, min_df, max_df,
  sublinear_tf)` com metodos `.fit`, `.top_terms`, `.top_terms_per_doc`.
- Funcoes auxiliares expostas: `normalize_text`, `tokenize_ptbr`,
  `get_ptbr_stopwords`.
- Stopwords PT-BR **hardcoded** no package (~200 termos): zero dep de runtime
  do NLTK; install/setup mais rapido tanto no worker quanto no Colab.
- Sem state global. Cada instancia `TfidfBaseline` eh isolada.

Integracao no worker (`services/nlp-worker`):

- Worker depende via path local. Em `pyproject.toml` documentamos a dep
  como comentario + `[tool.uv.sources]` (uv resolve direto). Para pip puro,
  o `Makefile` da raiz instala `packages/nlp-baseline` em modo editable
  antes do worker (`make worker-setup`).
- O step TF-IDF roda **depois** do PII Shield e antes/junto ao LLM. Texto
  redigido eh o input --- PII nao vaza pro ranking de termos.
- Resultado eh anexado ao response no campo opcional `baselineTerms` do
  schema `MeetingAnalysisV1`. Default vazio mantem o contrato existente
  (campo aditivo, nao quebra clientes antigos).

Integracao no notebook (em paralelo, outro agente):

- Notebook importa `from nlp_baseline import TfidfBaseline` no Colab via
  `pip install -e .` no path do repositorio clonado.
- Alternativamente, o notebook continua usando `sklearn.TfidfVectorizer`
  diretamente (Colab-only mode), com a documentacao do package servindo de
  referencia para garantir parametros equivalentes.

## Consequências

**Positivas**

- Baseline interpretavel disponivel em **producao e no notebook** com a
  mesma logica --- o que sai no relatorio academico bate com o que sai na
  UI da NORA.
- Manutencao centralizada: mudar a lista de stopwords PT-BR ou o ngram
  range eh uma mudanca em um lugar soh.
- Worker continua testavel sem o notebook, notebook continua executavel sem
  o deploy do worker.
- Sem custo adicional de runtime: TF-IDF eh determinista, rapido, e sem
  dependencia externa alem do sklearn.

**Negativas / trade-offs**

- +1 package no monorepo --- maior superficie de configuracao
  (`pyproject.toml`, `tests/`, lint).
- Dep de path local nao eh portavel em pip puro --- exige `make worker-setup`
  ou install manual em duas etapas. Mitigado pelo `Makefile` da raiz e
  `[tool.uv.sources]` para usuarios uv.
- O package nao vai pra PyPI (decisao consciente). Quem quiser usar fora do
  monorepo vai precisar clonar.

## Alternativas Consideradas

- **(a) TF-IDF soh no notebook.** Rejeitado: produto perde a feature pre-LLM
  interpretavel e auditavel; baseline academico vira artefato desconectado.
- **(b) TF-IDF soh no worker.** Rejeitado: notebook fica acoplado ao codigo
  de servico --- fragil, dificil de executar em Colab.
- **(c) Duplicacao notebook x worker.** Rejeitado: drift de implementacao
  garante que o relatorio academico vai contar uma historia diferente da UI
  do produto. Pior cenario para uma entrega academica que pretende mostrar
  rigor de engenharia.
