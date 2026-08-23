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
 * Использует пул слотов для параллельного выполнения.
 */
@Component
public class DeveloperNode implements Agent {

    private static final Logger log = LoggerFactory.getLogger(DeveloperNode.class);

    private final OpenCodeClient openCode;
    private final OpenCodeSessionPool sessionPool;
    private final TelegramGateway telegram;
    private final TaskRepository taskRepo;

    public DeveloperNode(OpenCodeClient openCode, OpenCodeSessionPool sessionPool, TelegramGateway telegram,
                         TaskRepository taskRepo) {
        this.openCode = openCode;
        this.sessionPool = sessionPool;
        this.telegram = telegram;
        this.taskRepo = taskRepo;
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

        telegram.sendMessage(Long.parseLong(chatId), "👨‍💻 Разработчик реализует задачу...");

        int slot = sessionPool.acquire(600);
        if (slot < 0) {
            return AgentResult.failed(io.github.asekka.springai.agents.core.AgentError.of("developer", new RuntimeException("Таймаут ожидания слота OpenCode")));
        }

        try {
            sessionPool.prepareSlot(slot, repoUrl);
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
                return AgentResult.failed(io.github.asekka.springai.agents.core.AgentError.of("developer", new RuntimeException("Ошибка разработчика: " + result.error())));
            }

            log.info("Разработчик: завершено. Коммит: {}", result.commitHash());

            telegram.sendMessage(Long.parseLong(chatId),
                    "✅ Реализация завершена. Коммит: " + result.commitHash());

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

        } finally {
            sessionPool.cleanupSlot(slot);
            sessionPool.release(slot);
        }
    }

    private static String toRepoUrl(String repo) {
        if (repo == null || repo.isBlank()) return null;
        if (repo.startsWith("https://")) return repo.endsWith(".git") ? repo : repo + ".git";
        return "https://github.com/" + repo + ".git";
    }
}
