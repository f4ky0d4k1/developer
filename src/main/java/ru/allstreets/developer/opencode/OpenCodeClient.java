package ru.allstreets.developer.opencode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Оркестратор запуска OpenCode-агента поверх {@link OpenCodeApi}.
 * <p>
 * Принцип: ни один HTTP-вызов не длится дольше секунд — долгая работа агента
 * выполняется в sidecar асинхронно ({@code prompt_async}), а мы наблюдаем её
 * повторяемыми короткими {@code GET}-опросами. Состояние прогона персистентно
 * ({@link OpenCodeRunEntity}), поэтому:
 * <ul>
 *   <li>обрыв TCP не теряет работу — агент продолжает работать, следующий опрос
 *       подхватывает результат;</li>
 *   <li>рестарт Spring не теряет работу — вместо отправки промпта заново продолжаем
 *       опрос того же {@code messageId} (resume);</li>
 *   <li>retry безопасен — повторяются только идемпотентные {@code GET}-опросы
 *       (внутри {@link OpenCodeApi}); неидемпотентный {@code prompt_async} не
 *       повторяется никогда без проверки через {@code getMessage}.</li>
 * </ul>
 * Публичный контракт {@code runAgent(...)} и {@link OpenCodeResult} сохранён — узлы
 * графа ({@code AnalystNode}, {@code DeveloperNode}, {@code TesterNode},
 * {@code ValidatorService}) не меняются.
 */
@Component
public class OpenCodeClient {

    private static final Logger log = LoggerFactory.getLogger(OpenCodeClient.class);

    private final OpenCodeApi api;
    private final OpenCodeRunRepository runRepo;
    private final TaskProgressRegistry progressRegistry;
    private final ru.allstreets.developer.metrics.TaskMetrics metrics;
    private final int timeoutSeconds;
    private final int pollIntervalSeconds;
    private final int stallTimeoutSeconds;

    public OpenCodeClient(
            OpenCodeApi api,
            OpenCodeRunRepository runRepo,
            TaskProgressRegistry progressRegistry,
            ru.allstreets.developer.metrics.TaskMetrics metrics,
            @Value("${opencode.timeout-seconds:300}") int timeoutSeconds,
            @Value("${opencode.poll-interval-seconds:5}") int pollIntervalSeconds,
            @Value("${opencode.stall-timeout-seconds:120}") int stallTimeoutSeconds
    ) {
        this.api = api;
        this.runRepo = runRepo;
        this.progressRegistry = progressRegistry;
        this.metrics = metrics;
        this.timeoutSeconds = timeoutSeconds;
        this.pollIntervalSeconds = Math.max(1, pollIntervalSeconds);
        this.stallTimeoutSeconds = Math.max(pollIntervalSeconds, stallTimeoutSeconds);
    }

    public OpenCodeResult runAgent(String agentName, String prompt, String cwd, String taskId) {
        return runAgent(agentName, prompt, cwd, taskId, null);
    }

    public OpenCodeResult runAgent(String agentName, String prompt, String cwd, String taskId, String sessionId) {
        long startNanos = System.nanoTime();
        OpenCodeResult result = null;
        try {
            result = runAgentInternal(agentName, prompt, cwd, taskId, sessionId);
            return result;
        } finally {
            // Метрика длительности прогона — на любой исход (успех/ошибка/исключение = error).
            metrics.runFinished(agentName, result != null ? result.status() : "error",
                    java.time.Duration.ofNanos(System.nanoTime() - startNanos));
        }
    }

    private OpenCodeResult runAgentInternal(String agentName, String prompt, String cwd, String taskId, String sessionId) {
        log.info("Запуск OpenCode агента: {} в {} (промпт: {} символов, taskId={}, session={})",
                agentName, cwd, prompt.length(), taskId, sessionId);

        if (taskId != null) {
            progressRegistry.start(taskId, agentName);
        }

        try {
            // === 1. Health gate (fail fast, ~2с) ===
            OpenCodeApi.HealthInfo health;
            try {
                health = api.health();
            } catch (Exception e) {
                log.error("[OpenCode:{}] sidecar недоступен (health gate): {}", agentName, e.getMessage());
                return fail(taskId, agentName, "OpenCode sidecar недоступен: " + e.getMessage(), null, "sidecar-unavailable");
            }
            if (!health.healthy()) {
                log.error("[OpenCode:{}] sidecar unhealthy (version={})", agentName, health.version());
                return fail(taskId, agentName, "OpenCode sidecar unhealthy (version=" + health.version() + ")", null, "sidecar-unhealthy");
            }

            // === 2. Resume-or-start ===
            OpenCodeRunEntity run = resumeOrStart(agentName, prompt, cwd, taskId, sessionId);
            log.info("[OpenCode:{}] прогон {}: session={}, messageId={}, status={}",
                    agentName, run.getId(), run.getSessionId(), run.getMessageId(), run.getStatus());

            // === 3. Цикл опроса с бюджетом ===
            return poll(agentName, taskId, run);

        } finally {
            if (taskId != null) {
                progressRegistry.markFinished(taskId);
            }
        }
    }

    /**
     * Resume или новый старт. Resume применяется только для crash-recovery (рестарт
     * Spring): если для пары (taskId, agentName) есть незавершённый прогон — не
     * отправляем промпт заново, а продолжаем опрос его {@code messageId}. Явно
     * переданный {@code sessionId} (HITL-возобновление) всегда означает новый промпт
     * в существующей сессии.
     */
    private OpenCodeRunEntity resumeOrStart(String agentName, String prompt, String cwd, String taskId, String sessionId) {
        if (sessionId == null && taskId != null) {
            var existing = runRepo
                    .findByTaskIdAndAgentNameAndStatusInOrderByStartedAtDesc(
                            taskId, agentName, List.of(OpenCodeRunStatus.RUNNING, OpenCodeRunStatus.STARTING))
                    .stream().findFirst().orElse(null);

            if (existing != null) {
                if (!existing.isPromptSent()) {
                    // Промпт мог уйти, но подтверждение потеряно (падение в окне между
                    // INSERT и prompt_async, либо сетевая ошибка на prompt_async). Не повторяем
                    // вслепую: неидемпотентный prompt_async повторяется только после проверки
                    // через GET (принцип из плана).
                    Boolean sent = messageExists(existing);
                    if (Boolean.TRUE.equals(sent)) {
                        log.info("[OpenCode:{}] resume: промпт фактически был отправлен, продолжаю опрос messageId={}",
                                agentName, existing.getMessageId());
                        existing.setPromptSent(true);
                        existing.setStatus(OpenCodeRunStatus.RUNNING);
                        runRepo.save(existing);
                    } else if (Boolean.FALSE.equals(sent)) {
                        log.info("[OpenCode:{}] resume: промпт не был отправлен (404), отправляю (messageId={})",
                                agentName, existing.getMessageId());
                        sendPrompt(existing, agentName, prompt);
                    } else {
                        // Не удалось проверить (сетевая ошибка на getMessage) — оставляем
                        // STARTING; цикл опроса сам выяснит, существует ли сообщение.
                        log.warn("[OpenCode:{}] resume: не удалось проверить промпт через getMessage, продолжаю опрос messageId={}",
                                agentName, existing.getMessageId());
                    }
                } else {
                    log.info("[OpenCode:{}] resume: продолжаю опрос существующего прогона messageId={}",
                            agentName, existing.getMessageId());
                }
                return existing;
            }
        }

        // Новый прогон
        String sid = sessionId != null ? sessionId : api.createSession(cwd);
        // sidecar требует messageID в формате "msg_..." (как ses_/msg_ у нативных id).
        String messageId = "msg_" + UUID.randomUUID();
        OpenCodeRunEntity run = new OpenCodeRunEntity(
                taskId, agentName, sid, messageId, cwd, OpenCodeRunStatus.STARTING);
        runRepo.save(run); // INSERT до prompt_async — не теряем messageId при падении
        sendPrompt(run, agentName, prompt);
        return run;
    }

    private void sendPrompt(OpenCodeRunEntity run, String agentName, String prompt) {
        try {
            api.promptAsync(run.getSessionId(), run.getCwd(), run.getMessageId(), agentName, prompt);
        } catch (OpenCodeApi.OpenCodeApiException e) {
            String ref = e.errorRef();
            log.error("[OpenCode:{}] prompt_async отклонён (HTTP {}, ref={}): {}", agentName, e.status(), ref, e.getMessage());
            throw new RuntimeException("OpenCode prompt_async error (ref=" + ref + "): " + e.getMessage(), e);
        }
        run.setPromptSent(true);
        run.setStatus(OpenCodeRunStatus.RUNNING);
        runRepo.save(run);
    }

    /**
     * Существует ли сообщение с нашим {@code messageId}. Трёхзначный результат:
     * {@code TRUE} — создано (промпт был отправлен), {@code FALSE} — 404 (не отправлен),
     * {@code null} — проверить не удалось (сетевая ошибка/5xx).
     */
    private Boolean messageExists(OpenCodeRunEntity run) {
        try {
            return api.getMessage(run.getSessionId(), run.getCwd(), run.getMessageId()) != null;
        } catch (OpenCodeApi.OpenCodeApiException | RestClientException e) {
            return null;
        }
    }

    private OpenCodeResult poll(String agentName, String taskId, OpenCodeRunEntity run) {
        String sessionId = run.getSessionId();
        String messageId = run.getMessageId();
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        long lastProgressAt = System.currentTimeMillis();
        String prevText = "";
        // Прогресс за текущий прогон: сколько tool-вызовов и шагов уже записали (дельта опроса).
        int recordedToolCalls = 0;
        java.util.Set<String> recordedSteps = new java.util.HashSet<>();
        long pollStartNanos = System.nanoTime();
        boolean ttfbRecorded = false;

        OpenCodeApi.EventSource events = openEventWake(run.getCwd());
        try {
            while (true) {
                long now = System.currentTimeMillis();
                if (now > deadline) {
                    return abortAndFail(run, agentName, taskId, prevText,
                            "Таймаут OpenCode (" + timeoutSeconds + "с) для агента: " + agentName, "timeout");
                }

                List<OpenCodeApi.MessageEnvelope> messages;
                try {
                    messages = collectRunMessages(sessionId, run.getCwd(), messageId);
                } catch (OpenCodeApi.OpenCodeApiException e) {
                    // 5xx от sidecar — не retriable (п.1.2 плана); фиксируем провал прогона.
                    log.error("[OpenCode:{}] listMessages HTTP {} (ref={}): {}",
                            agentName, e.status(), e.errorRef(), e.getMessage());
                    run.setStatus(OpenCodeRunStatus.FAILED);
                    run.setError(e.getMessage());
                    run.setLastPolledAt(Instant.now());
                    runRepo.save(run);
                    return fail(taskId, agentName, e.getMessage(), sessionId, "list-messages-error");
                } catch (ResourceAccessException e) {
                    // Транзиентная сетевая ошибка: агент продолжает работать в sidecar,
                    // не прерываем опрос, ждём до конца бюджета.
                    log.warn("[OpenCode:{}] сетевая ошибка опроса, жду следующего цикла: {}", agentName, e.getMessage());
                    if (taskId != null) {
                        progressRegistry.recordError(taskId, "network: " + e.getMessage());
                    }
                    sleep();
                    continue;
                } catch (RestClientException e) {
                    // Ошибка чтения тела ответа (read timeout/reset при большом JSON) — не 5xx и не
                    // сетевой отказ соединения, а сбой извлечения: «Error while extracting response for
                    // type [java.lang.String] ... application/json». GET идемпотентен — продолжаем опрос,
                    // а не валим задачу (инцидент: developer падал сразу после старта).
                    log.warn("[OpenCode:{}] ошибка чтения ответа опроса ({}), жду следующего цикла: {}",
                            agentName, e.getClass().getSimpleName(), e.getMessage());
                    if (taskId != null) {
                        progressRegistry.recordError(taskId, "read: " + e.getMessage());
                    }
                    sleep();
                    continue;
                }

                run.setLastPolledAt(Instant.now());

                // Ответы агента. OpenCode создаёт ОТДЕЛЬНОЕ assistant-сообщение на каждый шаг,
                // и все они имеют один parentID == нашему messageId. Шаг с finish=tool-calls —
                // промежуточный (агент продолжит после выполнения инструментов), его нельзя
                // принимать за результат: именно на нём мы останавливались и получали пустой или
                // вступительный текст вместо финального решения (инциденты 3c7b33db, fab06fb0).
                List<OpenCodeApi.MessageEnvelope> replies = messages.stream()
                        .filter(m -> m.info() != null && messageId.equals(m.info().parentID()))
                        .filter(OpenCodeApi.MessageEnvelope::isAssistant)
                        .sorted(Comparator.comparingLong((OpenCodeApi.MessageEnvelope m) ->
                                m.info().time() != null ? m.info().time().created() : Long.MAX_VALUE))
                        .toList();

                OpenCodeApi.MessageEnvelope errored = replies.stream()
                        .filter(OpenCodeApi.MessageEnvelope::hasError).findFirst().orElse(null);
                if (errored != null) {
                    String err = extractError(errored);
                    log.error("[OpenCode:{}] агент завершился с ошибкой: {}", agentName, err);
                    run.setStatus(OpenCodeRunStatus.FAILED);
                    run.setError(err);
                    runRepo.save(run);
                    return fail(taskId, agentName, err, sessionId, "agent-error");
                }

                // Прогресс steps/tool_calls/tokens (дельта, чтобы не дублировать при повторных опросах)
                // + метрики (агрегаты Prometheus — без taskId в тегах).
                List<OpenCodeApi.Part> parts = replies.stream()
                        .flatMap(r -> r.parts() == null
                                ? java.util.stream.Stream.<OpenCodeApi.Part>empty()
                                : r.parts().stream())
                        .toList();
                long toolTotal = parts.stream().filter(p -> "tool".equals(p.type())).count();
                if (toolTotal > recordedToolCalls) {
                    List<OpenCodeApi.Part> newTools = parts.stream()
                            .filter(p -> "tool".equals(p.type())).skip(recordedToolCalls).toList();
                    for (OpenCodeApi.Part p : newTools) {
                        String tool = p.tool() != null && !p.tool().isBlank() ? p.tool() : "tool";
                        metrics.toolCall(agentName, tool);
                        if (taskId != null) {
                            progressRegistry.recordToolCall(taskId, tool);
                        }
                    }
                    recordedToolCalls = (int) toolTotal;
                    lastProgressAt = now;
                }
                for (OpenCodeApi.MessageEnvelope r : replies) {
                    String rid = r.info() != null ? r.info().id() : null;
                    if (r.isCompleted() && rid != null && recordedSteps.add(rid)) {
                        metrics.steps(agentName, 1);
                        OpenCodeApi.Tokens tk = r.info().tokens();
                        if (tk != null) {
                            metrics.tokens(agentName, "input", orZero(tk.input()));
                            metrics.tokens(agentName, "output", orZero(tk.output()));
                            metrics.tokens(agentName, "reasoning", orZero(tk.reasoning()));
                        }
                        if (r.info().cost() != null) {
                            metrics.cost(agentName, api.model(), r.info().cost());
                        }
                        if (taskId != null) {
                            progressRegistry.recordStepFinish(taskId, r.info().totalTokens(),
                                    r.info().cost() != null ? r.info().cost() : 0.0,
                                    r.info().finish() != null ? r.info().finish() : "stop");
                        }
                        lastProgressAt = now;
                    }
                }

                // Текст всех шагов: прогресс (delta) и итоговый вывод.
                String full = replies.stream().map(OpenCodeApi.MessageEnvelope::text)
                        .collect(Collectors.joining());
                if (full.length() > prevText.length()) {
                    String delta = full.substring(prevText.length());
                    if (!ttfbRecorded && !full.isBlank()) {
                        ttfbRecorded = true;
                        metrics.firstResponse(agentName,
                                java.time.Duration.ofNanos(System.nanoTime() - pollStartNanos));
                    }
                    if (taskId != null) {
                        progressRegistry.recordText(taskId, delta);
                    }
                    prevText = full;
                    lastProgressAt = now;
                }

                // Завершение прогона — только финальный шаг (finish != tool-calls).
                OpenCodeApi.MessageEnvelope last = replies.isEmpty() ? null : replies.getLast();
                if (last != null && last.isCompleted()) {
                    if (isFinalFinish(last.info().finish())) {
                        // Агент сам проверил окружение и остановился с «ENV-ERROR: …» — это провал
                        // прогона (нехватка окружения), а не «успех» с текстом ошибки: иначе задача
                        // молча «завершается», хотя сборка/тесты не состоялись.
                        if (isEnvError(full)) {
                            log.error("[OpenCode:{}] агент сообщил о нехватке окружения: {}", agentName, full);
                            run.setStatus(OpenCodeRunStatus.FAILED);
                            run.setError(full);
                            runRepo.save(run);
                            return fail(taskId, agentName, full, sessionId, "env-error");
                        }
                        log.info("Агент {} завершил работу. session={}, шагов={}, текст={} символов, finish={}, parts={}",
                                agentName, sessionId, replies.size(), full.length(),
                                last.info().finish(), last.partTypes());
                        run.setStatus(OpenCodeRunStatus.DONE);
                        run.setOutput(full);
                        runRepo.save(run);
                        return new OpenCodeResult("success", full, null, null, List.of(), null, sessionId);
                    }
                    log.debug("[OpenCode:{}] шаг {} завершён (finish={}) — промежуточный, продолжаю опрос",
                            agentName, last.info().id(), last.info().finish());
                }

                // Детекция зависания: работающий агент держит сессию busy/retry (в т.ч. во время
                // долгих tool-вызовов) — такие long-running задачи не трогаем. Если же sidecar НЕ
                // busy (idle, либо сессии вообще нет в /session/status — busy=null) и прогресса нет
                // уже stallTimeout — агент завис. Раньше busy=null игнорировался, и idle-агент
                // (opencode принял промпт, но assistant-ответ так и не появился) висел до timeout
                // (инцидент 0f9e5fa2).
                // Долгий tool (mvn test и т.п.): sidecar НЕ помечает сессию busy (/session/status
                // отдаёт {}), поэтому ориентируемся на tool-парт — пока он running, работа идёт
                // и stall не поднимаем (иначе холостые ретраи на длинном tool calling).
                boolean toolRunning = parts.stream().anyMatch(OpenCodeClient::isToolRunning);
                Boolean busy = sessionIsBusy(sessionId);
                long nowMs = System.currentTimeMillis();
                if (Boolean.TRUE.equals(busy) || toolRunning) {
                    lastProgressAt = nowMs;
                } else if (nowMs - lastProgressAt > stallTimeoutSeconds * 1000L) {
                    throw abortAndThrow(run, agentName, taskId, prevText,
                            "OpenCode агент завис: сессия idle/неизвестна без прогресса " + stallTimeoutSeconds + "с", "stall");
                }

                events = waitForEventOrFallback(events, pollIntervalSeconds);
            }
        } finally {
            closeQuietly(events);
        }
    }

    /**
     * Собрать сообщения текущего прогона пагинированным обходом: страницы идут от новых к
     * старым, останавливаемся, когда дошли до нашего промпта ({@code info.id == messageId})
     * или страницы закончились. Страница берётся ЦЕЛИКОМ: внутри неё порядок вставки
     * (user-промпт раньше assistant-ответов), и ранний выход по промпту терял ответы.
     * Каждый HTTP-запрос ограничен {@link OpenCodeApi#MESSAGE_PAGE_SIZE}, поэтому payload
     * не растёт с историей сессии (инцидент: Premature end of Content-Length).
     */
    private List<OpenCodeApi.MessageEnvelope> collectRunMessages(String sessionId, String cwd, String messageId) {
        List<OpenCodeApi.MessageEnvelope> all = new ArrayList<>();
        String cursor = null;
        while (true) {
            OpenCodeApi.MessagesPage page = api.listMessagesPage(sessionId, cwd, OpenCodeApi.MESSAGE_PAGE_SIZE, cursor);
            boolean foundPrompt = false;
            for (OpenCodeApi.MessageEnvelope m : page.items()) {
                all.add(m);
                if (messageId.equals(m.info() != null ? m.info().id() : null)) {
                    foundPrompt = true;
                }
            }
            // Нашли наш промпт — берём страницу ЦЕЛИКОМ. Ответы-ассистенты идут в порядке вставки
            // (после user-промпта), и ранний выход по промпту терял их: replies оставался пустым →
            // ни шагов, ни текста → ложный stall и «0 шагов» на коротких сессиях.
            if (foundPrompt) {
                return all;
            }
            if (page.items().isEmpty() || page.nextCursor() == null) {
                return all;
            }
            cursor = page.nextCursor();
        }
    }

    /**
     * Открыть SSE-подписку на события sidecar'а. Возвращает {@code null}, если подписка
     * недоступна (упал sidecar, нет сети) — тогда цикл опроса поллит по таймеру.
     */
    private OpenCodeApi.EventSource openEventWake(String cwd) {
        try {
            return api.openEvents(cwd);
        } catch (Exception e) {
            log.warn("[OpenCode] SSE /event недоступен, поллю по таймеру: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Ожидание следующего события SSE (пробуждение) либо, если SSE нет/оборвался, — обычный
     * таймер. Возвращает источник событий, если он жив, иначе {@code null} (переключаемся на
     * поллинг по таймеру).
     */
    private OpenCodeApi.EventSource waitForEventOrFallback(OpenCodeApi.EventSource events, int pollIntervalSeconds) {
        if (events == null) {
            sleep();
            return null;
        }
        try {
            events.next();
            return events;
        } catch (IOException e) {
            log.warn("[OpenCode] SSE поток оборвался, переключаюсь на поллинг: {}", e.getMessage());
            closeQuietly(events);
            return null;
        }
    }

    private void closeQuietly(OpenCodeApi.EventSource events) {
        if (events == null) {
            return;
        }
        try {
            events.close();
        } catch (Exception ignored) {
            // ignore
        }
    }

    /**
     * Финальный ли шаг. {@code finish=tool-calls} означает, что агент остановился ради
     * вызова инструментов и продолжит работу следующим шагом — такой ответ нельзя считать
     * завершением прогона (иначе берём пустой/вступительный текст промежуточного шага).
     * {@code finish=null} считаем финальным (некоторые провайдеры его не отдают).
     */
    private static boolean isFinalFinish(String finish) {
        return !"tool-calls".equalsIgnoreCase(finish);
    }

    /**
     * Агент остановился с сигналом нехватки окружения: финальный вывод начинается с
     * {@code ENV-ERROR} (см. секцию «ПРОВЕРКА ОКРУЖЕНИЯ» в промптах агентов).
     */
    private static boolean isEnvError(String text) {
        return text != null && text.stripLeading().toUpperCase(java.util.Locale.ROOT).startsWith("ENV-ERROR");
    }

    /**
     * Tool-парт ещё выполняется ({@code state.status == running}): агент занят tool-вызовом
     * (в т.ч. долгим {@code mvn test}) — это прогресс, а не зависание.
     */
    private static boolean isToolRunning(OpenCodeApi.Part p) {
        return "tool".equals(p.type())
                && p.state() != null
                && "running".equalsIgnoreCase(p.state().status());
    }

    /**
     * Статус сессии: {@code TRUE} — busy/retry (работает), {@code FALSE} — явно idle,
     * {@code null} — неизвестно (ошибка/сессия отсутствует). При {@code null} не считаем
     * простой зависанием — консервативно, чтобы не убить рабочую задачу.
     */
    private Boolean sessionIsBusy(String sessionId) {
        try {
            var status = api.sessionStatus(sessionId);
            if (status == null) {
                return null;
            }
            return status.isBusy();
        } catch (Exception e) {
            log.debug("[OpenCode] статус сессии {} недоступен: {}", sessionId, e.getMessage());
            return null;
        }
    }

    /**
     * Прервать сессию и сохранить частичный вывод агента (иначе теряем работу без следа).
     */
    private void abortSession(OpenCodeRunEntity run, String agentName, String partialText, String message) {
        log.warn("[OpenCode:{}] {} — abort сессии {} (сохранён частичный вывод: {} символов)",
                agentName, message, run.getSessionId(), partialText.length());
        try {
            api.abort(run.getSessionId(), run.getCwd());
        } catch (Exception e) {
            log.warn("[OpenCode:{}] не удалось abort сессии {}: {}", agentName, run.getSessionId(), e.getMessage());
        }
        run.setStatus(OpenCodeRunStatus.ABORTED);
        run.setOutput(partialText);
        run.setError(message);
        run.setLastPolledAt(Instant.now());
        runRepo.save(run);
    }

    private OpenCodeResult abortAndFail(OpenCodeRunEntity run, String agentName, String taskId,
                                        String partialText, String message, String reason) {
        abortSession(run, agentName, partialText, message);
        return fail(taskId, agentName, message, run.getSessionId(), reason);
    }

    /**
     * Прервать зависшую сессию, сохранить частичный вывод и выбросить транзиентную
     * ошибку ({@link OpenCodeTransientException}) — граф ретраит узел: новая сессия +
     * повторная отправка промпта (старая сессия помечена ABORTED, повтор безопасен).
     */
    private OpenCodeTransientException abortAndThrow(OpenCodeRunEntity run, String agentName, String taskId,
                                                     String partialText, String message, String reason) {
        abortSession(run, agentName, partialText, message);
        recordFailure(taskId, agentName, message, reason);
        return new OpenCodeTransientException("[" + agentName + "] " + message);
    }

    private String extractError(OpenCodeApi.MessageEnvelope env) {
        var err = env.info().error();
        if (err == null) {
            return "unknown agent error";
        }
        String message = err.data() != null ? err.data().message() : null;
        String ref = err.data() != null ? err.data().ref() : null;
        return (message != null ? message : err.name()) + (ref != null ? " (ref=" + ref + ")" : "");
    }

    private OpenCodeResult fail(String taskId, String agentName, String message, String sessionId) {
        return fail(taskId, agentName, message, sessionId, "error");
    }

    private void recordFailure(String taskId, String agentName, String message, String reason) {
        if (taskId != null) {
            progressRegistry.recordError(taskId, message);
        }
        metrics.error(agentName, reason);
        log.info("Агент {} завершился с ошибкой: {}", agentName, message);
    }

    private OpenCodeResult fail(String taskId, String agentName, String message, String sessionId, String reason) {
        recordFailure(taskId, agentName, message, reason);
        return new OpenCodeResult("error", "", null, null, List.of(), message, sessionId);
    }

    private static long orZero(Integer v) {
        return v != null ? v : 0;
    }

    private void sleep() {
        try {
            Thread.sleep(pollIntervalSeconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public record OpenCodeResult(
            String status,
            String output,
            String diff,
            String commitHash,
            List<String> files,
            String error,
            String sessionId
    ) {
    }
}
