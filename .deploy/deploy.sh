#!/bin/bash
set -e

# ============================================================================
# Скрипт деплоя developer-агента на production VPS
# ============================================================================

WORK_DIR="${WORK_DIR:-/opt/developer}"
MAX_ATTEMPTS="${MAX_ATTEMPTS:-15}"
ATTEMPT_INTERVAL="${ATTEMPT_INTERVAL:-10}"

if [ -z "$DOCKER_USERNAME" ] || [ -z "$DOCKER_PASSWORD" ]; then
  echo "❌ Ошибка: не заданы DOCKER_USERNAME, DOCKER_PASSWORD"
  exit 1
fi

cd "$WORK_DIR"

echo "=== Логин в Docker Hub ==="
echo "${DOCKER_PASSWORD}" | docker login -u "${DOCKER_USERNAME}" --password-stdin

echo "=== Pull и запуск контейнеров ==="
docker compose -f docker-compose.prod.yml pull
docker compose -f docker-compose.prod.yml up -d

echo "=== Проверка запуска ==="
chmod +x healthcheck.sh
./healthcheck.sh

docker system prune -f
docker logout
echo "✅ Деплой завершён"
exit 0
