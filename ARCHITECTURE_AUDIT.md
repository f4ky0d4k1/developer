# Архитектурный аудит — отчёт и план исправлений

Дата аудита: 2026-09-01. Обновлено: 2026-09-05. Ведётся как живой документ: отмечать статус по мере исправления.

Статусы: `TODO` / `IN PROGRESS` / `DONE` / `REPORTED (не чиним сейчас)` / `WONTFIX (осознанно)`

---

## 1. Docker socket в обоих контейнерах

**Статус: DONE (сторона `developer`)**

`/var/run/docker.sock` монтируется в `developer` (нужен только для `docker exec ... opencode ...` в `OpenCodeClient`) и
в `opencode` (нужен для `docker run ... ghcr.io/github/github-mcp-server` per-запрос в `opencode.jsonc`).

Решение: переписать `OpenCodeClient` на HTTP-клиент к уже поднятому `opencode serve` (порт 4096, `opencode.base-url` уже
сконфигурирован, но не использовался). Использует HTTP API opencode:

- `POST /session` — создать сессию
- `POST /session/:id/message` — отправить промпт, дождаться ответа (`agent`, `model`, `parts`)
- `POST /session/:id/abort` — реальная отмена работающей сессии (решает и п.2 "иллюзорный таймаут")

После миграции — убрать монтирование `docker.sock` из сервиса `developer` в `docker-compose.yml`.

Монтирование в `opencode` (GitHub MCP через `docker run`) — **не трогаем сейчас** (отдельная задача, зависит от
доступности remote GitHub MCP или отдельного sidecar-сервиса — см. предыдущий отчёт).

## 2. Иллюзорный timeout у OpenCode-процессов

**Статус: решается пунктом 1.** HTTP-клиент с реальным `abort` вместо `Process.destroyForcibly()` над `docker exec`
-клиентом.

## 3. Взаимное истощение пула соединений БД

**Статус: DONE**

`TaskLockService` берёт JDBC-соединение из общего `DataSource` (используемого Hibernate/JPA) и держит его на всю
длительность выполнения графа задачи (до 300–600с на узел). При параллельных задачах (`taskExecutor` — 10 потоков) это
конкурирует с обычными JPA-запросами за один и тот же пул.

Решение:

- Выделить **отдельный `DataSource`** только для advisory-lock соединений `TaskLockService`, с размером пула =
  `taskExecutor` (10) + запас. Основной JPA-пул перестаёт зависеть от долгоживущих lock-соединений.
- Добавить **`ZombieTaskMonitor`** — `@Scheduled`-джоб, который сверяет задачи со статусом `RUNNING` в БД с живыми
  потоками (`TaskLauncher.isRunning`) и активными advisory-lock (`TaskLockService.isLocked`). Если задача помечена
  `RUNNING`, но не выполняется ни здесь, ни на другом инстансе — считается зомби и перезапускается через
  `TaskLauncher.restart(...)`.

Ранее это только частично было: `TaskMcpTools.getTaskDetails` детектировал зомби-статус (`⚠️ ZOMBIE`), но только по
явному запросу пользователя в Telegram, без авто-перезапуска и без периодической проверки.

## 4. Отключена проверка TLS при клонировании репозитория с embedded-токеном

**Статус: REPORTED (не чиним сейчас)**

`WorktreeManager` — `git config http.sslVerify false` + PAT в URL. Риск MITM/утечки токена. Требует отдельного решения (
credential.helper вместо URL-embedding).

## 5. Resilience4j сконфигурирован, но нигде не применяется

**Статус: DONE**

Конфигурация в `application.yml` (`opencode`, `github`, `tracker`, `grafana`, `llm` instances) не была подключена ни
одной аннотацией. Добавляем `@CircuitBreaker`/`@Retry` на реальные точки сетевых вызовов: `OpenCodeClient` (HTTP-вызовы
после миграции), `GitHubService`.

## 6. Нет координации при горизонтальном масштабировании

**Статус: REPORTED (не чиним сейчас)**

`OpenCodeSessionPool` (in-memory `Semaphore`) + `WorktreeManager` (slot-директории на общем volume) не рассчитаны на >1
реплику `developer`. Требует внешней координации слотов (например, через БД) — отдельная задача.

## 7. God-class `PostValidationNode`

**Статус: DONE**

Класс на ~490 строк совмещает: запуск тестов, построение промптов, создание PR, парсинг LLM-решения, применение
решения/reroute. Разбиваем на:

- `TestExecutionService` — запуск тестов через OpenCode + парсинг `ValidationReport`.
- `PullRequestCreationService` — запуск создания PR через OpenCode.
- `PostValidationNode` — тонкий оркестратор: проверяет состояние (`requiresDevelopment`/`requiresTesting` выполнены?),
  делегирует тестирование/PR сервисам, парсит решение LLM и применяет reroute. Никакой самостоятельной "доработки" —
  только контроль и возврат на нужный узел.

## 8. Дублирование в `TaskLauncher` (`resumeTask`/`resumeHitlTask`)

**Статус: DONE**

Объединяем в один приватный метод `resumeInternal(taskId, chatId, additionalMessages, logPrefix)`.

## 9. `ddl-auto: update` без миграций

**Статус: WONTFIX (осознанное решение)**

## 10. Тихое проглатывание ошибок checkpoint

**Статус: DONE**

`CheckpointService.saveCheckpoint` при ошибке сериализации только логировал `log.error`, не давая графу знать о сбое.
Пробрасываем `RuntimeException`, чтобы `JpaCheckpointStore.save` (и, соответственно, библиотека графа) корректно
перевели узел/задачу в ошибочное состояние вместо тихой потери checkpoint.

## 11. Хардкод имени контейнера `opencode`

**Статус: решается пунктом 1** — после перехода на HTTP имя контейнера больше не используется в коде, вместо него —
конфигурируемый `opencode.base-url`.

## 12. Непоследовательная очистка слота в `AnalystNode`

**Статус: DONE**

Приводим первый запуск (`first run`) к единому `try/finally`, как это уже сделано в `TesterNode`/`DeveloperNode`, вместо
ручного вызова `cleanupSlot`/`release` в каждой error-ветке.

## 13. Config drift в комментариях / упоминания DeepSeek

**Статус: REPORTED (не найдено реального drift)**

При ревью не найдено расхождения комментарий/код — только ожидаемые дефолты `@Value` и легитимный vendor-specific класс
`DeepSeekChatOptions` (workaround для thinking-mode). Оставлено как есть, стоит перепроверить при добавлении второго
провайдера LLM.

## 14. Открытый доступ по умолчанию (Telegram whitelist/trigger-users)

**Статус: DONE**

По умолчанию (`allowed-chat-ids`/`trigger-users` не заданы) бот сейчас принимает команды от всех. Меняем на
deny-by-default: если whitelist не задан явно — бот не обрабатывает команды и логирует предупреждение о необходимости
конфигурации.

## 15. Отключённая проверка TLS для Node/npm в sidecar

**Статус: REPORTED (не чиним сейчас)**

`opencode.Dockerfile`: `NODE_TLS_REJECT_UNAUTHORIZED=0`.

## 16. Debug/trace-логирование чувствительных клиентов в проде

**Статус: REPORTED (не чиним сейчас)**

`application.yml`: `trace` для `org.springframework.web.client`/`web.reactive.function.client`, `debug` для
`io.modelcontextprotocol`/`spring.ai` — риск утечки токенов в логи (Loki/Grafana Cloud).

---

## Прогресс по коммитам

- [x] OpenCodeClient → HTTP-клиент (`opencode serve` API: `/session`, `/session/{id}/message`, `/session/{id}/abort`),
  убран `docker.sock` из `developer` (compose + Dockerfile: убран `docker-cli`)
- [x] Пул соединений БД: `spring.datasource.hikari.maximum-pool-size` увеличен (10→30) под держащиеся на весь run задачи
  lock-соединения `TaskLockService` + запас для JPA
- [x] `ZombieTaskMonitor` — периодическая проверка RUNNING-задач без потока/блокировки, авто-restart
- [x] Resilience4j: добавлен `spring-boot-starter-aop` (без него аннотации не работали), `@CircuitBreaker`/`@Retry` на
  `OpenCodeClient.runAgent` и `GitHubService` (+ `recordExceptions`/`retryExceptions: RuntimeException`, т.к. код
  оборачивает сетевые ошибки)
- [x] Рефакторинг `PostValidationNode` → выделены `TestExecutionService` и `PullRequestCreationService`, узел графа —
  только оркестрация
- [x] Дедупликация `TaskLauncher.resumeTask`/`resumeHitlTask` → общий `resumeInternal`
- [x] `CheckpointService.saveCheckpoint` — пробрасывает `IllegalStateException` вместо тихого `log.error`
- [x] `AnalystNode` — единый try/finally для очистки слота (флаг `releaseSlotOnExit`)
- [x] Telegram whitelist/trigger-users — deny-by-default при отсутствии конфигурации
- [ ] DeepSeek-упоминания — при ревью не найдено реального config drift (комментарий vs код), только ожидаемые дефолты
  для единственного используемого провайдера; `DeepSeekChatOptions` оставлен как есть (легитимный vendor-specific
  workaround для thinking-mode). Помечено REPORTED, не WONTFIX — стоит перепроверить при добавлении второго провайдера.

## 17. `ObjectOptimisticLockingFailureException` на `TaskProgressEntity` при старте любого агента

**Статус: DONE**

Обнаружено в бою (BACKEND-342): аналитик падал сразу после старта с
`ObjectOptimisticLockingFailureException ... (or unsaved-value mapping was incorrect)`.
Причина: `TaskProgressEntity.taskId` — derived identifier через `@MapsId`, проставляется
уже в конструкторе, поэтому эвристика Spring Data `isNew() == (id == null)` всегда считала
новую сущность существующей и вызывала `merge()` вместо `persist()`. Hibernate в этом случае
пытался выполнить UPDATE по ещё не существующей строке → 0 затронутых строк → `StaleStateException`.
Ломало **любую** новую задачу на первом же вызове `TaskProgressRegistry.start()`.

Исправлено: `TaskProgressEntity` теперь реализует `Persistable<String>` с явным
transient-флагом `isNew`, выставляемым в конструкторе создания — `save()` корректно
идёт через `persist()` для реально новых записей.

### Важные оговорки для проверки перед деплоем

1. **HTTP API OpenCode** (`OpenCodeClient`) написан по документированному API форка `anomalyco/opencode`
   (`POST /session?directory=`, `POST /session/{id}/message`, `POST /session/{id}/abort`,
   заголовок `x-opencode-directory`), но не протестирован вживую против реального образа
   `ghcr.io/anomalyco/opencode` — точные названия полей в `parts[]` (text/tool part types) могут
   отличаться. Требуется интеграционный прогон одной задачи end-to-end и, при расхождении,
   точечная правка `parseResponse`/`parseResponse`'s `switch` в `OpenCodeClient`.
2. Убедиться, что `opencode.jsonc`/агенты форка поддерживают `agent`/`model` в body `/session/{id}/message` так же, как
   раньше в CLI `opencode run --agent --model`.

## 18. LLM-вызовы через `.content()` вместо `.entity()` — потеря structured output

**Статус: DONE**

Все LLM-вызовы в проекте использовали `.content()` (свободный текст) с ручным JSON extraction через
`extractJson()` + `ObjectMapper.readValue()`. Это приводило к:

- "Пустой ответ LLM" когда модель возвращала plain text вместо JSON
- Потере rich-контекста (модель возвращала развёрнутый ответ, но без JSON-обёртки)
- Каскад retry-логики в `ConversationAgent` (~60 строк ручного парсинга)

Исправлено: все `.content()` заменены на `.entity(TargetClass.class)` — Spring AI native structured output:

- `ConversationAgent.processMessage` → `.entity(FastDecision.class)` с tool calling
- `StructuredOutputHelper.callWithFallback` → fallback тоже через `.entity()` (не `.content()`)
- `TelegramGateway.reformatForTelegram` → `.entity(ReformattedText.class)`
- `TaskLauncher.generateTitle` → `.entity(TaskTitle.class)`
- Удалены: `extractJson()`, `buildJsonSchemaHint()`, `ObjectMapper` из `StructuredOutputHelper`
- Добавлены records `ReformattedText` и `TaskTitle` в `AgentResponses`

## 19. OpenCodeClient: `application/octet-stream` вместо `application/json`

**Статус: DONE**

OpenCode server возвращает `Content-Type: application/octet-stream` вместо `application/json`.
Spring `RestClient` не находит конвертер для десериализации в `JsonNode` → ошибка
`Error while extracting response for type [JsonNode]`.

Исправлено: ответы читаются как `String` + `mapper.readTree(rawResponse)` в `createSession()` и
`runAgentInternal()`. Добавлен `.accept(MediaType.APPLICATION_JSON)` на оба запроса.

## 20. Каскадный рестарт: двойной запуск задачи при `interruptAndReroute`

**Статус: DONE**

`interruptAndReroute` прерывал старую задачу **и** запускал новую через `launch()`.
Затем `LAUNCH_TASK` handler в `TelegramBotListener` тоже вызывал `launch()`.
Результат: 2 задачи на 1 сообщение пользователя (83e1152c + 09aeba81).

Исправлено: метод переименован в `interruptRunningTask` (Single Responsibility) — только прерывает.
Запуск новой задачи делает caller (`LAUNCH_TASK` handler). Удалён неиспользуемый параметр `newContext`.

## 21. PR-мониторинг 404 при пустом `GITHUB_MONITOR_REPO`

**Статус: DONE**

`GITHUB_MONITOR_REPO` не задан → GitHub API получает `/repos//pulls` → 404 каждую минуту.

Исправлено: `PrCommentMonitor.monitorPullRequests` пропускает цикл если `monitorRepo` пустой.
`GITHUB_BOT_LOGIN` в `application.yml` получил empty default (`${GITHUB_BOT_LOGIN:}`).

## 22. Зависание агента: детекция и сохранение партиала

**Статус: DONE**

Боевой случай (задача `f4a867e7`): аналитик «работал» 300с без прогресса, затем таймаут + `abort` — вся работа
потеряна без следа. `ZombieTaskMonitor` тут не помогает: он ловит только задачи без живого потока/лока, а **зависший
живой поток** (сидит в цикле опроса) не видит.

Решение:

- `OpenCodeClient.poll` дополнительно смотрит `GET /session/status`: `busy`/`retry` → агент работает (в т.ч. во время
  долгих tool-вызовов) — **long-running не трогаем**; явный `idle` и нет прогресса `opencode.stall-timeout-seconds`
  (default 120) → `abort` как «завис».
- Перед abort (и на таймауте) **сохраняем частичный вывод** в `opencode_run.output`/`error`.
- E2E против реального сайдкара подтверждает семантику: во время работы `SessionStatus[type=busy]`.

## 23. Пустой слот / отсутствие `TARGET_REPO` — fail-fast

**Статус: DONE**

`WorktreeManager.prepareSlot` при `repoUrl=null` молча создавал пустую директорию и запускал агента в неё (агент
искал код, которого нет). Теперь:

- пустой `repoUrl` → `IllegalStateException` (агент без кода работать не может);
- слот существует, но не git-репозиторий → очистка и повторный клон;
- после подготовки сверяем наличие `.git`.

## 24. `question`-инструмент блокирует аналитика

**Статус: DONE**

`analyst.md` имел `question: allow`, но агент работает в async-режиме (`prompt_async` + опрос) — отвечать на
`question` некому, инструмент блокирует сессию до таймаута. Заменено на `question: deny`; уточнения запрашиваются
только через JSON `needsClarification`/`clarificationQuestion` (обрабатывает Spring → Telegram HITL).

## 25. Per-chat память «проект ↔ задачи» (Фаза 1)

**Статус: DONE**

Классификатор не определял целевой репозиторий, а `TelegramBotListener` запускал задачи с `targetRepo=null` — из
Telegram задачи вообще не получали репо (падали fail-fast на подготовке слота).

**Принцип:** поведение (как определять репо, спрашивать ли, когда запускать) — в промпте и инструментах; код
управляет только инвариантами/границами.

Реализовано:

- `TaskEntity.repo` — репозиторий сохраняется на задаче (память чата);
- `TaskMcpTools.getChatProjects(chatId)` — проекты чата (уникальные репо + число задач + последняя), агрегация в БД +
  **кэп top-10** — защита контекста;
- `TaskMcpTools.getChatTasks(chatId, page, perPage)` — пагинация задач чата (свежие первыми, с репо);
- нормализация репо (trim + lowercase) при записи и группировке — дедупликация;
- **запуск — инструмент `launch_task(chatId, repo, description)`** с ОБЯЗАТЕЛЬНЫМ `repo` на уровне схемы тула: модель
  физически не может запустить задачу без репо, поэтому при неоднозначности она **спрашивает** (промпт), а не
  запускает. Отдельного action `LAUNCH_TASK` и поля `FastDecision.repo` больше нет — это была бы «логика агента в
  коде»;
- промпт `conversation-fast.md`: определяй репо из сообщения или через `getChatProjects`/`getChatTasks`; не знаешь —
  спроси; дефолт-репо осознанно отвергнут.

Урок инцидента: сначала я добавил хардкод (regex `owner/name`, Map «ожидающих» задач, кастомный вопрос) — это
дублирование агентности. Правильно — ограничение выразить **схемой тула** (`repo` required), а диалог/уточнение —
промптом; в коде остаётся только граница (не запускать без репо).

## 26. Курируемая память чата + RAG (Фаза 2)

**Статус: TODO**

Фаза 1 — память «выведенная из задач» (implicit). Она детерминирована и без новых отказов, но потолок есть:
нет инвалидации (репо переименован/удалён), нет слияния похожих (`AllStreets/Backend` vs `allstreets/backend` — частично
решается нормализацией), нет приоритета/уверенности, не масштабируется на «факты», а не только репозитории.

Фаза 2 — первоклассная **курируемая** память чата:

```
chat_memory(
  chat_id, kind{project|fact|preference}, key, value,
  confidence, hits, last_used_at, expires_at
)
```

- классификатор читает память (а не задачи напрямую);
- **rules-based кураторы** (job): dedupe/merge по схожести, TTL/инвалидация, decay confidence;
- **опционально LLM-агент курации** (периодический ревью: мерж/чистка) — но **не в горячем пути** оркестратора,
  чтобы не добавлять LLM-отказ и латентность;
- Фаза 3 (только если упрёмся в объём/качество retrieval): **pgvector** (расширение Postgres) + эмбеддинги записей,
  retrieval top-k.

Отдельно (найдено при аудите тестов): таблица сырых сообщений `agent_chat_messages` **растёт бесконечно** — окно
(`WINDOW_SIZE=30`) ограничивает только чтение в контекст, но не хранение. Нужен retention/TTL и на сырые сообщения
(Фаза 2).

Осознанно **не тащим внешний memory-сервис** (mem0/Zep/Qdrant) сейчас: мы только что стабилизировали надёжность
(зависания/таймауты), новый сетевой сервис в горячем пути = новый класс отказов. Postgres (+pgvector позже) покрывает
потребность без новой инфраструктуры.
