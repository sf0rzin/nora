---
title: "Auditoria pré-apresentação — Sumário executivo"
owner: Arquiteto NORA (Tech Lead)
status: approved
version: 1.0
last_reviewed: 2026-06-06
contexto: "Preparação para a 1ª apresentação acadêmica do NORA (FIAP) em 2026-06-15"
---

# Auditoria pré-apresentação — Sumário executivo

> Auditoria de prontidão do **NORA Core** para a primeira apresentação (2026-06-15).
> Cobre três frentes pedidas: (1) lacunas entre back-end e front-end, (2) higiene do
> repositório e (3) profissionalização da documentação. Todos os achados foram
> **verificados contra o código** (não apenas inferidos), com verificação adversarial
> dos gaps para eliminar falsos positivos.

## Como esta auditoria foi produzida

A análise foi conduzida por uma orquestração multiagente (inventário paralelo do
back-end, worker, web e admin → síntese de lacunas → verificação adversarial de cada
achado). Cada lacuna candidata passou por um agente independente cuja tarefa era
**refutá-la** procurando consumo real no front-end; só sobreviveram as confirmadas.
Cada remoção sugerida foi verificada por busca de referências em todo o repositório.

## Documentos desta auditoria

| # | Documento | Conteúdo |
|---|---|---|
| 01 | [Lacunas back-end × front-end](01-gaps-backend-frontend.md) | Capacidades que o back-end expõe e o front-end (web + admin) ainda não consome, priorizadas para 15/06 |
| 02 | [Auditoria do repositório](02-auditoria-repositorio.md) | Itens removíveis com segurança, branches obsoletas, *smells* de configuração e o que foi verificado e descartado |
| 03 | [Diagnóstico de documentação](03-diagnostico-documentacao.md) | *Drift* doc × código, problemas de tom/consistência e plano de profissionalização |
| 04 | [Guia de estilo e templates](04-guia-de-estilo-e-templates.md) | Padrão proposto para a documentação: front-matter, política de idioma, tom, templates de ADR e runbook |

O **README** foi reescrito nesta mesma branch como **documento-modelo** do novo padrão
(ver documento 03, seção "Documento-modelo").

## Conclusão executiva

O NORA Core está **sólido e demonstrável de ponta a ponta**. O fluxo central
(upload → análise → resumo/decisões/tarefas → chat com RAG) está real e funcional. Os
achados abaixo são de polimento e prontidão, não de fundação.

### O que precisa de atenção antes de 15/06

| Prioridade | Item | Onde | Esforço |
|---|---|---|---|
| **Crítico** | Botão **"Reprocessar"** no web (recuperar análise que falhou ao vivo) — o back-end e o Desktop já têm; o web só mostra o texto "Tente reprocessar" sem ação | [01 §1](01-gaps-backend-frontend.md) | Baixo |
| **Crítico (config)** | Garantir `NEXT_PUBLIC_USE_MOCKS≠true` no web **e** `NORA_ADMIN_USE_MOCKS=false` (+ tokens) no admin no ambiente da demo — ambos exibem dados fictícios por padrão | [01 §4](01-gaps-backend-frontend.md) | Configuração |
| Alto | Reconciliar o *drift* dos docs de produto (contagem de ADRs, migrations, status de RAG/LGPD) antes de qualquer leitura por banca/avaliador | [03](03-diagnostico-documentacao.md) | Médio |
| Alto (produto) | "Direito ao esquecimento" (LGPD) é **anunciado na landing** mas não tem botão que chame o endpoint já existente | [01 §1](01-gaps-backend-frontend.md) | Baixo |

> **Importante:** nenhum desses itens bloqueia o *happy-path* da demo. O único gap
> verdadeiramente crítico de fluxo é o botão de reprocessar; o restante é diferencial
> de produto, prontidão de configuração ou consistência documental.

### O que está saudável (verificado)

- Fluxo Core completo no web: autenticação, upload, polling de status, resumo em
  Markdown, decisões, action items, Productivity Score, Customer Confidence, chat com
  RAG por embeddings.
- IAM estilo AWS operacional (grupos, policies versionadas, audit log).
- Higiene de build sólida: nenhum artefato de build commitado; `.gitignore` efetivo.
- Qualidade técnica dos ADRs e da arquitetura é alta — o ponto fraco da documentação é
  consistência e tom, não conteúdo.

### Riscos de configuração da demo (não são código)

1. **Web em modo mock**: `NEXT_PUBLIC_USE_MOCKS=true` faz o dashboard/detalhe servirem
   *fixtures* JSON em vez da API. Confirmar desligado.
2. **Admin em modo mock por padrão**: `NORA_ADMIN_USE_MOCKS` é `true` a menos que
   explicitamente `=false`. Sem `PLATFORM_API_BASE_URL` + `PLATFORM_INTERNAL_TOKEN`, o
   console mostra catálogo fictício e qualquer mutação some sem efeito.

Detalhes e a checklist de configuração estão no documento [01, seção 4](01-gaps-backend-frontend.md).
