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
RUN addgroup -S nora && adduser -S nora -G nora
COPY --from=build /workspace/target/*.jar /app/app.jar
USER nora
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
