# Backend quality standards (Java/Spring Boot)

> Live configuration in `services/api/`. This README documents the mandatory tooling.

## Tools

- **Build**: Maven (Maven Wrapper `./mvnw`).
- **Formatter**: [Spotless](https://github.com/diffplug/spotless) with the **Google Java Format** profile (AOSP).
- **Static lint**: Checkstyle (lean profile) + Error Prone via Maven.
- **Tests**: JUnit 5 + AssertJ + Spring Boot Test + Testcontainers (real Postgres for integration).
- **Coverage**: JaCoCo, minimum 70% of lines in `application` and `domain`.

## Commands

```bash
./mvnw spotless:apply       # formatar
./mvnw spotless:check       # checar formatação (CI)
./mvnw verify               # build + testes + cobertura
```

## Plugins expected in `pom.xml`

- `spring-boot-maven-plugin`
- `spotless-maven-plugin` (with `googleJavaFormat()`)
- `maven-checkstyle-plugin` (config in `checkstyle.xml`)
- `jacoco-maven-plugin`
- `flyway-maven-plugin`

## PR rules

1. `spotless:check` must pass.
2. No new Checkstyle warnings.
3. Coverage must not drop relative to `main`.
4. Every new tenant-bound entity requires an isolation test.
