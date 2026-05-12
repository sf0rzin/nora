# 0012 — PII PERSON_NAME: estratégia regional BR no MVP, upgrade pra NER multi-idioma quando internacionalizar

- Status: aceito
- Data: 2026-05-12
- Decisores: Anthony Sforzin (PO) + Claude Opus 4.7 (Tech Lead)

## Contexto

O PII shield do worker NLP cobre EMAIL, CPF, CNPJ, PHONE, CREDIT_CARD desde a Subfase 1.0. A Subfase 1.1 (TF-IDF baseline) expôs problema sério: o ranking top-N de termos era dominado por **nomes próprios** (Lucas, Marina, Rafael, Camila) das transcrições sintéticas — não por termos de negócio. Em produção isso se traduz em:

- `baselineTerms` do response degrada (mostra nomes em vez de produtos/conceitos)
- Logs e auditoria expõem nomes de pessoas reais (LGPD risk se não redigir antes do LLM)
- LLM recebe nomes brutos no prompt — em alguns casos isso enviesa extração (LLM "lembra" do nome em decisão/risco)

A Subfase 1.3 (Fatia P) adicionou detecção de PERSON_NAME via **três padrões heurísticos**:

1. Prefixos formais: `Sr\.|Sra\.|Dr\.|Dra\.|Profa?\.` seguidos de Title Case
2. Title Case sequence: 2-4 palavras capitalizadas em sequência (filtradas por negative list)
3. **Lista hardcoded de ~270 nomes BR comuns** (Lucas, Marina, Camila, Rafael, Carlos, Ana, João, Maria, etc — variantes com e sem acento)

Mais uma **negative list (~80 termos)** evita falsos positivos de produtos, empresas e siglas técnicas (TOTVS, Protheus, NORA, SAP, Oracle, Salesforce, etc).

A solução cobre **bem o mercado-alvo MVP** (Brasil, ecossistema TOTVS), mas tem **limitações conhecidas**:

- **Não escala internacionalmente**: nomes anglo-saxões (John, Sarah), franceses (Pierre, Marie), asiáticos (Hiroshi, Wei), espanhóis (Diego, Pablo) não estão cobertos pela lista BR
- **Manutenção custosa por região**: cada mercado novo exigiria expansão da lista
- **Cobertura ~80%** para tenants brasileiros — nomes incomuns ou variantes raras escapam
- **Padrão 2 (Title Case sequence)** captura algumas pessoas estrangeiras por sorte (qualquer "Word1 Word2" Title Case fora da negative list) — mas com falsos positivos previsíveis (nomes de lugares, conceitos capitalizados)

## Decisão

**Aceitar a estratégia regional BR como solução MVP**, com limitação documentada e plano de upgrade explícito.

Esta decisão é uma escolha **consciente de escopo**, não negligência:

- Mercado-alvo MVP é Brasil (parceria FIAP × TOTVS, ecossistema lusófono)
- Custo de implementar NER multi-idioma agora vs. ganho real = não justifica
- Solução atual é **suficiente** pra demonstrar PII shield em pitch FIAP/NEXT 2026 e onboardar primeiros clientes BR
- Cobertura limitada é **explicitamente documentada** em testes e neste ADR

### Trigger pra upgrade (não automático)

Migrar pra solução NER multi-idioma quando **pelo menos uma** das condições abaixo for verdadeira:

1. **Primeiro tenant não-brasileiro** assinar contrato ou entrar em piloto formal
2. **>5% das transcrições processadas em produção** vierem em idioma ≠ pt-BR (medido via metadado `language` em `AnalyzeRequest` + telemetria do worker)
3. **Bug report de cliente** com falso negativo de nome causando vazamento via baselineTerms ou audit log
4. **Audit de segurança LGPD** flagar a lista hardcoded como inadequada

## Consequências

**Positivas:**

- Tempo de implementação na Subfase 1.3 foi mínimo (~30min agentic), sem dep externa
- Zero overhead runtime adicional (regex puro, ~ms)
- Sem dep pesada (Presidio ~30MB, spaCy ~100MB) no container do worker
- Testes determinísticos (lista fixa permite asserções exatas)
- Solução é honesta com o escopo MVP

**Negativas / dívidas:**

- **Não escala** pra internacionalização sem refactor
- **Manutenção implícita**: se descobrirmos nome BR comum não coberto, precisa update manual da lista
- Negative list cresce conforme produtos/empresas novos aparecem em transcrições
- Audit de PII em prod **precisará flagar** explicitamente que cobertura é regional, não universal

## Alternativas Consideradas

### (a) Lista global expandida (~5000+ nomes multi-cultural)

Rejeitado:
- Vira lista impossível de manter (top 1000 nomes de cada N região)
- Bloat de bytes no código sem solucionar problema sistêmico (sempre vai faltar algum)
- Falsos positivos crescem proporcionalmente

### (b) Heurística pura (regex Title Case sem lista)

Rejeitado:
- É exatamente o padrão 2 que já temos — sozinho não basta
- Falsos positivos explodem (cidades, conceitos capitalizados, produtos sem prefixo conhecido)
- Padrão fica indistinguível de noise

### (c) Microsoft Presidio (ML-based NER)

Adiada — **opção principal pra upgrade futuro** quando trigger condições baterem:
- Multi-idioma robusto (suporta EN, PT, ES, FR nativamente)
- ML-based: detecta nomes por contexto, não por lista
- Custo: dep pesada ~30MB + warmup model load (~2s no startup) + ~50ms por análise
- Maduro (Microsoft mantém)
- Pode coexistir com lista BR (Presidio detecta, lista BR confirma com alta confiança)

### (d) spaCy com modelos NER pré-treinados

Alternativa secundária ao Presidio:
- `pt_core_news_lg` (~500MB) ou `xx_ent_wiki_sm` (~10MB, multilingual)
- Maduro, oss, ampla comunidade
- Custo: dep mid-weight + warmup similar ao Presidio
- Menos focada em PII especificamente (Presidio é PII-first; spaCy é NER genérico)

### (e) LLM 2-stage (chamada extra dedicada a extrair nomes)

Rejeitado pra agora:
- Custo dobra por análise (~$0.04 → ~$0.08 com gpt-4o-mini)
- Latência adicional (~1-2s por análise)
- Multi-idioma nativo, mas o ROI não bate o custo agora

## Plano de Upgrade

Quando trigger condition for atingida:

1. Avaliar Presidio vs spaCy vs LLM 2-stage com benchmark real (latência, recall, precision em 50 transcrições reais)
2. Criar ADR sucessor (ex.: `0XXX-pii-person-name-ner-upgrade.md`) com decisão técnica final
3. Implementar substituição mantendo lista BR como camada de confiança alta (combo: NER detecta → lista confirma)
4. Migration estratégica: feature flag `PII_PERSON_DETECTOR=heuristic|presidio|spacy`, rollout gradual
5. Manter testes determinísticos atuais como regression suite

## Regras Acompanhantes

- Lista `_BR_TOP_NAMES` e `_PERSON_NAME_NEGATIVE_LIST` em `services/nlp-worker/src/nora_nlp/services/pii_shield.py` são **propriedade do produto** — qualquer adição passa por code review (alguém com contexto BR/PT) pra evitar bias regional
- Pull requests que adicionem nomes à lista BR devem incluir justificativa no commit message (ex.: "encontrado em transcrição de cliente Y, padrão N=120 outras conversas")
- Logs em produção que detectarem nome via padrão 2 (Title Case sem lista) devem emitir métrica `pii.person.fallback_heuristic` pra monitorar gap real entre lista vs heurística

## Histórico

| Data | Decisor | Mudança |
|---|---|---|
| 2026-05-12 | Anthony + Claude | ADR criado e aceito |
