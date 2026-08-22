package ru.allstreets.developer.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import ru.allstreets.developer.checkpoint.TaskChatEntity;
import ru.allstreets.developer.checkpoint.TaskChatRepository;
import ru.allstreets.developer.checkpoint.TaskEntity;
import ru.allstreets.developer.checkpoint.TaskRepository;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Реестр активных задач — DB-backed с in-memory cache.
 * Связывает chatId с множеством taskId (many-to-many).
 * Поддерживает несколько параллельных задач на один чат.
 * notify_chat_id — чат для уведомлений (по умолчанию первый).
 */
@Component
public class ActiveTaskRegistry {

    private static final Logger log = LoggerFactory.getLogger(ActiveTaskRegistry.class);

    private final TaskRepository taskRepo;
    private final TaskChatRepository taskChatRepo;
    private final Map<Long, Set<String>> chatToTasks = new ConcurrentHashMap<>();
    private final Map<String, Long> taskToChat = new ConcurrentHashMap<>();
    private final Map<String, TaskStatus> taskStatuses = new ConcurrentHashMap<>();

    public ActiveTaskRegistry(TaskRepository taskRepo, TaskChatRepository taskChatRepo) {
        this.taskRepo = taskRepo;
        this.taskChatRepo = taskChatRepo;
    }

    @jakarta.annotation.PostConstruct
    void restoreFromDb() {
        var runningTasks = taskRepo.findByStatus("RUNNING");
        for (var task : runningTasks) {
            String taskId = task.getTaskId();
            Long chatId = task.getNotifyChatId();
            if (chatId != null) {
                chatToTasks.computeIfAbsent(chatId, k -> ConcurrentHashMap.newKeySet()).add(taskId);
                taskToChat.put(taskId, chatId);
                taskStatuses.put(taskId, TaskStatus.RUNNING);
            }
            // Восстанавливаем все chat bindings
            var chats = taskChatRepo.findByTaskId(taskId);
            for (var tc : chats) {
                chatToTasks.computeIfAbsent(tc.getChatId(), k -> ConcurrentHashMap.newKeySet()).add(taskId);
            }
        }
        if (!runningTasks.isEmpty()) {
            log.info("TaskRegistry: восстановлено {} RUNNING задач из БД", runningTasks.size());
        }
    }

    public enum TaskStatus {RUNNING, COMPLETED, FAILED}

    @CacheEvict(cacheNames = "activeTasks", key = "#chatId")
    public void register(long chatId, String taskId, String description) {
        register(chatId, taskId, description, null);
    }

    @CacheEvict(cacheNames = "activeTasks", key = "#chatId")
    public void register(long chatId, String taskId, String description, String title) {
        chatToTasks.computeIfAbsent(chatId, k -> ConcurrentHashMap.newKeySet()).add(taskId);
        taskToChat.put(taskId, chatId);
        taskStatuses.put(taskId, TaskStatus.RUNNING);

        taskRepo.save(new TaskEntity(taskId, "RUNNING", description, title, chatId));
        taskChatRepo.save(new TaskChatEntity(taskId, chatId));

        log.info("TaskRegistry: регистрация task={} chat={} title={}", taskId, chatId, title);
    }

    @org.springframework.transaction.annotation.Transactional
    @CacheEvict(cacheNames = {"activeTasks", "taskStatus", "notifyChatId"}, allEntries = true)
    public void markCompleted(String taskId) {
        taskStatuses.put(taskId, TaskStatus.COMPLETED);
        taskRepo.findById(taskId).ifPresent(t -> {
            t.setStatus("COMPLETED");
            taskRepo.save(t);
        });
        log.info("TaskRegistry: task={} → COMPLETED", taskId);
    }

    @org.springframework.transaction.annotation.Transactional
    @CacheEvict(cacheNames = {"activeTasks", "taskStatus", "notifyChatId"}, allEntries = true)
    public void markFailed(String taskId) {
        taskStatuses.put(taskId, TaskStatus.FAILED);
        taskRepo.findById(taskId).ifPresent(t -> {
            t.setStatus("FAILED");
            taskRepo.save(t);
        });
        log.info("TaskRegistry: task={} → FAILED", taskId);
    }

    public Long getChatIdForTask(String taskId) {
        return taskToChat.get(taskId);
    }

    @Cacheable(cacheNames = "activeTasks", key = "#chatId")
    public Map<String, TaskStatus> getActiveTasks(long chatId) {
        var tasks = chatToTasks.get(chatId);
        if (tasks == null) return Map.of();
        var result = new java.util.LinkedHashMap<String, TaskStatus>();
        for (var taskId : tasks) {
            var status = taskStatuses.get(taskId);
            if (status != null) {
                result.put(taskId, status);
            }
        }
        return result;
    }

    @Cacheable(cacheNames = "notifyChatId", key = "#taskId")
    public Long getNotifyChatId(String taskId) {
        return taskRepo.findById(taskId)
                .map(TaskEntity::getNotifyChatId)
                .orElse(null);
    }

    @org.springframework.transaction.annotation.Transactional
    public void unregister(String taskId) {
        Long chatId = taskToChat.remove(taskId);
        if (chatId != null) {
            var tasks = chatToTasks.get(chatId);
            if (tasks != null) tasks.remove(taskId);
        }
        taskStatuses.remove(taskId);
        taskChatRepo.deleteByTaskId(taskId);
        taskRepo.deleteById(taskId);
    }
}
