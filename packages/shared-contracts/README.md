# shared-contracts

Contratos compartilhados entre serviços do NORA. Este diretório centraliza definições que precisam estar alinhadas entre múltiplas linguagens / runtimes (Java backend, Python worker, TypeScript web/desktop).

## Conteúdo atual

- `pii-types.json` — enum canônico de tipos de PII redacted (alinhado com ADR 0012 + `services/nlp-worker/src/nora_nlp/models.py:PiiType`)
- `processing-status.json` — enum de status de processamento de meeting (alinhado com `services/api/.../domain/meeting/ProcessingStatus.java`)
- `error-codes.md` — convenção de códigos de erro emitidos pelo backend e esperados pelo cliente

## Como usar

Por ora, cada serviço **espelha** estas definições no próprio código (Java enum, Pydantic Enum, TS union). Este pacote serve como **fonte da verdade documental** — divergências entre clientes devem ser resolvidas trazendo todos pra cá.

Geração de código automática (json-schema → tipos) é débito da Sub-fase 1.12 (ADR 0016).

## Por que não vazio?

Auditoria identificou que o diretório anterior `.gitkeep` apenas era confuso: documentação não referenciava o estado, devs viam ele e ignoravam. Agora ao menos as **listas de valores enum** que precisam casar entre serviços ficam centralizadas — quando um agregado novo é adicionado, lembramos de propagar pra todos os clientes.
