# JSON Schemas — Saída Estruturada do LLM

Estes schemas são a **fonte da verdade** dos formatos que o NLP Worker exige do LLM.

- **Padrão**: Draft 2020-12.
- **Uso**: passar como `response_format: { type: "json_schema", json_schema: ... }` na chamada Azure OpenAI.
- **Validação**: Pydantic (Python) e Bean Validation (Java) devem espelhar estes schemas.
- **Versionamento**: alterações breaking incrementam o sufixo do nome do arquivo (`-v2`).

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
