package ru.allstreets.developer.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import ru.allstreets.developer.checkpoint.TaskChatEntity;
import ru.allstreets.developer.checkpoint.TaskChatRepository;
import ru.allstreets.developer.checkpoint.TaskEntity;
import ru.allstreets.developer.checkpoint.TaskRepository;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Реестр активных задач — единственный источник истины: Postgres.
 * Связывает chatId с множеством taskId (many-to-many через {@link TaskChatEntity}).
 * Поддерживает несколько параллельных задач на один чат.
 * notify_chat_id — чат для уведомлений (по умолчанию первый).
 * <p>
 * Без in-memory кэша: при текущей нагрузке чтение из БД на каждый вызов
 * не является узким местом, а рассинхронизация кэша с БД (что было
 * источником зомби-задач и неверных статусов) устранена как класс проблем.
 */
@Component
public class ActiveTaskRegistry {

    private static final Logger log = LoggerFactory.getLogger(ActiveTaskRegistry.class);

    private final TaskRepository taskRepo;
    private final TaskChatRepository taskChatRepo;

    public ActiveTaskRegistry(TaskRepository taskRepo, TaskChatRepository taskChatRepo) {
        this.taskRepo = taskRepo;
        this.taskChatRepo = taskChatRepo;
    }

    public enum TaskStatus {RUNNING, COMPLETED, FAILED}

    public void register(long chatId, String taskId, String description) {
        register(chatId, taskId, description, null);
    }

    @Transactional
    public void register(long chatId, String taskId, String description, String title) {
        taskRepo.save(new TaskEntity(taskId, "RUNNING", description, title, chatId));
        taskChatRepo.save(new TaskChatEntity(taskId, chatId));
        log.info("TaskRegistry: регистрация task={} chat={} title={}", taskId, chatId, title);
    }

    @Transactional
    public void markCompleted(String taskId) {
        setStatus(taskId, "COMPLETED");
    }

    @Transactional
    public void markFailed(String taskId) {
        setStatus(taskId, "FAILED");
    }

    private void setStatus(String taskId, String status) {
        taskRepo.findById(taskId).ifPresent(t -> {
            t.setStatus(status);
            t.setUpdatedAt(Instant.now());
            taskRepo.save(t);
        });
        log.info("TaskRegistry: task={} → {}", taskId, status);
    }

    public Long getChatIdForTask(String taskId) {
        return taskRepo.findById(taskId).map(TaskEntity::getNotifyChatId).orElse(null);
    }

    public Map<String, TaskStatus> getActiveTasks(long chatId) {
        var bindings = taskChatRepo.findByChatId(chatId);
        if (bindings.isEmpty()) return Map.of();

        var taskIds = bindings.stream().map(TaskChatEntity::getTaskId).toList();
        var result = new LinkedHashMap<String, TaskStatus>();
        for (var task : taskRepo.findAllById(taskIds)) {
            if (task.isDeleted()) continue;
            try {
                result.put(task.getTaskId(), TaskStatus.valueOf(task.getStatus()));
            } catch (IllegalArgumentException e) {
                log.warn("TaskRegistry: неизвестный статус '{}' у task={}, пропуск", task.getStatus(), task.getTaskId());
            }
        }
        return result;
    }

    public Long getNotifyChatId(String taskId) {
        return taskRepo.findById(taskId)
                .map(TaskEntity::getNotifyChatId)
                .orElse(null);
    }

    @Transactional
    public void unregister(String taskId) {
        taskChatRepo.deleteByTaskId(taskId);
        taskRepo.findById(taskId).ifPresent(t -> {
            t.setDeleted(true);
            t.setUpdatedAt(Instant.now());
            taskRepo.save(t);
        });
    }
}
