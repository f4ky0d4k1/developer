package ru.allstreets.developer.telegram;

import io.github.asekka.springai.agents.core.AgentContext;
import io.github.asekka.springai.agents.core.AgentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import ru.allstreets.developer.agents.AgentResponses;
import ru.allstreets.developer.checkpoint.CheckpointService;
import ru.allstreets.developer.checkpoint.TaskLockService;
import ru.allstreets.developer.checkpoint.TaskRepository;
import ru.allstreets.developer.config.AgentGraphRunner;
import ru.allstreets.developer.humanloop.HumanInputRegistry;
import ru.allstreets.developer.opencode.OpenCodeSessionPool;
import ru.allstreets.developer.state.TaskState;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;

@Component
public class TaskLauncher {

    private static final Logger log = LoggerFactory.getLogger(TaskLauncher.class);

    private final AgentGraphRunner graphRunner;
    private final TelegramGateway telegram;
    private final ActiveTaskRegistry taskRegistry;
    private final HumanInputRegistry humanInputRegistry;
    private final CheckpointService checkpointService;
    private final OpenCodeSessionPool sessionPool;
    private final ThreadPoolExecutor executor;
    private final ChatClient fallbackChatClient;
    private final PriorTaskContextBuilder priorTaskContextBuilder;
    private final ru.allstreets.developer.metrics.TaskMetrics metrics;
    private final TaskRepository taskRepo;
    private final TaskLockService taskLockService;
    private final ReplyAnchorRegistry replyAnchors;

    // taskId → running future (для interrupt)
    private final Map<String, Future<?>> runningTasks = new ConcurrentHashMap<>();

    public TaskLauncher(AgentGraphRunner graphRunner, TelegramGateway telegram,
                        ActiveTaskRegistry taskRegistry,
                        HumanInputRegistry humanInputRegistry,
                        CheckpointService checkpointService,
                        OpenCodeSessionPool sessionPool,
                        @Qualifier("taskExecutor") ThreadPoolExecutor executor,
                        @Qualifier("fallbackChatClient") ChatClient fallbackChatClient,
                        PriorTaskContextBuilder priorTaskContextBuilder,
                        ru.allstreets.developer.metrics.TaskMetrics metrics,
                        TaskRepository taskRepo,
                        TaskLockService taskLockService,
                        ReplyAnchorRegistry replyAnchors) {
        this.graphRunner = graphRunner;
        this.telegram = telegram;
        this.taskRegistry = taskRegistry;
        this.humanInputRegistry = humanInputRegistry;
        this.checkpointService = checkpointService;
        this.sessionPool = sessionPool;
        this.executor = executor;
        this.fallbackChatClient = fallbackChatClient;
        this.priorTaskContextBuilder = priorTaskContextBuilder;
        this.metrics = metrics;
        this.taskRepo = taskRepo;
        this.taskLockService = taskLockService;
        this.replyAnchors = replyAnchors;
    }

    /**
     * @param priorTaskId id предыдущего прогона при ретрае через НОВУЮ задачу (чекпоинта нет);
     *                    его описание/Tracker/переписка переносятся в контекст новой задачи.
     */
    public void launch(String taskDescription, long chatId, String targetRepo, String priorTaskId) {
        String taskId = UUID.randomUUID().toString();
        // Привязываем задачу к сообщению-источнику: все её асинхронные сообщения уйдут reply-to.
        replyAnchors.anchorTask(taskId, chatId);
        String title = generateTitle(taskDescription);

        String startMsg = (title != null && !title.isBlank()
                ? "📋 " + title + " (ID: " + taskId.substring(0, 8) + ")"
                : "Задача принята. ID: " + taskId.substring(0, 8))
                + "\nЗапускаю агентов...";
        telegram.sendMessage(chatId, startMsg, taskId);

        String repo = normalizeRepo(targetRepo);
        String priorContext = priorTaskContextBuilder.build(priorTaskId);
        try {
            Future<?> future = executor.submit(() ->
                    runTask(taskId, taskDescription, chatId, title, repo, priorTaskId, priorContext));
            runningTasks.put(taskId, future);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            log.error("TaskLauncher: задача {} отклонена (backpressure): {}", taskId, e.getMessage());
            String rejectMsg = "⏳ Система перегружена — слишком много параллельных задач. Попробуйте позже.";
            telegram.sendMessage(chatId, rejectMsg, taskId);
        }
    }

    /**
     * Нормализация целевого репозитория для дедупликации памяти чата: trim + lowercase
     * (GitHub owner/repo регистронезависим). Пустое → {@code null}.
     */
    public static String normalizeRepo(String repo) {
        if (repo == null) {
            return null;
        }
        String trimmed = repo.trim();
        return trimmed.isEmpty() ? null : trimmed.toLowerCase(java.util.Locale.ROOT);
    }

    private String generateTitle(String taskDescription) {
        try {
            String prompt = """
                    Сгенерируй краткое название задачи (до 80 символов) для отображения в списке задач.
                    Только название, без пояснений, кавычек и точки в конце.
                    
                    Описание задачи:
                    %s
                    """.formatted(taskDescription.length() > 500 ? taskDescription.substring(0, 500) : taskDescription);
            var result = fallbackChatClient.prompt().user(prompt).call().entity(AgentResponses.TaskTitle.class);
            String title = result != null ? result.title() : null;
            if (title != null) {
                title = title.trim().replaceAll("^[\"']+|[\"']+$", "");
                if (title.length() > 80) title = title.substring(0, 80);
            }
            return title;
        } catch (Exception e) {
            log.warn("generateTitle: ошибка LLM: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Прервать running задачу: cancel future, очистить checkpoints, пометить FAILED.
     * Запуск новой задачи делает caller (инструмент launch_task).
     */
    public void interruptRunningTask(String taskId, long chatId) {
        Future<?> future = runningTasks.get(taskId);
        if (future != null && !future.isDone()) {
            log.info("TaskLauncher: interrupt задачи {} для reroute", taskId);
            future.cancel(true);
            runningTasks.remove(taskId);

            // Отмена pending HITL вопросов
            humanInputRegistry.cancel(taskId);

            // Помечаем старую задачу как FAILED
            taskRegistry.markFailed(taskId);

            // Очистка checkpoints старой задачи
            checkpointService.cleanup(taskId);

            // Ждём остановки старой задачи (до 5 секунд)
            boolean stopped = false;
            for (int i = 0; i < 50; i++) {
                if (future.isDone()) {
                    stopped = true;
                    break;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            if (!stopped) {
                log.warn("TaskLauncher: задача {} не остановилась за 5с — запуск новой принудительно", taskId);
            } else {
                log.info("TaskLauncher: задача {} остановлена, запускаю новую", taskId);
            }

            telegram.sendMessage(chatId, "🔄 Задача " + taskId.substring(0, 8) +
                    " прервана. Запускаю новую...");
        } else {
            log.debug("TaskLauncher: задача {} не running, interrupt не нужен", taskId);
            // Если задача HITL-paused — освобождаем ресурсы
            cancel(taskId);
        }
    }

    private void runTask(String taskId, String taskDescription, long chatId, String title, String targetRepo,
                         String priorTaskId, String priorContext) {
        try {
            var ctx = AgentContext.of(taskDescription)
                    .with(TaskState.TASK_ID, taskId)
                    .with(TaskState.TASK_TITLE, title)
                    .with(TaskState.TG_CHAT_ID, String.valueOf(chatId))
                    .with(TaskState.REWORK_COUNT, 0);
            if (targetRepo != null && !targetRepo.isBlank()) {
                ctx = ctx.with(TaskState.TARGET_REPO, targetRepo);
            }
            if (priorContext != null && !priorContext.isBlank()) {
                ctx = ctx.with(TaskState.PRIOR_TASK_ID, priorTaskId)
                        .with(TaskState.PRIOR_CONTEXT, priorContext);
            }

            taskRegistry.register(chatId, taskId, taskDescription, title, targetRepo);

            AgentResult result = graphRunner.run(ctx);

            if (result.isInterrupted() && result.interrupt() != null) {
                // HITL-пауза: граф сохранён, поток освобождён, задача ждёт ответа пользователя.
                // Не помечаем как completed/failed — задача остаётся RUNNING.
                log.info("TaskLauncher: задача {} приостановлена (HITL interrupt: {})",
                        taskId.substring(0, 8), result.interrupt().reason());
                metrics.taskOutcome("awaiting_hitl");
                return;
            }

            String resultMsg;
            if (!result.hasError()) {
                // Ссылку на PR уже отправила пост-валидация («✅ PR создан: …») — здесь не дублируем.
                resultMsg = "✅ Задача " + taskId.substring(0, 8) + " завершена.";
                taskRegistry.markCompleted(taskId);
                metrics.taskOutcome("completed");
            } else {
                resultMsg = "❌ Задача " + taskId.substring(0, 8) + " не завершена: " + result.error();
                taskRegistry.markFailed(taskId);
                metrics.taskOutcome("failed");
            }

            telegram.sendMessage(chatId, resultMsg, taskId);

        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted()) {
                log.info("TaskLauncher: задача {} прервана (interrupt)", taskId);
                return;
            }
            log.error("Ошибка выполнения задачи {}: {}", taskId, e.getMessage(), e);
            String errMsg = "❌ Ошибка: " + e.getMessage();
            telegram.sendMessage(chatId, errMsg, taskId);
            taskRegistry.markFailed(taskId);
            metrics.taskOutcome("failed");
        } finally {
            runningTasks.remove(taskId);
        }
    }

    /**
     * Возобновить задачу после HITL-паузы с ответом пользователя.
     * Граф продолжит с того же узла (AnalystNode), агент прочитает ответ из messages.
     *
     * @param taskId ID задачи
     * @param answer ответ пользователя
     */
    public void resumeWithAnswer(String taskId, String answer) {
        log.info("TaskLauncher: resumeWithAnswer для задачи {}", taskId.substring(0, 8));
        Long chatId = humanInputRegistry.getChatIdForPending(taskId);
        humanInputRegistry.provideAnswer(taskId);

        if (chatId == null) {
            log.warn("TaskLauncher: нет chatId для pending задачи {} — resume невозможен", taskId);
            return;
        }

        try {
            Future<?> future = executor.submit(() -> resumeHitlTask(taskId, chatId, answer));
            runningTasks.put(taskId, future);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            log.error("TaskLauncher: resume задачи {} отклонён (backpressure): {}", taskId, e.getMessage());
            telegram.sendMessage(chatId, "⏳ Система перегружена — попробуйте позже.", taskId);
        }
    }

    public boolean isRunning(String taskId) {
        Future<?> f = runningTasks.get(taskId);
        return f != null && !f.isDone();
    }

    /**
     * Отменить задачу без перезапуска.
     * Прерывает running future, отменяет HITL, освобождает ресурсы.
     * Для HITL-paused задач — читает checkpoint, освобождает OpenCode слот, удаляет checkpoint.
     */
    public void cancel(String taskId) {
        Future<?> future = runningTasks.get(taskId);
        if (future != null && !future.isDone()) {
            log.info("TaskLauncher: cancel задачи {}", taskId);
            future.cancel(true);
        }
        runningTasks.remove(taskId);
        replyAnchors.forgetTask(taskId);
        humanInputRegistry.cancel(taskId);
        checkpointService.cleanup(taskId);
        // Слот задачи НЕ освобождаем: он закреплён за taskId и живёт до CLOSED
        // (TaskLauncher.close / releaseSlot). Иначе семафор утечёт или рассинхронит taskSlots.
    }

    /**
     * Освободить слот задачи (закрытие CLOSED или удаление задачи). Отдельно от {@link #cancel},
     * т.к. реворк отменяет ран, но слот задачи сохраняет (та же задача продолжается).
     */
    public void releaseSlot(String taskId) {
        sessionPool.releaseForTask(taskId);
    }

    /**
     * Дождаться, пока старый ран задачи отпустит advisory-lock (иначе новый run получит
     * «Task already locked»). {@link #cancel} лишь интерраптит поток, а unlock происходит
     * в finally графа — поэтому короткий поллинг до 5 секунд.
     */
    private void awaitLockReleased(String taskId) {
        for (int i = 0; i < 50 && taskLockService.isLocked(taskId); i++) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Закрыть задачу: RUNNING — прервать ран (это отмена), затем статус CLOSED и освобождение
     * слота. worktree задачи чистится ТОЛЬКО здесь (CLOSED) — до закрытия слот неприкосновен,
     * иначе нельзя (guard в пуле). Для COMPLETED/FAILED — просто перевод в CLOSED.
     *
     * @return true — задача закрыта; false — задача не найдена
     */
    public boolean close(String taskId, long chatId) {
        var task = taskRepo.findById(taskId).orElse(null);
        if (task == null) {
            log.warn("TaskLauncher: close — задача {} не найдена", taskId);
            return false;
        }
        if (isRunning(taskId)) {
            log.info("TaskLauncher: close — задача {} RUNNING, отменяю ран", taskId.substring(0, 8));
            telegram.sendMessage(chatId, "🛑 Задача " + taskId.substring(0, 8) + " отменена и закрыта.", taskId);
        }
        cancel(taskId);                        // interrupt + освобождение HITL-слота/чекпоинта
        replyAnchors.forgetTask(taskId);       // anchor reply-to задачи больше не нужен
        taskRegistry.markClosed(taskId);
        sessionPool.releaseForTask(taskId);    // слот задачи освобождается только при CLOSED
        log.info("TaskLauncher: задача {} закрыта (CLOSED), слот освобождён", taskId.substring(0, 8));
        return true;
    }

    /**
     * Вернуть задачу в работу с новыми вводными (например, замечаниями из PR-комментариев):
     * прервать текущий ран (если идёт), добавить вводные к описанию и перезапустить граф
     * С АНАЛИТИКА — он из вводных сам решает, что дописать (тесты/код). Одна и та же задача
     * (тот же taskId) и для RUNNING, и для COMPLETED/FAILED — новых задач не создаём.
     *
     * @return true — задача поставлена в работу; false — задача неизвестна или executor перегружен
     */
    public boolean rework(String taskId, long chatId, String additionalInput) {
        ru.allstreets.developer.checkpoint.TaskEntity task = taskRepo.findById(taskId).orElse(null);
        if (task == null) {
            log.warn("TaskLauncher: rework — задача {} не найдена", taskId);
            return false;
        }
        // Прерываем текущий ран (если идёт) и освобождаем слот/чекпоинт, затем запускаем заново.
        cancel(taskId);
        awaitLockReleased(taskId);

        String description = (task.getDescription() != null && !task.getDescription().isBlank())
                ? task.getDescription() : task.getTitle();
        String merged = (additionalInput == null || additionalInput.isBlank())
                ? description
                : description + "\n\n## Новые вводные (замечания из PR)\n" + additionalInput;

        taskRegistry.markRunning(taskId);
        telegram.sendMessage(chatId, "🔄 Возвращаю задачу " + taskId.substring(0, 8)
                + " в работу (реитерация с аналитика)...", taskId);

        try {
            Future<?> future = executor.submit(() ->
                    runTask(taskId, merged, chatId, task.getTitle(), task.getRepo(), null, null));
            runningTasks.put(taskId, future);
            return true;
        } catch (java.util.concurrent.RejectedExecutionException e) {
            log.error("TaskLauncher: rework задачи {} отклонён (backpressure): {}",
                    taskId.substring(0, 8), e.getMessage());
            telegram.sendMessage(chatId, "⏳ Система перегружена — попробуйте позже.", taskId);
            return false;
        }
    }

    /**
     * Перезапустить упавшую задачу из checkpoint.
     * Восстанавливает контекст, запускает граф с того же taskId.
     * Если additionalContext не null — добавляет его в описание задачи.
     *
     * @return true если перезапуск удался, false если checkpoint не найден
     */
    public boolean restart(String taskId, long chatId, String additionalContext) {
        // Проверяем что задача не running
        if (isRunning(taskId)) {
            log.warn("TaskLauncher: задача {} ещё running — cancel перед restart", taskId);
            cancel(taskId);
        }

        // Проверяем наличие checkpoint
        var checkpoint = checkpointService.getLatestCheckpoint(taskId);
        if (checkpoint == null) {
            log.warn("TaskLauncher: нет checkpoint для задачи {} — restart невозможен", taskId);
            return false;
        }

        log.info("TaskLauncher: restart задачи {} из checkpoint (node={})",
                taskId, checkpoint.getNodeName());
        telegram.sendMessage(chatId, "🔄 Перезапуск задачи " + taskId.substring(0, 8) +
                " из checkpoint (узел: " + checkpoint.getNodeName() + ")...", taskId);

        // Возвращаем задачу в RUNNING, не пересоздавая строку (сохраняем title/repo).
        taskRegistry.markRunning(taskId);

        try {
            Future<?> future = executor.submit(() -> resumeTask(taskId, chatId, additionalContext));
            runningTasks.put(taskId, future);
            return true;
        } catch (java.util.concurrent.RejectedExecutionException e) {
            log.error("TaskLauncher: restart задачи {} отклонён (backpressure): {}", taskId, e.getMessage());
            telegram.sendMessage(chatId, "⏳ Система перегружена — попробуйте позже.", taskId);
            return false;
        }
    }

    /**
     * Возобновить задачу, незавершённую до перезапуска приложения ({@code CheckpointRecoveryListener}).
     * <p>В отличие от «ручного» возобновления через {@link #graphRunner}, идёт через общий
     * {@code taskExecutor} и регистрируется в {@code runningTasks}: старт приложения не блокируется,
     * возобновлённую задачу можно остановить ({@link #cancel}), она попадает в graceful shutdown,
     * а уведомления об успехе/провале отправляет {@link #resumeInternal}.
     *
     * @return {@code true} — возобновление поставлено в очередь; {@code false} — нет чекпоинта или executor перегружен
     */
    public boolean resumeAfterRestart(String taskId, long chatId) {
        if (isRunning(taskId)) {
            log.warn("TaskLauncher: задача {} уже running — recovery не требуется", taskId.substring(0, 8));
            return false;
        }
        if (checkpointService.getLatestCheckpoint(taskId) == null) {
            log.warn("TaskLauncher: нет checkpoint для recovery задачи {}", taskId.substring(0, 8));
            telegram.sendMessage(chatId, "❌ Не удалось возобновить задачу " + taskId.substring(0, 8)
                    + " — checkpoint не найден.", taskId);
            return false;
        }

        taskRegistry.markRunning(taskId);
        telegram.sendMessage(chatId, "🔄 Приложение перезапущено. Возобновляю задачу "
                + taskId.substring(0, 8) + " из checkpoint...", taskId);

        try {
            Future<?> future = executor.submit(() ->
                    resumeInternal(taskId, chatId, new Message[0], "recovery", "Ошибка возобновления: "));
            runningTasks.put(taskId, future);
            return true;
        } catch (java.util.concurrent.RejectedExecutionException e) {
            log.error("TaskLauncher: recovery задачи {} отклонён (backpressure): {}", taskId, e.getMessage());
            telegram.sendMessage(chatId, "⏳ Система перегружена — не удалось возобновить задачу "
                    + taskId.substring(0, 8) + ".", taskId);
            return false;
        }
    }

    private void resumeTask(String taskId, long chatId, String additionalContext) {
        Message[] additional = (additionalContext != null && !additionalContext.isBlank())
                ? new Message[]{new UserMessage("Дополнение от пользователя при перезапуске:\n" + additionalContext)}
                : new Message[0];
        resumeInternal(taskId, chatId, additional, "restart", "Ошибка перезапуска: ");
    }

    private void resumeHitlTask(String taskId, long chatId, String answer) {
        Message[] additional = new Message[]{new UserMessage(answer)};
        resumeInternal(taskId, chatId, additional, "HITL resume", "Ошибка возобновления: ");
    }

    /**
     * Общая логика возобновления графа: используется и при restart из checkpoint,
     * и при resume после HITL-ответа пользователя — отличаются только тем, какое
     * дополнительное сообщение подмешивается в контекст.
     */
    private void resumeInternal(String taskId, long chatId, Message[] additional, String logContext, String errorPrefix) {
        try {
            AgentResult result = graphRunner.resume(taskId, additional);
            handleResumeResult(taskId, chatId, result);
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted()) {
                log.info("TaskLauncher: {} задачи {} прерван (interrupt)", logContext, taskId);
                return;
            }
            log.error("Ошибка {} задачи {}: {}", logContext, taskId, e.getMessage(), e);
            telegram.sendMessage(chatId, "❌ " + errorPrefix + e.getMessage(), taskId);
            taskRegistry.markFailed(taskId);
        } finally {
            runningTasks.remove(taskId);
        }
    }

    private void handleResumeResult(String taskId, long chatId, AgentResult result) {
        if (result == null) {
            telegram.sendMessage(chatId,
                    "❌ Не удалось возобновить задачу " + taskId.substring(0, 8) + " — checkpoint повреждён.",
                    taskId);
            taskRegistry.markFailed(taskId);
            return;
        }

        if (result.isInterrupted() && result.interrupt() != null) {
            // Повторная HITL-пауза — задача снова ждёт ответа
            log.info("TaskLauncher: задача {} снова приостановлена (HITL: {})",
                    taskId.substring(0, 8), result.interrupt().reason());
            return;
        }

        String resultMsg;
        if (!result.hasError()) {
            resultMsg = "✅ Задача " + taskId.substring(0, 8) + " завершена.";
            taskRegistry.markCompleted(taskId);
        } else {
            resultMsg = "❌ Задача " + taskId.substring(0, 8) + " упала: " + result.error();
            taskRegistry.markFailed(taskId);
        }
        telegram.sendMessage(chatId, resultMsg, taskId);
    }

    @jakarta.annotation.PreDestroy
    void shutdown() {
        log.info("TaskLauncher: graceful shutdown — {} running задач", runningTasks.size());
        for (var entry : runningTasks.entrySet()) {
            if (!entry.getValue().isDone()) {
                log.info("TaskLauncher: interrupt задачи {} при shutdown", entry.getKey());
                entry.getValue().cancel(true);
                humanInputRegistry.cancel(entry.getKey());
            }
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }
    }
}
