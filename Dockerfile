# Build stage: only here is Maven needed.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

COPY pom.xml .
RUN mvn -q -B -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -q -B -DskipTests package

# Runtime stage: JDK only, no Maven, no build cache.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/target/tcp-probe-service.jar /app/tcp-probe-service.jar
COPY config /app/config

# The service listens on 8080 by default; override with --server.port=<n>.
EXPOSE 8080

# JAVA_OPTS lets operators add heap/GC flags without rebuilding the image.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/tcp-probe-service.jar \"$@\"", "--"]
