package ru.allstreets.developer.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import ru.allstreets.developer.checkpoint.*;
import ru.allstreets.developer.humanloop.HumanInputRegistry;
import ru.allstreets.developer.opencode.TaskProgress;
import ru.allstreets.developer.opencode.TaskProgressRegistry;
import ru.allstreets.developer.telegram.ActiveTaskRegistry;
import ru.allstreets.developer.telegram.TaskLauncher;

import java.util.List;

/**
 * Локальные task tools для ConversationAgent (оркестратора).
 * Позволяют агенту-собеседнику отвечать на вопросы о ходе работы
 * и удалять/отменять задачи.
 */
@Component
public class TaskMcpTools {

    private static final Logger log = LoggerFactory.getLogger(TaskMcpTools.class);

    private final ActiveTaskRegistry taskRegistry;
    private final TaskRepository taskRepo;
    private final CheckpointRepository checkpointRepo;
    private final CheckpointService checkpointService;
    private final TaskLockService taskLockService;
    private final TaskLauncher taskLauncher;
    private final HumanInputRegistry humanInputRegistry;
    private final TaskProgressRegistry progressRegistry;

    public TaskMcpTools(
            ActiveTaskRegistry taskRegistry,
            TaskRepository taskRepo,
            CheckpointRepository checkpointRepo,
            CheckpointService checkpointService,
            TaskLockService taskLockService,
            TaskLauncher taskLauncher,
            HumanInputRegistry humanInputRegistry,
            TaskProgressRegistry progressRegistry
    ) {
        this.taskRegistry = taskRegistry;
        this.taskRepo = taskRepo;
        this.checkpointRepo = checkpointRepo;
        this.checkpointService = checkpointService;
        this.taskLockService = taskLockService;
        this.taskLauncher = taskLauncher;
        this.humanInputRegistry = humanInputRegistry;
        this.progressRegistry = progressRegistry;
    }

    @Tool(description = "Get detailed status of a task: description, current agent node, git branch, PR number, " +
            "checkpoint history, running status, and LIVE OpenCode progress (steps completed, tokens used, cost, " +
            "tool calls, current tool, recent events, last text output). Use this when user asks 'что по задаче' " +
            "or wants details on a specific task. taskId can be partial (first 8 chars are enough).")
    public String getTaskDetails(
            @ToolParam(description = "Task ID (full or first 8 characters)") String taskId
    ) {
        String fullTaskId = resolveTaskId(taskId);
        if (fullTaskId == null) {
            return "Task not found: " + taskId;
        }

        log.info("MCP getTaskDetails: taskId={}", fullTaskId);
        StringBuilder sb = new StringBuilder();

        // Task info from DB
        TaskEntity task = taskRepo.findById(fullTaskId).orElse(null);
        if (task == null) {
            return "Task not found in DB: " + fullTaskId;
        }

        sb.append("task_id: ").append(fullTaskId, 0, 8).append("\n");
        sb.append("status: ").append(task.getStatus()).append("\n");

        if (task.getTitle() != null && !task.getTitle().isBlank()) {
            sb.append("title: ").append(task.getTitle()).append("\n");
        }

        if (task.getDescription() != null && !task.getDescription().isBlank()) {
            String desc = task.getDescription();
            sb.append("description: ").append(desc.length() > 200 ? desc.substring(0, 200) + "..." : desc).append("\n");
        }

        if (task.getGitBranch() != null) {
            sb.append("git_branch: ").append(task.getGitBranch()).append("\n");
        }
        if (task.getPrNumber() != null) {
            sb.append("pr_number: ").append(task.getPrNumber()).append("\n");
        }

        sb.append("analysis_done: ").append(task.isAnalysisDone()).append("\n");
        sb.append("requires_development: ").append(task.isRequiresDevelopment()).append("\n");
        sb.append("development_done: ").append(task.isDevelopmentDone()).append("\n");
        sb.append("pr_created: ").append(task.isPrCreated()).append("\n");
        sb.append("requires_testing: ").append(task.isRequiresTesting()).append("\n");
        sb.append("tests_written: ").append(task.isTestsWritten()).append("\n");
        sb.append("testing_done: ").append(task.isTestingDone()).append("\n");

        sb.append("created_at: ").append(task.getCreatedAt()).append("\n");
        sb.append("updated_at: ").append(task.getUpdatedAt()).append("\n");

        // Running status
        boolean running = taskLauncher.isRunning(fullTaskId);
        sb.append("is_running: ").append(running).append("\n");

        // Zombie detection: DB says RUNNING but no live thread
        if (!running && "RUNNING".equalsIgnoreCase(task.getStatus())) {
            sb.append("⚠️ ZOMBIE: задача помечена RUNNING в БД, но процесс не активен. Вероятно зависла или упала.\n");
        }

        // Live OpenCode progress
        TaskProgress progress = progressRegistry.get(fullTaskId);
        if (progress != null) {
            sb.append("\n--- Live OpenCode Progress ---\n");
            sb.append(progress.formatSummary());
        } else if (running) {
            sb.append("\n(no live progress data yet)\n");
        }

        // Checkpoint info — last node and history
        List<CheckpointEntity> checkpoints = checkpointRepo.findByRunIdOrderByCreatedAtAsc(fullTaskId);
        if (!checkpoints.isEmpty()) {
            CheckpointEntity last = checkpoints.getLast();
            sb.append("current_node: ").append(last.getNodeName()).append("\n");
            sb.append("checkpoint_status: ").append(last.getStatus()).append("\n");

            sb.append("checkpoint_history:\n");
            for (CheckpointEntity cp : checkpoints) {
                sb.append("  • ").append(cp.getNodeName())
                        .append(" [").append(cp.getStatus()).append("]")
                        .append(" at ").append(cp.getCreatedAt())
                        .append("\n");
            }
        } else {
            sb.append("checkpoint_history: (none)\n");
        }

        // Lock status
        sb.append("locked: ").append(taskLockService.isLocked(fullTaskId)).append("\n");

        // Pending HITL questions
        Long chatId = taskRegistry.getChatIdForTask(fullTaskId);
        if (chatId != null) {
            var pendingQuestions = humanInputRegistry.getPendingQuestionsForChat(chatId);
            String pendingForTask = pendingQuestions.get(fullTaskId);
            if (pendingForTask != null) {
                sb.append("pending_question: ").append(pendingForTask).append("\n");
            }
        }

        return sb.toString();
    }

    @Tool(description = "Cancel and delete a task. Stops running agents, cleans up checkpoints, " +
            "marks task as deleted in DB. Use when user says 'отмени задачу' or 'удали задачу'. " +
            "taskId can be partial (first 8 chars are enough). Returns confirmation message.")
    public String cancelTask(
            @ToolParam(description = "Task ID (full or first 8 characters)") String taskId
    ) {
        String fullTaskId = resolveTaskId(taskId);
        if (fullTaskId == null) {
            return "Task not found: " + taskId;
        }

        log.info("MCP cancelTask: taskId={}", fullTaskId);
        StringBuilder sb = new StringBuilder();

        // Get task info before deletion
        TaskEntity task = taskRepo.findById(fullTaskId).orElse(null);
        String taskDesc = task != null ? task.getDescription() : "N/A";

        // Interrupt if running
        boolean wasRunning = taskLauncher.isRunning(fullTaskId);
        if (wasRunning) {
            taskLauncher.cancel(fullTaskId);
            sb.append("Task interrupted (was running).\n");
        }

        // Cancel pending HITL
        humanInputRegistry.cancel(fullTaskId);

        // Clean up checkpoints
        checkpointService.cleanup(fullTaskId);

        // Unlock
        taskLockService.cleanup(fullTaskId);

        // Unregister from active tasks + delete from DB
        taskRegistry.unregister(fullTaskId);

        sb.append("Task ").append(fullTaskId, 0, 8).append(" cancelled and deleted.\n");
        sb.append("Description: ").append(taskDesc != null && taskDesc.length() > 100
                ? taskDesc.substring(0, 100) + "..." : taskDesc).append("\n");
        sb.append("Was running: ").append(wasRunning);

        log.info("Task {} cancelled via MCP tool", fullTaskId);
        return sb.toString();
    }

    /**
     * Resolve partial taskId (first 8 chars) to full taskId.
     */
    private String resolveTaskId(String partial) {
        if (partial == null || partial.isBlank()) return null;

        // Try exact match (skip deleted)
        TaskEntity exact = taskRepo.findById(partial).orElse(null);
        if (exact != null && !exact.isDeleted()) {
            return partial;
        }

        // Try partial match — find non-deleted task starting with the partial ID
        List<TaskEntity> matches = taskRepo.findByTaskIdStartingWith(partial);
        for (TaskEntity t : matches) {
            if (!t.isDeleted()) {
                return t.getTaskId();
            }
        }

        return null;
    }

    @Tool(description = "Restart a failed task from its checkpoint. Resumes execution from the last saved state " +
            "with the same taskId. Optionally accepts additional context from the user to augment the task. " +
            "Use when user says 'перезапусти задачу' or 'возобнови задачу' or wants to retry a failed task " +
            "without losing previous progress. taskId can be partial (first 8 chars are enough). " +
            "If user says 'перезапусти ПОСЛЕДНЮЮ задачу' without taskId — FIRST call getLastTaskForChat to get the taskId, THEN call restartTask.")
    public String restartTask(
            @ToolParam(description = "Task ID (full or first 8 characters)") String taskId,
            @ToolParam(description = "Additional context from user to augment the task (optional, can be null)") String additionalContext
    ) {
        String fullTaskId = resolveTaskId(taskId);
        if (fullTaskId == null) {
            return "Task not found: " + taskId;
        }

        log.info("MCP restartTask: taskId={}, additionalContext={}", fullTaskId,
                additionalContext != null ? additionalContext.length() + " chars" : "null");

        // Get chatId for this task
        Long chatId = taskRegistry.getChatIdForTask(fullTaskId);
        if (chatId == null) {
            // Try from task-chat repo
            return "Cannot restart: no chatId associated with task " + fullTaskId.substring(0, 8);
        }

        // Check checkpoint exists
        var checkpoint = checkpointService.getLatestCheckpoint(fullTaskId);
        if (checkpoint == null) {
            return "Cannot restart: no checkpoint found for task " + fullTaskId.substring(0, 8) +
                    ". The task may have completed successfully (checkpoints are cleaned up after success).";
        }

        boolean started = taskLauncher.restart(fullTaskId, chatId, additionalContext);
        if (started) {
            return "Task " + fullTaskId.substring(0, 8) + " restarted from checkpoint (node: " +
                    checkpoint.getNodeName() + "). The task will resume with its previous state" +
                    (additionalContext != null ? " plus the new context provided." : ".");
        } else {
            return "Failed to restart task " + fullTaskId.substring(0, 8) + ".";
        }
    }

    @Tool(description = "Get the last task for a Telegram chat. Returns taskId (first 8 chars), status, description, " +
            "and created_at of the most recent task. Use this when user says 'перезапусти последнюю задачу' " +
            "or 'возобнови задачу' without specifying a taskId, or when getActiveTasks returns nothing but " +
            "you need to find a recent failed/completed task.")
    public String getLastTaskForChat(
            @ToolParam(description = "Telegram chat ID") long chatId
    ) {
        log.info("MCP getLastTaskForChat: chatId={}", chatId);

        var tasks = taskRepo.findByNotifyChatId(chatId).stream()
                .filter(t -> !t.isDeleted())
                .toList();
        if (tasks.isEmpty()) {
            return "No tasks found for chat " + chatId;
        }

        // Sort by createdAt descending and take first
        var last = tasks.stream().min((a, b) -> {
                    if (a.getCreatedAt() == null && b.getCreatedAt() == null) return 0;
                    if (a.getCreatedAt() == null) return 1;
                    if (b.getCreatedAt() == null) return -1;
                    return b.getCreatedAt().compareTo(a.getCreatedAt());
                })
                .orElse(null);

        StringBuilder sb = new StringBuilder();
        sb.append("task_id: ").append(last.getTaskId(), 0, 8).append("\n");
        sb.append("status: ").append(last.getStatus()).append("\n");
        if (last.getDescription() != null) {
            String desc = last.getDescription();
            sb.append("description: ").append(desc.length() > 200 ? desc.substring(0, 200) + "..." : desc).append("\n");
        }
        sb.append("created_at: ").append(last.getCreatedAt()).append("\n");

        return sb.toString();
    }
}
