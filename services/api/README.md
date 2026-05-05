# NORA API

Backend Spring Boot 3 + Java 21 do projeto NORA.

## Pre-requisitos

- Java 21
- Maven 3.9+ (ou use Docker para build)
- Postgres 16 rodando (use `make db-up` na raiz do repo)

## Configuracao local (.env.local)

A API le automaticamente `services/api/.env.local` quando o profile `local` esta ativo.

- Copie `services/api/.env.example` para `services/api/.env.local`
- Para envio real de e-mail (Resend), preencha `RESEND_API_KEY`. Se ficar vazio, a API usa `LogEmailSender` e imprime os links no log.

## Comandos

```bash
mvn spring-boot:run        # roda em http://localhost:8080
mvn verify                 # build + spotless + checkstyle + testes + jacoco
mvn spotless:apply         # formata o codigo
mvn test                   # apenas testes (Testcontainers requer Docker)
```

## Estrutura DDD

```
src/main/java/br/com/nora/api/
  NoraApiApplication.java
  api/                 # controllers, DTOs, exception handlers
  application/         # casos de uso (commands, queries, ports)
  domain/              # entidades e regras puras
  infrastructure/      # persistencia, seguranca, clients externos
```

Regras detalhadas em `docs/development-standards.md`.

## Endpoints disponiveis no esqueleto

- `GET /healthz` (publico)
- `GET /actuator/health` (publico)
- demais rotas: protegidas por JWT (US01-US04). Ver OpenAPI em `/swagger-ui/index.html`.

## Multi-tenancy

Toda nova entidade tenant-bound deve incluir `tenant_id`. Ver `docs/adr/0002-multi-tenancy.md` e `docs/data-model.md`.
