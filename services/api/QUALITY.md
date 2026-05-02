# Padrões de qualidade do backend (Java/Spring Boot)

> Configurações vivas em `services/api/`. Este README documenta as ferramentas obrigatórias.

## Ferramentas

- **Build**: Maven (Maven Wrapper `./mvnw`).
- **Formatador**: [Spotless](https://github.com/diffplug/spotless) com perfil **Google Java Format** (AOSP).
- **Lint estático**: Checkstyle (perfil enxuto) + Error Prone via Maven.
- **Testes**: JUnit 5 + AssertJ + Spring Boot Test + Testcontainers (Postgres real para integração).
- **Cobertura**: JaCoCo, mínimo 70% de linhas em `application` e `domain`.

## Comandos

```bash
./mvnw spotless:apply       # formatar
./mvnw spotless:check       # checar formatação (CI)
./mvnw verify               # build + testes + cobertura
```

## Plugins esperados no `pom.xml`

- `spring-boot-maven-plugin`
- `spotless-maven-plugin` (com `googleJavaFormat()`)
- `maven-checkstyle-plugin` (config em `checkstyle.xml`)
- `jacoco-maven-plugin`
- `flyway-maven-plugin`

## Regras de PR

1. `spotless:check` precisa passar.
2. Sem warnings novos do Checkstyle.
3. Cobertura não pode cair em relação à `main`.
4. Toda nova entidade tenant-bound exige teste de isolamento.
