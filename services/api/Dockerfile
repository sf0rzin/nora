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

# OpenTelemetry Java Agent (auto-instrumentation). Replaces the applicationinsights-agent
# (ADR 0034 — exit from Azure).
#
# Why SWAP THE JAR and not just the env var: the App Insights agent exports to the Breeze
# endpoint derived from APPLICATIONINSIGHTS_CONNECTION_STRING and IGNORES
# OTEL_EXPORTER_OTLP_ENDPOINT. Pointing the env at the otel-collector while keeping the old
# jar would make the application boot normally, instrument everything and export to nowhere —
# a TOTAL and SILENT loss of telemetry, with no error at boot or in the healthcheck.
#
# No code change needed: JDBC + HTTP server/client + Spring + Hibernate traces go to the
# otel-collector when OTEL_EXPORTER_OTLP_ENDPOINT is set (infra/host/docker-compose.yml
# sets http://otel-collector:4317). When empty, the ENTRYPOINT disables the SDK — local dev
# and CI keep working without needing a collector (see the no-op note below).
#
# Version pinned for reproducibility. Bump via Dependabot in the future
# (agent is self-versioned, does not depend on the BOM).
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
# The runtime memory limit still comes from the outside (deploy.resources.limits.memory in
# the compose file; it used to be the 1Gi default of Container Apps). JVM default heap in a
# container = ~25% (250MB). Spring Boot 3 + Hibernate + Tomcat OOMs under load with that
# default. We raise it to 75% (~750MB heap in a 1Gi container). G1GC and JDK 21's default
# container ergonomics cover the rest.
#
# `-javaagent` loads the OTel agent. The memory flags are the same as before — swapping the
# agent does not change the heap profile.
ENV JAVA_TOOL_OPTIONS="-javaagent:/app/opentelemetry-javaagent.jar -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD wget --quiet --tries=1 --spider http://localhost:8080/actuator/health || exit 1
# No-op when there is no collector — preserves the behaviour of the App Insights agent, which
# stayed idle without a CONNECTION_STRING. Here the OTel default is the OPPOSITE: without
# OTEL_EXPORTER_OTLP_ENDPOINT it tries http://localhost:4318 and fills the log with
# "Connection refused" in dev/CI. We explicitly disable the SDK in that case, so that
# `docker run` without observability keeps working without requiring a collector.
# Prod is not affected: the compose file sets OTEL_EXPORTER_OTLP_ENDPOINT.
ENTRYPOINT ["/bin/sh", "-c", "if [ -z \"${OTEL_EXPORTER_OTLP_ENDPOINT}\" ]; then export OTEL_SDK_DISABLED=true; fi; exec java -jar /app/app.jar"]
