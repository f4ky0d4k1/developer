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

    public CheckpointRecoveryListener(CheckpointService checkpointService,
                                      AgentGraphRunner graphRunner,
                                      TelegramGateway telegram) {
        this.checkpointService = checkpointService;
        this.graphRunner = graphRunner;
        this.telegram = telegram;
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
                        telegram.sendMessage(Long.parseLong(chatId),
                                "🔄 Приложение перезапущено. Возобновляю задачу с узла: " + lastNode);
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
                graphRunner.resume(runId);
            } catch (Exception e) {
                log.error("Ошибка возобновления задачи {}: {}", runId, e.getMessage(), e);
            }
        }
    }
}
