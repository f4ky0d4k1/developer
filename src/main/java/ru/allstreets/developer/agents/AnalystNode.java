package ru.allstreets.developer.agents;

import io.github.asekka.springai.agents.core.Agent;
import io.github.asekka.springai.agents.core.AgentContext;
import io.github.asekka.springai.agents.core.AgentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.allstreets.developer.checkpoint.TaskRepository;
import ru.allstreets.developer.humanloop.HumanLoopService;
import ru.allstreets.developer.opencode.OpenCodeClient;
import ru.allstreets.developer.opencode.OpenCodeSessionPool;
import ru.allstreets.developer.state.TaskState;
import ru.allstreets.developer.telegram.TelegramGateway;

/**
 * Аналитик — работает через OpenCode sidecar с MCP инструментами (GitHub, Tracker, Grafana).
 * OpenCode агент вызывает MCP tools напрямую (create_branch, issue_create, alerting_manage_rules).
 * Spring AI ChatClient используется только для парсинга ответа OpenCode в structured output.
 */
@Component
public class AnalystNode implements Agent {

    private static final Logger log = LoggerFactory.getLogger(AnalystNode.class);

    private final OpenCodeClient openCode;
    private final OpenCodeSessionPool sessionPool;
    private final ChatClient chatClient;
    private final ChatClient fallbackChatClient;
    private final TelegramGateway telegram;
    private final HumanLoopService humanLoop;
    private final StructuredOutputHelper structuredOutput;
    private final TaskRepository taskRepo;
    private final int maxClarifications;

    public AnalystNode(OpenCodeClient openCode, OpenCodeSessionPool sessionPool,
                       @Qualifier("analystChatClient") ChatClient chatClient,
                       @Qualifier("fallbackChatClient") ChatClient fallbackChatClient,
                       TelegramGateway telegram, HumanLoopService humanLoop,
                       StructuredOutputHelper structuredOutput,
                       TaskRepository taskRepo,
                       @Value("${opencode.max-clarifications:3}") int maxClarifications) {
        this.openCode = openCode;
        this.sessionPool = sessionPool;
        this.chatClient = chatClient;
        this.fallbackChatClient = fallbackChatClient;
        this.telegram = telegram;
        this.humanLoop = humanLoop;
        this.structuredOutput = structuredOutput;
        this.taskRepo = taskRepo;
        this.maxClarifications = maxClarifications;
    }

    @Override
    public AgentResult execute(AgentContext ctx) {
        String taskDescription = ctx.messages().isEmpty() ? "" : ctx.messages().getFirst().getText();
        String chatId = ctx.get(TaskState.TG_CHAT_ID);
        String taskId = ctx.get(TaskState.TASK_ID);

        if (chatId == null || chatId.isBlank()) {
            log.warn("Аналитик: нет chatId в контексте, пропуск (возможно stale checkpoint)");
            return AgentResult.builder()
                    .text("Skipped: no chat context")
                    .stateUpdates(java.util.Map.of(
                            TaskState.AGENT_ROLE, "analyst",
                            TaskState.NEXT_STEP, "done"))
                    .completed(true)
                    .build();
        }

        long chatIdLong = Long.parseLong(chatId);
        String targetRepo = ctx.get(TaskState.TARGET_REPO);
        String repoUrl = toRepoUrl(targetRepo);

        log.info("Аналитик: начало работы над задачей (repo: {})", targetRepo);
        telegram.sendMessage(chatIdLong, "🔍 Аналитик начал работу над задачей...");

        int slot = sessionPool.acquire(600);
        if (slot < 0) {
            return AgentResult.failed(io.github.asekka.springai.agents.core.AgentError.of("analyst",
                    new RuntimeException("Таймаут ожидания слота OpenCode")));
        }

        String openCodeOutput;
        String sessionId;
        try {
            sessionPool.prepareSlot(slot, repoUrl);
            String workDir = sessionPool.getSlotWorkDir(slot);

            String prompt = buildAnalystPrompt(ctx, taskDescription);

            var ocResult = openCode.runAgent("analyst", prompt, workDir, taskId);
            openCodeOutput = ocResult.output() != null ? ocResult.output() : "";
            sessionId = ocResult.sessionId();

            if (ocResult.error() != null && !ocResult.error().isEmpty()) {
                log.error("Аналитик: ошибка OpenCode: {}", ocResult.error());
                telegram.sendMessage(chatIdLong, "❌ Ошибка OpenCode: " + ocResult.error());
                sessionPool.cleanupSlot(slot);
                sessionPool.release(slot);
                return AgentResult.failed(io.github.asekka.springai.agents.core.AgentError.of("analyst",
                        new RuntimeException("OpenCode error: " + ocResult.error())));
            }

            if (openCodeOutput.isBlank()) {
                log.error("Аналитик: OpenCode вернул пустой вывод");
                telegram.sendMessage(chatIdLong, "❌ OpenCode вернул пустой результат");
                sessionPool.cleanupSlot(slot);
                sessionPool.release(slot);
                return AgentResult.failed(io.github.asekka.springai.agents.core.AgentError.of("analyst",
                        new RuntimeException("OpenCode returned empty output")));
            }

            log.info("Аналитик: OpenCode завершён. output: {} символов, session={}", openCodeOutput.length(), sessionId);

        } catch (Exception e) {
            log.error("Аналитик: ошибка OpenCode: {}", e.getMessage(), e);
            sessionPool.cleanupSlot(slot);
            sessionPool.release(slot);
            return AgentResult.failed(io.github.asekka.springai.agents.core.AgentError.of("analyst", e));
        }
        // ВНИМАНИЕ: слот НЕ освобождается здесь — он нужен для HITL-цикла ниже.
        // Освобождение в finally после HITL.

        String spec;
        String trackerIssueId;
        String nextStep;
        boolean requiresDev;
        boolean requiresTest;

        // Цикл HITL-уточнений: парсим ответ → если needsClarification → спрашиваем →
        // возобновляем сессию с ответом → парсим заново. Максимум 3 итерации.
        AgentResponses.AnalystResult result = null;
        String currentOutput = openCodeOutput;
        String currentSessionId = sessionId;

        for (int i = 0; i <= maxClarifications; i++) {
            // Парсим ответ OpenCode через Spring AI structured output
            try {
                String parsePrompt = """
                        Извлеки JSON из ответа аналитика и верни как structured output.
                        Если поле отсутствует — верни null. nextStep по умолчанию "done".
                        
                        Ответ аналитика:
                        %s
                        """.formatted(currentOutput);

                result = structuredOutput.callWithFallback(
                        chatClient, fallbackChatClient, parsePrompt, AgentResponses.AnalystResult.class);
            } catch (Exception e) {
                log.error("Аналитик: ошибка парсинга structured output: {}", e.getMessage());
                result = null;
                break;
            }

            if (result == null) {
                break;
            }

            // Проверяем needsClarification
            if (!result.needsClarification() || result.clarificationQuestion() == null) {
                break;
            }

            //noinspection NonStrictComparisonCanBeEquality
            if (i >= maxClarifications) {
                log.warn("Аналитик: достигнут лимит HITL-уточнений ({}), выходим", maxClarifications);
                break;
            }

            // Спрашиваем пользователя
            String answer = humanLoop.askHuman(taskId, chatIdLong, result.clarificationQuestion());
            if (answer == null) {
                log.warn("Аналитик: ответ пользователя не получен (таймаут), выходим из HITL-цикла");
                break;
            }

            log.info("Аналитик: получен ответ пользователя, возобновление сессии {} с уточнением", currentSessionId);

            // Возобновляем сессию — LLM помнит контекст, отправляем только ответ
            String resumePrompt = "Ответ пользователя на уточняющий вопрос: " + answer;
            try {
                var resumeResult = openCode.runAgent("analyst", resumePrompt, sessionPool.getSlotWorkDir(slot), taskId, currentSessionId);
                if (resumeResult.error() != null && !resumeResult.error().isEmpty()) {
                    log.error("Аналитик: ошибка возобновления сессии: {}", resumeResult.error());
                    break;
                }
                currentOutput = resumeResult.output() != null ? resumeResult.output() : currentOutput;
                if (resumeResult.sessionId() != null) {
                    currentSessionId = resumeResult.sessionId();
                }
            } catch (Exception e) {
                log.error("Аналитик: ошибка возобновления OpenCode: {}", e.getMessage(), e);
                break;
            }
        }

        if (result != null) {
            spec = (result.spec() != null && !result.spec().isBlank()) ? result.spec() : currentOutput;
            trackerIssueId = "N/A".equalsIgnoreCase(result.trackerIssue()) ? null : result.trackerIssue();
            nextStep = result.nextStep() != null ? result.nextStep() : "done";
            requiresDev = result.requiresDevelopment();
            requiresTest = result.requiresTesting();
        } else {
            // Fallback: используем raw output
            spec = currentOutput;
            trackerIssueId = null;
            nextStep = "done";
            requiresDev = false;
            requiresTest = false;
        }

        log.info("Аналитик: tracker={}, nextStep={}, requiresDev={}, requiresTest={}, session={}",
                trackerIssueId, nextStep, requiresDev, requiresTest, currentSessionId);

        String tgMessage = "📋 Анализ завершён.";
        if (trackerIssueId != null) {
            tgMessage += "\n📌 Tracker: [" + trackerIssueId + "](https://tracker.yandex.ru/" + trackerIssueId + ")";
        }
        if (!spec.isBlank()) {
            int maxLen = 4000;
            tgMessage += "\n\n" + (spec.length() > maxLen ? spec.substring(0, maxLen) + "..." : spec);
        }
        telegram.sendMessage(chatIdLong, tgMessage);

        // Освобождаем слот только после завершения HITL-цикла
        sessionPool.cleanupSlot(slot);
        sessionPool.release(slot);

        var stateMap = new java.util.HashMap<io.github.asekka.springai.agents.core.StateKey<?>, Object>();
        stateMap.put(TaskState.SPEC, spec);
        stateMap.put(TaskState.TRACKER_ISSUE, trackerIssueId != null ? trackerIssueId : "");
        stateMap.put(TaskState.AGENT_ROLE, "analyst");
        stateMap.put(TaskState.NEXT_STEP, nextStep);
        stateMap.put(TaskState.ANALYSIS_DONE, true);
        stateMap.put(TaskState.REQUIRES_DEVELOPMENT, requiresDev);
        stateMap.put(TaskState.REQUIRES_TESTING, requiresTest);

        taskRepo.findById(taskId).ifPresent(task -> {
            task.setAnalysisDone(true);
            task.setRequiresDevelopment(requiresDev);
            task.setRequiresTesting(requiresTest);
            taskRepo.save(task);
        });

        return AgentResult.builder()
                .text(spec)
                .stateUpdates(stateMap)
                .completed(true)
                .build();
    }

    private String buildAnalystPrompt(AgentContext ctx, String taskDescription) {
        var sb = new StringBuilder();
        sb.append("Задача от пользователя:\n").append(taskDescription).append("\n");

        // Контекст предыдущих итераций (если есть — это reroute)
        String spec = ctx.get(TaskState.SPEC);
        String implementation = ctx.get(TaskState.IMPLEMENTATION);
        String testPlan = ctx.get(TaskState.TEST_PLAN);
        String commitHash = ctx.get(TaskState.COMMIT_HASH);
        String branch = ctx.get(TaskState.GIT_BRANCH);
        String trackerIssue = ctx.get(TaskState.TRACKER_ISSUE);
        Integer reworkCount = ctx.get(TaskState.REWORK_COUNT);
        var validation = ctx.get(TaskState.VALIDATION);
        var feedback = ctx.get(TaskState.FEEDBACK);

        Boolean analysisDone = ctx.get(TaskState.ANALYSIS_DONE);
        Boolean devDone = ctx.get(TaskState.DEVELOPMENT_DONE);
        Boolean testsWritten = ctx.get(TaskState.TESTS_WRITTEN);
        Boolean testingDone = ctx.get(TaskState.TESTING_DONE);
        Boolean prCreated = ctx.get(TaskState.PR_CREATED);

        var ctxSb = new StringBuilder();

        if (reworkCount != null && reworkCount > 0) {
            ctxSb.append("\n## Контекст итерации #").append(reworkCount + 1).append("\n");
        }

        if (spec != null && !spec.isBlank()) {
            ctxSb.append("\n### Предыдущий ТЗ/анализ:\n").append(truncate(spec, 2000)).append("\n");
        }

        if (branch != null && !branch.isBlank()) {
            ctxSb.append("\n### Ветка: ").append(branch).append("\n");
        }

        if (trackerIssue != null && !trackerIssue.isBlank()) {
            ctxSb.append("### Tracker: ").append(trackerIssue).append("\n");
        }

        if (commitHash != null && !commitHash.isBlank()) {
            ctxSb.append("### Последний коммит: ").append(commitHash).append("\n");
        }

        if (implementation != null && !implementation.isBlank()) {
            ctxSb.append("\n### Что было реализовано:\n").append(truncate(implementation, 2000)).append("\n");
        }

        if (testPlan != null && !testPlan.isBlank()) {
            ctxSb.append("\n### План тестов:\n").append(truncate(testPlan, 1000)).append("\n");
        }

        if (validation != null) {
            ctxSb.append("\n### Результат валидации:\n");
            ctxSb.append("Статус: ").append(validation.status()).append("\n");
            ctxSb.append("Тестов: ").append(validation.total())
                    .append(", прошло: ").append(validation.passed())
                    .append(", упало: ").append(validation.failed()).append("\n");
            if (!validation.failures().isEmpty()) {
                ctxSb.append("Ошибки:\n");
                for (var f : validation.failures()) {
                    ctxSb.append("- ").append(f.test()).append(": ").append(f.message()).append("\n");
                }
            }
        }

        if (feedback != null && !feedback.isEmpty()) {
            ctxSb.append("\n### Feedback (комментарии к PR):\n");
            for (var fb : feedback) {
                ctxSb.append("- ").append(fb.fromAgent()).append(": ").append(truncate(fb.message(), 500)).append("\n");
            }
        }

        // Tracking статус
        String statusSb = """
                ### Статус задачи:
                - analysis_done: %s
                - development_done: %s
                - tests_written: %s
                - testing_done: %s
                - pr_created: %s
                """.formatted(
                analysisDone != null && analysisDone,
                devDone != null && devDone,
                testsWritten != null && testsWritten,
                testingDone != null && testingDone,
                prCreated != null && prCreated);

        sb.append("\n## Текущий контекст задачи\n");
        sb.append(ctxSb);
        sb.append(statusSb);
        sb.append("\nУчти этот контекст при анализе. Если это доработка — скорректируй ТЗ с учётом предыдущих результатов.\n");

        return sb.toString();
    }

    private String truncate(String text, int maxLen) {
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }

    private static String toRepoUrl(String repo) {
        if (repo == null || repo.isBlank()) return null;
        if (repo.startsWith("https://")) return repo.endsWith(".git") ? repo : repo + ".git";
        return "https://github.com/" + repo + ".git";
    }
}
