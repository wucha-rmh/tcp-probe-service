# 构建阶段：只有这一步需要 Maven。
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

COPY pom.xml .
RUN mvn -q -B -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -q -B -DskipTests package

# 运行阶段：只保留 JDK，没有 Maven，也没有构建缓存。
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/target/tcp-probe-service.jar /app/tcp-probe-service.jar
COPY config /app/config

# 服务默认监听 8080；可用 --server.port=<n> 覆盖。
EXPOSE 8080

# JAVA_OPTS 让运维人员能追加堆/GC 参数而不必重新构建镜像。
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/tcp-probe-service.jar \"$@\"", "--"]
