package ru.allstreets.developer.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.allstreets.developer.checkpoint.*;
import ru.allstreets.developer.humanloop.HumanInputRegistry;
import ru.allstreets.developer.opencode.TaskProgress;
import ru.allstreets.developer.opencode.TaskProgressRegistry;
import ru.allstreets.developer.telegram.ActiveTaskRegistry;
import ru.allstreets.developer.telegram.TaskLauncher;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Локальные task tools для ConversationAgent (оркестратора).
 * Позволяют агенту-собеседнику отвечать на вопросы о ходе работы
 * и удалять/отменять задачи.
 */
@Component
public class TaskMcpTools {

    private static final Logger log = LoggerFactory.getLogger(TaskMcpTools.class);

    /**
     * Максимум проектов в ответе getChatProjects — защита контекста классификатора.
     */
    private static final int MAX_PROJECTS = 10;

    private final ActiveTaskRegistry taskRegistry;
    private final TaskRepository taskRepo;
    private final CheckpointRepository checkpointRepo;
    private final TaskLockService taskLockService;
    private final TaskLauncher taskLauncher;
    private final HumanInputRegistry humanInputRegistry;
    private final TaskProgressRegistry progressRegistry;
    private final ChatMessageRepository chatMessageRepo;
    /**
     * Кто имеет право запускать задачи (deny-by-default, если список пуст).
     */
    private final Set<String> triggerUsers;

    public TaskMcpTools(
            ActiveTaskRegistry taskRegistry,
            TaskRepository taskRepo,
            CheckpointRepository checkpointRepo,
            TaskLockService taskLockService,
            TaskLauncher taskLauncher,
            HumanInputRegistry humanInputRegistry,
            TaskProgressRegistry progressRegistry,
            ChatMessageRepository chatMessageRepo,
            @Value("${telegram.trigger-users:}") String triggerUsersRaw
    ) {
        this.taskRegistry = taskRegistry;
        this.taskRepo = taskRepo;
        this.checkpointRepo = checkpointRepo;
        this.taskLockService = taskLockService;
        this.taskLauncher = taskLauncher;
        this.humanInputRegistry = humanInputRegistry;
        this.progressRegistry = progressRegistry;
        this.chatMessageRepo = chatMessageRepo;
        this.triggerUsers = triggerUsersRaw == null || triggerUsersRaw.isBlank()
                ? Set.of()
                : Arrays.stream(triggerUsersRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
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
            return cancelOrphanRun(taskId);
        }

        log.info("MCP cancelTask: taskId={}", fullTaskId);
        StringBuilder sb = new StringBuilder();

        // Get task info before deletion
        TaskEntity task = taskRepo.findById(fullTaskId).orElse(null);
        String taskDesc = task != null ? task.getDescription() : "N/A";

        // Interrupt if running + clean up HITL state (checkpoint)
        boolean wasRunning = taskLauncher.isRunning(fullTaskId);
        taskLauncher.cancel(fullTaskId);
        if (wasRunning) {
            sb.append("Task interrupted (was running).\n");
        }

        // Задача удаляется — освобождаем её слот из пула (иначе утечёт).
        taskLauncher.releaseSlot(fullTaskId);

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

    @Tool(description = "Close a task (finalize it). If it is RUNNING — the run is cancelled (this counts as " +
            "task cancellation); the task's OpenCode slot/worktree is freed ONLY for CLOSED tasks, so use this " +
            "to free slots when they run out. Use when user says 'закрой задачу', 'заверши задачу', " +
            "'освободи слот'. taskId can be partial (first 8 chars). Returns confirmation message.")
    public String closeTask(
            @ToolParam(description = "Task ID (full or first 8 characters)") String taskId
    ) {
        String fullTaskId = resolveTaskId(taskId);
        if (fullTaskId == null) {
            return cancelOrphanRun(taskId);
        }
        log.info("MCP closeTask: taskId={}", fullTaskId);

        Long chatId = taskRegistry.getChatIdForTask(fullTaskId);
        boolean wasRunning = taskLauncher.isRunning(fullTaskId);
        boolean closed = taskLauncher.close(fullTaskId, chatId != null ? chatId : 0L);
        if (!closed) {
            return "Failed to close task " + fullTaskId.substring(0, 8) + ".";
        }
        return "Task " + fullTaskId.substring(0, 8) + " closed (CLOSED)"
                + (wasRunning ? ", running run cancelled" : "") + ". Slot freed.";
    }

    /**
     * Отменить осиротевший run: строки в agent_tasks нет (напр. legacy pr-* от старого
     * PrCommentMonitor, который запускал граф напрямую без регистрации), но есть checkpoint
     * по этому runId. Останавливаем ран и чистим checkpoint — иначе recovery «оживлял» бы его.
     */
    private String cancelOrphanRun(String runId) {
        var cp = checkpointRepo.findTopByRunIdOrderByCreatedAtDesc(runId).orElse(null);
        if (cp == null) {
            return "Task not found: " + runId;
        }
        log.info("MCP cancelTask: осиротевший run {} (нет в agent_tasks) — отменяю и чищу checkpoint", runId);
        taskLauncher.cancel(runId);
        checkpointRepo.deleteByRunId(runId);
        return "Orphan run " + runId + " cancelled and its checkpoint removed.";
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

    @Tool(description = "Restart/continue the SAME task. Resumes from its checkpoint if one exists, otherwise " +
            "re-runs it from the analyst with the additional context appended — always the SAME taskId. Use when " +
            "the user says 'перезапусти задачу' / 'возобнови задачу'. Create a NEW task from an old one's context " +
            "ONLY if the user explicitly wants a fresh task (that's launch_task with priorTaskId). " +
            "taskId can be partial (first 8 chars are enough).")
    public String restartTask(
            @ToolParam(description = "Task ID (full or first 8 characters)") String taskId,
            @ToolParam(description = "Additional context/instructions for the retry (optional, can be null)") String additionalContext
    ) {
        String fullTaskId = resolveTaskId(taskId);
        if (fullTaskId == null) {
            return "Task not found: " + taskId;
        }

        Long chatId = taskRegistry.getChatIdForTask(fullTaskId);
        if (chatId == null) {
            return "Cannot restart: no chatId associated with task " + fullTaskId.substring(0, 8);
        }

        // 1) Есть checkpoint — продолжаем ту же задачу с упавшего/прерванного узла.
        if (taskLauncher.restart(fullTaskId, chatId, additionalContext)) {
            return "Task " + fullTaskId.substring(0, 8) + " restarted from checkpoint (same task).";
        }
        // 2) Чекпоинта нет (завершена/чистая) — перезапуск ТОЙ ЖЕ задачи с аналитика.
        if (taskLauncher.rework(fullTaskId, chatId, additionalContext)) {
            return "Task " + fullTaskId.substring(0, 8) + " re-run from analyst (same task).";
        }
        return "Failed to restart task " + fullTaskId.substring(0, 8) + ".";
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

    @Tool(description = "List the projects/repositories (owner/name) that tasks in this Telegram chat belong to, " +
            "with the number of tasks for each. Use this to determine the target repository for a NEW task: " +
            "if the chat has exactly one project — use it; if several — match the repository against the user's " +
            "message/context and the listed tasks. If you cannot determine the repository confidently, DO NOT " +
            "launch the task: ask the user which project (owner/name) it belongs to.")
    public String getChatProjects(
            @ToolParam(description = "Telegram chat ID") long chatId
    ) {
        log.info("MCP getChatProjects: chatId={}", chatId);

        // Агрегация и LIMIT на стороне БД: на большом чате не тянем все задачи в память.
        // Запрашиваем на 1 больше — чтобы понять, есть ли ещё проекты.
        List<Object[]> rows = taskRepo.findChatProjects(chatId, MAX_PROJECTS + 1);
        if (rows.isEmpty()) {
            return "No projects recorded for chat " + chatId
                    + ". Ask the user which repository (owner/name) this task belongs to before launching.";
        }

        boolean more = rows.size() > MAX_PROJECTS;
        StringBuilder sb = new StringBuilder("Projects in this chat:\n");
        for (Object[] row : rows.stream().limit(MAX_PROJECTS).toList()) {
            String repo = (String) row[0];
            long count = row[1] == null ? 0 : ((Number) row[1]).longValue();
            String label = (String) row[2];
            sb.append("- ").append(repo).append(" (").append(count).append(" task(s)");
            if (label != null && !label.isBlank()) {
                sb.append("; last: ").append(label.length() > 80 ? label.substring(0, 80) + "..." : label);
            }
            sb.append(")\n");
        }
        if (more) {
            sb.append("(+more projects)\n");
        }
        return sb.toString();
    }

    @Tool(description = "List tasks of this Telegram chat, newest first, with pagination. Each entry: taskId, " +
            "status, repository, title, date. Use when you need more task history than is shown in context " +
            "(for example, to determine which project/repository a new task belongs to). page starts at 0.")
    public String getChatTasks(
            @ToolParam(description = "Telegram chat ID") long chatId,
            @ToolParam(description = "Page number, starting at 0") int page,
            @ToolParam(description = "Page size, e.g. 10") int perPage
    ) {
        log.info("MCP getChatTasks: chatId={}, page={}, perPage={}", chatId, page, perPage);

        var taskPage = taskRegistry.getChatTasksPage(chatId, page, perPage);
        if (taskPage.isEmpty()) {
            return "No tasks on page " + page + " for chat " + chatId;
        }

        StringBuilder sb = new StringBuilder();
        for (TaskEntity t : taskPage.getContent()) {
            sb.append(t.getTaskId()).append(" | ").append(t.getStatus());
            if (t.getRepo() != null && !t.getRepo().isBlank()) {
                sb.append(" | repo=").append(t.getRepo());
            }
            if (t.getTitle() != null && !t.getTitle().isBlank()) {
                sb.append(" | ").append(t.getTitle());
            }
            if (t.getCreatedAt() != null) {
                sb.append(" | ").append(t.getCreatedAt());
            }
            sb.append("\n");
        }
        sb.append("page ").append(page).append(" of ").append(Math.max(1, taskPage.getTotalPages()))
                .append(" (total ").append(taskPage.getTotalElements()).append(" tasks)");
        return sb.toString();
    }

    @Tool(description = "Read the chat history to dig into earlier context, previous tasks, clarifications and " +
            "answers — beyond the short window already in context. Returns newest first with role, taskId (if any) " +
            "and timestamp. Use when the user refers to an earlier task/discussion, or to reconstruct the chain of " +
            "a previous retry. Prefer this over guessing.")
    public String getChatHistory(
            @ToolParam(description = "Telegram chat ID") long chatId,
            @ToolParam(description = "How many recent messages to return (1..50)") int limit
    ) {
        int n = Math.clamp(limit, 1, 50);
        log.info("MCP getChatHistory: chatId={}, limit={}", chatId, n);

        var messages = chatMessageRepo.findByChatIdOrderByCreatedAtDesc(chatId, org.springframework.data.domain.PageRequest.of(0, n));
        if (messages.isEmpty()) {
            return "No messages found for chat " + chatId;
        }
        StringBuilder sb = new StringBuilder("Chat history (newest first, ")
                .append(messages.size()).append("):\n");
        for (ChatMessageEntity m : messages) {
            sb.append("[").append(m.getCreatedAt()).append("] ").append(m.getRole());
            if (m.getTaskId() != null) {
                sb.append(" [task:").append(m.getTaskId(), 0, Math.min(8, m.getTaskId().length())).append("]");
            }
            String text = m.getText() != null ? m.getText() : "";
            sb.append(": ").append(text.length() > 300 ? text.substring(0, 300) + "..." : text).append("\n");
        }
        return sb.toString();
    }

    @Tool(description = "Start a development task in this Telegram chat for the given repository. " +
            "repo is REQUIRED (owner/name): if you do NOT know the target repository — do NOT call this tool, " +
            "ask the user which repository to use first (never guess). Interrupts a running task in the chat, " +
            "if any. Returns a confirmation to relay to the user.")
    public String launchTask(
            @ToolParam(description = "Telegram chat ID") long chatId,
            @ToolParam(description = "Target repository, owner/name (required)") String repo,
            @ToolParam(description = "Task description") String description,
            @ToolParam(description = "Optional: id (or first 8 chars) of the PREVIOUS task this is a retry/follow-up " +
                    "of. When set, its description/Tracker-issue/chat history are carried into the new task so agents " +
                    "don't start from scratch. Set it when the user asks to retry/redo an earlier task and there is no " +
                    "checkpoint to resume.", required = false) String priorTaskId,
            ToolContext context
    ) {
        // Граница доступа: запускать может только trigger-user (username приходит из
        // ToolContext — его задаёт приложение, модель не может его подделать).
        String username = context != null ? (String) context.getContext().get("username") : null;
        if (!isTriggerUser(username)) {
            log.warn("MCP launchTask: пользователь '{}' не имеет права запускать задачи", username);
            return "ERROR: user is not allowed to launch tasks.";
        }

        String normalized = TaskLauncher.normalizeRepo(repo);
        if (normalized == null) {
            // Схема тула требует repo, но защищаемся от пустой строки: возвращаем ошибку,
            // которую модель увидит и уточнит у пользователя (никакого запуска без репо).
            return "ERROR: repository (owner/name) is required. Ask the user which repository to use.";
        }
        if (description == null || description.isBlank()) {
            return "ERROR: task description is required.";
        }

        String resolvedPrior = (priorTaskId != null && !priorTaskId.isBlank())
                ? resolveTaskId(priorTaskId) : null;

        log.info("MCP launchTask: chatId={}, repo={}, priorTask={}", chatId, normalized,
                resolvedPrior != null ? resolvedPrior.substring(0, 8) : "none");

        // Прерываем running-задачу в чате (reroute).
        for (var entry : taskRegistry.getActiveTasks(chatId).entrySet()) {
            if (entry.getValue() == ActiveTaskRegistry.TaskStatus.RUNNING
                    && taskLauncher.isRunning(entry.getKey())) {
                taskLauncher.interruptRunningTask(entry.getKey(), chatId);
                break;
            }
        }

        taskLauncher.launch(description, chatId, normalized, resolvedPrior);
        return "Task started for repository " + normalized + ". Tell the user it is running.";
    }

    private boolean isTriggerUser(String username) {
        return !triggerUsers.isEmpty() && username != null && triggerUsers.contains(username.toLowerCase());
    }
}
