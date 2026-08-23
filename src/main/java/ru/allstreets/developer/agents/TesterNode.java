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
 * Тестировщик — вызывается через OpenCode sidecar.
 * Пишет тесты по ТЗ, коммитит в ветку.
 * Использует пул слотов для параллельного выполнения.
 */
@Component
public class TesterNode implements Agent {

    private static final Logger log = LoggerFactory.getLogger(TesterNode.class);

    private final OpenCodeClient openCode;
    private final OpenCodeSessionPool sessionPool;
    private final TelegramGateway telegram;
    private final TaskRepository taskRepo;

    public TesterNode(OpenCodeClient openCode, OpenCodeSessionPool sessionPool, TelegramGateway telegram,
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
        String repoUrl = toRepoUrl(targetRepo);

        if (chatId == null || chatId.isBlank()) {
            log.warn("Тестировщик: нет chatId в контексте, пропуск (stale checkpoint)");
            return AgentResult.builder()
                    .text("Skipped: no chat context")
                    .stateUpdates(java.util.Map.of(
                            TaskState.AGENT_ROLE, "tester"))
                    .completed(true)
                    .build();
        }

        log.info("Тестировщик: начало работы, ветка {}, repo {}", branch, targetRepo);

        telegram.sendMessage(Long.parseLong(chatId), "🧪 Тестировщик пишет тесты...");

        int slot = sessionPool.acquire(600);
        if (slot < 0) {
            return AgentResult.failed(io.github.asekka.springai.agents.core.AgentError.of("tester", new RuntimeException("Таймаут ожидания слота OpenCode")));
        }

        try {
            sessionPool.prepareSlot(slot, repoUrl);
            String workDir = sessionPool.getSlotWorkDir(slot);

            String prompt = """
                    Напиши тесты для следующего ТЗ:
                    
                    %s
                    
                    Переключись на ветку: git checkout -b %s
                    Используй JUnit5, MockMvc, Testcontainers.
                    Покрой: позитивные сценарии, 4xx ошибки, граничные случаи.
                    После написания — закоммить в текущую ветку.
                    """.formatted(spec, branch != null && !branch.isBlank() ? branch : "feature/new-task");

            var result = openCode.runAgent("tester", prompt, workDir, taskId);

            if (result.error() != null && !result.error().isEmpty()) {
                log.error("Тестировщик: ошибка OpenCode: {}", result.error());
                return AgentResult.failed(io.github.asekka.springai.agents.core.AgentError.of("tester", new RuntimeException("Ошибка тестировщика: " + result.error())));
            }

            log.info("Тестировщик: завершено. Файлов: {}", result.files() != null ? result.files().size() : 0);

            telegram.sendMessage(Long.parseLong(chatId),
                    "✅ Тесты написаны. Файлов: " + (result.files() != null ? result.files().size() : 0));

            taskRepo.findById(taskId).ifPresent(task -> {
                task.setTestsWritten(true);
                taskRepo.save(task);
            });

            return AgentResult.builder()
                    .text(result.output())
                    .stateUpdates(java.util.Map.of(
                            TaskState.TEST_PLAN, result.output() != null ? result.output() : "",
                            TaskState.AGENT_ROLE, "tester",
                            TaskState.TESTS_WRITTEN, true))
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
