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
for i in 1 2 3; do
  if echo "${DOCKER_PASSWORD}" | docker login -u "${DOCKER_USERNAME}" --password-stdin; then
    break
  fi
  echo "⚠️ Попытка логина $i не удалась, retry через 10s..."
  sleep 10
  if [ $i -eq 3 ]; then
    echo "❌ Не удалось залогиниться в Docker Hub после 3 попыток"
    exit 1
  fi
done

echo "=== Pull и запуск контейнеров ==="
for i in 1 2 3; do
  if docker compose -f docker-compose.yml -f docker-compose.prod.yml pull; then
    break
  fi
  echo "⚠️ Попытка pull $i не удалась, retry через 15s..."
  sleep 15
  if [ $i -eq 3 ]; then
    echo "❌ Не удалось pull образы после 3 попыток"
    exit 1
  fi
done
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d

echo "=== Проверка запуска ==="
chmod +x healthcheck.sh
./healthcheck.sh

docker system prune -f
docker logout
echo "✅ Деплой завершён"
exit 0
