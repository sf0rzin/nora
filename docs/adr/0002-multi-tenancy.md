# 0002 — Estratégia de multi-tenancy

- Status: aceito
- Data: 2026-05-02
- Decisores: Time NORA

## Contexto

NORA é multi-tenant desde o dia 1. Cada empresa cliente é um tenant; nenhum dado pode vazar entre tenants. Existem três abordagens viáveis:

1. **Banco por tenant** (separação física).
2. **Schema por tenant** dentro do mesmo banco.
3. **Schema único compartilhado com `tenant_id` em toda tabela tenant-bound**, opcionalmente com Row-Level Security.

O time é pequeno e o MVP precisa entregar rápido sem comprometer a segurança.

## Decisão

Adotar **schema único compartilhado com coluna `tenant_id` obrigatória** em toda tabela tenant-bound, com a seguinte progressão:

- **MVP**: o filtro `tenant_id = ?` é aplicado por uma camada de aplicação (interceptor/aspect no Spring) que recupera o tenant do JWT autenticado. Toda repository expõe apenas métodos que recebem o `tenantId` explicitamente. Testes de integração obrigatórios cobrem cenários de cross-tenant.
- **Produção**: habilitar **Postgres Row-Level Security** em todas as tabelas tenant-bound, com policy baseada em `current_setting('app.tenant_id')` setado no início de cada conexão/transação.

Tabelas globais (`system_plans`, etc.) ficam sem `tenant_id`.

## Consequências

- Custo operacional baixo, escala bem para milhares de tenants.
- Zero risco de "esquecer" o filtro em produção (a RLS impede).
- Backups e migrations únicas.
- Não atende clientes que exijam separação física do dado por contrato — esses migram para deployment dedicado no futuro.
- Exige disciplina nos repositórios e testes específicos de isolamento.

## Alternativas Consideradas

- **Banco por tenant.** Rejeitado: custo de provisionamento, complexidade de migrations e custo de Azure por banco.
- **Schema por tenant.** Rejeitado: explosão de objetos no banco e complexidade de migrations multiplicadas pelo número de tenants.

## Regras Acompanhantes

- Nunca buscar entidade tenant-bound só por `id`. Sempre `tenant_id + id`.
- Um endpoint que retorna 404 cross-tenant **não deve** distinguir "não existe" de "não autorizado" para usuários sem privilégio elevado, evitando enumeração.
- Toda nova tabela passa por checklist de PR: tem `tenant_id`? índice composto? teste de isolamento?
