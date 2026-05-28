# %% [markdown]
# # NORA — Transcrições TOTVS: limpeza, TF-IDF e EDA
#
# Pipeline de Data Science sobre a base **`ANON_nome_transcricao.csv`** (transcrições
# anonimizadas de agendas TOTVS, 2026). Cobre os itens da disciplina *Data Science &
# Statistical Computing* do Challenge:
#
# 1. **Entendimento do problema** — transformar transcrições em inteligência acionável.
# 2. **Ingestão e preparação** — limpeza textual rigorosa (lowercase, pontuação,
#    stopwords, normalização) do formato `[LOCUTOR N]`.
# 3. **Feature engineering com TF-IDF** — matriz TF-IDF + termos mais relevantes
#    (reusa o `nlp_baseline.TfidfBaseline`, o MESMO usado no worker da NORA — ADR 0010).
# 4. **Análise exploratória** — distribuições (formato, segmento, UF, NPS) + padrões.
# 5. **Insights de negócio** — correlação linguagem × NPS (sinais de risco/oportunidade).
#
# > Este arquivo está em formato *jupytext* (`# %%`): roda como script
# > (`python notebooks/totvs_transcricoes_eda.py`) **e** abre como notebook no
# > Jupyter/Colab/VS Code.
# >
# > **Dados:** coloque o CSV em `data/private/totvs/ANON_nome_transcricao.csv`
# > (pasta já no `.gitignore` — o dado da TOTVS NÃO é versionado) ou aponte via
# > `TOTVS_CSV=/caminho/arquivo.csv`. Use `TOTVS_SAMPLE=N` pra amostrar N linhas.

# %%
from __future__ import annotations

import os
import re
import sys
from pathlib import Path

import pandas as pd

# Reusa o baseline de NLP da própria NORA (consistência produção × notebook).
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "packages" / "nlp-baseline" / "src"))
from nlp_baseline.normalize import normalize_text  # noqa: E402
from nlp_baseline.tfidf import TfidfBaseline  # noqa: E402

CSV_PATH = os.environ.get("TOTVS_CSV", str(ROOT / "data" / "private" / "totvs" / "ANON_nome_transcricao.csv"))
SAMPLE_N = int(os.environ.get("TOTVS_SAMPLE", "5000"))  # 0 = base inteira

pd.set_option("display.max_colwidth", 80)
pd.set_option("display.width", 120)

# %% [markdown]
# ## 1–2. Ingestão e preparação
#
# Carregamos a base, normalizamos nomes de coluna (a base de produção usa
# `FAIXA_FATURAMENTO_CLIENTE_EC`; o mock usa `..._CLIENTE`) e parseamos o campo
# `ANON_TRANSCRICAO`, que vem no formato `[LOCUTOR N]: fala`.

# %%
HEADER = [
    "ID_MEETING", "DT_MEETING", "FORMATO_MEETING", "ID_STATUS_MEETING", "STATUS_MEETING",
    "DURACAO_MEETING", "CODT", "TP_RECURSO", "FLG_EXTERNO", "DT_CRIACAO", "ANON_TRANSCRICAO",
    "UF", "CNAE", "NOME_UNIDADE", "NOME_SEGMENTO", "FAIXA_FATURAMENTO_CLIENTE_EC",
    "DT_ULTIMA_PESQUISA", "NOTA_NPS",
]

# Fronteira de registro no export real: início de linha com "<id>,<data ISO>.
_RECORD_BOUND = re.compile(r'(?m)^"(?=\d{3,},\d{4}-\d\d-\d\d)')


def _parse_messy_export(path: str, sample_n: int) -> pd.DataFrame:
    """Parser dedicado ao export malformado da TOTVS.

    O arquivo não é RFC-4180: cada registro é ``"<10 metadados>,""<transcrição>"",
    <7 campos CRM>"`` — a transcrição tem aspas internas duplicadas e quebras de
    linha sem fechamento limpo, então o parser de CSV padrão desalinha as colunas.
    Aqui fatiamos por registro (regex de fronteira) e separamos:
      * metadados: antes do primeiro ``""``;
      * transcrição: o miolo (desfazendo ``""`` -> ``"``);
      * CRM: após o último ``"",`` (UF, CNAE, unidade, segmento, faixa, data, NPS).
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
    # Sniff: export real da TOTVS começa cada registro com "<id>,<data> (malformado).
    with open(CSV_PATH, encoding="utf-8", errors="replace") as fh:
        fh.readline()  # pula header
        second = fh.readline()
    if re.match(r'^"\d{3,},\d{4}-\d\d-\d\d', second):
        df = _parse_messy_export(CSV_PATH, SAMPLE_N)
    else:
        kwargs: dict = {"engine": "python", "on_bad_lines": "skip"}
        if SAMPLE_N > 0:
            kwargs["nrows"] = SAMPLE_N
        df = pd.read_csv(CSV_PATH, **kwargs)
    # Normaliza variações de nome de coluna entre base real e mock.
    df = df.rename(columns={"FAIXA_FATURAMENTO_CLIENTE": "FAIXA_FATURAMENTO_CLIENTE_EC"})
    return df


SPEAKER_RE = re.compile(r"\[\s*LOCUTOR\s*\d+\s*\]\s*:?", re.IGNORECASE)


def strip_speakers(text: object) -> str:
    """Remove as marcações [LOCUTOR N] deixando só a fala."""
    return SPEAKER_RE.sub(" ", str(text or ""))


def turn_count(text: object) -> int:
    """Número de turnos de fala (quantas marcações [LOCUTOR N])."""
    return len(SPEAKER_RE.findall(str(text or "")))


df = load_dataframe()
print(f"Linhas: {len(df):,} | Colunas: {len(df.columns)}")
print("Colunas:", list(df.columns))

# Texto limpo (sem marcações de locutor) e features estruturais simples.
df["transcricao_limpa"] = df["ANON_TRANSCRICAO"].map(strip_speakers)
df["n_turnos"] = df["ANON_TRANSCRICAO"].map(turn_count)
df["n_chars"] = df["transcricao_limpa"].str.len()

# Documentos válidos pro TF-IDF (transcrição com conteúdo real).
valid = df[df["transcricao_limpa"].str.strip().str.len() > 20].copy()
print(f"\nTranscrições com conteúdo útil: {len(valid):,} de {len(df):,}")
print(f"Turnos por reunião — média {df['n_turnos'].mean():.1f}, mediana {df['n_turnos'].median():.0f}")
print(f"Tamanho (chars) — média {df['n_chars'].mean():.0f}, mediana {df['n_chars'].median():.0f}")

# %% [markdown]
# ### Exemplo de normalização textual
# O `normalize_text` (mesmo do worker) aplica lowercase, remove pontuação e acentos.
# As stopwords PT-BR entram no `TfidfBaseline`.

# %%
_amostra = valid["transcricao_limpa"].iloc[0][:280] if len(valid) else ""
print("ANTES:\n", _amostra)
print("\nDEPOIS (normalize_text):\n", normalize_text(_amostra)[:280])

# %% [markdown]
# ## 4. Análise exploratória (EDA)
# Distribuições das dimensões de negócio: formato, classificação do recurso,
# reunião externa, segmento, UF e NPS.

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
# ### NPS — distribuição e categorização
# Classificamos cada cliente pela régua oficial do NPS: **Detrator (0–6)**,
# **Neutro (7–8)** e **Promotor (9–10)**. É a base pra correlacionar o que se *fala*
# nas reuniões com a *satisfação* do cliente.

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
# Matriz TF-IDF (unigramas + bigramas) sobre as transcrições limpas. Os mesmos
# parâmetros do worker garantem que o termo do relatório é o termo que a NORA usa.

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
# ## 5–6. Insights de negócio — linguagem × NPS
# Comparamos os termos mais salientes entre **detratores** e **promotores**. Termos
# fortes só nos detratores são candidatos a **sinais de risco/churn**; termos só nos
# promotores, a **sinais de oportunidade/satisfação**. Isso alimenta o Customer
# Confidence / Account Health da NORA (Enterprise).

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
# ## Bônus — NORA processando uma transcrição (LLM)
# Fecha o ciclo da ponta-produto: uma transcrição REAL da TOTVS vira resumo +
# decisões + action items + sinais estruturados — o mesmo que o worker da NORA
# entrega no pipeline. Gated em `LLM_API_KEY` (não roda sem chave).

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
# ### Conclusão
# A base TOTVS é rica: além da transcrição, traz NPS, segmento, UF e faixa de
# faturamento por reunião. Isso permite ir além do resumo — correlacionar **o que é
# dito** com **a saúde do cliente**, exatamente a promessa do NORA Enterprise
# (Customer Confidence + Account Health). O mesmo TF-IDF aqui roda no worker da NORA.
print("\nPipeline concluído.")
