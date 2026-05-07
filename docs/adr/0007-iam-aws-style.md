# 0007 — IAM estilo AWS (Root + Users + Groups + Policies)

- Status: aceito
- Data: 2026-05-07
- Decisores: Time NORA

## Contexto

A versão inicial dos documentos previa um IAM híbrido **RBAC + ABAC** com cinco roles fixas (`ROOT`, `ADMIN`, `MANAGER`, `ANALYST`, `VIEWER`) e uma tabela `access_scopes` para limitar visibilidade por `team` ou `region`. Esse modelo cresceu organicamente entre `docs/PROJECT.md`, `docs/visao-do-produto.md`, `docs/data-model.md` e `docs/backlog-mvp.md`, gerando incongruências:

- `visao-do-produto.md` listava 4 níveis ("Root, Admin, Manager, Analyst/Viewer") enquanto `PROJECT.md` listava 5.
- `access_scopes` usava `OWN_MEETINGS / TEAMS / REGIONS`, mas a narrativa falava em `department / project / client_account`.
- `access_scopes.user_id` era PK, impedindo combinar mais de um eixo de escopo por usuário.
- O conceito "region" estava órfão (sem tabela própria, sem story que o exigisse).
- Roles fixas contradiziam a comparação repetida com "AWS IAM" — que é justamente o oposto.

Além disso, o time alinhou que tenants Enterprise precisam de **liberdade real** para modelar a própria estrutura de acesso (departamentos, projetos, contas, regiões, qualquer eixo), sem depender da plataforma adicionar novas roles.

## Decisão

Adotar um modelo **estilo AWS IAM** desde o MVP:

```
Tenant
├── Root user           — owner do tenant; criado no provisionamento; bypass total
├── Users               — convidados pelo Root ou por quem tiver permissão de IAM
├── Groups              — coleções nomeadas de usuários, criadas livremente pelo tenant
├── Policies            — documentos JSON: Effect / Action / Resource [/ Condition]
├── Users ⇄ Groups       (N:N)
├── Groups ⇄ Policies    (N:N)
└── Users ⇄ Policies     (N:N — anexação direta, opcional)
```

**Vocabulário:**

- **Actions** seguem o padrão `service:operation`: `meeting:read`, `meeting:upload`, `analysis:export`, `tenant:context:write`, `iam:user:invite`, `iam:group:create`, `iam:policy:create`, `audit:read`, etc. Wildcards permitidos (`meeting:*`, `*`).
- **Resources** são ARN-like: `nora:tenant/{tenantId}:meeting/{meetingId}`, `nora:tenant/{tenantId}:meeting/*`, com wildcard.
- **Conditions** estilo AWS (`stringEquals`, `stringIn`, `dateGreaterThan`, etc.) operando sobre atributos definidos pelo próprio tenant: `nora:Department`, `nora:Project`, `nora:Account`. A NORA não impõe taxonomia.

**Avaliação de autorização (Policy Evaluator):**

1. Se o usuário é o Root do tenant → **Allow**.
2. Coletar todas as policies anexadas ao usuário diretamente e via grupos.
3. Avaliar **Deny primeiro**: qualquer Deny aplicável vence.
4. Caso contrário, exigir pelo menos um **Allow** que case `Action` + `Resource` + `Condition`.
5. Default: **Deny**.

**Modelo de dados:** ver `docs/data-model.md` (tabelas `users.is_root`, `iam_groups`, `iam_user_groups`, `iam_policies`, `iam_policy_versions`, `iam_group_policies`, `iam_user_policies`). As tabelas antigas `roles`, `user_roles`, `teams`, `user_teams` e `access_scopes` são removidas.

**Escopo do MVP (decisão explícita):**

A funcionalidade **completa** entra no MVP — sem fatiamento. Isso inclui:

- CRUD de Users, Groups, Policies, anexações.
- Policies em JSON com Effect/Action/Resource **e** Condition.
- Wildcards em Action/Resource.
- Versionamento imutável de Policies.
- Auditoria de mudanças de IAM.
- Templates opcionais ("ReadOnlyAccess", "MeetingAnalystAccess") para acelerar onboarding.
- UI Web mínima para gerenciamento.

Editor visual form-based, simulador de policy e permission boundaries entram como **Should/Could** no backlog (US42–US44), mas não são bloqueio para a primeira release.

## Consequências

**Positivas:**

- Tenants têm liberdade total para modelar acesso conforme sua estrutura organizacional, sem esperar roadmap da NORA.
- Modelo familiar para equipes de TI Enterprise (vocabulário AWS).
- Auditoria fica natural: cada policy tem versão, cada anexação tem timestamp e autor.
- Account Health Score, Customer Confidence e Product Context podem usar conditions para filtrar dados sem inventar conceitos novos.

**Negativas / custos:**

- Implementar Policy Evaluator robusto (Deny-first, wildcards, conditions) é trabalho relevante — estimado em 3–4 semanas de 1 dev sênior.
- UI de IAM exige cuidado para não assustar usuários menos técnicos (templates e, futuramente, editor visual mitigam).
- Errar uma policy pode bloquear acesso legítimo. Mitigação: Root sempre tem bypass; auditoria registra mudanças; templates seguros como ponto de partida.

## Alternativas Consideradas

1. **Manter roles fixas (RBAC clássico).** Rejeitado: não atende a heterogeneidade dos tenants Enterprise; já estava em conflito com a própria narrativa do produto que se compara ao AWS IAM.
2. **Modelo híbrido reduzido (Root + Groups + permissões pré-definidas, sem JSON policies).** Rejeitado: ainda exigiria roadmap NORA para cada nova capacidade de filtragem; perderia o principal diferencial.
3. **Adiar IAM AWS-style para post-MVP, manter roles fixas no MVP.** Rejeitado pelo time: o IAM granular é parte da promessa Enterprise e do pitch FIAP/NEXT 2026; entregar o MVP sem ele enfraquece a demonstração.

## Regras Acompanhantes

- Toda nova feature define quais Actions e Resources expõe; documentar em `docs/development-standards.md` (seção a criar) ou em ADR específico.
- O backend deve ter um interceptor único (`@RequiresPermission("meeting:read")` ou equivalente) que aciona o Policy Evaluator. Nunca avaliar permissões manualmente em controller.
- Mudanças de IAM (criar/editar/anexar policy ou grupo, adicionar/remover membro) **sempre** geram registro em `audit_events`.
- O Root do tenant é único por tenant e não pode ser removido nem rebaixado via UI; troca de Root é processo administrativo separado (post-MVP).
- Default de qualquer recurso novo: **Deny** se nenhuma policy o cobrir.
