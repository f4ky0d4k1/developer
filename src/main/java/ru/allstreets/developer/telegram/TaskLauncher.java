package ru.allstreets.developer.telegram;

import io.github.asekka.springai.agents.core.AgentContext;
import io.github.asekka.springai.agents.core.AgentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import ru.allstreets.developer.checkpoint.CheckpointService;
import ru.allstreets.developer.config.AgentGraphRunner;
import ru.allstreets.developer.humanloop.HumanInputRegistry;
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
    private final ThreadPoolExecutor executor;
    private final ChatClient fallbackChatClient;

    // taskId → running future (для interrupt)
    private final Map<String, Future<?>> runningTasks = new ConcurrentHashMap<>();

    public TaskLauncher(AgentGraphRunner graphRunner, TelegramGateway telegram,
                        ActiveTaskRegistry taskRegistry,
                        HumanInputRegistry humanInputRegistry,
                        CheckpointService checkpointService,
                        @Qualifier("taskExecutor") ThreadPoolExecutor executor,
                        @Qualifier("fallbackChatClient") ChatClient fallbackChatClient) {
        this.graphRunner = graphRunner;
        this.telegram = telegram;
        this.taskRegistry = taskRegistry;
        this.humanInputRegistry = humanInputRegistry;
        this.checkpointService = checkpointService;
        this.executor = executor;
        this.fallbackChatClient = fallbackChatClient;
    }

    public void launch(String taskDescription, long chatId) {
        String taskId = UUID.randomUUID().toString();
        String title = generateTitle(taskDescription);

        String startMsg = "Задача принята. ID: " + taskId.substring(0, 8)
                + (title != null ? "\n📋 " + title : "")
                + "\nЗапускаю агентов...";
        telegram.sendMessage(chatId, startMsg, taskId);

        try {
            Future<?> future = executor.submit(() -> runTask(taskId, taskDescription, chatId, title));
            runningTasks.put(taskId, future);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            log.error("TaskLauncher: задача {} отклонена (backpressure): {}", taskId, e.getMessage());
            String rejectMsg = "⏳ Система перегружена — слишком много параллельных задач. Попробуйте позже.";
            telegram.sendMessage(chatId, rejectMsg, taskId);
        }
    }

    private String generateTitle(String taskDescription) {
        try {
            String prompt = """
                    Сгенерируй краткое название задачи (до 80 символов) для отображения в списке задач.
                    Только название, без пояснений, кавычек и точки в конце.
                    
                    Описание задачи:
                    %s
                    """.formatted(taskDescription.length() > 500 ? taskDescription.substring(0, 500) : taskDescription);
            String title = fallbackChatClient.prompt().user(prompt).call().content();
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
     * Прервать running задачу, сохранить checkpoint, передать работу аналитику
     * с дополненным контекстом (новое сообщение пользователя).
     */
    public void interruptAndReroute(String taskId, long chatId, String newContext) {
        Future<?> future = runningTasks.get(taskId);
        if (future != null && !future.isDone()) {
            log.info("TaskLauncher: interrupt задачи {} для reroute", taskId);
            future.cancel(true);
            runningTasks.remove(taskId);

            // Отмена pending HITL вопросов
            humanInputRegistry.cancel(taskId);

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
                    " прервана. Передаю аналитику с дополненным контекстом...");

            // Запуск новой задачи с дополненным контекстом
            String augmentedDesc = "Контекст от пользователя (дополнение к предыдущей задаче):\n" + newContext;
            launch(augmentedDesc, chatId);
        } else {
            log.debug("TaskLauncher: задача {} не running, interrupt не нужен", taskId);
        }
    }

    private void runTask(String taskId, String taskDescription, long chatId, String title) {
        try {
            var ctx = AgentContext.of(taskDescription)
                    .with(TaskState.TASK_ID, taskId)
                    .with(TaskState.TG_CHAT_ID, String.valueOf(chatId))
                    .with(TaskState.REWORK_COUNT, 0);

            taskRegistry.register(chatId, taskId, taskDescription, title);

            AgentResult result = graphRunner.run(ctx);

            String resultMsg;
            if (!result.hasError()) {
                String resultText = result.text() != null ? result.text() : "";
                if (resultText.startsWith("http")) {
                    resultMsg = "✅ Задача " + taskId.substring(0, 8) + " завершена. PR: " + resultText;
                } else if (resultText.isBlank() || "Готово".equals(resultText)) {
                    resultMsg = "✅ Задача " + taskId.substring(0, 8) + " завершена.";
                } else {
                    // Analysis-only tasks: AnalystNode already sent the full analysis to user.
                    // Don't duplicate the full text — just send a short confirmation.
                    resultMsg = "✅ Задача " + taskId.substring(0, 8) + " завершена.";
                }
                taskRegistry.markCompleted(taskId);
            } else {
                resultMsg = "❌ Задача " + taskId.substring(0, 8) + " не завершена: " + result.error();
                taskRegistry.markFailed(taskId);
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
        } finally {
            runningTasks.remove(taskId);
        }
    }

    public boolean isRunning(String taskId) {
        Future<?> f = runningTasks.get(taskId);
        return f != null && !f.isDone();
    }

    /**
     * Отменить задачу без перезапуска.
     * Прерывает running future, отменяет HITL, освобождает ресурсы.
     */
    public void cancel(String taskId) {
        Future<?> future = runningTasks.get(taskId);
        if (future != null && !future.isDone()) {
            log.info("TaskLauncher: cancel задачи {}", taskId);
            future.cancel(true);
        }
        runningTasks.remove(taskId);
        humanInputRegistry.cancel(taskId);
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

        // Перерегистрируем задачу — обновляем статус на RUNNING
        taskRegistry.register(chatId, taskId, "Restart from checkpoint: " + checkpoint.getNodeName());

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

    private void resumeTask(String taskId, long chatId, String additionalContext) {
        try {
            // graphRunner.resume сам восстанавливает контекст из checkpoint
            // Но если есть additionalContext — нужно восстановить, добавить, и запустить graph
            AgentResult result;
            if (additionalContext != null && !additionalContext.isBlank()) {
                var ctx = checkpointService.restoreCheckpoint(taskId);
                if (ctx == null) {
                    log.error("TaskLauncher: не удалось восстановить контекст для задачи {}", taskId);
                    telegram.sendMessage(chatId, "❌ Не удалось восстановить контекст задачи " + taskId.substring(0, 8), taskId);
                    taskRegistry.markFailed(taskId);
                    return;
                }
                String existingDesc = ctx.messages().isEmpty() ? "" : ctx.messages().getFirst().getText();
                String augmentedDesc = existingDesc + "\n\nДополнение от пользователя при перезапуске:\n" + additionalContext;
                ctx = AgentContext.of(augmentedDesc).withState(ctx.state());
                result = graphRunner.run(ctx);
            } else {
                result = graphRunner.resume(taskId);
            }

            String resultMsg;
            if (result == null) {
                resultMsg = "❌ Не удалось перезапустить задачу " + taskId.substring(0, 8) + " — checkpoint повреждён.";
                taskRegistry.markFailed(taskId);
            } else if (!result.hasError()) {
                resultMsg = "✅ Задача " + taskId.substring(0, 8) + " перезапущена и завершена.";
                taskRegistry.markCompleted(taskId);
            } else {
                resultMsg = "❌ Задача " + taskId.substring(0, 8) + " снова упала: " + result.error();
                taskRegistry.markFailed(taskId);
            }

            telegram.sendMessage(chatId, resultMsg, taskId);

        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted()) {
                log.info("TaskLauncher: restart задачи {} прерван (interrupt)", taskId);
                return;
            }
            log.error("Ошибка restart задачи {}: {}", taskId, e.getMessage(), e);
            telegram.sendMessage(chatId, "❌ Ошибка перезапуска: " + e.getMessage(), taskId);
            taskRegistry.markFailed(taskId);
        } finally {
            runningTasks.remove(taskId);
        }
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
