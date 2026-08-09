# %% [markdown]
# # NORA — TOTVS transcripts: cleaning, TF-IDF and EDA
#
# Data Science pipeline over the **`ANON_nome_transcricao.csv`** dataset (anonymized
# transcripts of TOTVS meetings, 2026). Covers the items of the Challenge's *Data
# Science & Statistical Computing* subject:
#
# 1. **Problem understanding** — turn transcripts into actionable intelligence.
# 2. **Ingestion and preparation** — strict text cleaning (lowercase, punctuation,
#    stopwords, normalization) of the `[LOCUTOR N]` format.
# 3. **Feature engineering with TF-IDF** — TF-IDF matrix + most relevant terms
#    (reuses `nlp_baseline.TfidfBaseline`, the SAME one used in NORA's worker — ADR 0010).
# 4. **Exploratory analysis** — distributions (format, segment, UF, NPS) + patterns.
# 5. **Business insights** — language × NPS correlation (risk/opportunity signals).
#
# > This file is in *jupytext* format (`# %%`): it runs as a script
# > (`python notebooks/totvs_transcricoes_eda.py`) **and** opens as a notebook in
# > Jupyter/Colab/VS Code.
# >
# > **Data:** put the CSV in `data/private/totvs/ANON_nome_transcricao.csv`
# > (folder already in `.gitignore` — TOTVS data is NOT versioned) or point to it via
# > `TOTVS_CSV=/caminho/arquivo.csv`. Use `TOTVS_SAMPLE=N` to sample N rows.

# %%
from __future__ import annotations

import os
import re
import sys
from pathlib import Path

import pandas as pd

# Reuses NORA's own NLP baseline (production × notebook consistency).
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "packages" / "nlp-baseline" / "src"))
from nlp_baseline.normalize import normalize_text  # noqa: E402
from nlp_baseline.tfidf import TfidfBaseline  # noqa: E402

CSV_PATH = os.environ.get("TOTVS_CSV", str(ROOT / "data" / "private" / "totvs" / "ANON_nome_transcricao.csv"))
SAMPLE_N = int(os.environ.get("TOTVS_SAMPLE", "5000"))  # 0 = whole dataset

pd.set_option("display.max_colwidth", 80)
pd.set_option("display.width", 120)

# %% [markdown]
# ## 1–2. Ingestion and preparation
#
# We load the dataset, normalize column names (the production dataset uses
# `FAIXA_FATURAMENTO_CLIENTE_EC`; the mock uses `..._CLIENTE`) and parse the
# `ANON_TRANSCRICAO` field, which comes in the `[LOCUTOR N]: fala` format.

# %%
HEADER = [
    "ID_MEETING", "DT_MEETING", "FORMATO_MEETING", "ID_STATUS_MEETING", "STATUS_MEETING",
    "DURACAO_MEETING", "CODT", "TP_RECURSO", "FLG_EXTERNO", "DT_CRIACAO", "ANON_TRANSCRICAO",
    "UF", "CNAE", "NOME_UNIDADE", "NOME_SEGMENTO", "FAIXA_FATURAMENTO_CLIENTE_EC",
    "DT_ULTIMA_PESQUISA", "NOTA_NPS",
]

# Record boundary in the real export: line start with "<id>,<ISO date>.
_RECORD_BOUND = re.compile(r'(?m)^"(?=\d{3,},\d{4}-\d\d-\d\d)')


def _parse_messy_export(path: str, sample_n: int) -> pd.DataFrame:
    """Parser dedicated to the malformed TOTVS export.

    The file is not RFC-4180: each record is ``"<10 metadata>,""<transcript>"",
    <7 CRM fields>"`` — the transcript has doubled inner quotes and line breaks
    with no clean close, so the standard CSV parser misaligns the columns.
    Here we slice per record (boundary regex) and split out:
      * metadata: before the first ``""``;
      * transcript: the middle (undoing ``""`` -> ``"``);
      * CRM: after the last ``"",`` (UF, CNAE, unit, segment, band, date, NPS).
    """
    raw = Path(path).read_text(encoding="utf-8", errors="replace")
    body = raw.split("\n", 1)[1] if "\n" in raw else raw
    starts = [m.start() for m in _RECORD_BOUND.finditer(body)]
    rows: list[dict] = []
    for i, start in enumerate(starts):
        if sample_n and len(rows) >= sample_n:
            break
        end = starts[i + 1] if i + 1 < len(starts) else len(body)
        blob = body[start:end].strip().lstrip('"')
        ti = blob.find('""')
        meta_part, rest = (blob[:ti], blob[ti + 2:]) if ti != -1 else (blob, "")
        meta = meta_part.split(",")
        ci = rest.rfind('"",')
        if ci != -1:
            transcript = rest[:ci]
            crm = rest[ci + 3:].rstrip().rstrip('"').split(",")
        else:
            transcript, crm = rest, []
        row = {h: "" for h in HEADER}
        for j in range(min(10, len(meta))):
            row[HEADER[j]] = meta[j].strip()
        row["ANON_TRANSCRICAO"] = transcript.replace('""', '"').strip()
        if len(crm) >= 7:
            row["UF"], row["CNAE"], row["NOME_UNIDADE"], row["NOME_SEGMENTO"] = (c.strip() for c in crm[:4])
            row["FAIXA_FATURAMENTO_CLIENTE_EC"] = ",".join(crm[4:-2]).strip()
            row["DT_ULTIMA_PESQUISA"] = crm[-2].strip()
            row["NOTA_NPS"] = crm[-1].strip()
        rows.append(row)
    return pd.DataFrame(rows, columns=HEADER)


def load_dataframe() -> pd.DataFrame:
    if not Path(CSV_PATH).exists():
        raise SystemExit(
            f"CSV não encontrado em {CSV_PATH}.\n"
            "Coloque a base em data/private/totvs/ ou aponte com TOTVS_CSV=/caminho.csv"
        )
    # Sniff: the real TOTVS export starts every record with "<id>,<date> (malformed).
    with open(CSV_PATH, encoding="utf-8", errors="replace") as fh:
        fh.readline()  # skip header
        second = fh.readline()
    if re.match(r'^"\d{3,},\d{4}-\d\d-\d\d', second):
        df = _parse_messy_export(CSV_PATH, SAMPLE_N)
    else:
        kwargs: dict = {"engine": "python", "on_bad_lines": "skip"}
        if SAMPLE_N > 0:
            kwargs["nrows"] = SAMPLE_N
        df = pd.read_csv(CSV_PATH, **kwargs)
    # Normalizes column-name variations between the real dataset and the mock.
    df = df.rename(columns={"FAIXA_FATURAMENTO_CLIENTE": "FAIXA_FATURAMENTO_CLIENTE_EC"})
    return df


SPEAKER_RE = re.compile(r"\[\s*LOCUTOR\s*\d+\s*\]\s*:?", re.IGNORECASE)


def strip_speakers(text: object) -> str:
    """Removes the [LOCUTOR N] markers, leaving only the speech."""
    return SPEAKER_RE.sub(" ", str(text or ""))


def turn_count(text: object) -> int:
    """Number of speech turns (how many [LOCUTOR N] markers)."""
    return len(SPEAKER_RE.findall(str(text or "")))


df = load_dataframe()
print(f"Linhas: {len(df):,} | Colunas: {len(df.columns)}")
print("Colunas:", list(df.columns))

# Clean text (without speaker markers) and simple structural features.
df["transcricao_limpa"] = df["ANON_TRANSCRICAO"].map(strip_speakers)
df["n_turnos"] = df["ANON_TRANSCRICAO"].map(turn_count)
df["n_chars"] = df["transcricao_limpa"].str.len()

# Valid documents for TF-IDF (transcript with real content).
valid = df[df["transcricao_limpa"].str.strip().str.len() > 20].copy()
print(f"\nTranscrições com conteúdo útil: {len(valid):,} de {len(df):,}")
print(f"Turnos por reunião — média {df['n_turnos'].mean():.1f}, mediana {df['n_turnos'].median():.0f}")
print(f"Tamanho (chars) — média {df['n_chars'].mean():.0f}, mediana {df['n_chars'].median():.0f}")

# %% [markdown]
# ### Text normalization example
# `normalize_text` (the worker's own) lowercases, strips punctuation and accents.
# The PT-BR stopwords go into `TfidfBaseline`.

# %%
_amostra = valid["transcricao_limpa"].iloc[0][:280] if len(valid) else ""
print("ANTES:\n", _amostra)
print("\nDEPOIS (normalize_text):\n", normalize_text(_amostra)[:280])

# %% [markdown]
# ## 4. Exploratory analysis (EDA)
# Distributions of the business dimensions: format, resource classification,
# external meeting, segment, UF and NPS.

# %%
def show_counts(col: str, top: int = 10) -> None:
    if col not in df.columns:
        print(f"(coluna {col} ausente)")
        return
    vc = df[col].value_counts(dropna=False).head(top)
    print(f"\n== {col} ==")
    for k, v in vc.items():
        print(f"  {str(k)[:42]:<42} {v:>7,} ({v / len(df) * 100:4.1f}%)")


for c in ["FORMATO_MEETING", "STATUS_MEETING", "TP_RECURSO", "FLG_EXTERNO", "NOME_SEGMENTO", "UF"]:
    show_counts(c)

# %% [markdown]
# ### NPS — distribution and bucketing
# We classify each customer by the official NPS scale: **Detractor (0–6)**,
# **Passive (7–8)** and **Promoter (9–10)**. It is the basis for correlating what is
# *said* in the meetings with the customer's *satisfaction*.

# %%
def nps_bucket(v: object) -> str | float:
    try:
        n = float(v)
    except (TypeError, ValueError):
        return pd.NA
    if n <= 6:
        return "Detrator (0-6)"
    if n <= 8:
        return "Neutro (7-8)"
    return "Promotor (9-10)"


if "NOTA_NPS" in df.columns:
    df["NPS_CAT"] = df["NOTA_NPS"].map(nps_bucket)
    valid["NPS_CAT"] = valid["NOTA_NPS"].map(nps_bucket)
    nps_num = pd.to_numeric(df["NOTA_NPS"], errors="coerce")
    print(f"NPS — média {nps_num.mean():.1f} | preenchido em {nps_num.notna().mean() * 100:.0f}% das linhas")
    show_counts("NPS_CAT")
else:
    print("(coluna NOTA_NPS ausente — pulando análise de NPS)")

# %% [markdown]
# ## 3. Feature engineering — TF-IDF
# TF-IDF matrix (unigrams + bigrams) over the cleaned transcripts. The same
# parameters as the worker guarantee the term in the report is the term NORA uses.

# %%
def top_terms_of(texts: list[str], top_n: int = 25, max_features: int = 800) -> list[tuple[str, float]]:
    texts = [t for t in texts if t and t.strip()]
    if len(texts) < 2:
        return []
    baseline = TfidfBaseline(ngram_range=(1, 2), max_features=max_features, min_df=2, max_df=0.9)
    baseline.fit(texts)
    return baseline.top_terms(top_n=top_n)


corpus = valid["transcricao_limpa"].tolist()
overall = top_terms_of(corpus, top_n=30)
print(f"Vocabulário treinado em {len(corpus):,} transcrições.")
print("\n== TOP 30 TERMOS (TF-IDF médio) ==")
for term, score in overall:
    print(f"  {term:<28} {score:.4f}")

# %% [markdown]
# ## 5–6. Business insights — language × NPS
# We compare the most salient terms between **detractors** and **promoters**. Terms
# strong only among detractors are candidates for **risk/churn signals**; terms only
# among promoters, for **opportunity/satisfaction signals**. This feeds NORA's Customer
# Confidence / Account Health (Enterprise).

# %%
if "NPS_CAT" in valid.columns:
    det = valid[valid["NPS_CAT"] == "Detrator (0-6)"]["transcricao_limpa"].tolist()
    pro = valid[valid["NPS_CAT"] == "Promotor (9-10)"]["transcricao_limpa"].tolist()
    det_terms = dict(top_terms_of(det, top_n=40))
    pro_terms = dict(top_terms_of(pro, top_n=40))

    only_det = sorted(
        ((t, s) for t, s in det_terms.items() if t not in pro_terms),
        key=lambda x: -x[1],
    )[:15]
    only_pro = sorted(
        ((t, s) for t, s in pro_terms.items() if t not in det_terms),
        key=lambda x: -x[1],
    )[:15]

    print(f"Detratores: {len(det):,} transcrições | Promotores: {len(pro):,}")
    print("\n== Termos salientes SÓ entre DETRATORES (sinais de risco/churn) ==")
    for t, s in only_det:
        print(f"  ⚠️  {t:<26} {s:.4f}")
    print("\n== Termos salientes SÓ entre PROMOTORES (sinais de oportunidade) ==")
    for t, s in only_pro:
        print(f"  ✅  {t:<26} {s:.4f}")
else:
    print("(sem NPS — pulando correlação linguagem × satisfação)")

# %% [markdown]
# ## Bonus — NORA processing a transcript (LLM)
# Closes the product-end loop: a REAL TOTVS transcript becomes summary +
# decisions + action items + structured signals — the same thing NORA's worker
# delivers in the pipeline. Gated on `LLM_API_KEY` (does not run without a key).

# %%
def nora_analyze(transcript: str) -> str | None:
    import json
    import urllib.request

    key = os.environ.get("LLM_API_KEY")
    if not key:
        print("(LLM_API_KEY ausente — pulando a demonstração de análise da NORA)")
        return None
    base = os.environ.get("LLM_BASE_URL", "https://api.openai.com/v1").rstrip("/")
    model = os.environ.get("LLM_MODEL", "gpt-4o-mini")
    prompt = (
        "Você é a NORA, assistente de inteligência de reuniões. Analise a transcrição "
        "(PT-BR, já anonimizada com [PESSOA]/[EMPRESA]/[LOCAL]) e devolva um JSON com as "
        "chaves: resumo (3-4 frases), decisoes (lista), action_items (lista de "
        "{texto, responsavel, prioridade}), sinais_risco (lista), sinais_oportunidade "
        "(lista). Seja fiel ao conteúdo; não invente.\n\nTRANSCRIÇÃO:\n" + transcript[:12000]
    )
    payload = json.dumps(
        {
            "model": model,
            "messages": [{"role": "user", "content": prompt}],
            "response_format": {"type": "json_object"},
            "temperature": 0.2,
        }
    ).encode()
    req = urllib.request.Request(
        base + "/chat/completions",
        data=payload,
        headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=90) as resp:
        data = json.loads(resp.read())
    return data["choices"][0]["message"]["content"]


if len(valid):
    out = nora_analyze(valid["transcricao_limpa"].iloc[0])
    if out:
        print("== NORA — análise estruturada da 1ª transcrição TOTVS ==")
        print(out[:1800])

# %% [markdown]
# ### Conclusion
# The TOTVS dataset is rich: besides the transcript, it brings NPS, segment, UF and
# revenue band per meeting. That allows going beyond the summary — correlating **what
# is said** with **customer health**, exactly the NORA Enterprise promise
# (Customer Confidence + Account Health). The same TF-IDF here runs in NORA's worker.
print("\nPipeline concluído.")
