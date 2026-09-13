#!/bin/bash
set -e

# ============================================================================
# Скрипт деплоя developer-агента на production VPS
# ============================================================================

WORK_DIR="${WORK_DIR:-/opt/developer}"
MAX_ATTEMPTS="${MAX_ATTEMPTS:-15}"
ATTEMPT_INTERVAL="${ATTEMPT_INTERVAL:-10}"

# Иммутабельный тег образа (git SHA) передаётся из CI. Без него — fallback на master.
IMAGE_TAG="${IMAGE_TAG:-master}"
export IMAGE_TAG

if [ -z "$DOCKER_USERNAME" ] || [ -z "$DOCKER_PASSWORD" ]; then
  echo "❌ Ошибка: не заданы DOCKER_USERNAME, DOCKER_PASSWORD"
  exit 1
fi

cd "$WORK_DIR"

echo "=== Логин в Docker Hub (через прокси Timeweb) ==="
for i in 1 2 3; do
  if echo "${DOCKER_PASSWORD}" | docker login dockerhub.timeweb.cloud -u "${DOCKER_USERNAME}" --password-stdin; then
    break
  fi
  echo "⚠️ Попытка логина $i не удалась, retry через 10s..."
  sleep 10
  if [ $i -eq 3 ]; then
    echo "❌ Не удалось залогиниться после 3 попыток"
    exit 1
  fi
done

echo "=== Pull и запуск контейнеров ==="
pull_ok=0
for i in 1 2 3; do
  if docker compose -f docker-compose.yml -f docker-compose.prod.yml pull; then
    pull_ok=1
    break
  fi
  echo "⚠️ Попытка pull $i не удалась, retry через 15s..."
  sleep 15
done

if [ "$pull_ok" -eq 0 ]; then
  # Зеркало dockerhub.timeweb.cloud может закешировать битый блоб (gzip: invalid header) —
  # особенно на крупном developer-образе. Тянем напрямую с Docker Hub и ретегируем под
  # именем зеркала, чтобы compose up -d взял уже локальные образы.
  echo "=== Зеркало не отдало образы — тяну напрямую с Docker Hub ==="
  echo "${DOCKER_PASSWORD}" | docker login -u "${DOCKER_USERNAME}" --password-stdin || \
    echo "⚠️ Docker Hub login не удался — пробую pull анонимно"

  for img in developer opencode-with-mcp grafana-alloy; do
    src="docker.io/f4ky0d4k/${img}:${IMAGE_TAG}"
    dst="dockerhub.timeweb.cloud/f4ky0d4k/${img}:${IMAGE_TAG}"
    echo "--- pull ${src}"
    if ! docker pull "${src}"; then
      echo "❌ Не удалось pull ${src}"
      exit 1
    fi
    docker tag "${src}" "${dst}"
  done
fi

docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --no-build

echo "=== Проверка запуска ==="
chmod +x healthcheck.sh
./healthcheck.sh

docker system prune -f
docker logout
echo "✅ Деплой завершён"
exit 0
