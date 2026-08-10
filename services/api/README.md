# NORA API

Spring Boot 3 + Java 21 backend of the NORA project.

## Prerequisites

- Java 21
- Maven 3.9+ (or use Docker for the build)
- Postgres 16 running (use `make db-up` at the repo root)

## Local configuration (.env.local)

The API automatically reads `services/api/.env.local` when the `local` profile is active.

- Copy `services/api/.env.example` to `services/api/.env.local`
- For real e-mail sending (Resend), fill in `RESEND_API_KEY`. If it is left empty, the API uses `LogEmailSender` and prints the links to the log.

## Commands

```bash
mvn spring-boot:run        # runs at http://localhost:8080
mvn verify                 # build + spotless + checkstyle + tests + jacoco
mvn spotless:apply         # formats the code
mvn test                   # tests only (Testcontainers requires Docker)
```

## DDD structure

```
src/main/java/br/com/nora/api/
  NoraApiApplication.java
  api/                 # controllers, DTOs, exception handlers
  application/         # casos de uso (commands, queries, ports)
  domain/              # entidades e regras puras
  infrastructure/      # persistencia, seguranca, clients externos
```

Detailed rules in `docs/engineering/standards.md`.

## Endpoints available in the skeleton

- `GET /healthz` (public)
- `GET /actuator/health` (public)
- all other routes: protected by JWT (US01-US04). See the OpenAPI at `/swagger-ui/index.html`.

## Multi-tenancy

Every new tenant-bound entity must include `tenant_id`. See `docs/adr/0002-multi-tenancy.md` and `docs/engineering/data-model.md`.
