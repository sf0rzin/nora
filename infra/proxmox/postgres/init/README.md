# `postgres/init` — scripts de bootstrap do Postgres primário

Este diretório é montado em `/docker-entrypoint-initdb.d` no container `postgres`
(banco `nora`). Só ele — o `postgres-platform` **não** monta nada aqui.

## A regra que morde: isto roda UMA vez

O entrypoint da imagem oficial executa os scripts deste diretório **apenas quando
`PGDATA` está vazio**, ou seja, no primeiro boot com o volume `pgdata` recém-criado.
Em todo boot seguinte o entrypoint encontra o cluster já inicializado, pula o
diretório inteiro em silêncio e sobe o Postgres.

Consequências práticas:

- Editar `01-roles-and-db.sql` e dar `docker compose up -d` **não faz nada**.
- Adicionar um `02-*.sql` num ambiente já em produção **não faz nada**.
- Um erro em qualquer script **aborta o initdb**: o container não fica healthy,
  o volume fica num estado meio-inicializado e a API nunca sobe (o healthcheck do
  `postgres` é dependência de `depends_on` da `api`).

Ordem de execução: alfabética. Extensões `.sql`, `.sql.gz` e `.sh` são suportadas;
os `.sql` rodam como o superuser `${POSTGRES_ADMIN_USER}` conectado em `nora`, com
`ON_ERROR_STOP` ligado.

## Como saber se rodou

```bash
docker compose logs postgres | grep -F 'NORA initdb'
# ou, direto no banco:
docker compose exec -T postgres psql -U nora_admin -d nora \
  -c "\du nora_app" -c "\dx"
```

Se `nora_app` e `nora_telemetry` aparecem em `\du`, e `pgcrypto`/`citext` em `\dx`,
o script rodou.

## Banco já existente: o que fazer

Os scripts foram escritos para serem **idempotentes**, então o caminho é aplicá-los
à mão. Não recrie o volume só para "rodar o init" — isso apaga o banco.

```bash
# do host, na pasta infra/proxmox:
docker compose exec -T postgres \
  psql -v ON_ERROR_STOP=1 -U nora_admin -d nora < postgres/init/01-roles-and-db.sql
```

Rodar num banco já migrado é, na verdade, **melhor** do que no initdb: as tabelas
existem, então os `GRANT ... ON ALL TABLES` e o `GRANT SELECT ON meeting_analyses`
(o do `nora_telemetry`) deixam de ser no-op.

### O grant que fica pendente no primeiro boot

`meeting_analyses` nasce na migration `V005`, depois do initdb. O script avisa com
`WARNING` e segue. Feche o grant **após o primeiro `flyway migrate`**:

```bash
docker compose exec -T postgres psql -U nora_admin -d nora \
  -c "GRANT SELECT ON meeting_analyses TO nora_telemetry"
```

Sem esse grant o painel de negócio do operador retorna **zero** — sem erro, sem log,
sem alerta. É a falha silenciosa mais cara desta stack.

### Senhas dos roles

O compose não injeta `NORA_APP_PASSWORD` / `RLS_TELEMETRY_PASSWORD` no container do
Postgres, então por padrão os dois roles nascem **sem senha** (fail-closed: com
`scram-sha-256` um role sem senha não autentica). Defina-as no cutover de RLS:

```bash
docker compose exec -T postgres psql -U nora_admin -d nora \
  -c "ALTER ROLE nora_app       WITH PASSWORD 'xxx'" \
  -c "ALTER ROLE nora_telemetry WITH PASSWORD 'yyy'"
```

E replique os mesmos valores no `.env` (`DATASOURCE_PASSWORD` via
`NORA_APP_PASSWORD`, e `NORA_TELEMETRY_DATASOURCE_PASSWORD`). Sequência completa em
[`docs/operations/rls-cutover-runbook.md`](../../../../docs/operations/rls-cutover-runbook.md).

## Relação com o `R001` da aplicação

A fonte da verdade da semântica dos roles é
[`services/api/src/main/resources/db/operational/R001__provision_app_roles.sql`](../../../../services/api/src/main/resources/db/operational/R001__provision_app_roles.sql)
(ADR 0026 / 0028). O `01-roles-and-db.sql` é a adaptação dele para o momento do
initdb, onde ainda não existe nenhuma tabela — a seção "Diferenças em relação ao
R001" no cabeçalho do script lista item a item o que muda e por quê. Mudou o R001?
Reavalie os dois arquivos juntos.

## Recomeçar do zero (destrutivo)

```bash
docker compose down
docker volume rm nora_pgdata     # APAGA O BANCO
docker compose up -d --wait
```

Só faz sentido em ambiente descartável. Em produção, restaure de
`${BACKUP_DIR}` (dump lógico horário) ou do snapshot do Proxmox Backup Server.
