# 构建阶段：只有这一步需要 Maven 和完整源码。
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

# 先只拷 pom，把依赖层缓存住；改代码不会重新下载依赖。
COPY pom.xml .
RUN mvn -q -B -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -q -B -DskipTests package

# 运行阶段：只要 JRE，没有 Maven，也没有构建缓存。
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/target/tcp-probe-service.jar /app/app.jar

# 服务默认监听 8080；可用 --server.port=<n> 覆盖。
EXPOSE 8080

# JAVA_OPTS 让运维人员追加堆/GC 参数而不必重新构建镜像。
# 挂载配置：-v $PWD/config:/app/config 配合 --spring.config.additional-location=file:/app/config/
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar \"$@\"", "--"]
