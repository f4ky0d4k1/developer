package ru.allstreets.developer.agents;

import io.github.asekka.springai.agents.core.Agent;
import io.github.asekka.springai.agents.core.AgentContext;
import io.github.asekka.springai.agents.core.AgentError;
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
 * Репортёр — агент текста в Трекере (вызывается через OpenCode sidecar).
 * <p>
 * Единственный, кто пишет/правит содержимое задачи Трекера: отчёты, итоги, описания доработок,
 * правку описания. Запуском репортёра управляет АНАЛИТИК ({@code nextStep=reporter}) — это
 * исключение из «роутинг по флагам», потому что текст в Трекере не является кодом.
 * Кода репортёр не трогает, задачи в Трекере не создаёт (это делает аналитик).
 * <p>
 * Слот закреплён за задачей на всё её время жизни и освобождается только при CLOSED.
 */
@Component
public class ReporterNode implements Agent {

    private static final Logger log = LoggerFactory.getLogger(ReporterNode.class);

    /**
     * Сколько ждать свободный слот, прежде чем спросить пользователя (HITL_SLOT).
     */
    private static final long SLOT_WAIT_SECONDS = 30;

    private final OpenCodeClient openCode;
    private final OpenCodeSessionPool sessionPool;
    private final TelegramGateway telegram;
    private final TaskRepository taskRepo;
    private final SlotUnavailableHandler slotHandler;

    public ReporterNode(OpenCodeClient openCode, OpenCodeSessionPool sessionPool, TelegramGateway telegram,
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
        String chatId = ctx.get(TaskState.TG_CHAT_ID);
        String taskId = ctx.get(TaskState.TASK_ID);
        String targetRepo = ctx.get(TaskState.TARGET_REPO);
        String trackerIssue = ctx.get(TaskState.TRACKER_ISSUE);
        String repoUrl = toRepoUrl(targetRepo);

        if (chatId == null || chatId.isBlank()) {
            log.warn("Репортёр: нет chatId в контексте, пропуск (stale checkpoint)");
            return AgentResult.builder()
                    .text("Skipped: no chat context")
                    .stateUpdates(java.util.Map.of(
                            TaskState.AGENT_ROLE, "reporter"))
                    .completed(true)
                    .build();
        }

        log.info("Репортёр: начало работы, tracker={}, repo={}", trackerIssue, targetRepo);

        telegram.sendMessage(Long.parseLong(chatId), "📝 Репортёр оформляет текст в Трекере...", taskId);

        int slot = sessionPool.acquireForTask(taskId, repoUrl, SLOT_WAIT_SECONDS);
        if (slot < 0) {
            return slotHandler.askToFreeSlots(taskId, Long.parseLong(chatId), "reporter");
        }
        String workDir = sessionPool.getSlotWorkDir(slot);

        String prompt = """
                Оформи текст задачи в Трекере по этому заданию:
                
                %s
                
                Задача Трекера: %s
                Репозиторий задачи: %s
                Следуй своей роли (reporter) и навыку оформления комментариев Трекера.
                Кода не трогай, новых задач в Трекере не создавай.
                """.formatted(
                spec != null ? spec : "(спека не передана)",
                trackerIssue != null && !trackerIssue.isBlank() ? trackerIssue : "(не указана)",
                targetRepo != null && !targetRepo.isBlank() ? targetRepo : "(не указан)");

        var result = openCode.runAgent("reporter", prompt, workDir, taskId);

        if (result.error() != null && !result.error().isEmpty()) {
            log.error("Репортёр: ошибка OpenCode: {}", result.error());
            return AgentResult.failed(AgentError.of("reporter",
                    new RuntimeException("Ошибка репортёра: " + result.error())));
        }

        log.info("Репортёр: завершено. output {} символов",
                result.output() != null ? result.output().length() : 0);

        String doneMsg = "✅ Текст в Трекере оформлен.";
        if (trackerIssue != null && !trackerIssue.isBlank()) {
            doneMsg += "\n📌 " + trackerIssue + " — https://tracker.yandex.ru/" + trackerIssue;
        }
        telegram.sendMessage(Long.parseLong(chatId), doneMsg, taskId);

        taskRepo.findById(taskId).ifPresent(task -> {
            task.setUpdatedAt(java.time.Instant.now());
            taskRepo.save(task);
        });

        return AgentResult.builder()
                .text(result.output())
                .stateUpdates(java.util.Map.of(
                        TaskState.AGENT_ROLE, "reporter"))
                .completed(true)
                .build();
    }

    private static String toRepoUrl(String repo) {
        if (repo == null || repo.isBlank()) return null;
        if (repo.startsWith("https://")) return repo.endsWith(".git") ? repo : repo + ".git";
        return "https://github.com/" + repo + ".git";
    }
}
