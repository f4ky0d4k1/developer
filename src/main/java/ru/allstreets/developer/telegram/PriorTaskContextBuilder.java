package ru.allstreets.developer.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.allstreets.developer.checkpoint.CheckpointService;
import ru.allstreets.developer.checkpoint.TaskEntity;
import ru.allstreets.developer.checkpoint.TaskRepository;
import ru.allstreets.developer.state.TaskState;

/**
 * Собирает контекст предыдущей задачи для повторного запуска «с нуля» (когда чекпоинта нет).
 * Оркестратор сам достаёт по чату историю и кладёт компактный блок в контекст новой задачи,
 * чтобы аналитик не начинал с чистого листа и не создавал дубль Tracker-задачи.
 * <p>
 * Не хардкодит решения: просто переносит факты (описание прошлой задачи, её Tracker-issue,
 * недавнюю переписку чата) — интерпретирует их агент.
 */
@Component
public class PriorTaskContextBuilder {

    private static final Logger log = LoggerFactory.getLogger(PriorTaskContextBuilder.class);

    private static final int MAX_DESCRIPTION = 1500;
    private static final int MAX_HISTORY = 4000;

    private final TaskRepository taskRepo;
    private final CheckpointService checkpointService;
    private final ChatMemoryService chatMemory;

    public PriorTaskContextBuilder(TaskRepository taskRepo,
                                   CheckpointService checkpointService,
                                   ChatMemoryService chatMemory) {
        this.taskRepo = taskRepo;
        this.checkpointService = checkpointService;
        this.chatMemory = chatMemory;
    }

    /**
     * Компактный блок «контекст предыдущей задачи» или пустая строка, если ничего не нашлось.
     */
    public String build(String priorTaskId) {
        if (priorTaskId == null || priorTaskId.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        TaskEntity task = taskRepo.findById(priorTaskId).orElse(null);

        if (task != null) {
            sb.append("Предыдущая задача ").append(shortId(priorTaskId))
                    .append(" [").append(task.getStatus()).append("]");
            if (task.getTitle() != null && !task.getTitle().isBlank()) {
                sb.append(": ").append(task.getTitle());
            }
            sb.append("\n");
            if (task.getDescription() != null && !task.getDescription().isBlank()) {
                sb.append("Исходное описание: ")
                        .append(truncate(task.getDescription(), MAX_DESCRIPTION)).append("\n");
            }
        } else {
            sb.append("Предыдущая задача ").append(shortId(priorTaskId)).append(" (нет в БД)\n");
        }

        // Tracker-issue сохраняется в state чекпоинта (пока он есть — у FAILED мы его не чистим).
        try {
            var ctx = checkpointService.restoreCheckpoint(priorTaskId);
            if (ctx != null) {
                String tracker = ctx.get(TaskState.TRACKER_ISSUE);
                if (tracker != null && !tracker.isBlank()) {
                    sb.append("Tracker-задача предыдущего прогона: ").append(tracker)
                            .append(" — используй её, НЕ создавай новую.\n");
                }
            }
        } catch (Exception e) {
            log.warn("PriorTaskContextBuilder: не удалось восстановить state задачи {}: {}",
                    shortId(priorTaskId), e.getMessage());
        }

        Long chatId = task != null ? task.getNotifyChatId() : null;
        if (chatId != null) {
            String history = chatMemory.getHistoryText(chatId);
            if (history != null && !history.isBlank()) {
                sb.append("Недавняя переписка чата:\n").append(truncate(history, MAX_HISTORY)).append("\n");
            }
        }

        return sb.toString();
    }

    private static String shortId(String id) {
        return id != null && id.length() > 8 ? id.substring(0, 8) : id;
    }

    private static String truncate(String text, int max) {
        return text.length() > max ? text.substring(0, max) + "..." : text;
    }
}
