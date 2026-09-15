#!/bin/bash
set -e

# ============================================================================
# Скрипт деплоя developer-агента на production VPS
# ============================================================================

WORK_DIR="${WORK_DIR:-/opt/developer}"
MAX_ATTEMPTS="${MAX_ATTEMPTS:-15}"
ATTEMPT_INTERVAL="${ATTEMPT_INTERVAL:-10}"

# Иммутабельные теги образов (каждый меняется только при изменении входов сервиса) — из CI.
# Без них — fallback на master (ручной запуск).
DEVELOPER_TAG="${DEVELOPER_TAG:-master}"
OPENCODE_TAG="${OPENCODE_TAG:-master}"
ALLOY_TAG="${ALLOY_TAG:-master}"
export DEVELOPER_TAG OPENCODE_TAG ALLOY_TAG

if [ -z "$DOCKER_USERNAME" ] || [ -z "$DOCKER_PASSWORD" ]; then
  echo "❌ Ошибка: не заданы DOCKER_USERNAME, DOCKER_PASSWORD"
  exit 1
fi

cd "$WORK_DIR"

# Освободить место ДО pull: старые dangling-образы/кэш копятся и могут забить диск (pull упадёт).
echo "=== Очистка перед pull ==="
docker container prune -f >/dev/null 2>&1 || true
docker image prune -f >/dev/null 2>&1 || true
docker builder prune -f >/dev/null 2>&1 || true

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

  pull_direct() {
    local img="$1" tag="$2"
    local src="docker.io/f4ky0d4k/${img}:${tag}"
    local dst="dockerhub.timeweb.cloud/f4ky0d4k/${img}:${tag}"
    echo "--- pull ${src}"
    docker pull "${src}" && docker tag "${src}" "${dst}"
  }

  pull_direct developer "${DEVELOPER_TAG}" || { echo "❌ Не удалось pull developer"; exit 1; }
  pull_direct opencode-with-mcp "${OPENCODE_TAG}" || { echo "❌ Не удалось pull opencode-with-mcp"; exit 1; }
  pull_direct grafana-alloy "${ALLOY_TAG}" || { echo "❌ Не удалось pull grafana-alloy"; exit 1; }
fi

docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --no-build

echo "=== Проверка запуска ==="
chmod +x healthcheck.sh
./healthcheck.sh

echo "=== Очистка Docker-мусора (диск VPS) ==="
# Иммутабельные теги (dev-*/oc-*/alloy-*) и testcontainers-образы копятся после каждого
# деплоя; образы, используемые контейнерами (в т.ч. остановленными), не трогаются.
docker container prune -f || true
# -a: удалить ВСЕ образы, на которые не ссылается ни один контейнер (старые теги + testcontainers)
docker image prune -a -f || true
# весь build cache
docker builder prune -a -f || true
# неиспользуемые анонимные тома
docker volume prune -f || true
# неиспользуемые сети
docker network prune -f || true

# max-size/ротация применится только к НОВЫМ контейнерам — уже разросшиеся json-логи
# (DEBUG opencode) обрезаем явно.
for cid in $(docker ps -q); do
  log_path="$(docker inspect --format '{{.LogPath}}' "$cid" 2>/dev/null || true)"
  if [ -n "$log_path" ] && [ -f "$log_path" ]; then
    : > "$log_path" 2>/dev/null || true
  fi
done

echo "--- Диск после очистки ---"
df -h / || true
docker system df || true

docker logout
echo "✅ Деплой завершён"
exit 0
