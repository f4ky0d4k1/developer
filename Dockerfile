FROM eclipse-temurin:21-jre-alpine

LABEL authors="allstreets"

WORKDIR /app

# uv (uvx) + Python для MCP stdio transport (yandex-tracker, grafana)
# Предустанавливаем Python и кэшируем MCP пакеты чтобы избежать таймаута при старте
RUN apk add --no-cache curl bash git python3 py3-pip docker-cli && \
    curl -LsSf https://astral.sh/uv/install.sh | sh && \
    mv /root/.local/bin/uv /usr/local/bin/uv && \
    mv /root/.local/bin/uvx /usr/local/bin/uvx && \
    uvx --python python3 yandex-tracker-mcp@latest --help 2>/dev/null; \
    uvx --python python3 yandex-wiki-search-mcp@latest --help 2>/dev/null; \
    uvx --python python3 mcp-grafana --help 2>/dev/null; \
    true

COPY target/developer-*.jar app.jar

EXPOSE 8080 8081

ENV JAVA_OPTS="-Dfile.encoding=UTF-8 -XX:+UseStringDeduplication -Djava.security.egd=file:/dev/./urandom"
ENV SPRING_PROFILES_ACTIVE=prod
ENV UV_PYTHON=python3

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
