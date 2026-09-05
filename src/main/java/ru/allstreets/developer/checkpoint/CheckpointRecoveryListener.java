package ru.allstreets.developer.checkpoint;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import ru.allstreets.developer.config.AgentGraphRunner;
import ru.allstreets.developer.telegram.TelegramGateway;

/**
 * Восстановление незавершённых задач при старте приложения.
 * Проверяет checkpoint store на наличие задач со статусом RUNNING
 * и возобновляет их выполнение.
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

            log.info("Восстановление задачи: runId={}, последний узел: {}", runId, lastNode);

            // Уведомляем в ТГ о возобновлении (если chatId доступен)
            try {
                var restoredCtx = checkpointService.restoreCheckpoint(runId);
                if (restoredCtx != null) {
                    String chatId = restoredCtx.get(ru.allstreets.developer.state.TaskState.TG_CHAT_ID);
                    if (chatId != null) {
                        String title = taskRepo.findById(runId)
                                .map(ru.allstreets.developer.checkpoint.TaskEntity::getTitle)
                                .orElse(null);
                        String taskLabel = (title != null && !title.isBlank())
                                ? title + " (" + runId.substring(0, 8) + ")"
                                : runId.substring(0, 8);
                        telegram.sendMessage(Long.parseLong(chatId),
                                "🔄 Приложение перезапущено. Возобновляю задачу: " + taskLabel
                                        + " (узел: " + lastNode + ")");
                    }
                } else {
                    log.warn("Восстановление невозможно для runId={} — checkpoint повреждён или старый формат. Пропуск.", runId);
                    continue;
                }
            } catch (Exception e) {
                log.warn("Не удалось отправить ТГ уведомление о возобновлении: {}", e.getMessage());
            }

            // Возобновляем выполнение графа
            try {
                var result = graphRunner.resume(runId);
                if (result == null) {
                    log.warn("Возобновление задачи {} не удалось — checkpoint повреждён", runId);
                } else if (result.hasError()) {
                    log.error("Возобновление задачи {} завершилось с ошибкой: {}", runId, result.error());
                } else {
                    log.info("Возобновление задачи {} завершено успешно", runId);
                }
            } catch (Exception e) {
                log.error("Ошибка возобновления задачи {}: {}", runId, e.getMessage(), e);
            }
        }
    }
}
