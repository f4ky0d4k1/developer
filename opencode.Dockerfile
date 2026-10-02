# Тег `latest` (не 1.0.x): версии 1.0.x отстают по схеме конфига (external_directory map +
# compaction/skills), их принимает только свежий latest. MCP-серверы ниже запинены по версиям,
# поэтому digest меняется только при реальном релизе opencode, а не на каждом билде.
FROM ghcr.io/anomalyco/opencode

# uvx для MCP stdio transport (yandex-tracker, grafana) + docker CLI для GitHub MCP
# + JDK 21 для сборки/тестов целевых Java-проектов (Maven подтягивается через ./mvnw)
# ca-certificates обновляем для фикс TLS ошибок с Cloudflare
# ВАЖНО: базовый образ и MCP-серверы закреплены по версии (не :latest/@latest) —
# иначе каждый билд тянет свежие версии, digest образа меняется под тем же тегом,
# и VPS перекачивает opencode целиком при каждом деплое (см. deploy.yml tags).
RUN apk add --no-cache --update ca-certificates python3 py3-pip curl docker-cli git nodejs npm openjdk21 && \
    update-ca-certificates && \
    curl -LsSf https://astral.sh/uv/install.sh | sh && \
    mv /root/.local/bin/uv /usr/local/bin/uv && \
    mv /root/.local/bin/uvx /usr/local/bin/uvx && \
    uvx --python python3 yandex-tracker-mcp@0.10.0 --help 2>/dev/null; \
    uvx --python python3 yandex-wiki-search-mcp@1.5.1 --help 2>/dev/null; \
    uvx --python python3 mcp-grafana@2.0.0 --help 2>/dev/null; \
    true

ENV UV_PYTHON=python3
ENV NODE_TLS_REJECT_UNAUTHORIZED=0
ENV SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt
ENV REQUESTS_CA_BUNDLE=/etc/ssl/certs/ca-certificates.crt
ENV CURL_CA_BUNDLE=/etc/ssl/certs/ca-certificates.crt
