# 0021 — Estratégia de soft-delete em entidades tenant-owned

- Status: aceito (ADR retroativo — decisão já implementada e mergeada; registro formal criado na auditoria 2026-05-21)
- Data: 2026-05-21
- Decisores: Tech Lead
- Relacionado: ADR 0002 (multi-tenancy); LGPD (direito ao esquecimento)

## Contexto

Até a V012, deletar uma entidade tenant-owned era hard-delete (DELETE físico + cascatas). Isso traz problemas para um SaaS multi-tenant sob LGPD:

- **Sem reversibilidade**: exclusão acidental de um meeting/tenant é irrecuperável fora de backup.
- **Sem trilha**: não dá para distinguir "nunca existiu" de "foi removido".
- **Tensão com unicidade**: se um `user` é removido e o mesmo e-mail tenta novo signup, o UNIQUE total `(tenant_id, email)` bloquearia para sempre (ou exigiria hard-delete imediato).

Ao mesmo tempo, LGPD exige **hard-delete real** para o direito ao esquecimento — então soft-delete não pode ser o único caminho.

## Decisão

Adotar **soft-delete por default** nas entidades tenant-owned principais, com hard-delete preservado como operação explícita (V013).

### Modelo (V013)

- Coluna `deleted_at TIMESTAMPTZ NULL` em `tenants`, `users`, `tenant_contexts`, `meetings` (`NULL` = ativo).
- Cada `@Entity` correspondente usa Hibernate `@SQLDelete(sql = "UPDATE <t> SET deleted_at = NOW(), updated_at = NOW() WHERE id = ?")` + `@SQLRestriction("deleted_at IS NULL")` — toda query do Spring Data passa a **filtrar registros vivos por default**, e `repository.delete()` vira UPDATE.
- **UNIQUEs totais viram parciais** `WHERE deleted_at IS NULL`: `tenants.slug`, `users(tenant_id, email)`, `tenant_contexts.tenant_id`. Assim slug/email podem ser reusados após soft-delete (um user removido não bloqueia novo signup com o mesmo e-mail).
- Índices `*_deleted_at_idx` apoiam o filtro.

### Hard-delete

Continua possível via **native query** explícita, para LGPD (direito ao esquecimento) e retenção. É a exceção consciente, não o caminho default.

## Consequências

**Positivas:**

- Exclusão reversível por default; trilha de "quando foi removido".
- Reuso de slug/email pós-remoção sem colisão de UNIQUE.
- `@SQLRestriction` torna o filtro transparente — código de query existente não precisa adicionar `AND deleted_at IS NULL`.

**Negativas / trade-offs:**

- **Pegadinha de unicidade**: o filtro vive na camada Hibernate (`@SQLRestriction`), não no banco. Queries nativas/relatórios que não passam pelo Hibernate **veem** linhas soft-deleted — atenção em jobs/SQL ad-hoc.
- Dados "removidos" continuam no banco até hard-delete — relevante para LGPD (retenção precisa de processo) e para tamanho de tabela.
- Tabelas filhas (ex.: `meeting_analyses`) hoje não têm `deleted_at` próprio; dependem do soft-delete do pai + cascata lógica. Inconsistência a observar se uma filha for consultada diretamente.
- `UserJpaEntity` aplica `@SQLRestriction` mas não mapeia a propriedade `deletedAt` (lê via SQL apenas) — débito menor de mapeamento.

## Alternativas Consideradas

1. **Hard-delete only (status quo ≤V012)** — rejeitado: irreversível, sem trilha, colisão de UNIQUE no re-signup.
2. **Soft-delete only (sem caminho de hard-delete)** — rejeitado: incompatível com LGPD (direito ao esquecimento exige remoção real).
3. **Tabela de auditoria/arquivo separada** (mover linha deletada para `*_archive`) — rejeitado para já: mais complexidade de schema/migração que `deleted_at`; reconsiderar se a retenção exigir separação física.

## Histórico

| Data | Decisor | Mudança |
|---|---|---|
| 2026-05-21 | Tech Lead | ADR retroativo criado na auditoria doc×código. Decisão já implementada em `V013__add_soft_delete.sql` + `@SQLDelete`/`@SQLRestriction` nas entidades (audit follow-up, PR #114) |
