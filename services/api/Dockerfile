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

# Application Insights Java Agent (auto-instrumentation).
# Sem mudanca de codigo: traces JDBC + HTTP server/client + Spring + Hibernate
# vao para Application Insights quando APPLICATIONINSIGHTS_CONNECTION_STRING esta
# setada (default do Bicep deploy). Quando vazia, o agent vira no-op silencioso —
# dev local e CI nao precisam configurar nada.
#
# Versao pinada por reprodutibilidade. Bump via Dependabot futuramente
# (agent self-versioned, nao depende do BOM).
ARG APPINSIGHTS_AGENT_VERSION=3.5.4
ADD --chown=root:root \
    https://github.com/microsoft/ApplicationInsights-Java/releases/download/${APPINSIGHTS_AGENT_VERSION}/applicationinsights-agent-${APPINSIGHTS_AGENT_VERSION}.jar \
    /app/applicationinsights-agent.jar

RUN addgroup -S nora && adduser -S nora -G nora && \
    chown nora:nora /app/applicationinsights-agent.jar
COPY --from=build /workspace/target/*.jar /app/app.jar
RUN chown nora:nora /app/app.jar
USER nora
EXPOSE 8080
# Container Apps default = 1Gi memory. JVM default heap em container = ~25% (250MB).
# Spring Boot 3 + Hibernate + Tomcat estoura OOM em load com esse default. Subimos pra
# 75% (~750MB heap). G1GC e ergonomia padrao de container do JDK 21 cobrem o resto.
#
# `-javaagent` carrega o agent App Insights. Em ausencia de CONNECTION_STRING o agent
# loga warn e continua sem exportar — runtime impact ~zero.
ENV JAVA_TOOL_OPTIONS="-javaagent:/app/applicationinsights-agent.jar -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD wget --quiet --tries=1 --spider http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
