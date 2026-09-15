package ru.allstreets.developer.agents;

import io.github.asekka.springai.agents.core.Agent;
import io.github.asekka.springai.agents.core.AgentContext;
import io.github.asekka.springai.agents.core.AgentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ru.allstreets.developer.checkpoint.TaskRepository;
import ru.allstreets.developer.opencode.OpenCodeClient;
import ru.allstreets.developer.opencode.OpenCodeSessionPool;
import ru.allstreets.developer.state.TaskState;
import ru.allstreets.developer.telegram.TelegramGateway;

/**
 * Разработчик — вызывается через OpenCode sidecar.
 * Реализует задачу по ТЗ, коммитит в ветку.
 * Слот закреплён за задачей на всё её время жизни и освобождается только при CLOSED.
 */
@Component
public class DeveloperNode implements Agent {

    private static final Logger log = LoggerFactory.getLogger(DeveloperNode.class);

    /**
     * Сколько ждать свободный слот, прежде чем спросить пользователя (HITL_SLOT).
     */
    private static final long SLOT_WAIT_SECONDS = 30;

    private final OpenCodeClient openCode;
    private final OpenCodeSessionPool sessionPool;
    private final TelegramGateway telegram;
    private final TaskRepository taskRepo;
    private final SlotUnavailableHandler slotHandler;

    public DeveloperNode(OpenCodeClient openCode, OpenCodeSessionPool sessionPool, TelegramGateway telegram,
                         TaskRepository taskRepo, SlotUnavailableHandler slotHandler) {
        this.openCode = openCode;
        this.sessionPool = sessionPool;
        this.telegram = telegram;
        this.taskRepo = taskRepo;
        this.slotHandler = slotHandler;
    }

    @Override
    public AgentResult execute(AgentContext ctx) {
        String spec = ctx.get(TaskState.SPEC);
        String branch = ctx.get(TaskState.GIT_BRANCH);
        String chatId = ctx.get(TaskState.TG_CHAT_ID);
        String taskId = ctx.get(TaskState.TASK_ID);
        String targetRepo = ctx.get(TaskState.TARGET_REPO);
        String trackerIssue = ctx.get(TaskState.TRACKER_ISSUE);
        String repoUrl = toRepoUrl(targetRepo);

        if (chatId == null || chatId.isBlank()) {
            log.warn("Разработчик: нет chatId в контексте, пропуск (stale checkpoint)");
            return AgentResult.builder()
                    .text("Skipped: no chat context")
                    .stateUpdates(java.util.Map.of(
                            TaskState.AGENT_ROLE, "developer"))
                    .completed(true)
                    .build();
        }

        log.info("Разработчик: начало работы, ветка {}, repo {}", branch, targetRepo);

        telegram.sendMessage(Long.parseLong(chatId), "👨‍💻 Разработчик реализует задачу...", taskId);

        int slot = sessionPool.acquireForTask(taskId, repoUrl, SLOT_WAIT_SECONDS);
        if (slot < 0) {
            return slotHandler.askToFreeSlots(taskId, Long.parseLong(chatId), "developer");
        }
        String workDir = sessionPool.getSlotWorkDir(slot);

        String branchName = branch != null && !branch.isBlank()
                ? branch
                : trackerIssue != null && !trackerIssue.isBlank()
                  ? "feature/" + trackerIssue
                  : "feature/" + java.util.UUID.randomUUID().toString().substring(0, 8);

        String prompt = """
                Реализуй задачу по следующему ТЗ:
                
                %s
                
                Переключись на ветку: git checkout -b %s
                Следуй conventions.md проекта.
                После реализации — закоммить и убедись что проект компилируется.
                """.formatted(spec, branchName);

        var result = openCode.runAgent("developer", prompt, workDir, taskId);

        if (result.error() != null && !result.error().isEmpty()) {
            log.error("Разработчик: ошибка OpenCode: {}", result.error());
            return AgentResult.failed(io.github.asekka.springai.agents.core.AgentError.of("developer",
                    new RuntimeException("Ошибка разработчика: " + result.error())));
        }

        log.info("Разработчик: завершено. Файлов: {}", result.files() != null ? result.files().size() : 0);

        telegram.sendMessage(Long.parseLong(chatId), "✅ Реализация завершена.", taskId);

        taskRepo.findById(taskId).ifPresent(task -> {
            task.setDevelopmentDone(true);
            taskRepo.save(task);
        });

        return AgentResult.builder()
                .text(result.output())
                .stateUpdates(java.util.Map.of(
                        TaskState.IMPLEMENTATION, result.output() != null ? result.output() : "",
                        TaskState.COMMIT_HASH, result.commitHash() != null ? result.commitHash() : "",
                        TaskState.GIT_BRANCH, branchName,
                        TaskState.AGENT_ROLE, "developer",
                        TaskState.DEVELOPMENT_DONE, true))
                .completed(true)
                .build();
    }

    private static String toRepoUrl(String repo) {
        if (repo == null || repo.isBlank()) return null;
        if (repo.startsWith("https://")) return repo.endsWith(".git") ? repo : repo + ".git";
        return "https://github.com/" + repo + ".git";
    }
}
