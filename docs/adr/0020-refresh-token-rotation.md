# 0020 — Rotação de refresh token + detecção de reuso (token families)

- Status: aceito (ADR retroativo — decisão já implementada e mergeada; registro formal criado na auditoria 2026-05-21)
- Data: 2026-05-21
- Decisores: Tech Lead
- Relacionado: Sub-fase 1.3 (refresh tokens stateful, PR #59); `data-model.md §2.24`

## Contexto

A Sub-fase 1.3 (PR #59) introduziu refresh tokens stateful: access JWT curto (15 min) + refresh opaco de longa duração (30 dias, UUID, hash SHA-256 persistido em `refresh_tokens`, cookie httpOnly `nora_refresh`). Cada `/auth/refresh` renovava o access sem rotacionar o refresh.

**Problema (audit follow-up #3):** sem rotação, um refresh token era válido até expirar (30 dias). Se um atacante exfiltrasse o cookie (XSS residual, malware no device, proxy), poderia renovar access tokens livremente por até 30 dias — **sem detecção** — até a vítima fazer logout manual. O blast radius de um cookie vazado era enorme e silencioso.

## Decisão

Adotar **rotação de refresh token com detecção de reuso baseada em token families** (V014).

### Modelo (V014)

- `refresh_tokens.family_id UUID NOT NULL` — tokens da mesma cadeia de rotação compartilham `family_id` (backfill: tokens existentes viram `family_id = id`).
- `refresh_tokens.replaced_by_id UUID NULL REFERENCES refresh_tokens(id)` — quando rotacionado, aponta para o sucessor; `NULL` = token ativo da cadeia ou revogado sem sucessor (logout).
- Índice `idx_refresh_tokens_family(family_id)` para revogar a cadeia inteira.

### Comportamento

- **Rotação:** cada `/auth/refresh` valida o token apresentado, emite um **novo** token na **mesma `family_id`**, marca o anterior como revogado e seta seu `replaced_by_id` para o novo.
- **Detecção de reuso:** se um token **já revogado** for apresentado, assume-se comprometimento (o legítimo já rotacionou; alguém está usando uma cópia antiga). Resposta: **revogar a family inteira** via `RefreshTokenRepository.revokeAllByFamilyId(familyId, now)` — atacante **e** vítima são deslogados, forçando re-login.

## Consequências

**Positivas:**

- Blast radius de um cookie vazado cai de ~30 dias para uma janela de uso: assim que legítimo ou atacante rotaciona, o outro lado dispara a detecção e mata a family.
- Detecção ativa de comprometimento (vs. expiração passiva): o reuso de um token revogado é sinal forte e acionável.
- Padrão de mercado (OAuth 2.0 BCP / refresh token rotation) — familiar a quem revisa segurança B2B.

**Negativas / trade-offs:**

- Falsos positivos possíveis em corridas legítimas (duas abas/dispositivos rotacionando "ao mesmo tempo" com o mesmo token antigo) ⇒ logout da family. Mitigação aceitável para o perfil de risco; janela é curta.
- Estado extra por token (family + replaced_by). Cleanup de tokens antigos da cadeia é débito (hard-delete por retenção).
- Clientes precisam tratar `REFRESH_TOKEN_INVALID` re-logando (já contemplado em `error-codes.md`).

## Alternativas Consideradas

1. **Manter refresh sem rotação (status quo 1.3)** — rejeitado: cookie vazado = 30 dias de acesso silencioso.
2. **Rotação sem detecção de reuso** (só emitir novo + revogar antigo) — rejeitado: rotaciona mas não reage ao reuso do antigo; perde a detecção de comprometimento, que é o maior ganho.
3. **Encurtar a validade do refresh** (ex.: 24h) — rejeitado isoladamente: piora UX (re-login frequente) sem detectar comprometimento; rotação resolve melhor o trade-off segurança×UX.

## Histórico

| Data | Decisor | Mudança |
|---|---|---|
| 2026-05-21 | Tech Lead | ADR retroativo criado na auditoria doc×código. Decisão já implementada em `V014__refresh_token_rotation.sql` (audit follow-up #3, PR #116) + `RefreshTokenRepositoryAdapter.revokeAllByFamilyId` |
