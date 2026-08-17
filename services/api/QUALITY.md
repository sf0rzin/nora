# Backend quality standards (Java/Spring Boot)

> Live configuration in `services/api/`. This README documents the mandatory tooling.

## Tools

- **Build**: Maven (Maven Wrapper `./mvnw`).
- **Formatter**: [Spotless](https://github.com/diffplug/spotless) with the **Google Java Format** profile (AOSP).
- **Static lint**: Checkstyle (lean profile) + Error Prone via Maven.
- **Tests**: JUnit 5 + AssertJ + Spring Boot Test + Testcontainers (real Postgres for integration).
- **Coverage**: JaCoCo. The report runs on `verify` and is printed by CI (`scripts/report-coverage.sh`); the only **gate** is a rule over the single class `PolicyEvaluator` — instruction >= 90%, branch >= 75%, `haltOnFailure` (`pom.xml`). There is **no** 70%-of-lines rule on `application` and `domain`; this file claimed one until 2026-08-17 and the build never enforced it. Measured 2026-08-17: 77.1-77.3% instruction / 61.5-61.6% branch overall.

## Commands

```bash
./mvnw spotless:apply       # format
./mvnw spotless:check       # check formatting (CI)
./mvnw verify               # build + tests + coverage
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
3. Coverage must not drop relative to `main`. Nothing automates this comparison — the `api` job prints the figure on both branches (`Coverage report (JaCoCo)`), and reading the two is the review step.
4. Every new tenant-bound entity requires an isolation test.
