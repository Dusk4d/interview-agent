# ---- 构建阶段 ---------------------------------------------------------------
# 说明：本项目在无外网环境下使用 .m2repo 离线仓库开发。
# 这里给出两种构建路径，默认走「离线仓库 COPY 模式」（--build-arg OFFLINE=true），
# 有外网时把 OFFLINE 设为 false 即走标准的依赖下载模式。
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

ARG OFFLINE=true
ENV MAVEN_OPTS="-Dfile.encoding=UTF-8"

# 先复制 POM 与（离线时）本地仓库，尽量复用 Docker 层缓存
COPY pom.xml ./
COPY .m2settings.xml ./
COPY .m2repo/ ./.m2repo/

RUN if [ "$OFFLINE" = "true" ]; then \
      mvn -o -s .m2settings.xml -q -DskipTests=true package; \
    else \
      mvn -q -DskipTests=true -B package; \
    fi

# 运行所需的第三方依赖（供运行时 COPY 到 lib/）
RUN mvn -o -s .m2settings.xml -q -DskipTests=true test-compile

# ---- 运行阶段 ---------------------------------------------------------------
FROM eclipse-temurin:21-jre AS runtime

LABEL org.opencontainers.image.title="interview-agent" \
      org.opencontainers.image.description="Resume-driven AI interview coach (RAG + agent workflow)" \
      org.opencontainers.image.version="0.1.0"

# 以非 root 运行
RUN useradd --create-home --uid 10001 appuser

WORKDIR /app
COPY --from=build /build/target/app.jar /app/app.jar
# 运行期数据与配置目录（compose 会挂载 data 做持久化）
RUN mkdir -p /app/data && chown -R appuser:appuser /app
VOLUME ["/app/data"]

USER appuser
EXPOSE 8090

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -Dfile.encoding=UTF-8 -Dapp.home=/app" \
    INTERVIEW_DATA_DIR=/app/data \
    INTERVIEW_STORAGE_MODE=file

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD ["sh", "-c", "java -version >/dev/null 2>&1 && (wget -qO- http://127.0.0.1:8090/api/health | grep -q '\"status\":\"UP\"' || exit 1)"]

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
