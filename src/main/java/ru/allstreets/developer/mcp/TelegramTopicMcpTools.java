package ru.allstreets.developer.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import ru.allstreets.developer.checkpoint.TaskEntity;
import ru.allstreets.developer.checkpoint.TaskRepository;
import ru.allstreets.developer.telegram.TelegramTopicService;

import java.util.List;

/**
 * MCP-инструменты forum-тем оркестратора (BACKEND-443, T8).
 * <p>
 * У Telegram Bot API нет метода листинга forum-тем, поэтому источник истины — БД
 * ({@code agent_tasks.thread_id}). Инструменты дают агентам возможность посмотреть
 * известные системе темы чата, детали темы задачи и перепривязать задачу вместе с
 * цепочкой наследников.
 */
@Component
public class TelegramTopicMcpTools {

    private static final Logger log = LoggerFactory.getLogger(TelegramTopicMcpTools.class);

    private static final int MAX_TOPICS = 30;

    private final TelegramTopicService topicService;
    private final TaskRepository taskRepo;

    public TelegramTopicMcpTools(TelegramTopicService topicService, TaskRepository taskRepo) {
        this.topicService = topicService;
        this.taskRepo = taskRepo;
    }

    @Tool(description = "List forum topics known to the orchestrator for a Telegram chat. Telegram Bot API has no " +
            "topic-listing method, so the source of truth is the DB. Returns taskId, thread_id, status and title for " +
            "each task that has a forum topic. Use when the user asks 'какие темы/топики в чате' or wants to map " +
            "topics to tasks.")
    public String getForumTopics(
            @ToolParam(description = "Telegram chat ID") long chatId
    ) {
        List<TaskEntity> tasks = topicService.topicsForChat(chatId);
        if (tasks.isEmpty()) {
            return "No forum topics recorded for chat " + chatId;
        }
        StringBuilder sb = new StringBuilder("Forum topics for chat ").append(chatId).append(":\n");
        tasks.stream().limit(MAX_TOPICS).forEach(t -> sb
                .append("- task ").append(shortId(t.getTaskId()))
                .append(" | thread_id=").append(t.getThreadId())
                .append(" | ").append(t.getStatus())
                .append(t.getTitle() != null && !t.getTitle().isBlank() ? " | " + t.getTitle() : "")
                .append("\n"));
        if (tasks.size() > MAX_TOPICS) {
            sb.append("(+more topics)\n");
        }
        return sb.toString();
    }

    @Tool(description = "Get forum topic info for a task: its thread_id, root_thread_id, parent_task_id, status " +
            "and chat. taskId can be partial (first 8 chars). Use to answer 'в какой теме эта задача' or to inspect " +
            "the retry/rework chain.")
    public String getForumTopicInfo(
            @ToolParam(description = "Task ID (full or first 8 characters)") String taskId
    ) {
        String fullTaskId = resolveTaskId(taskId);
        if (fullTaskId == null) {
            return "Task not found: " + taskId;
        }
        TaskEntity task = taskRepo.findById(fullTaskId).orElse(null);
        if (task == null) {
            return "Task not found in DB: " + fullTaskId;
        }
        if (task.getThreadId() == null) {
            return "Task " + shortId(fullTaskId) + " has no forum topic (fallback / terminal / not created).";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("task_id: ").append(shortId(fullTaskId)).append("\n");
        sb.append("chat_id: ").append(task.getNotifyChatId()).append("\n");
        sb.append("thread_id: ").append(task.getThreadId()).append("\n");
        sb.append("root_thread_id: ").append(task.getRootThreadId()).append("\n");
        sb.append("parent_task_id: ").append(task.getParentTaskId() != null ? shortId(task.getParentTaskId()) : "—")
                .append("\n");
        sb.append("status: ").append(task.getStatus()).append("\n");
        boolean root = task.getParentTaskId() == null;
        sb.append("role: ").append(root ? "root (создаёт тему)" : "heir (наследует тему корня)");
        return sb.toString();
    }

    @Tool(description = "Reassign a task and its whole retry/rework chain to another forum topic, consistently " +
            "updating thread_id/root_thread_id of every descendant. taskId can be partial (first 8 chars). " +
            "threadId is the target message_thread_id. Use when a task ended up in the wrong topic.")
    public String reassignTaskTopic(
            @ToolParam(description = "Task ID (full or first 8 characters)") String taskId,
            @ToolParam(description = "Target forum topic thread_id (message_thread_id)") long threadId
    ) {
        String fullTaskId = resolveTaskId(taskId);
        if (fullTaskId == null) {
            return "Task not found: " + taskId;
        }
        List<String> affected = topicService.reassignTopic(fullTaskId, threadId);
        if (affected.isEmpty()) {
            return "Failed to reassign topic for task " + shortId(fullTaskId) + ".";
        }
        log.info("MCP reassignTaskTopic: task={} → thread={} ({} задач)", shortId(fullTaskId), threadId, affected.size());
        return "Task " + shortId(fullTaskId) + " and " + (affected.size() - 1)
                + " heir(s) reassigned to thread_id=" + threadId + ".";
    }

    private String resolveTaskId(String partial) {
        if (partial == null || partial.isBlank()) return null;
        TaskEntity exact = taskRepo.findById(partial).orElse(null);
        if (exact != null && !exact.isDeleted()) {
            return partial;
        }
        for (TaskEntity t : taskRepo.findByTaskIdStartingWith(partial)) {
            if (!t.isDeleted()) {
                return t.getTaskId();
            }
        }
        return null;
    }

    private static String shortId(String taskId) {
        return taskId != null && taskId.length() > 8 ? taskId.substring(0, 8) : taskId;
    }
}
