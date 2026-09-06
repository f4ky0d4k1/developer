# Отказоустойчивое общение Spring ↔ OpenCode: план

Создан 2026-09-06. Живой документ — отмечать статус по мере реализации.
Решение: **вариант A** — асинхронная модель с персистентным состоянием.

Статусы: `TODO` / `IN PROGRESS` / `DONE` / `BLOCKED`

---

## Часть 1. Факты, добытые из Grafana Loki (не догадки)

Датасорс `grafanacloud-logs`, `{service_name="developer"}`, окно 7 дней.
Закрывает оговорку п.1 в `ARCHITECTURE_AUDIT.md` — API больше не «непроверенный по докам».

### 1.1 Что реально работает

- **`POST /session?directory=` работает.** Возвращает валидный JSON, `readTree` парсит без проблем.
  Реальные session id: `ses_f8d577cabffebb0T19byKlrzcQ`, `ses_f8d576999ffenkUla8XJ6QxK0N`.
  → Проблемы `application/octet-stream` на создании сессии **нет**. LLM-fallback на парсинг сессии был
  лишним и уже удалён.
- **`POST /session/{id}/message` иногда успешно завершается.** Лог:
  `Агент analyst завершил работу. session=ses_f8d577cabffebb0T19byKlrzcQ, текст=9052 символов, error=null`.
  → Эндпоинт и разбор `parts[]` в целом верны.
- **`toolCalls=[]` всегда пустой** во всех успешных прогонах. → Подтверждает, что парсинг
  tool-частей был мёртвым кодом (уже убран): OpenCode вызывает свои MCP-инструменты внутри
  sidecar и не отдаёт их наружу в том виде, в котором мы их читали.

### 1.2 Режим отказа №1 — HTTP 500 от sidecar

```
17:40:48.393 Запуск OpenCode агента: analyst в /work/slot-2 (taskId=09aeba81, session=null)
17:40:48.724 создана сессия ses_f8d576031ffe8kPBvPoRfjyb7F
17:40:48.805 ERROR 500 Internal Server Error:
             {"name":"UnknownError","data":{"message":"Unexpected server error...","ref":"err_41848462"}}
17:40:48.838 сессия ses_f8d576031ffe8kPBvPoRfjyb7F остановлена (abort)
17:40:58.856 Запуск OpenCode агента: analyst  ← RETRY через 10с, тот же taskId
17:40:58.878 создана сессия ses_f8d573887ffeyMO9wSVmZYYQj0
17:40:58.907 ERROR 500 ... {"ref":"err_3dbade67"}
17:40:58.936 сессия ses_f8d573887ffeyMO9wSVmZYYQj0 остановлена (abort)
17:40:58.950 Слот 2 освобождён
```

Наблюдения:

- 500 приходит **через ~0.4с** после создания сессии → это отказ на стороне sidecar при разборе
  запроса, а не падение агента в процессе работы. Наиболее вероятно — форма body
  (`agent` / вложенный `model: {providerID, modelID}`).
- **500 не retriable.** Повтор просто сжёг 10с и создал вторую мусорную сессию.
- Точная причина (`ref: err_XXXXX`) **лежит только в `docker logs opencode`**. В Loki её нет:
  `grafana-alloy/config.alloy` содержит лишь `prometheus.scrape` — пайплайна для логов
  контейнера `opencode` не существует. См. задачу 0 ниже.

### 1.3 Режим отказа №2 — read timeout при долгой работе

`java.net.SocketTimeoutException: Read timed out`, стек проходит через
`SimpleClientHttpResponse.getHeaders` → `DefaultRestClient.getContentType`.
Таймаут сработал **на ожидании заголовков ответа**, т.е. агент внутри sidecar всё ещё работал,
когда `readTimeout` (300+30с) истёк. Работа агента при этом теряется безвозвратно —
переподключиться к ней нечем.

### 1.4 Усилители отказов

- `@Retry(name="opencode")` + `@CircuitBreaker(name="opencode")` на `runAgent`, у обоих
  `recordExceptions/retryExceptions: [java.lang.RuntimeException]` → под retry попадает
  **неидемпотентная** операция (агент коммитит код, создаёт PR), а breaker считает отказами
  и бизнес-ошибки (пустой вывод агента).
- В стеке 500-ошибки виден и второй путь входа:
  `CheckpointRecoveryListener.recoverUnfinishedTasks` → `AgentGraphRunner.resume` → `runAgent`.
  Т.е. рестарт приложения сам по себе порождает волну вызовов OpenCode.

### 1.5 Реальный API (docs + подтверждено логами)

Подтверждённый набор эндпоинтов `opencode serve` (форк `anomalyco/opencode`):

| Метод  | Путь                              | Назначение                        | Ответ            |
|--------|-----------------------------------|-----------------------------------|------------------|
| `GET`  | `/global/health`                  | health + версия                   | `{healthy, version}` |
| `POST` | `/session?directory=`             | создать сессию                    | `Session`        |
| `GET`  | `/session/status`                 | статус **всех** сессий            | `{[id]: SessionStatus}` |
| `POST` | `/session/:id/prompt_async`       | отправить промпт **без ожидания** | `204 No Content` |
| `GET`  | `/session/:id/message`            | список сообщений сессии           | `{info, parts}[]`|
| `GET`  | `/session/:id/message/:messageID` | одно сообщение + parts            | `{info, parts}`  |
| `POST` | `/session/:id/abort`              | отменить работу сессии            | `boolean`        |
| `GET`  | `/event`                          | SSE-поток событий                 | event stream     |

Body у `prompt_async` тот же, что у `message`:
`{ messageID?, model?, agent?, noReply?, system?, tools?, parts }`.

**Ключевой вывод:** `messageID` можно задать *своим* → это готовый ключ идемпотентности.
Мы больше не обязаны угадывать, «долетел» ли запрос: можно спросить у сервера
`GET /session/:id/message/:messageID`.

---

## Часть 2. Целевая архитектура

Принцип: **ни один HTTP-вызов не длится дольше секунд.** Долгое ожидание превращается
в повторяемый опрос состояния, которое живёт в БД, а не в стеке потока.

```
        ┌─ health gate: GET /global/health (fail fast, ~2с)
        │
        ├─ аренда слота (в БД, с TTL)
        │
        ├─ POST /session?directory=            → sessionId
        │
        ├─ INSERT opencode_run(taskId, agent, sessionId, messageId=<наш UUID>,
        │                      slot, status=STARTING)          ← до отправки промпта
        │
        ├─ POST /session/{id}/prompt_async     → 204, возвращается мгновенно
        │     status=RUNNING
        │
        └─ цикл опроса (каждые N секунд, короткие запросы):
              GET /session/{id}/message/{messageId}
                 ├─ не завершено      → ждать дальше, писать прогресс
                 ├─ завершено         → status=DONE, вернуть output
                 ├─ ошибка в info     → status=FAILED
                 └─ бюджет истёк      → POST abort, status=ABORTED
```

Что это даёт:

- **Обрыв TCP не теряет работу.** Агент продолжает работать в sidecar; следующий опрос
  подхватывает результат.
- **Рестарт Spring не теряет работу.** Прогон лежит в БД → при восстановлении не отправляем
  промпт заново, а продолжаем опрос того же `messageId`.
- **Retry становится безопасным.** Повторяем только идемпотентные `GET`-опросы.
  Неидемпотентный `prompt_async` не повторяется никогда без проверки через `GET`.
- **Прогресс в реальном времени** — то, что раньше было недоступно при блокирующем вызове.

---

## Часть 3. Задачи

### Задача 0 — сделать причину 500 наблюдаемой `TODO`

Без логов sidecar причина `ref: err_XXXXX` недоступна. Добавить в
`grafana-alloy/config.alloy` сбор логов docker-контейнеров (`loki.source.docker` +
`discovery.docker`) и отправку в Loki, чтобы `opencode` попал в `service_name`.

Иначе диагностика 500 остаётся ручным `docker logs opencode`.

### Задача 1 — низкоуровневый клиент `OpenCodeApi` `TODO`

Новый класс, только HTTP, без бизнес-логики:

- `health()` → `HealthInfo`, таймаут ~2с
- `createSession(cwd, title)` → `sessionId`
- `promptAsync(sessionId, cwd, messageId, agent, model, prompt)` → void (ожидает 204)
- `getMessage(sessionId, cwd, messageId)` → `MessageEnvelope` (`info` + `parts`)
- `sessionStatuses()` → карта статусов
- `abort(sessionId, cwd)` → boolean

Требования:

- Заменить `SimpleClientHttpRequestFactory` на пул соединений (Apache HttpClient 5)
  — сейчас нет ни пула, ни контроля keep-alive.
- **Короткие таймауты для всех вызовов** (connect ~5с, read ~15с). Долгих запросов больше нет.
- `@Retry` допустим **только** на `GET`-методах (идемпотентны). На `promptAsync` — запрещён.
- Убрать `llmParseResponse` целиком: п.1.1 показал, что сессия отдаёт валидный JSON,
  а на не-JSON ответ (HTML-ошибка) LLM всё равно не придумает `parts[]`. Это была маскировка.

### Задача 2 — персистентность прогона `TODO`

`OpenCodeRunEntity` + `OpenCodeRunRepository`:

| поле           | смысл                                                     |
|----------------|-----------------------------------------------------------|
| `id`           | PK                                                        |
| `taskId`       | задача                                                    |
| `agentName`    | analyst / developer / tester / post_validation            |
| `sessionId`    | сессия OpenCode                                           |
| `messageId`    | **ключ идемпотентности**, генерируем сами до отправки      |
| `slot`         | занятый слот                                              |
| `status`       | STARTING / RUNNING / DONE / FAILED / ABORTED              |
| `output`       | накопленный текст                                         |
| `error`        | текст ошибки                                              |
| `startedAt`    | старт                                                     |
| `lastPolledAt` | последний успешный опрос                                  |

Уникальный индекс по `(taskId, agentName, messageId)`.
Запись создаётся **до** `prompt_async` — иначе при падении между отправкой и записью
мы потеряем `messageId` и не сможем найти работу агента.

### Задача 3 — переписать `OpenCodeClient` на опрос `TODO`

Публичный контракт `runAgent(...)` и `OpenCodeResult` **сохранить** — вызывающие узлы
(`AnalystNode`, `DeveloperNode`, `TesterNode`, `TestExecutionService`,
`PullRequestCreationService`) не трогаем на этом шаге.

Внутри: health gate → resume-or-start → цикл опроса с бюджетом.
Resume: если для `(taskId, agentName)` есть строка в статусе `RUNNING` — **не отправлять промпт**,
а продолжить опрос существующего `messageId`.

Прогресс писать в `TaskProgressRegistry` из цикла опроса — это чинит и то, что сейчас
прогресс появляется только по завершении.

### Задача 4 — снять опасные аннотации `TODO`

В `application.yml`:

- **Убрать `retry.instances.opencode`** от `runAgent`. Retry остаётся только внутри
  `OpenCodeApi` на идемпотентных `GET`.
- Сузить `circuitbreaker.instances.opencode.recordExceptions` до сетевых
  (`IOException`, `ConnectException`, `SocketTimeoutException`) — сейчас там
  `RuntimeException`, из-за чего breaker открывают бизнес-ошибки.
- `slowCallDurationThreshold: 300s` пересмотреть: вызовы стали короткими.

### Задача 5 — слоты в БД `TODO`

Сейчас `OpenCodeSessionPool` = in-memory `Semaphore` + `AtomicBoolean[]`. Проблемы:

- `AnalystNode` держит слот **через HITL-паузу** и пишет `slot` в checkpoint
  (`AnalystNode.java:130-133`). После рестарта Spring слот считается свободным → выдаётся
  другой задаче → две задачи пишут в один `/work/slot-N` → git-конфликты.
- Рассинхрон конфигурации: `application.yml` → `slots: 5`, дефолт в `WorktreeManager` →
  `${opencode.slots:1}`. Привести к одному значению.

Решение: аренда слота в БД с TTL и владельцем (`taskId`), по образцу `TaskLockService`
(PostgreSQL advisory lock, уже используется для задач). Освобождение — по завершении
либо по истечении TTL (подхватит `ZombieTaskMonitor`).

### Задача 6 — проверка end-to-end `TODO`

- Один прогон аналитика целиком, с проверкой, что опрос видит завершение.
- Рестарт `developer` посреди работы агента → работа не потеряна, опрос продолжен.
- Намеренный обрыв связи с sidecar → переподключение.
- Проверить, что 500 больше не приводит к созданию второй сессии.

---

## Часть 4. Порядок работ

1. Задача 1 (`OpenCodeApi`) — фундамент.
2. Задача 2 (персистентность) — без неё нет resume.
3. Задача 3 (`OpenCodeClient` на опрос) — основная ценность.
4. Задача 4 (аннотации) — маленькая, но снимает главный усилитель отказов.
5. Задача 0 (логи sidecar) — параллельно, нужна для добивания причины 500.
6. Задача 5 (слоты) — отдельным шагом, независима от 1–4.
7. Задача 6 (проверка).

Открытый вопрос к проверке в бою: точное поле завершения в `info`
(ожидается `time.completed`). На первом опросе логировать фактическую форму `info`,
чтобы уточнить детекцию по реальным данным, а не по докам.
