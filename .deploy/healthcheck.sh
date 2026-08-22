#!/bin/bash
set -e

WORK_DIR="${WORK_DIR:-/opt/developer}"
MAX_ATTEMPTS="${MAX_ATTEMPTS:-15}"
ATTEMPT_INTERVAL="${ATTEMPT_INTERVAL:-10}"

cd "$WORK_DIR"

echo "=== Проверка запуска developer ==="
echo "Максимум попыток: $MAX_ATTEMPTS (интервал: ${ATTEMPT_INTERVAL}s)"

ATTEMPT=1
while [ $ATTEMPT -le $MAX_ATTEMPTS ]; do
  echo "Попытка $ATTEMPT из $MAX_ATTEMPTS..."

  if curl -f http://localhost:8081/actuator/health 2>/dev/null; then
    echo "✅ Приложение запущено за $((ATTEMPT * ATTEMPT_INTERVAL)) секунд!"
    exit 0
  fi

  if ! docker compose -f docker-compose.prod.yml ps developer | grep -q "Up"; then
    echo "❌ Контейнер developer упал!"
    docker compose -f docker-compose.prod.yml logs developer --tail 100
    exit 1
  fi

  sleep $ATTEMPT_INTERVAL
  ATTEMPT=$((ATTEMPT + 1))
done

echo "❌ Приложение не запустилось за $((MAX_ATTEMPTS * ATTEMPT_INTERVAL)) секунд"
docker compose -f docker-compose.prod.yml logs developer --tail 200
exit 1
