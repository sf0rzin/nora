# JSON Schemas — Saída Estruturada do LLM

Estes schemas são a **fonte da verdade** dos formatos que o NLP Worker exige do LLM.

- **Padrão**: Draft 2020-12.
- **Uso**: passar como `response_format: { type: "json_schema", json_schema: ... }` na chamada ao provider LLM (provider-agnóstico — default OpenAI direto `gpt-4o-mini`; Azure OpenAI em Enterprise. Ver ADR 0004).
- **Validação**: Pydantic (Python) e Bean Validation (Java) devem espelhar estes schemas.
- **Versionamento**: alterações breaking incrementam o sufixo do nome do arquivo (`-v2`).

> **Caveat de fidelidade (2026-05-21):** o `meeting-analysis-v1.schema.json` é o contrato documental, mas o worker emite via `build_json_schema_for_analysis()` (`services/nlp-worker/src/nora_nlp/clients/llm.py`) e valida via Pydantic `MeetingAnalysisV1` (`models.py`). O campo `customerConfidence` consta no schema mas **não** é emitido pelo worker (ver ADR 0015 — não implementado); `participants` e `baselineTerms` são emitidos pelo worker e foram adicionados ao schema nesta reconciliação. Mudança de campo aqui exige sincronizar Pydantic + o builder inline.

| Arquivo | Conteúdo |
|---|---|
| `meeting-analysis-v1.schema.json` | Saída completa do prompt principal de análise de reunião |
| `pii-redaction-v1.schema.json` | Resultado do shield de PII antes do envio ao LLM |
| `tenant-context-embedding-v1.schema.json` | Chunk indexável do contexto do tenant para RAG |

## Regras

1. **Nada de campos opcionais sem valor padrão claro.** Se a IA não souber, retorna `null` ou array vazio.
2. **Toda quote de origem é obrigatória** para itens acionáveis (action items, risks, opportunities).
3. **Confidências são `0.0–1.0`**, nunca percentual.
4. **Enums fechados.** Categorias livres só dentro de `topics`.
5. **Sem markdown** dentro dos campos de texto. Texto puro.
