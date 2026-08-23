# Backend quality standards (Java/Spring Boot)

> Live configuration in `services/api/`. This README documents the mandatory tooling.

## Tools

- **Build**: Maven. There is **no** Maven Wrapper in this repository, so `mvn` has to be on your `PATH` — the root `README.md` says the same thing and it is the entry point people actually read. This file said `./mvnw` until 2026-08-23 and every command below failed with `command not found`.
- **Formatter**: [Spotless](https://github.com/diffplug/spotless) with the **Google Java Format** profile (AOSP).
- **Static lint**: Checkstyle (lean profile) + Error Prone via Maven.
- **Tests**: JUnit 5 + AssertJ + Spring Boot Test + Testcontainers (real Postgres for integration).
- **Coverage**: JaCoCo. The report runs on `verify` and is printed by CI (`scripts/report-coverage.sh`). Three rules **gate** the build, all with `haltOnFailure` (`pom.xml`): the class `PolicyEvaluator` at instruction >= 90% / branch >= 75%; the package `domain.iam` at instruction >= 80%; and the whole bundle at instruction >= 70% / branch >= 55%. The last two were added on 2026-08-23 — until then the single-class rule was the entire gate, so a coverage drop anywhere else passed `mvn verify` in silence. The thresholds are floors with headroom below the measurement of 2026-08-17 (77.1-77.3% instruction / 61.5-61.6% branch overall), not targets: they exist to catch a regression, and raising them needs a fresh measurement in CI with Docker, since Testcontainers is what covers `application/identity` and `infrastructure/security`.

## Commands

```bash
mvn spotless:apply       # format
mvn spotless:check       # check formatting (CI)
mvn verify               # build + tests + coverage
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
