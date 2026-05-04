# 0004 — Estratégia de Provider de LLM (agnóstica, OpenAI como default)

- Status: aceito
- Data: 2026-05-04
- Decisores: Time NORA
- Substitui parcialmente: 0001 (na parte que cita Azure OpenAI como provider único)

## Contexto

A documentação inicial (`PROJECT.md`, ADR 0001) assumia **Azure OpenAI** como provider de LLM por causa da parceria Microsoft × TOTVS, da disponibilidade em região Brasil e do SLA enterprise.

Na prática, durante o setup do MVP, identificamos:

1. **Acesso ao Azure OpenAI exige aprovação manual** da Microsoft via formulário corporativo. O processo é lento (dias a semanas) e tipicamente nega contas de estudante / contas individuais. Bloqueio real para o time começar a US11–US14 do backlog.
2. O time não tem orçamento corporativo para destravar o acesso no prazo das Sprints 1+2.
3. O consumo previsto do MVP é baixo: ordem de centenas a alguns milhares de análises de reunião durante todo o ciclo acadêmico. Custo total estimado abaixo de **US$ 5–10**.
4. O contrato com o LLM já é estável (JSON Schemas em `docs/api/llm-schemas/` + ADR 0003). Trocar de provider não exige mudar o resto do sistema.

Portanto, faz sentido tornar o worker **agnóstico de provider** e escolher o provider mais simples e barato para o MVP, mantendo Azure OpenAI como upgrade para Enterprise quando o acesso for aprovado.

## Decisão

1. O worker NLP fala com **qualquer provider compatível com a API Chat Completions da OpenAI** (mesma SDK, mesmo formato de request/response). Isso cobre OpenAI direto, Azure OpenAI, Groq, OpenRouter, Together AI, Ollama local e quaisquer outros que sigam o padrão de fato.
2. O **provider default no MVP é OpenAI direto** com o modelo `gpt-4o-mini`.
3. As variáveis de ambiente do worker são generalizadas:
   - `LLM_PROVIDER` (rótulo informativo: `openai`, `azure`, `groq`, `openrouter`, `ollama`, `openrouter`, etc.)
   - `LLM_BASE_URL`
   - `LLM_API_KEY`
   - `LLM_MODEL`
   - `LLM_TEMPERATURE`
   - `USE_LLM_STUB` continua existindo e **continua sendo o padrão em CI e dev local**.
4. Provider Azure OpenAI permanece **suportado e recomendado para tenants Enterprise** quando o acesso estiver aprovado — basta apontar `LLM_BASE_URL` para o endpoint Azure, ajustar o modelo/deployment e usar a `AZURE_OPENAI_API_KEY` no campo `LLM_API_KEY`.
5. Embeddings e busca semântica seguem o mesmo princípio: **qualquer endpoint compatível com OpenAI Embeddings**. No MVP usamos `text-embedding-3-small` da OpenAI; em Enterprise, Azure OpenAI + Azure AI Search (ADR a parte quando essa parte entrar).

## Por que OpenAI direto e não OpenRouter

Avaliamos OpenRouter como alternativa para concentrar billing e ter flexibilidade de modelo. Foi rejeitado para o MVP:

- **+5% de markup** sobre o preço do mesmo modelo na OpenAI.
- Hop adicional → latência maior e mais um ponto de falha.
- Structured Output strict (`response_format: json_schema`) tem suporte irregular dependendo do modelo escolhido — na OpenAI é first-class.
- Vantagem real do OpenRouter (trocar provider sem mudar código) **já está coberta** pelo design agnóstico do worker. Se um dia quisermos OpenRouter, basta mudar o `.env`.

## Por que Groq não é o default (e por que segue como fallback documentado)

Groq tem free tier excelente, é compatível com a SDK da OpenAI, e Llama 3.3 70B funciona bem em PT-BR. Continua sendo a opção recomendada para quem **não quer gastar nada**.

Não escolhemos como default porque:

- Free tier tem rate limits que podem incomodar em demos longas.
- Structured Output strict em modelos open-source é menos confiável que em `gpt-4o-mini`.
- Para o pitch e a entrega, a previsibilidade do JSON estruturado vale mais do que o custo zero.

## Consequências

**Positivas**
- Time desbloqueado para começar US11–US14 imediatamente, sem depender de aprovação Azure.
- Custo total estimado do MVP < US$ 10. Cabe em uma compra única de US$ 5 de crédito na OpenAI.
- Código continua portável: trocar de provider é mudança de `.env`, não de código.
- Stub determinístico (`USE_LLM_STUB=true`) segue como default de CI e dev local — nenhum teste depende de chave externa.

**Negativas / trade-offs**
- Saímos da promessa "tudo em Azure" do PROJECT.md original. Mitigado: documentamos que Azure volta a ser o provider de produção Enterprise quando aprovado, sem mudança de código.
- Dependência de provider externo americano (OpenAI). Mitigado: os dados sensíveis já passam pelo PII Shield antes de qualquer chamada (ADR de PII).

## Operacional

- Chaves vivem em `.env` local (nunca commitado) e em GitHub Actions Secrets para CI quando rodarmos integração (ainda não no MVP).
- Estimativa de custo deve ser monitorada manualmente pelo dono da chave durante o ciclo da Sprint.
- Quando o acesso Azure for aprovado para o tenant TOTVS, abrir nova ADR de migração e ajustar `.env` do ambiente Enterprise.
