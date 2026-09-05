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
