# --- build stage ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B -q -DskipTests dependency:go-offline
COPY src ./src
COPY checkstyle.xml .
RUN mvn -B -q -DskipTests package

# --- runtime ---
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# OpenTelemetry Java Agent (auto-instrumentation). Substitui o applicationinsights-agent
# (ADR 0034 — saida do Azure).
#
# Por que TROCAR O JAR e nao so a env var: o agent do App Insights exporta para o endpoint
# Breeze derivado da APPLICATIONINSIGHTS_CONNECTION_STRING e IGNORA
# OTEL_EXPORTER_OTLP_ENDPOINT. Apontar a env pro otel-collector com o jar antigo faria a
# aplicacao subir normalmente, instrumentar tudo e exportar para lugar nenhum — perda TOTAL
# e SILENCIOSA de telemetria, sem erro no boot nem no healthcheck.
#
# Sem mudanca de codigo: traces JDBC + HTTP server/client + Spring + Hibernate vao para o
# otel-collector quando OTEL_EXPORTER_OTLP_ENDPOINT esta setada (infra/proxmox/docker-compose.yml
# seta http://otel-collector:4317). Quando vazia, o ENTRYPOINT desliga o SDK — dev local e CI
# continuam sem precisar de collector (ver nota do no-op abaixo).
#
# Versao pinada por reprodutibilidade. Bump via Dependabot futuramente
# (agent self-versioned, nao depende do BOM).
ARG OTEL_AGENT_VERSION=2.30.0
ADD --chown=root:root \
    https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar \
    /app/opentelemetry-javaagent.jar

RUN addgroup -S nora && adduser -S nora -G nora && \
    chown nora:nora /app/opentelemetry-javaagent.jar
COPY --from=build /workspace/target/*.jar /app/app.jar
RUN chown nora:nora /app/app.jar
USER nora
EXPOSE 8080
# O limite de memoria do runtime segue vindo de fora (deploy.resources.limits.memory no
# compose; antes era o 1Gi default do Container Apps). JVM default heap em container = ~25%
# (250MB). Spring Boot 3 + Hibernate + Tomcat estoura OOM em load com esse default. Subimos
# pra 75% (~750MB heap num container de 1Gi). G1GC e ergonomia padrao de container do JDK 21
# cobrem o resto.
#
# `-javaagent` carrega o agent OTel. Os flags de memoria sao os mesmos de antes — a troca do
# agent nao muda o perfil de heap.
ENV JAVA_TOOL_OPTIONS="-javaagent:/app/opentelemetry-javaagent.jar -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD wget --quiet --tries=1 --spider http://localhost:8080/actuator/health || exit 1
# No-op quando nao ha collector — preserva o comportamento do agent do App Insights, que
# ficava inerte sem CONNECTION_STRING. Aqui o default do OTel e o OPOSTO: sem
# OTEL_EXPORTER_OTLP_ENDPOINT ele tenta http://localhost:4318 e enche o log de
# "Connection refused" em dev/CI. Desligamos o SDK explicitamente nesse caso, para que
# `docker run` sem observabilidade continue funcionando sem exigir collector.
# Prod nao e afetada: o compose seta OTEL_EXPORTER_OTLP_ENDPOINT.
ENTRYPOINT ["/bin/sh", "-c", "if [ -z \"${OTEL_EXPORTER_OTLP_ENDPOINT}\" ]; then export OTEL_SDK_DISABLED=true; fi; exec java -jar /app/app.jar"]
