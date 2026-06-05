# 0029 — LGPD operacional: direito ao esquecimento + retenção

- Status: aceito
- Data: 2026-06-05
- Decisores: Arquiteto + Stratfy (PO)
- Relacionado: concretiza o hard-delete previsto no ADR 0021 (soft-delete); opera sob o RLS enforce do ADR 0028; complementa o PII Shield (ADR 0012)

## Contexto

A auditoria de fundação apontou um gap de compliance: o NORA se posiciona **LGPD-first**, mas
não tinha **retenção formal** nem **direito ao esquecimento**. O risco concreto: `transcripts.raw_text`
guarda a transcrição **bruta** (PII em repouso) indefinidamente — o PII Shield (ADR 0012) só redige o
que vai pra LLM, não o que fica no banco.

O ADR 0021 (soft-delete) já tinha previsto a saída: "hard-delete continua possível via native query
explícita, para LGPD (direito ao esquecimento) e retenção. É a exceção consciente, não o default."
Este ADR concretiza essa exceção.

## Decisão

Duas capacidades, ambas via **hard-delete físico** (não soft): uma native query `DELETE FROM meetings`
ignora o `@SQLDelete`/`@SQLRestriction` da entidade, e o FK `ON DELETE CASCADE` (V004) propaga pra
`transcripts` (raw PII), `meeting_participants`, `meeting_tags` e `meeting_analyses` (+ filhos).

### 1. Direito ao esquecimento — `DELETE /privacy/meetings/{id}`

Endpoint autenticado, escopado por tenant do JWT, gate `meeting:update` (mesmo da remoção destrutiva
de goal). Apaga DEFINITIVAMENTE o meeting e todo o PII em cascata. 204 em sucesso; **404 se não existir
no tenant** (não vaza existência cross-tenant). Auditável via log (só ids, nunca conteúdo).

### 2. Retenção — sweeper agendado

`RetentionSweeper` (`@Scheduled`, cron configurável) purga meetings mais antigos que
`nora.privacy.retention-days`. **Desligado por default (`0`)**: retenção é destrutiva, então só liga por
opt-in explícito do ambiente (`NORA_PRIVACY_RETENTION_DAYS`). Itera **por tenant** porque, sob RLS
enforce (ADR 0028), a thread do scheduler não tem JWT — propaga o tenant via `TenantRlsContext` pra que
o aspect aplique o GUC na transação da purga. A listagem de tenants funciona sem GUC (`tenants` é exempta
da RLS na V020).

### 3. Prova obrigatória

`PrivacyFlowIntegrationTest` (Testcontainers) sobe o app e valida ponta-a-ponta: o erasure remove o
meeting **e o transcript (raw PII) fisicamente** (assert direto no `TranscriptRepository`); 404 em meeting
inexistente; tenant B não apaga meeting de A (e o de A permanece); exige autenticação.

## Consequências

- Fecha o gap de compliance: PII em repouso passa a ter caminho de remoção sob demanda + retenção opt-in.
- Hard-delete é **irreversível** (sem lixeira) — proposital: direito ao esquecimento exige remoção real.
- Sob RLS enforce, o cascade do FK roda no nível do banco (bypassa RLS dos filhos), então apagar o meeting
  (que passa pela RLS do tenant) purga os filhos enforced corretamente.

**Negativas / trade-offs:**
- Erasure hoje é **por meeting**, não por **titular** (e-mail). Erasure por data-subject (varrer todos os
  meetings em que uma pessoa participou) é o próximo incremento — depende de decidir a semântica (apagar o
  meeting inteiro vs. anonimizar o participante num transcript compartilhado).
- Retenção é **global** (uma janela pra todos os tenants). Retenção por-tenant/por-plano exige uma tabela de
  config — deferido até billing existir.
- O gate é `meeting:update`; um permission dedicado `privacy:erase` (mais restritivo) é hardening futuro.

## Alternativas Consideradas

- **Anonimizar em vez de deletar** (substituir PII por placeholders no `raw_text`): preserva agregados, mas
  é mais frágil (depende da cobertura do redator) e não é "esquecimento" de verdade. Rejeitado pro caso de
  direito ao esquecimento; pode complementar a retenção no futuro.
- **Soft-delete + purga depois**: adiciona latência ao esquecimento sem ganho — o ADR 0021 já cobre o
  soft-delete pro fluxo normal; LGPD quer remoção física imediata.
