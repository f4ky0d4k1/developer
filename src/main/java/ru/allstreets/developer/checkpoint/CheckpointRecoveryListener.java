package ru.allstreets.developer.checkpoint;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import ru.allstreets.developer.config.AgentGraphRunner;
import ru.allstreets.developer.state.TaskState;
import ru.allstreets.developer.telegram.TelegramGateway;

/**
 * Восстановление незавершённых задач при старте приложения.
 * Проверяет checkpoint store на наличие задач со статусом RUNNING
 * и возобновляет их выполнение.
 * <p>
 * Ключевые гарантии:
 * <ul>
 *   <li>задача, уже помеченная {@code FAILED}/{@code COMPLETED} в БД, не поднимается заново
 *       (устаревший RUNNING-checkpoint чистится) — иначе отработавшая задача «оживает» на рестарте;</li>
 *   <li>провал возобновления <b>уведомляется в Telegram</b> (раньше только логировался — задача
 *       «тихо умирала»).</li>
 * </ul>
 */
@Component
public class CheckpointRecoveryListener {

    private static final Logger log = LoggerFactory.getLogger(CheckpointRecoveryListener.class);

    private final CheckpointService checkpointService;
    private final AgentGraphRunner graphRunner;
    private final TelegramGateway telegram;
    private final TaskRepository taskRepo;

    public CheckpointRecoveryListener(CheckpointService checkpointService,
                                      AgentGraphRunner graphRunner,
                                      TelegramGateway telegram,
                                      TaskRepository taskRepo) {
        this.checkpointService = checkpointService;
        this.graphRunner = graphRunner;
        this.telegram = telegram;
        this.taskRepo = taskRepo;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverUnfinishedTasks() {
        var unfinished = checkpointService.getUnfinishedCheckpoints();

        if (unfinished.isEmpty()) {
            log.info("Незавершённых задач не найдено.");
            return;
        }

        log.info("Найдено {} незавершённых задач. Восстановление...", unfinished.size());

        for (var checkpoint : unfinished) {
            String runId = checkpoint.getRunId();
            String lastNode = checkpoint.getNodeName();

            // Не поднимаем задачу, которая уже завершилась (устаревший RUNNING-checkpoint).
            String status = taskRepo.findById(runId).map(TaskEntity::getStatus).orElse(null);
            if ("FAILED".equalsIgnoreCase(status) || "COMPLETED".equalsIgnoreCase(status)) {
                log.warn("Пропуск восстановления runId={}: задача уже {} — чищу устаревший checkpoint", runId, status);
                checkpointService.cleanup(runId);
                continue;
            }

            log.info("Восстановление задачи: runId={}, последний узел: {}", runId, lastNode);

            // Уведомляем в ТГ о возобновлении (если chatId доступен)
            Long chatId = null;
            try {
                var restoredCtx = checkpointService.restoreCheckpoint(runId);
                if (restoredCtx == null) {
                    log.warn("Восстановление невозможно для runId={} — checkpoint повреждён или старый формат. Пропуск.", runId);
                    continue;
                }
                String chatIdStr = restoredCtx.get(TaskState.TG_CHAT_ID);
                if (chatIdStr != null) {
                    chatId = Long.parseLong(chatIdStr);
                    String title = taskRepo.findById(runId).map(TaskEntity::getTitle).orElse(null);
                    String taskLabel = (title != null && !title.isBlank())
                            ? title + " (" + runId.substring(0, 8) + ")"
                            : runId.substring(0, 8);
                    telegram.sendMessage(chatId,
                            "🔄 Приложение перезапущено. Возобновляю задачу: " + taskLabel
                                    + " (узел: " + lastNode + ")");
                }
            } catch (Exception e) {
                log.warn("Не удалось отправить ТГ уведомление о возобновлении: {}", e.getMessage());
            }

            // Возобновляем выполнение графа
            try {
                var result = graphRunner.resume(runId);
                if (result == null) {
                    log.warn("Возобновление задачи {} не удалось — checkpoint повреждён", runId);
                    notifyFailure(chatId, runId, "checkpoint повреждён");
                } else if (result.hasError()) {
                    log.error("Возобновление задачи {} завершилось с ошибкой: {}", runId, result.error());
                    notifyFailure(chatId, runId, describeError(result.error()));
                } else {
                    log.info("Возобновление задачи {} завершено успешно", runId);
                }
            } catch (Exception e) {
                log.error("Ошибка возобновления задачи {}: {}", runId, e.getMessage(), e);
                notifyFailure(chatId, runId, e.getMessage());
            }
        }
    }

    private static String describeError(io.github.asekka.springai.agents.core.AgentError error) {
        if (error == null) {
            return "unknown error";
        }
        Throwable cause = error.cause();
        return cause != null && cause.getMessage() != null ? cause.getMessage() : String.valueOf(error);
    }

    /**
     * Уведомить chatId о провале возобновления (иначе задача умирает молча).
     */
    private void notifyFailure(Long chatId, String runId, String reason) {
        if (chatId == null) {
            return;
        }
        try {
            telegram.sendMessage(chatId, "❌ Возобновление задачи " + runId.substring(0, 8)
                    + " не удалось: " + reason);
        } catch (Exception e) {
            log.warn("Не удалось отправить ТГ уведомление об ошибке возобновления: {}", e.getMessage());
        }
    }
}
