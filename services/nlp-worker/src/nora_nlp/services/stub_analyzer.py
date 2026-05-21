"""Stub deterministico do analisador.

Permite desenvolver web e backend sem custo de LLM. Aplica heuristicas simples para
gerar uma `MeetingAnalysisV1` valida a partir da transcricao + contexto do tenant.

Quando `USE_LLM_STUB=false` no .env, este modulo deixa de ser usado e o worker chama
o provedor real (Azure OpenAI). A implementacao real entra na story do backlog que
plugar Azure OpenAI; aqui mantemos apenas o contrato.
"""

from __future__ import annotations

import re
import time

from ..models import (
    ActionItem,
    AnalyzeRequest,
    AnalyzeResponse,
    BuyingSignal,
    BuyingSignalType,
    ConfidenceBand,
    CoverageStatus,
    CustomerConfidence,
    Decision,
    Objection,
    ObjectionType,
    Opportunity,
    OpportunityCategory,
    Participant,
    Priority,
    ProductivityAssessment,
    ProductivityBand,
    ProductivityCoverage,
    Risk,
    RiskCategory,
    Sentiment,
    Severity,
)

_NEGATIVE_KEYWORDS = {
    "concorrente",
    "concorrencia",
    "churn",
    "perder",
    "barato",
    "abaixo",
    "atras",
    "atraso",
    "adiar",
    "preocupa",
    "risco",
    "objecao",
    "objecoes",
}
_POSITIVE_KEYWORDS = {
    "fechado",
    "fechar",
    "aprovado",
    "interesse",
    "entusiasmado",
    "evoluir",
    "expansao",
    "renovar",
}
_DECISION_HINTS = {"decidido", "fechado", "combinado", "vamos com", "decisao"}
_ACTION_HINTS = ("vou ", "vamos ", "envio ", "mando ", "agendar", "preparar", "marcar")
_RISK_HINTS = (
    "concorrente",
    "concorrencia",
    "preco",
    "abaixo",
    "atras",
    "churn",
    "atraso",
    "adiar",
)
_OPP_HINTS = ("upsell", "expandir", "expansao", "novo modulo", "incluir o", "novo plano")


def _normalize(s: str) -> str:
    return (
        s.lower()
        .replace("ã", "a")
        .replace("á", "a")
        .replace("â", "a")
        .replace("é", "e")
        .replace("ê", "e")
        .replace("í", "i")
        .replace("ó", "o")
        .replace("ô", "o")
        .replace("õ", "o")
        .replace("ú", "u")
        .replace("ç", "c")
    )


def _split_sentences(text: str) -> list[str]:
    # quebras pelas marcacoes de fala "[timestamp] Pessoa: ..." ou pontuacao final
    lines = [line.strip() for line in re.split(r"\r?\n", text) if line.strip()]
    sentences: list[str] = []
    for line in lines:
        # remove o prefixo de timestamp/falante
        stripped = re.sub(r"^\[[^\]]+\]\s*", "", line)
        stripped = re.sub(r"^[A-Z][\wáéíóúãâêôç ]{0,40}:\s*", "", stripped)
        sentences.extend(p.strip() for p in re.split(r"(?<=[.!?])\s+", stripped) if p.strip())
    return sentences


def _sentiment(text: str) -> Sentiment:
    norm = _normalize(text)
    pos = sum(1 for w in _POSITIVE_KEYWORDS if w in norm)
    neg = sum(1 for w in _NEGATIVE_KEYWORDS if w in norm)
    if pos == 0 and neg == 0:
        return Sentiment.NEUTRAL
    if pos >= neg * 2:
        return Sentiment.POSITIVE
    if neg >= pos * 2:
        return Sentiment.NEGATIVE
    return Sentiment.MIXED


def _topics(text: str, ctx_terms: list[str]) -> list[str]:
    norm = _normalize(text)
    candidates = {
        "renovacao": "renovação",
        "proposta": "proposta",
        "preco": "preço",
        "concorrencia": "concorrência",
        "demo": "demo",
        "implantacao": "implantação",
        "upsell": "upsell",
        "tpv": "TPV",
        "onboarding": "onboarding",
        "roadmap": "roadmap",
    }
    found: list[str] = []
    for key, label in candidates.items():
        if key in norm and label not in found:
            found.append(label)
    for term in ctx_terms:
        n = _normalize(term)
        if n and n in norm and term not in found:
            found.append(term)
    return found[:12]


def _make_summary(req: AnalyzeRequest, sentences: list[str]) -> str:
    head = " ".join(sentences[:4]) if sentences else "Reuniao sem conteudo significativo."
    head = re.sub(r"\s+", " ", head)[:1500]
    suffix = (
        f" Contexto considerado: {req.tenant_context.company_name}"
        f" ({req.tenant_context.industry or 'setor nao informado'})."
    )
    full = (head + suffix).strip()
    if len(full) < 30:
        full = (full + " Resumo breve gerado pelo stub deterministico do worker.")[:2000]
    return full[:2000]


def _decisions(sentences: list[str]) -> list[Decision]:
    out: list[Decision] = []
    for s in sentences:
        n = _normalize(s)
        if any(h in n for h in _DECISION_HINTS):
            confidence = 0.85 if ("data" in n or "ate " in n or "vamos com" in n) else 0.7
            out.append(Decision(text=s[:500], confidence=confidence))
        if len(out) >= 5:
            break
    return out


def _action_items(sentences: list[str], max_items: int) -> list[ActionItem]:
    out: list[ActionItem] = []
    for s in sentences:
        n = _normalize(s)
        if any(n.startswith(h) or f" {h}" in n for h in _ACTION_HINTS):
            is_urgent = "ate " in n or "hoje" in n or "amanha" in n
            priority = Priority.HIGH if is_urgent else Priority.MEDIUM
            title = s[:200]
            out.append(
                ActionItem.model_validate(
                    {
                        "title": title,
                        "assignee": None,
                        "dueDate": None,
                        "priority": priority,
                        "sourceQuote": s[:500],
                    }
                )
            )
        if len(out) >= max_items:
            break
    return out


def _risks(sentences: list[str]) -> list[Risk]:
    out: list[Risk] = []
    for s in sentences:
        n = _normalize(s)
        if any(h in n for h in _RISK_HINTS):
            cat = RiskCategory.COMPETITION
            if "preco" in n or "abaixo" in n or "barato" in n:
                cat = RiskCategory.PRICE
            elif "churn" in n:
                cat = RiskCategory.CHURN
            elif "atras" in n or "atraso" in n or "adiar" in n:
                cat = RiskCategory.TIMELINE
            severity = Severity.HIGH if ("perder" in n or "churn" in n) else Severity.MEDIUM
            out.append(
                Risk(
                    text=s[:500],
                    severity=severity,
                    category=cat,
                    sourceQuote=s[:500],
                )
            )
        if len(out) >= 5:
            break
    return out


def _opportunities(sentences: list[str]) -> list[Opportunity]:
    out: list[Opportunity] = []
    for s in sentences:
        n = _normalize(s)
        if any(h in n for h in _OPP_HINTS):
            out.append(
                Opportunity(
                    text=s[:500],
                    estimatedValue=Severity.MEDIUM,
                    category=OpportunityCategory.UPSELL,
                    sourceQuote=s[:500],
                )
            )
        if len(out) >= 5:
            break
    return out


def _productivity_coverage(outcome: str, sentences: list[str]) -> tuple[CoverageStatus, str | None]:
    """Retorna (status, evidence) baseado em quantos tokens (>=4 chars) do outcome
    aparecem na sentence de maior overlap. Heuristica determinista pro stub.
    """
    outcome_norm = _normalize(outcome)
    tokens = [t for t in re.split(r"\W+", outcome_norm) if len(t) >= 4]
    if not tokens:
        return CoverageStatus.MISSED, None

    best_match: str | None = None
    best_score = 0
    for s in sentences:
        s_norm = _normalize(s)
        hits = sum(1 for t in tokens if t in s_norm)
        if hits > best_score:
            best_score = hits
            best_match = s

    ratio = best_score / len(tokens)
    if ratio >= 0.7:
        return CoverageStatus.ADDRESSED, (best_match or "")[:500] or None
    if ratio >= 0.3:
        return CoverageStatus.PARTIAL, (best_match or "")[:500] or None
    return CoverageStatus.MISSED, None


def _productivity(
    req: AnalyzeRequest,
    sentences: list[str],
    decisions: list[Decision],
) -> ProductivityAssessment | None:
    """Calcula productivity quando ha goal declarado. Sem goal, retorna None (ADR 0005)."""
    if req.goal is None:
        return None

    coverage_list: list[ProductivityCoverage] = []
    addressed = 0
    partial = 0
    for outcome in req.goal.expected_outcomes:
        status, evidence = _productivity_coverage(outcome, sentences)
        coverage_list.append(
            ProductivityCoverage.model_validate(
                {
                    "expectedOutcome": outcome,
                    "status": status.value,
                    "evidence": evidence,
                }
            )
        )
        if status == CoverageStatus.ADDRESSED:
            addressed += 1
        elif status == CoverageStatus.PARTIAL:
            partial += 1

    total = len(coverage_list)
    coverage_ratio = (addressed + 0.5 * partial) / total if total else 0.0

    # Densidade normalizada: 5+ decisoes = 100%
    decision_density = min(1.0, len(decisions) / 5.0)

    # Off-topic heuristico: poucas frases = foco; muitas = mais ruido
    off_topic_ratio = 0.2 if len(sentences) < 30 else min(0.5, 0.2 + (len(sentences) - 30) / 100)

    # Score 0-100: cobertura domina (70%), density (20%), foco (10%)
    score = int(coverage_ratio * 70 + decision_density * 20 + (1 - off_topic_ratio) * 10)
    score = max(0, min(100, score))

    if score >= 70:
        band = ProductivityBand.HIGH
    elif score >= 40:
        band = ProductivityBand.MEDIUM
    else:
        band = ProductivityBand.LOW

    rationale = (
        f"Stub deterministico: {addressed}/{total} outcomes ADDRESSED, "
        f"{partial} PARTIAL. Densidade de decisoes: {decision_density:.2f}. "
        f"Off-topic estimado: {off_topic_ratio:.2f}."
    )

    return ProductivityAssessment.model_validate(
        {
            "score": score,
            "band": band.value,
            "coverage": [c.model_dump(by_alias=True) for c in coverage_list],
            "offTopicRatio": off_topic_ratio,
            "decisionDensity": decision_density,
            "rationale": rationale,
        }
    )


# Cues que sinalizam conversa com cliente/lead (vs. reuniao interna). Heuristica
# determinista do stub; o LLM real decide com semantica.
_BUYING_SIGNAL_HINTS: dict[str, BuyingSignalType] = {
    "orcamento": BuyingSignalType.BUDGET_DISCUSSED,
    "budget": BuyingSignalType.BUDGET_DISCUSSED,
    "investimento": BuyingSignalType.BUDGET_DISCUSSED,
    "prazo": BuyingSignalType.TIMELINE_DISCUSSED,
    "cronograma": BuyingSignalType.TIMELINE_DISCUSSED,
    "diretor": BuyingSignalType.STAKEHOLDER_INVOLVED,
    "decisor": BuyingSignalType.STAKEHOLDER_INVOLVED,
    "proximo passo": BuyingSignalType.NEXT_STEP_REQUESTED,
    "proximos passos": BuyingSignalType.NEXT_STEP_REQUESTED,
    "referencia": BuyingSignalType.REFERENCE_REQUESTED,
    "case": BuyingSignalType.REFERENCE_REQUESTED,
    "proposta": BuyingSignalType.PROPOSAL_REQUESTED,
}
_OBJECTION_HINTS: dict[str, ObjectionType] = {
    "caro": ObjectionType.PRICE,
    "preco": ObjectionType.PRICE,
    "barato": ObjectionType.PRICE,
    "abaixo": ObjectionType.PRICE,
    "demora": ObjectionType.TIMELINE,
    "atraso": ObjectionType.TIMELINE,
    "concorrente": ObjectionType.COMPETITOR_MENTION,
    "concorrencia": ObjectionType.COMPETITOR_MENTION,
    "confianca": ObjectionType.TRUST,
    "seguranca": ObjectionType.TRUST,
    "falta": ObjectionType.FEATURE_GAP,
}


def _customer_confidence(
    req: AnalyzeRequest,
    sentences: list[str],
) -> CustomerConfidence | None:
    """Detecta Customer Confidence de forma determinista (ADR 0006).

    Retorna None quando a transcricao nao tem cues de conversa com cliente/lead
    (default de reuniao interna); caso contrario, emite um objeto pequeno com
    sinais/objecoes citados. O LLM real toma essa decisao com semantica; aqui e
    so uma heuristica pra manter o contrato e exercitar o modelo.
    """
    buying_signals: list[BuyingSignal] = []
    seen_signal_types: set[BuyingSignalType] = set()
    objections: list[Objection] = []
    seen_objection_types: set[ObjectionType] = set()

    for s in sentences:
        n = _normalize(s)
        for hint, sig_type in _BUYING_SIGNAL_HINTS.items():
            if hint in n and sig_type not in seen_signal_types:
                seen_signal_types.add(sig_type)
                buying_signals.append(BuyingSignal(type=sig_type, quote=s[:500], weight=0.5))
        for hint, obj_type in _OBJECTION_HINTS.items():
            if hint in n and obj_type not in seen_objection_types:
                seen_objection_types.add(obj_type)
                competitor = None
                if obj_type == ObjectionType.COMPETITOR_MENTION:
                    competitor = next(
                        (c for c in req.tenant_context.competitors if c and _normalize(c) in n),
                        None,
                    )
                severity = Severity.HIGH if competitor or "perder" in n else Severity.MEDIUM
                objections.append(
                    Objection.model_validate(
                        {
                            "type": obj_type.value,
                            "quote": s[:500],
                            "severity": severity.value,
                            "competitor": competitor,
                        }
                    )
                )

    # Sem nenhum cue de venda (sinal de compra ou objecao), tratamos como
    # reuniao interna -> None. O LLM real decide com semantica; aqui e cue-based.
    if not buying_signals and not objections:
        return None

    # Score: sinais positivos sobem, objecoes descem. Heuristica determinista.
    raw = 50 + len(buying_signals) * 12 - len(objections) * 10
    score = max(0, min(100, raw))
    if score >= 70:
        band = ConfidenceBand.HIGH
    elif score >= 40:
        band = ConfidenceBand.MEDIUM
    else:
        band = ConfidenceBand.LOW

    rationale = (
        f"Stub deterministico: {len(buying_signals)} sinal(is) de compra, "
        f"{len(objections)} objecao(oes) detectada(s). Score derivado das cues "
        f"de conversa com cliente/lead."
    )

    return CustomerConfidence.model_validate(
        {
            "score": score,
            "band": band.value,
            "trend": None,
            # Stub nao parseia nome do cliente; o LLM real preenche. Mantemos null.
            "accountName": None,
            "buyingSignals": [b.model_dump(by_alias=True) for b in buying_signals],
            "objections": [o.model_dump(by_alias=True) for o in objections],
            "rationale": rationale,
        }
    )


_SPEAKER_RE = re.compile(r"^[A-Z][\wáéíóúãâêôç ]{0,40}:\s*")


def _participants(text: str) -> list[Participant]:
    speakers: dict[str, int] = {}
    for line in re.split(r"\r?\n", text):
        line = line.strip()
        m = _SPEAKER_RE.match(line)
        if m:
            name = m.group(0).rstrip(": ").strip()
            if len(name) >= 2:
                speakers[name] = speakers.get(name, 0) + 1
    return [
        Participant(name=name, role=None, mentionCount=count)
        for name, count in sorted(speakers.items(), key=lambda x: -x[1])
    ]


def analyze(req: AnalyzeRequest, *, pii_redactions_applied: int = 0) -> AnalyzeResponse:
    """Analisa a transcricao de forma deterministica. Sem chamada externa."""
    started = time.monotonic()
    sentences = _split_sentences(req.transcript)
    ctx_terms = (
        [p.name for p in req.tenant_context.products]
        + req.tenant_context.competitors
        + [g.term for g in req.tenant_context.glossary]
    )

    decisions = _decisions(sentences)
    productivity = _productivity(req, sentences, decisions)
    customer_confidence = _customer_confidence(req, sentences)

    response = AnalyzeResponse.model_validate(
        {
            "meetingId": req.meeting_id,
            "summary": _make_summary(req, sentences),
            "decisions": decisions,
            "actionItems": _action_items(sentences, req.options.max_action_items),
            "risks": _risks(sentences) if req.options.include_risks else [],
            "opportunities": _opportunities(sentences) if req.options.include_opportunities else [],
            "sentimentOverall": _sentiment(req.transcript),
            "topics": _topics(req.transcript, ctx_terms),
            "participants": _participants(req.transcript),
            "productivity": productivity.model_dump(by_alias=True) if productivity else None,
            "customerConfidence": (
                customer_confidence.model_dump(by_alias=True) if customer_confidence else None
            ),
            "metadata": {
                "modelVersion": "stub-deterministic-v1",
                "promptVersion": req.options.prompt_version,
                "tokensInput": 0,
                "tokensOutput": 0,
                "processingMillis": int((time.monotonic() - started) * 1000),
                "piiRedactionsApplied": pii_redactions_applied,
            },
        }
    )
    return response
