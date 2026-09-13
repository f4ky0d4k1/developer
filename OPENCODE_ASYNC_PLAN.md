# Отказоустойчивое общение Spring ↔ OpenCode: план

Создан 2026-09-06. Живой документ — отмечать статус по мере реализации.
Решение: **вариант A** — асинхронная модель с персистентным состоянием.

Статусы: `TODO` / `IN PROGRESS` / `DONE` / `BLOCKED`

---

## Часть 1. Факты, добытые из Grafana Loki + SDK types + GitHub issues (не догадки)

Датасорс `grafanacloud-logs`, `{service_name="developer"}`, окно 7 дней.
Источники: Loki-логи `developer`, SDK types `@opencode-ai/sdk` (TS + Elixir),
GitHub issues `sst/opencode` и `anomalyco/opencode`, docs `uplift-labs/opencode-dev-docs`.

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

### 1.5 Реальный API (docs + подтверждено логами + SDK types)

Подтверждённый набор эндпоинтов `opencode serve` (форк `anomalyco/opencode`):

| Метод  | Путь                              | Назначение                        | Ответ                   |
|--------|-----------------------------------|-----------------------------------|-------------------------|
| `GET`  | `/global/health`                  | health + версия                   | `{healthy, version}`    |
| `POST` | `/session?directory=`             | создать сессию                    | `Session`               |
| `GET`  | `/session/status`                 | статус **всех** сессий            | `{[id]: SessionStatus}` |
| `POST` | `/session/:id/prompt_async`       | отправить промпт **без ожидания** | **204 No Content**      |
| `GET`  | `/session/:id/message`            | список сообщений сессии           | `Array<{info, parts}>`  |
| `GET`  | `/session/:id/message/:messageID` | одно сообщение + parts            | `{info, parts}`         |
| `POST` | `/session/:id/abort`              | отменить работу сессии            | `boolean`               |
| `GET`  | `/event`                          | SSE-поток событий                 | event stream            |

#### 1.5.1 `prompt_async` — критическая деталь

**Ответ: 204 No Content** — сервер НЕ возвращает `messageID`.
Но `messageID` — это **опциональное поле в request body**:

```json
{
  "messageID": "our-uuid-here",
  ←
  мы
  генерируем
  сами
  "model": {
    "providerID": "...",
    "modelID": "..."
  },
  "agent": "analyst",
  "parts": [
    {
      "type": "text",
      "text": "..."
    }
  ]
}
```

Источник: `SessionPromptAsyncData.body.messageID?: string` (SDK types.gen.ts),
`PromptInput.message_id: Option<String>` (Rust SDK docs.rs).

**Ключевой вывод:** `messageID` можно задать *своим* → это готовый ключ идемпотентности.
Мы больше не обязаны угадывать, «долетел» ли запрос: можно спросить у сервера
`GET /session/:id/message/:messageID`.

#### 1.5.2 Форма ответа `GET /session/:id/message/:messageID`

Подтверждено из TS SDK (`SessionMessageResponses.200`) и Elixir SDK (`session_message_200_json_resp`):

```json
{
  "info": {
    // UserMessage | AssistantMessage
    "id": "msg_...",
    "sessionID": "ses_...",
    "role": "assistant",
    "time": {
      "created": 1694000000000,
      "completed": 1694000060000
      ←
      присутствует
      =
      завершено
    },
    "error": null,
    ← {
  name,
  data
} при ошибке
"finish": "stop", ← причина завершения
"cost": 0.001,
"tokens": {...},
"parentID": "msg_...",
"modelID": "...",
"providerID": "...",
"mode": "...",
"path": {"cwd": "...", "root": "..."}
},
"parts": [
{"type": "text", "text": "...", "id": "...", ...},
{"type": "tool", "callID": "...", "tool": "...", "state": {...}},
{"type": "step-start", ...},
{"type": "step-finish", ...},
...
]
}
```

**Детекция завершения** (для `AssistantMessage`):

- `info.time.completed` присутствует (не null) → сообщение завершено
- `info.error` не null → ошибка (структура: `{ name: "...", data: { message, ref? } }`)
- `info.finish` присутствует → причина завершения (`"stop"`, `"length"`, и т.д.)
- `info.role == "assistant"` — подтверждает, что это ответ агента, а не user-промпт

**Part-типы** (полный список из SDK):
`text`, `reasoning`, `file`, `tool`, `step-start`, `step-finish`,
`snapshot`, `patch`, `agent`, `retry`, `compaction`, `subtask`.

Для извлечения текста агента: `parts[].type == "text"` → `parts[].text`.

#### 1.5.3 `GET /session/status` — форма ответа

```json
{
  "ses_xxx": {
    "type": "idle"
  },
  "ses_yyy": {
    "type": "busy"
  },
  "ses_zzz": {
    "type": "retry",
    "attempt": 2,
    "message": "...",
    "next": 1694000060000
  }
}
```

Карта `sessionID → SessionStatus`. Используется как health-gate перед опросом:
если сессия `busy` → агент ещё работает, можно опрашивать `getMessage`.
Если `idle` → агент завершил, опрашиваем `getMessage` для результата.

#### 1.5.4 SSE `/event` — НЕ использовать как основной канал

**Найден критический баг** (GitHub issue #27966, `anomalyco/opencode`):
в версиях 1.14.42–1.15.1 SSE-поток `/event` **не доставляет** `message.part.updated`
и `message.updated` события (SyncEvent → Bus.subscribeAll не доходит).
Исправлено в 1.15.5+ (#27825, #28051, #27959).

Мы используем `ghcr.io/anomalyco/opencode` без тега → версия неизвестна.
SSE ненадёжен для детектирования завершения.

**Решение: опрос `GET /session/:id/message/:messageID` — единственный надёжный способ.**
SSE можно добавить позже как оптимизацию (для прогресса в реальном времени),
но не как механизм детектирования завершения.

#### 1.5.5 Структура 500-ошибки

Тело 500-ответа от OpenCode:

```json
{
  "name": "UnknownError",
  "data": {
    "message": "Unexpected server error. Check server logs for details.",
    "ref": "err_3dbade67"
  }
}
```

Соответствует SDK-типу `UnknownError = { name: "UnknownError", data: { message: string } }`.
Поле `ref` — server-side correlation ID для поиска в `docker logs opencode`.

Другие типы ошибок: `ProviderAuthError`, `MessageOutputLengthError`,
`MessageAbortedError`, `APIError` (с `statusCode`, `isRetryable`, `responseBody`).

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

### Задача 0 — сделать причину 500 наблюдаемой `DONE`

Без логов sidecar причина `ref: err_XXXXX` недоступна. Добавить в
`grafana-alloy/config.alloy` сбор логов docker-контейнеров (`loki.source.docker` +
`discovery.docker`) и отправку в Loki, чтобы `opencode` попал в `service_name`.

Иначе диагностика 500 остаётся ручным `docker logs opencode`.

### Задача 1 — низкоуровневый клиент `OpenCodeApi` `DONE`

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

#### Парсинг ответов — Java records вместо JsonNode и LLM

**Контекст:** OpenCode сервер написан на Effect.ts с `HttpApiEndpoint` — каждый эндпоинт
декларирует `success`/`error` схемы через `Schema.Struct`. Фреймворк Effect **enforces** их:
серверный код не может вернуть объект, не matching схеме. SDK-типы (`types.gen.ts`)
генерируются **автоматически из OpenAPI-спецификации** через `@hey-api/openapi-ts`.
TS и Elixir SDK совпадают, потому что оба идут из одного источника.

**Где гарантия ломается** (и почему нужен защитный парсинг):

1. **Мы не пиним версию образа.** `FROM ghcr.io/anomalyco/opencode` без тега → `docker pull`
   может принести любую версию. Схема меняется между мажорами: уже есть **v1**
   (`types.gen.ts`) и **v2** (`v2/gen/types.gen.ts`) — разные поля, разные структуры.
   Обновление образа = потенциально другой JSON.
2. **Это форк** (`anomalyco/opencode`), не upstream `sst/opencode`. Форк может расходиться
   с upstream — добавлять поля, менять структуры, не обновляя SDK-типы.
3. **Error-ответы — другая схема.** Успешный `GET /message/:id` → `{info, parts}`.
   Ошибка 500 → `{name: "UnknownError", data: {message, ref}}`. Это **не тот же JSON**,
   Jackson упадёт при попытке десериализовать 500-ответ в `MessageEnvelope`.
4. **Union-типы.** `info` — это `UserMessage | AssistantMessage`. У них **разные поля**:
   у `AssistantMessage` есть `time.completed`, `error`, `finish`, `cost`, `tokens`;
   у `UserMessage` их нет. Jackson не умеет polymorphic deserialization без
   `@JsonTypeInfo` discriminator. Поле `role` ("assistant" | "user") работает как
   дискриминатор, но его надо настроить.
5. **Сетевой слой.** 502/503/504 от proxy (docker network, nginx) → HTML, не JSON.
   `mapper.readValue()` выбросит `JsonProcessingException`.

**Решение: прямые Java records + защитный парсинг.**

Records (минимально нужные поля, `FAIL_ON_UNKNOWN_PROPERTIES = false`):

```java
// Ответ GET /session/:id/message/:messageID
record MessageEnvelope(MessageInfo info, List<Part> parts) {
}

// AssistantMessage — только то, что используем
// role = discriminator для UserMessage vs AssistantMessage
record MessageInfo(
        String id,
        String role,              // "assistant" | "user"
        TimeInfo time,            // { created, completed? }
        MessageError error,       // null = нет ошибки
        String finish             // "stop" | "length" | null
) {
}

record TimeInfo(long created, Long completed) {
}  // completed != null → завершено

record MessageError(String name, ErrorData data) {
}

record ErrorData(String message, String ref) {
}   // ref = "err_XXXXX"

// Part — union из 12 типов, но нам нужен только text
record Part(String type, String text) {
}           // остальные поля игнорируем

// Error-ответ (500/4xx)
record OpenCodeError(String name, ErrorData data) {
}

// SessionStatus
record SessionStatus(String type, Integer attempt, String message, Long next) {
}
// type = "idle" | "busy" | "retry"
```

Парсинг:

- `mapper.configure(FAIL_ON_UNKNOWN_PROPERTIES, false)` — forward-compatible,
  новые поля не ломают парсинг при обновлении образа.
- HTTP-статус **до** парсинга тела: 2xx → `MessageEnvelope`; 4xx/5xx → `OpenCodeError`;
  не-JSON (HTML/прокси) → catch `JsonProcessingException`, fallback на ручной
  `JsonNode` обход (не на LLM).
- `role` как discriminator: если `info.role == "assistant"` → проверяем `time.completed`;
  если `"user"` → игнорируем (нас интересует только ответ агента).
- Детекция завершения: `info.time.completed != null`.
- Извлечение текста: `parts.stream().filter(p -> "text".equals(p.type())).map(Part::text)`.
- LLM fallback **не нужен** ни в каком сценарии: валидный JSON парсит Jackson,
  невалидный (HTML/прокси-ошибка) LLM тоже не спасёт.

**Пинить версию образа:** `ghcr.io/anomalyco/opencode:1.15.5` вместо `latest`
(см. Задачу 0 — после уточнения текущей версии через `docker logs opencode`).

### Задача 2 — персистентность прогона `DONE`

`OpenCodeRunEntity` + `OpenCodeRunRepository`:

| поле           | смысл                                                 |
|----------------|-------------------------------------------------------|
| `id`           | PK                                                    |
| `taskId`       | задача                                                |
| `agentName`    | analyst / developer / tester / post_validation        |
| `sessionId`    | сессия OpenCode                                       |
| `messageId`    | **ключ идемпотентности**, генерируем сами до отправки |
| `slot`         | занятый слот                                          |
| `status`       | STARTING / RUNNING / DONE / FAILED / ABORTED          |
| `output`       | накопленный текст                                     |
| `error`        | текст ошибки                                          |
| `startedAt`    | старт                                                 |
| `lastPolledAt` | последний успешный опрос                              |

Уникальный индекс по `(taskId, agentName, messageId)`.
Запись создаётся **до** `prompt_async` — иначе при падении между отправкой и записью
мы потеряем `messageId` и не сможем найти работу агента.

### Задача 3 — переписать `OpenCodeClient` на опрос `DONE`

Публичный контракт `runAgent(...)` и `OpenCodeResult` **сохранить** — вызывающие узлы
(`AnalystNode`, `DeveloperNode`, `TesterNode`, `TestExecutionService`,
`PullRequestCreationService`) не трогаем на этом шаге.

Внутри: health gate → resume-or-start → цикл опроса с бюджетом.
Resume: если для `(taskId, agentName)` есть строка в статусе `RUNNING` — **не отправлять промпт**,
а продолжить опрос существующего `messageId`.

Прогресс писать в `TaskProgressRegistry` из цикла опроса — это чинит и то, что сейчас
прогресс появляется только по завершении.

### Задача 4 — снять опасные аннотации `DONE`

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

### 1.6 Дополнительная находка: StaleObjectStateException

В логах найден второй тип ошибки — `org.hibernate.StaleObjectStateException`
на `TaskProgressEntity`. Concurrent-обновление прогресса из разных потоков
вызывает `merge` конфликт. Это отдельный баг, не связанный с OpenCode API,
но требует исправления (optimistic locking или `@Version` поле).

---

## Часть 5. Открытые вопросы (закрыты исследованием)

| Вопрос                                 | Ответ                                                    | Источник                 |
|----------------------------------------|----------------------------------------------------------|--------------------------|
| Форма `info`/`parts` в реальном ответе | `{info: AssistantMessage, parts: Part[]}`                | SDK TS + Elixir, п.1.5.2 |
| Поле завершения                        | `info.time.completed` (не null) + `info.finish`          | SDK types.gen.ts         |
| SSE `/event` как альтернатива опросу   | **Нет**, баг в 1.14.42–1.15.1                            | GitHub #27966            |
| Форма `SessionStatus`                  | `{type: "idle\|busy\|retry"}`                            | SDK types.gen.ts         |
| Причина 500 в body                     | `UnknownError` с `ref` — server-side, нужны логи sidecar | Loki + SDK types         |
| `prompt_async` возвращает messageID?   | **Нет**, 204 No Content. messageID — input параметр      | SDK types, Rust docs     |

**Все гипотезы проверены. План готов к реализации.**

---

## Часть 6. Статус реализации (2026-09-13)

Задачи 0–4 реализованы, 5–6 — остались.

- **Задача 1 (`OpenCodeApi`)** — новый класс, Apache HttpClient 5 (пул 50/20, evict idle 30с),
  connect 5с / read 15с, `FAIL_ON_UNKNOWN_PROPERTIES=false`, records-модель ответов.
  `@Retry(name="opencodeApi")` только на GET (health/getMessage/sessionStatuses) и только на
  `ResourceAccessException`; `promptAsync` — без retry. `llmParseResponse` удалён.
- **Задача 2 (персистентность)** — `OpenCodeRunEntity` + `OpenCodeRunRepository` + enum
  `OpenCodeRunStatus` (STARTING/RUNNING/DONE/FAILED/ABORTED). Отклонение от плана: вместо
  поля `slot` хранится `cwd` (slot не виден на уровне `OpenCodeClient` — его держат узлы
  графа); добавлен флаг `promptSent` для корректного resume в окне INSERT↔prompt_async.
  Уникальный индекс `(task_id, agent_name, message_id)`.
- **Задача 3 (`OpenCodeClient`)** — health gate → resume-or-start → цикл опроса с бюджетом.
  Resume различает «промпт ушёл / не ушёл / не проверить» через `messageExists()`
  (проверка GET перед повторным отправом — принцип «неидемпотентный prompt_async не
  повторяется без проверки через GET»). Прогресс пишется в `TaskProgressRegistry` по
  приращению текста (delta). Публичный контракт `runAgent`/`OpenCodeResult` сохранён.
- **Задача 4 (аннотации)** — из `runAgent` сняты `@Retry` и `@CircuitBreaker`; убраны
  `retry.instances.opencode` и `circuitbreaker.instances.opencode`; добавлен
  `retry.instances.opencodeApi`; в `opencode`-блок добавлен `poll-interval-seconds`.
- **Задача 0 (логи sidecar)** — в `config.alloy` добавлены `discovery.docker` +
  `loki.source.docker` + `loki.relabel` + `loki.write` (service_name="opencode"); в
  `docker-compose.yml` смонтирован docker.sock в `grafana-alloy` и добавлены env
  `ALLOY_LOKI_URL/USER/PASSWORD`; обновлён `.env.template`.

Осталось: **Задача 5** (слоты в БД с TTL) и **Задача 6** (end-to-end проверка: опрос видит
завершение, рестарт не теряет работу, 500 не плодит вторую сессию).

---

## Часть 7. Тесты (Testcontainers + WireMock, 2026-09-13)

Автоматизирована проверка ядра новой архитектуры (покрывает суть Задачи 6):

- `OpenCodeApiTest` — WireMock-стаб sidecar: форма `prompt_async` (messageID/model/agent/parts),
  парсинг assistant/user/404/500 с `ref`. 9 тестов.
- `OpenCodeRunRepositoryTest` — Testcontainers PostgreSQL: save/find, resume-выборка,
  уникальный индекс `(task_id, agent_name, message_id)`. 5 тестов.
- `OpenCodeClientTest` — Testcontainers + WireMock: полный цикл «health gate → прогон →
  опрос», delta-прогресс, resume без повторной отправки промпта, unhealthy → error без промпта,
  таймаут → abort + ABORTED. 5 тестов.

Запуск: `./mvnw test -Dtest='OpenCodeApiTest,OpenCodeRunRepositoryTest,OpenCodeClientTest'`
(требует Docker Desktop). Версия Testcontainers переопределена на `2.0.5` в `pom.xml` —
пиннутая Spring Boot 3.4.1 версия `1.20.4` несовместима с Docker Desktop 29.x, а модули
в 2.0 переименованы (`junit-jupiter` → `testcontainers-junit-jupiter`, `postgresql` →
`testcontainers-postgresql`).

Не покрыто тестами (нужен живой sidecar/продакшн-лог): фактическая форма `info`/`parts` на
первом опросе против реального OpenCode, и Задача 5 (слоты в БД).
