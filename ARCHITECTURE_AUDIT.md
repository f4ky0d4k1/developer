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

## 27. Recovery при рестарте: «тихая смерть» и «оживление» FAILED-задач

**Статус: DONE**

Инцидент (13.09): после рестарта `CheckpointRecoveryListener` возобновил задачи `d876615d`/`99864b36` на узле `analyst`,
они снова упали на том же `TARGET_REPO=null` — и **молча** (был только `log.error`, без уведомления в Telegram).

Две причины:

1. **Провал возобновления не уведомлялся.** `CheckpointRecoveryListener` логировал ошибку `resume`, но не писал в ТГ —
   пользователь видел только «возобновляю…», затем задача в `/status` становилась FAILED.
2. **Recovery поднимал уже завершённую задачу.** Штатный провал помечает задачу `FAILED`, но **чекпоинт оставляет
   RUNNING** (намеренно — для ручного `restartTask`). Recovery смотрел только на статус чекпоинта → «оживлял»
   FAILED-задачу.

Исправлено:

- recovery **пропускает** задачи со статусом `FAILED`/`COMPLETED` в БД и чистит их устаревший RUNNING-чекпоинт;
- статус задачи (а не только чекпоинта) стал дискриминатором «возобновлять / нет»: RUNNING → resume (в т.ч. HITL-пауза и
  крэш посреди узла), FAILED/COMPLETED → не трогать;
- возобновление идёт через `TaskLauncher.resumeAfterRestart` (общий `taskExecutor` + регистрация в `runningTasks`), а не
  синхронным `graphRunner.resume` на потоке `ApplicationReadyEvent`: старт не блокируется, возобновлённую задачу можно
  остановить, она попадает в graceful shutdown, а исход уведомляется теми же путями, что и обычный resume.

Тесты: `CheckpointRecoveryListenerTest` (5), `TaskLauncherResumeAfterRestartTest` (2).

## 28. Проектный `opencode.json` репозитория ломает createSession

**Статус: DONE**

Инцидент (13.09, задача `19617033`, репо `iamponamarev/allstreets-spring`):
`OpenCode createSession HTTP 400: bad file reference "{file:./.secrets/yandex-token}"` — в целевом репозитории лежит
**свой** `opencode.json` со ссылкой на несуществующий в слоте секрет. По докам OpenCode конфиги **мёржируются**
(remote → global → custom → **project** → `.opencode` → inline → managed), и проектный файл перекрывает global/
`OPENCODE_CONFIG`,
а `{file:...}` резолвится на этапе загрузки — значит сессия падает до старта агента. Единственный надёжный путь —
**убрать битый проектный конфиг из слота**, не сломав git.

Исправлено (`WorktreeManager`):

- после clone/update в слоте нейтрализуем `opencode.json`/`opencode.jsonc` заглушкой `{"$schema": ...}`: наш конфиг и
  агенты и так применяются сидекаром как global (`/root/.config/opencode/opencode.jsonc` + `/work/.opencode/agents`),
  копировать их в репо не нужно (образ developer содержит только jar и `opencode-config/` не видит);
- **не коммитить**: tracked-файл помечаем `git update-index --skip-worktree` (переживает `git add -A` агента),
  untracked → пишем путь в локальный `.git/info/exclude`;
- перед `fetch/checkout/pull` нейтрализация снимается (`--no-skip-worktree` + `git checkout --`), после — накладывается
  заново.

Тесты: `WorktreeManagerTest` — нейтрализация + чистое рабочее дерево + `git add -A` не стейджит конфиг; reuse слота;
repo без конфига ничего не создаёт.

Отмечено: `DeveloperApplicationTests.contextLoads` (голый `@SpringBootTest` без Testcontainers) требует хост `postgres`
и падает в обычном окружении — предсуществующее, не связано с изменениями (проверено на базовой ревизии).

## 29. Пустой ответ аналитика молча закрывал задачу как «готово»

**Статус: DONE**

Инцидент (13.09, задача `3c7b33db`): аналитик вернул одну вводную фразу «Начну с анализа задачи…», без единого
tool-call (`steps=0, tokens=0`), без JSON-решения. Пайплайн выставил `analysis_done=true`, `requires_development=false`
и закрыл задачу как **✅ завершённую** — код не писался, PR не создавался. Пользователь: «Всмысле завершена?».

Причина — два места, оба «молчаливые»:

1. **Промпт structured-output-разбора** прямо велел: *«nextStep по умолчанию done»* — то есть на ответе без решения
   LLM-экстрактор подставлял `done`, и это принималось за осознанный вердикт «разработка не нужна».
2. **`AnalystNode` при `result == null`** (не распарсили) молча уходил в ветку `else`: `spec = сырой текст`,
   `nextStep = "done"`, `requiresDevelopment = false`, `analysis_done = true`.

Исправлено (контракт аналитика — JSON-блок с `nextStep`, см. `analyst.md`, стр. «обязательно укажи nextStep»):

- **решение разбирается детерминированно**: Jackson (`JsonMapper`, case-insensitive enums, игнор неизвестных полей)
  из JSON-блока финального ответа — **второй LLM (`StructuredOutputHelper`) из пути решения убран**; невалидный блок → null;
- **детерминированный guard**: `hasDecisionBlock(output)` — ответ без `nextStep` не считается решением;
- **retry вместо мгновенного fail**: вывод без решения → агента нуджим в той же сессии
  (`runAgent(..., sessionId)` с `CONTINUE_ANALYSIS_PROMPT`, до `MAX_CONTINUE_ATTEMPTS`); решение так и не получено →
  `AgentResult.failed` + понятное «❌ Аналитик не вернул решение»; ветка `result == null` удалена как недостижимая.

Тесты: `AnalystNodeDecisionGuardTest` (4) — пустой ответ + безуспешный нудж → ошибка; нудж довёл до решения → успех;
решение есть, но разбор упал → ошибка; решение есть и распарсено → успех. (Старый `AnalystNodeFallbackTest` фиксировал
как раз багованное «нет JSON → done».)

## 30. Слот закреплён за задачей; CLOSED/closeTask; restartTask переосмыслен

**Статус: DONE**

Раньше слот OpenCode брался на время узла и освобождался сразу (`finally`). Следствия: слот «утекал» на долгих/
зависших ранах, освободить слот задачи иначе как прервать узел было нельзя, а исчерпание пула = 10 минут ожидания
и падение задачи.

Исправлено:

- **пул 10** (`opencode.slots: 10`); `OpenCodeSessionPool.acquireForTask(taskId, repoUrl, timeout)` закрепляет слот
  за задачей на всё её время жизни (готовит worktree только при первом закреплении), `releaseForTask(taskId)`
  освобождает;
- **статус `CLOSED`** (`ActiveTaskRegistry.markClosed`); `TaskLauncher.close(taskId, chatId)`: RUNNING — прервать ран
  (отмена), затем `CLOSED` + `releaseForTask`. Только CLOSED (или удаление задачи) освобождает слот задачи;
- **MCP `closeTask`** — закрытие из оркестратора; `cancelTask` (удаление) тоже освобождает слот;
- `TaskLauncher.cancel` слот НЕ трогает (реворк отменяет ран, но слот задачи сохраняет — та же задача продолжается);
- **`restartTask` переосмыслен**: больше не «resume из checkpoint», а создание НОВОЙ задачи из контекста старой
  (`priorTaskId`) — только когда контекстно нужно начать заново. Продолжение той же задачи — PR-комментарий/closeTask.

Тесты: `TaskLauncherReworkTest` (rework под тем же taskId; close → CLOSED + releaseForTask; unknown task).

## 31. Исчерпание слотов — HITL с inline-кнопками (а не таймаут-фейл)

**Статус: DONE**

После перехода на «слот до CLOSED» завершённые задачи держат слоты, и пул исчерпывается. Раньше задача ждала
10 минут и падала («Таймаут ожидания слота OpenCode»), без диалога.

Исправлено:

- `SLOT_WAIT_SECONDS=30`: короткое ожидание; нет слота → `SlotUnavailableHandler.askToFreeSlots(...)` — вопрос в чат
  со списком задач-держателей и **inline-кнопками** (`close:<taskId>` на каждую + «Готово»), регистрация pending и
  `interrupt("HITL_SLOT")`;
- `TelegramGateway.sendMessageWithKeyboard/answerCallbackQuery` + `Update.callback_query`; `TelegramBotListener` по
  `close:<taskId>` закрывает задачу (освобождает слот) и подтверждает нажатие;
- валидатор (возвращает String) бросает `SlotUnavailableException`, `PostValidationNode` ловит → тот же HITL.

Поток: задача без слота → вопрос с кнопками → тап → слот свободен → waiting-задача возобновляется с того же узла.

Тесты: `SlotUnavailableHandlerTest`.

## 32. PR-комментарий возвращает в работу исходную задачу (не плодит задачи)

**Статус: DONE**

Инцидент 15.09: комментарии в PR создавали отдельные `pr-<n>-<id>` задачи, каждая заново гоняла аналитика и
Tracker — вместо доработки той же задачи.

Исправлено (`PrCommentMonitor`):

- опрашивает **целевые репозитории задач** (`TaskRepository.findDistinctRepos`) + `monitor-repo` как fallback
  (был баг: смотрел на свой репозиторий `f4ky0d4k1/developer`, а PR жил в целевом);
- новые комментарии PR → находит **исходную задачу по ветке** (`findByGitBranch`) и `TaskLauncher.rework(...)`
  (перезапуск с аналитика с добавленными вводными, тот же taskId);
- persistent-дедуп обработанных комментариев (`agent_processed_pr_comments`) — не повторяется после рестарта;
- валидатор вешает метку `GITHUB_PR_LABEL` (`agent-generated`) при создании PR, иначе монитор PR не видит.

Тесты: `PrCommentMonitorTest`, `TaskLauncherReworkTest`.

## 33. TDD-порядок и «программно-первый»: роутинг аналитика, валидатор без прогона тестов, заголовок задачи

**Статус: DONE**

- **Аналитик роутится по флагам, а не по `nextStep`**: `requiresTesting` → `tester` первым (TDD), иначе
  `requiresDevelopment` → `developer`, иначе → `post_validation`. Защита от ошибки аналитика, который ставит
  `nextStep=developer`, игнорируя `requiresTesting` (инцидент ec0a2004: developer раньше tester).
- **Валидатор не запускает тесты**: прогон в `post_validation` убран (`TestExecutionService`/`ValidationReport`
  удалены). Тесты пишет и гоняет `tester` (TDD red), доводит до зелёного `developer`; валидатор только
  инспектирует worktree и решает PR/reroute (LLM-driven, куда угодно); «PR без URL» — fail, само-ребро
  `post_validation → post_validation` убрано.
- **Заголовок задачи** во всех сообщениях: `📋 <название> (<id8>)` (`TelegramGateway.withTaskHeader` +
  `ActiveTaskRegistry.titleOf`), `taskId` прокинут во все узлы.

Тесты: `AgentFlowConfigRoutingTest`, `PostValidationNodeTest`, `TelegramGatewayTest`, `ConversationFastPromptTest`.

## 34. Pending HITL — источник истины БД, а не память (ответ не теряется при рестарте)

**Статус: DONE**

- **Инцидент 427edb3c**: задача спросила уточнение (`HITL_CLARIFICATION`, pending зарегистрирован в БД), затем
  произошёл рестарт (деплой). Ответ пользователя дошёл и был классифицирован
  (`ConversationAgent [fast]: action=HITL_ANSWER`), но `TaskLauncher.resumeWithAnswer` не смог продолжить:
  `HumanInput: нет pending запроса` / `нет chatId для pending задачи — resume невозможен`. Задача навсегда
  зависла в HITL, бот молчал (и `ZombieTaskMonitor` её не поднимал — для HITL это верно).
- **Причина**: `HumanInputRegistry` читал pending из in-memory `ConcurrentHashMap`, а `PendingInputRepository`
  писался и **никогда не читался** (`getChatIdForPending`/`hasPendingInputs`/`getPendingQuestionsForChat` смотрели
  только в map). Класс-комментарий обещал персистентность, которой не было.
- **Фикс**: реестр стал DB-backed — все чтения идут в `agent_pending_inputs`
  (`findById`, `findByChatId`), запись/удаление через `deleteById`; in-memory map удалён. `ZombieTaskMonitor`
  уже читал репозиторий (`existsById`), поэтому расхождение и было заметно: монитор «видел» pending, а резюм — нет.

Тесты: `HumanInputRegistryTest`.

## 35. Recovery решает по свежему checkpoint, а не по любой RUNNING-строке

**Статус: DONE**

- **Инцидент 427edb3c (продолжение)**: после рестарта задача, стоявшая в HITL-паузе, была поднята
  (`🔄 Приложение перезапущено. Возобновляю задачу из checkpoint...`), аналитик перезапустился **без ответа
  пользователя** и упал: `Analyst produced no decision (missing nextStep)`.
- **Причина**: `CheckpointService.getUnfinishedCheckpoints()` = `repository.findByStatus("RUNNING")` возвращает
  **все** RUNNING-строки, а checkpoint пишется на каждый узел и на каждую паузу. Для runId их было минимум две:
  строка узла (`interruptReason=null`) и HITL-пауза (`HITL_CLARIFICATION`). Recovery наткнулся на строку узла,
  guard `interruptReason != null` не сработал → `resumeAfterRestart`.
- **Фикс**: слушатель итерирует **distinct runId** и принимает решение по `getLatestCheckpoint(runId)` —
  ровно по той строке, из которой resume и продолжит граф (`loadCheckpoint` →
  `findTopByRunIdOrderByCreatedAtDesc`). Решение и возобновляемое состояние теперь согласованы по построению.
- **Побочный эффект для 427edb3c**: неудачный перезапуск перезаписал HITL-checkpoint строкой узла, поэтому
  ответ пользователя задачу уже не поднимет — её нужно перезапустить (`restartTask`).

Тесты: `CheckpointRecoveryListenerTest` (2 новых: устаревшая строка не перекрывает HITL; решение одно на runId).

## 36. У post-validation есть исход «выполнено без PR» (`done`)

**Статус: DONE**

- **Инцидент 427edb3c (продолжение)**: валидатор корректно отработал трекерную задачу (правки только в
  BACKEND-437, кода нет) и написал «PR не требуется, доработка не нужна», но узел упал:
  `Post-validation: решение не определено` — в схеме решения были только `prUrl` / `reroute` / `failed`.
  Законный успех без PR выразить было нечем, поэтому он превращался в FAILED.
- **Фикс**: в `AgentResponses.PostValidationDecision` добавлено поле `done` («что сделано и почему PR не
  требуется»), в промпт валидатора — пункт и поле JSON; `applyDecision` обрабатывает `done` как успешное
  завершение (сообщение в чат, сброс `REROUTE_TARGET`, `completed(true)`). Приоритет: `prUrl` → `done` →
  `reroute` → `failed` → ошибка «решение не определено».
- Это не ослабление требования PR для код-задач: PR по-прежнему обязателен, когда есть изменения кода —
  `done` описывает случай, когда создавать PR не из чего (Трекер, документация).

Тесты: `PostValidationNodeTest` (+1).

## 37. Разделение ответственности за Трекер — общее знание всех агентов

**Статус: DONE**

- Правило: **задачу в Трекере создаёт/связывает только аналитик; результат и доработки в Трекере описывает
  только валидатор; `developer`/`tester` Трекер не трогают**.
- Раньше оно было размазано: у `analyst.md` (создание задачи) и `validator.md` (отчёт), а у `developer.md`/
  `tester.md` — вообще отсутствовало. Агент, не знающий правила, дублирует чужую работу (создаёт вторую задачу,
  пишет свой отчёт) — ровно тот класс ошибок, что чинили в §30–33.
- Теперь блок «РАЗДЕЛЕНИЕ ОТВЕТСТВЕННОСТИ ЗА TRACKER» есть во всех четырёх
  `opencode-config/agents/*.md`; расхождение ловит `AgentPolicyConsistencyTest` (политика, известная одному
  агенту и неизвестная остальным, — это баг, а не мелочь).
- Заодно `validator.md` синхронизирован с §36: в его JSON появилось поле `done`.

Тесты: `AgentPolicyConsistencyTest`.

## 38. Агент `reporter`: весь текст в Трекере — его; запускает аналитик

**Статус: DONE**

- **Пробел** (§37 выявил): «описывает валидатор» не сходилось. Для трекерной задачи (оформить отчёт,
  поправить описание) артефакт работы — сам текст в Трекере, поэтому исполнителем оказывался `developer`
  (холостой прогон без кода, инцидент 427edb3c), а валидатор добавлял сверху третий комментарий-дубль
  (id 787). Валидатору нечего было написать такого, чего не написал исполнитель.
- **Решение — отдельный агент `reporter`** (`opencode-config/agents/reporter.md`): единственный, кто
  пишет/правит содержимое задачи Трекера (отчёты, итоги, описание доработок, правка описания). Кода не
  трогает, задачи не создаёт, оформляет по навыку `tracker-commenting`.
- **Запуск — встроен в флоу, репортёр терминальный**: два пути к репортёру — (1) аналитик коротко
  замыкает трекерную задачу (`nextStep=reporter`, без кода/тестов); (2) валидатор ЗАКОНЧИЛ задачу
  (`post_validation → reporter` при пустом `REROUTE_TARGET` и наличии тикета). При `reroute` задача ещё не
  закончена → итог не пишется, репортёр сработает на финальном проходе. Итог в Трекере ровно один, со
  ссылкой на реально созданный PR. Полный граф: `analyst → tester → developer → validator → reporter`.
- **Валидатор больше не пишет в Трекер** (`validator.md` + промпт узла): что стоит зафиксировать —
  формулирует в `summary`. Так в тикете ровно один автор текста и нет дублей.
- **Ролевая модель** — общий блок во всех пяти `opencode-config/agents/*.md`, расхождение ловит
  `AgentPolicyConsistencyTest`: аналитик (анализ + создаёт задачу + запускает репортёра), репортёр
  (текст в Трекере), тестировщик (тесты), разработчик (код, PR не создаёт), валидатор (проверка, PR,
  в Трекер не пишет).

Тесты: `ReporterNodeTest`, `AgentPolicyConsistencyTest`, `AgentFlowConfigRoutingTest`, `PostValidationNodeTest`.
