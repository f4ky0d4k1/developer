FROM ghcr.io/anomalyco/opencode

# uvx для MCP stdio transport (yandex-tracker, grafana) + docker CLI для GitHub MCP
# ca-certificates обновляем для фикс TLS ошибок с Cloudflare
RUN apk add --no-cache --update ca-certificates python3 py3-pip curl docker-cli git && \
    update-ca-certificates && \
    curl -LsSf https://astral.sh/uv/install.sh | sh && \
    mv /root/.local/bin/uv /usr/local/bin/uv && \
    mv /root/.local/bin/uvx /usr/local/bin/uvx && \
    uvx --python python3 yandex-tracker-mcp@latest --help 2>/dev/null; \
    uvx --python python3 mcp-grafana --help 2>/dev/null; \
    true

ENV UV_PYTHON=python3
ENV NODE_TLS_REJECT_UNAUTHORIZED=0
ENV SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt
ENV REQUESTS_CA_BUNDLE=/etc/ssl/certs/ca-certificates.crt
ENV CURL_CA_BUNDLE=/etc/ssl/certs/ca-certificates.crt
