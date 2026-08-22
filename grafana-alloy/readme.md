# Grafana Alloy — сбор метрик → Grafana Cloud

Alloy скрейпит `/actuator/prometheus` Spring Boot приложения `developer` и отправляет метрики в Grafana Cloud через
`prometheus.remote_write`.

## Файлы

| Файл            | Описание                                             |
|-----------------|------------------------------------------------------|
| `config.alloy`  | Конфигурация Alloy (scrape + relabel + remote_write) |
| `Dockerfile`    | `FROM grafana/alloy:latest` + COPY config.alloy      |
| `.env.template` | Шаблон env-переменных                                |

## Переменные окружения

```env
DEVELOPER_ADDRESS=developer:8081
ALLOY_REMOTE_WRITE_URL=<Grafana Cloud remote_write endpoint>
ALLOY_REMOTE_WRITE_USER=<Grafana Cloud user ID>
ALLOY_REMOTE_WRITE_TOKEN=<Grafana Cloud auth token>
```

Переменные передаются через корневой `.env` — отдельный `.env` в этой папке не нужен.

## Запуск

Alloy запускается как часть основного docker-compose:

```bash
docker compose up -d
```

## Обновление конфигурации

```bash
docker compose restart grafana-alloy
```

## Логи

```bash
docker compose logs -f grafana-alloy
```
