package ru.allstreets.developer.opencode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
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
 * {@code TestExecutionService}, {@code PullRequestCreationService}) не меняются.
 */
@Component
public class OpenCodeClient {

    private static final Logger log = LoggerFactory.getLogger(OpenCodeClient.class);

    private final OpenCodeApi api;
    private final OpenCodeRunRepository runRepo;
    private final TaskProgressRegistry progressRegistry;
    private final int timeoutSeconds;
    private final int pollIntervalSeconds;
    private final int stallTimeoutSeconds;

    public OpenCodeClient(
            OpenCodeApi api,
            OpenCodeRunRepository runRepo,
            TaskProgressRegistry progressRegistry,
            @Value("${opencode.timeout-seconds:300}") int timeoutSeconds,
            @Value("${opencode.poll-interval-seconds:5}") int pollIntervalSeconds,
            @Value("${opencode.stall-timeout-seconds:120}") int stallTimeoutSeconds
    ) {
        this.api = api;
        this.runRepo = runRepo;
        this.progressRegistry = progressRegistry;
        this.timeoutSeconds = timeoutSeconds;
        this.pollIntervalSeconds = Math.max(1, pollIntervalSeconds);
        this.stallTimeoutSeconds = Math.max(pollIntervalSeconds, stallTimeoutSeconds);
    }

    public OpenCodeResult runAgent(String agentName, String prompt, String cwd, String taskId) {
        return runAgentInternal(agentName, prompt, cwd, taskId, null);
    }

    public OpenCodeResult runAgent(String agentName, String prompt, String cwd, String taskId, String sessionId) {
        return runAgentInternal(agentName, prompt, cwd, taskId, sessionId);
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
                return fail(taskId, agentName, "OpenCode sidecar недоступен: " + e.getMessage(), null);
            }
            if (!health.healthy()) {
                log.error("[OpenCode:{}] sidecar unhealthy (version={})", agentName, health.version());
                return fail(taskId, agentName, "OpenCode sidecar unhealthy (version=" + health.version() + ")", null);
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

        while (true) {
            long now = System.currentTimeMillis();
            if (now > deadline) {
                return abortAndFail(run, agentName, taskId, prevText,
                        "Таймаут OpenCode (" + timeoutSeconds + "с) для агента: " + agentName);
            }

            List<OpenCodeApi.MessageEnvelope> messages;
            try {
                messages = api.listMessages(sessionId, run.getCwd());
            } catch (OpenCodeApi.OpenCodeApiException e) {
                // 5xx от sidecar — не retriable (п.1.2 плана); фиксируем провал прогона.
                log.error("[OpenCode:{}] listMessages HTTP {} (ref={}): {}",
                        agentName, e.status(), e.errorRef(), e.getMessage());
                run.setStatus(OpenCodeRunStatus.FAILED);
                run.setError(e.getMessage());
                run.setLastPolledAt(Instant.now());
                runRepo.save(run);
                return fail(taskId, agentName, e.getMessage(), sessionId);
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
                return fail(taskId, agentName, err, sessionId);
            }

            // Текст всех шагов: прогресс (delta) и итоговый вывод.
            String full = replies.stream().map(OpenCodeApi.MessageEnvelope::text)
                    .collect(Collectors.joining());
            if (full.length() > prevText.length()) {
                String delta = full.substring(prevText.length());
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
            // долгих tool-вызовов) — такие long-running задачи не трогаем. Если же sidecar
            // явно сообщает idle, а прогресса нет уже stallTimeout — агент завис.
            Boolean busy = sessionIsBusy(sessionId);
            long nowMs = System.currentTimeMillis();
            if (Boolean.TRUE.equals(busy)) {
                lastProgressAt = nowMs;
            } else if (Boolean.FALSE.equals(busy) && nowMs - lastProgressAt > stallTimeoutSeconds * 1000L) {
                return abortAndFail(run, agentName, taskId, prevText,
                        "OpenCode агент завис: сессия idle без прогресса " + stallTimeoutSeconds + "с");
            }

            sleep();
        }
    }

    /**
     * Финальный ли шаг. {@code finish=tool-calls} означает, что агент остановился ради
     * вызова инструментов и продолжит работу следующим шагом — такой ответ нельзя считать
     * завершением прогона (иначе берём пустой/вступительный текст промежуточного шага).
     * {@code finish=null} считаем финальным (некоторые провайдеры его не отдают).
     */
    private static boolean isFinalFinish(String finish) {
        return finish == null || !"tool-calls".equalsIgnoreCase(finish);
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
     * Прервать сессию и вернуть ошибку, сохранив накопленный частичный вывод агента
     * (иначе при таймауте теряем всю работу без следа).
     */
    private OpenCodeResult abortAndFail(OpenCodeRunEntity run, String agentName, String taskId,
                                        String partialText, String message) {
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
        return fail(taskId, agentName, message, run.getSessionId());
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
        if (taskId != null) {
            progressRegistry.recordError(taskId, message);
        }
        log.info("Агент {} завершился с ошибкой: {}", agentName, message);
        return new OpenCodeResult("error", "", null, null, List.of(), message, sessionId);
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
