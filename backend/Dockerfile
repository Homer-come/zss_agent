# ============================================================
# 阶段 1: 用 Maven 构建可执行 jar
# ============================================================
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build

# 先 copy 依赖文件，单独 cache 这一层避免每次改代码都重下依赖
COPY pom.xml ./
COPY backend/pom.xml backend/pom.xml
RUN mvn -B -pl backend -am -DskipTests dependency:go-offline

# 再 copy 源码编译
COPY backend/src backend/src
RUN mvn -B -pl backend -DskipTests package

# ============================================================
# 阶段 2: 运行时镜像（仅 JRE，无 Maven）
# ============================================================
FROM eclipse-temurin:17-jre AS runtime
WORKDIR /app

# 非 root 运行，遵循安全最佳实践
RUN groupadd -r app && useradd -r -g app app \
    && mkdir -p /app/logs \
    && chown -R app:app /app

COPY --from=builder --chown=app:app /build/backend/target/*.jar app.jar

USER app

# 默认端口（可用 SERVER_PORT 覆盖）
ENV SERVER_PORT=8080
EXPOSE 8080

# 健康检查：5s 起步，间隔 30s
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD wget -qO- http://localhost:${SERVER_PORT}/actuator/health || exit 1

# 默认走 H2 内存库（生产应通过环境变量切到 MySQL）
ENV DB_URL="jdbc:h2:mem:zss_agent;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1" \
    DB_USERNAME=sa \
    DB_PASSWORD= \
    DB_DRIVER=org.h2.Driver \
    FLYWAY_ENABLED=true

ENTRYPOINT ["java", "-jar", "app.jar"]