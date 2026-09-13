package ru.allstreets.developer.checkpoint;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import ru.allstreets.developer.state.TaskState;
import ru.allstreets.developer.telegram.TaskLauncher;

/**
 * Восстановление незавершённых задач при старте приложения: находит checkpoint'ы со статусом RUNNING
 * и ставит их на возобновление.
 * <p>
 * Ключевые гарантии:
 * <ul>
 *   <li>задача {@code COMPLETED} не поднимается, а её устаревший RUNNING-checkpoint чистится;</li>
 *   <li>задача {@code FAILED} не поднимается (авто-оживания нет), но её checkpoint СОХРАНЯЕТСЯ —
 *       ручной restart возобновляется с упавшего узла, а не с нуля;</li>
 *   <li>HITL-пауза ({@code interruptReason} != null) не возобновляется — задача ждёт ответа пользователя;
 *       checkpoint сохраняется для resume по ответу;</li>
 *   <li>возобновление идёт через {@link TaskLauncher#resumeAfterRestart} — на общем executor, с регистрацией
 *       в {@code runningTasks}: старт не блокируется, задачу можно остановить, а успех/провал уведомляется в Telegram.</li>
 * </ul>
 */
@Component
public class CheckpointRecoveryListener {

    private static final Logger log = LoggerFactory.getLogger(CheckpointRecoveryListener.class);

    private final CheckpointService checkpointService;
    private final TaskLauncher taskLauncher;
    private final TaskRepository taskRepo;

    public CheckpointRecoveryListener(CheckpointService checkpointService,
                                      TaskLauncher taskLauncher,
                                      TaskRepository taskRepo) {
        this.checkpointService = checkpointService;
        this.taskLauncher = taskLauncher;
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
            if ("COMPLETED".equalsIgnoreCase(status)) {
                log.warn("Пропуск восстановления runId={}: задача уже COMPLETED — чищу устаревший checkpoint", runId);
                checkpointService.cleanup(runId);
                continue;
            }
            // Упавшую задачу сами не поднимаем (авто-оживания нет), но чекпоинт СОХРАНЯЕМ:
            // ручной restart должен возобновиться с упавшего узла, а не с нуля (инцидент 0ad6c58f).
            if ("FAILED".equalsIgnoreCase(status)) {
                log.info("Пропуск восстановления runId={}: задача FAILED — чекпоинт оставлен для ручного restart", runId);
                continue;
            }

            // HITL-пауза: checkpoint с interruptReason — задача намеренно ждёт ответа пользователя.
            // Автовосстановление её не поднимает (иначе агент продолжит без ответа); checkpoint НЕ
            // чистим — он нужен для resume по ответу пользователя.
            if (checkpoint.getInterruptReason() != null) {
                log.info("Пропуск восстановления runId={}: HITL-пауза ({}) — ждём ответ пользователя",
                        runId, checkpoint.getInterruptReason());
                continue;
            }

            Long chatId = restoreChatId(runId);
            if (chatId == null) {
                log.warn("Нет chatId для runId={} — возобновление пропущено", runId);
                continue;
            }

            log.info("Возобновление задачи: runId={}, последний узел: {}", runId, lastNode);
            taskLauncher.resumeAfterRestart(runId, chatId);
        }
    }

    /** Достать chatId из восстановленного контекста; null — если checkpoint повреждён или chatId отсутствует. */
    private Long restoreChatId(String runId) {
        try {
            var restoredCtx = checkpointService.restoreCheckpoint(runId);
            if (restoredCtx == null) {
                log.warn("Восстановление невозможно для runId={} — checkpoint повреждён или старый формат", runId);
                return null;
            }
            String chatIdStr = restoredCtx.get(TaskState.TG_CHAT_ID);
            return chatIdStr != null ? Long.parseLong(chatIdStr) : null;
        } catch (Exception e) {
            log.warn("Не удалось восстановить контекст runId={}: {}", runId, e.getMessage());
            return null;
        }
    }
}
