# ------- Stage 1: build the JAR with Maven -------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# Cache dependencies — only re-downloads when pom.xml changes
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

# Build
COPY src ./src
RUN mvn -B -q package -DskipTests

# ------- Stage 2: minimal JRE runtime -------
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# Run as non-root for safety
RUN groupadd -r clinic && useradd -r -g clinic clinic \
 && mkdir -p /data/uploads \
 && chown -R clinic:clinic /app /data

COPY --from=build --chown=clinic:clinic /build/target/clinic-ops-app.jar app.jar

USER clinic

# Defaults — overridable via docker-compose env
ENV CLINIC_UPLOADS_DIR=/data/uploads \
    SERVER_PORT=8090 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

EXPOSE 8090
VOLUME ["/data/uploads"]

HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=3 \
    CMD wget -qO- http://localhost:8090/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
