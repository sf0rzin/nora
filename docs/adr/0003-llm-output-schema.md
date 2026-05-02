# 0003 — Saída do LLM via JSON Schema obrigatório

- Status: aceito
- Data: 2026-05-02
- Decisores: Time NORA

## Contexto

NORA depende de saídas estáveis do LLM para alimentar UI, dashboards, action items e auditoria. Texto livre quebra a aplicação, dificulta testes e impede evolução do prompt sem impacto no consumo a jusante.

A Azure OpenAI suporta `response_format: { type: "json_schema" }` em GPT-4o e similares.

## Decisão

Toda chamada do worker NLP que produz dados estruturados consumidos pela aplicação **deve**:

1. Definir um JSON Schema versionado em `docs/api/llm-schemas/` (fonte da verdade) e espelhá-lo como Pydantic model no worker.
2. Enviar o schema para a API com `response_format=json_schema` (modo estrito quando disponível).
3. Validar a resposta com Pydantic antes de retornar para o backend. Se inválida, **rejeitar** (HTTP 502 da chamada interna) com retry limitado e log estruturado.
4. Versionar prompts atrelados ao schema (`promptVersion`, `modelVersion` gravados em `meeting_analyses`).
5. Quebras de schema incrementam a versão (`-v2`); a versão antiga continua suportada por pelo menos uma release para permitir reprocessamento idempotente.

O backend Java valida novamente o payload no boundary HTTP via Bean Validation/DTOs gerados a partir do mesmo OpenAPI/Schema, evitando trust em serviço interno.

## Consequências

- A UI nunca recebe campo desconhecido sem aviso.
- Testes do worker conseguem usar fixtures de transcrição → JSON validado.
- Mudança de modelo (ex.: GPT-4o → GPT-5) é trocada via configuração; o schema mantém a estabilidade.
- Custo extra mínimo no prompt (instrução de schema cabe no system message).
- Falhas de validação geram retries — observar custo se o LLM começar a errar com frequência (alarme).

## Alternativas Consideradas

- **Texto livre + parsing por regex.** Rejeitado: frágil e hostil a manutenção.
- **Function calling (tools).** Aceitável, mas mais verboso para um único output. Mantemos como opção futura para fluxos com múltiplas chamadas/tools.
- **Confiar em "JSON mode" sem schema.** Rejeitado: garante JSON sintático, mas não a forma. Schema estrito previne campo faltante e enums inválidos.
