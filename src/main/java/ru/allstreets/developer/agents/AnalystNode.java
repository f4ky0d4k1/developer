package ru.allstreets.developer.agents;

import io.github.asekka.springai.agents.core.Agent;
import io.github.asekka.springai.agents.core.AgentContext;
import io.github.asekka.springai.agents.core.AgentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.MessageType;
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
        String chatId = ctx.get(TaskState.TG_CHAT_ID);
        String taskId = ctx.get(TaskState.TASK_ID);

        if (chatId == null || chatId.isBlank()) {
            log.error("Аналитик: нет chatId в контексте (taskId={}) — обязательное поле отсутствует, отказ", taskId);
            return AgentResult.failed(io.github.asekka.springai.agents.core.AgentError.of("analyst",
                    new IllegalStateException("Missing required TG_CHAT_ID in AgentContext")));
        }

        long chatIdLong = Long.parseLong(chatId);

        // === Detect resume from HITL interrupt ===
        String existingSessionId = ctx.get(TaskState.OPENCODE_SESSION_ID);
        Integer existingSlot = ctx.get(TaskState.OPENCODE_SLOT);
        Integer savedClarificationCount = ctx.get(TaskState.CLARIFICATION_COUNT);
        int clarificationCount = savedClarificationCount != null ? savedClarificationCount : 0;

        String currentOutput;
        String currentSessionId;
        int slot;

        if (existingSessionId != null && existingSlot != null) {
            // === Resume from HITL interrupt ===
            slot = existingSlot;
            currentSessionId = existingSessionId;

            String userAnswer = extractLastUserMessage(ctx);
            if (userAnswer == null || userAnswer.isBlank()) {
                log.warn("Аналитик: resume без ответа пользователя — продолжаю с сохранённым выводом");
                currentOutput = ctx.get(TaskState.OPENCODE_OUTPUT);
            } else {
                log.info("Аналитик: resume с ответом пользователя, возобновление сессии {}", currentSessionId);
                String resumePrompt = "Ответ пользователя на уточняющий вопрос: " + userAnswer;
                try {
                    var resumeResult = openCode.runAgent("analyst", resumePrompt,
                            sessionPool.getSlotWorkDir(slot), taskId, currentSessionId);
                    if (resumeResult.error() != null && !resumeResult.error().isEmpty()) {
                        log.error("Аналитик: ошибка возобновления сессии: {}", resumeResult.error());
                        currentOutput = ctx.get(TaskState.OPENCODE_OUTPUT);
                    } else {
                        currentOutput = resumeResult.output() != null ? resumeResult.output()
                                : ctx.get(TaskState.OPENCODE_OUTPUT);
                        if (resumeResult.sessionId() != null) {
                            currentSessionId = resumeResult.sessionId();
                        }
                    }
                } catch (Exception e) {
                    log.error("Аналитик: ошибка возобновления OpenCode: {}", e.getMessage(), e);
                    currentOutput = ctx.get(TaskState.OPENCODE_OUTPUT);
                }
            }
        } else {
            // === First run ===
            String taskDescription = ctx.messages().isEmpty() ? "" : ctx.messages().getFirst().getText();
            String targetRepo = ctx.get(TaskState.TARGET_REPO);
            String repoUrl = toRepoUrl(targetRepo);

            log.info("Аналитик: начало работы над задачей (repo: {})", targetRepo);
            String title = ctx.get(TaskState.TASK_TITLE);
            String taskLabel = (title != null && !title.isBlank())
                    ? title + " (" + taskId.substring(0, 8) + ")"
                    : taskId.substring(0, 8);
            telegram.sendMessage(chatIdLong, "🔍 Аналитик начал работу над задачей: " + taskLabel);

            slot = sessionPool.acquire(600);
            if (slot < 0) {
                return AgentResult.failed(io.github.asekka.springai.agents.core.AgentError.of("analyst",
                        new RuntimeException("Таймаут ожидания слота OpenCode")));
            }

            // Слот освобождается здесь только при ошибке/пустом выводе. При успехе он
            // намеренно остаётся зарезервированным — либо будет освобождён общим кодом
            // ниже (после structured output), либо сохранён в checkpoint при HITL-паузе.
            boolean releaseSlotOnExit = true;
            try {
                sessionPool.prepareSlot(slot, repoUrl);
                String workDir = sessionPool.getSlotWorkDir(slot);
                String prompt = buildAnalystPrompt(ctx, taskDescription);

                var ocResult = openCode.runAgent("analyst", prompt, workDir, taskId);
                currentOutput = ocResult.output() != null ? ocResult.output() : "";
                currentSessionId = ocResult.sessionId();

                if (ocResult.error() != null && !ocResult.error().isEmpty()) {
                    log.error("Аналитик: ошибка OpenCode: {}", ocResult.error());
                    telegram.sendMessage(chatIdLong, "❌ Ошибка OpenCode: " + ocResult.error());
                    return AgentResult.failed(io.github.asekka.springai.agents.core.AgentError.of("analyst",
                            new RuntimeException("OpenCode error: " + ocResult.error())));
                }

                if (currentOutput.isBlank()) {
                    log.error("Аналитик: OpenCode вернул пустой вывод");
                    telegram.sendMessage(chatIdLong, "❌ OpenCode вернул пустой результат");
                    return AgentResult.failed(io.github.asekka.springai.agents.core.AgentError.of("analyst",
                            new RuntimeException("OpenCode returned empty output")));
                }

                log.info("Аналитик: OpenCode завершён. output: {} символов, session={}",
                        currentOutput.length(), currentSessionId);
                releaseSlotOnExit = false;

            } catch (Exception e) {
                log.error("Аналитик: ошибка OpenCode: {}", e.getMessage(), e);
                return AgentResult.failed(io.github.asekka.springai.agents.core.AgentError.of("analyst", e));
            } finally {
                if (releaseSlotOnExit) {
                    sessionPool.cleanupSlot(slot);
                    sessionPool.release(slot);
                }
            }
        }

        // === Parse output via structured output ===
        AgentResponses.AnalystResult result;
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
        }

        // === Check if it needs clarification → interrupt (non-blocking) ===
        if (result != null && result.needsClarification() && result.clarificationQuestion() != null
                && clarificationCount < maxClarifications) {

            log.info("Аналитик: требуется уточнение #{} (session={})", clarificationCount + 1, currentSessionId);
            humanLoop.askHuman(taskId, chatIdLong, result.clarificationQuestion());

            // Save state for resume — slot is NOT released
            var stateMap = new java.util.HashMap<io.github.asekka.springai.agents.core.StateKey<?>, Object>();
            stateMap.put(TaskState.OPENCODE_SLOT, slot);
            stateMap.put(TaskState.OPENCODE_SESSION_ID, currentSessionId);
            stateMap.put(TaskState.CLARIFICATION_COUNT, clarificationCount + 1);
            stateMap.put(TaskState.OPENCODE_OUTPUT, currentOutput);

            return AgentResult.builder()
                    .stateUpdates(stateMap)
                    .interrupt("HITL_CLARIFICATION")
                    .completed(false)
                    .build();
        }

        if (result != null && result.needsClarification() && clarificationCount >= maxClarifications) {
            log.warn("Аналитик: достигнут лимит HITL-уточнений ({}), выходим", maxClarifications);
        }

        // === Analysis complete — release slot and return ===
        sessionPool.cleanupSlot(slot);
        sessionPool.release(slot);

        String spec;
        String trackerIssueId;
        String nextStep;
        boolean requiresDev;
        boolean requiresTest;

        if (result != null) {
            spec = (result.spec() != null && !result.spec().isBlank()) ? result.spec() : currentOutput;
            trackerIssueId = "N/A".equalsIgnoreCase(result.trackerIssue()) ? null : result.trackerIssue();
            nextStep = result.nextStep() != null ? result.nextStep().name().toLowerCase() : "done";
            requiresDev = result.requiresDevelopment();
            requiresTest = result.requiresTesting();
        } else {
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

        var stateMap = new java.util.HashMap<io.github.asekka.springai.agents.core.StateKey<?>, Object>();
        stateMap.put(TaskState.SPEC, spec);
        stateMap.put(TaskState.TRACKER_ISSUE, trackerIssueId != null ? trackerIssueId : "");
        stateMap.put(TaskState.AGENT_ROLE, "analyst");
        stateMap.put(TaskState.NEXT_STEP, nextStep);
        stateMap.put(TaskState.ANALYSIS_DONE, true);
        stateMap.put(TaskState.REQUIRES_DEVELOPMENT, requiresDev);
        stateMap.put(TaskState.REQUIRES_TESTING, requiresTest);

        // SDD-поля для передачи разработчику
        if (result != null) {
            if (result.userStory() != null) {
                stateMap.put(TaskState.USER_STORY, result.userStory());
            }
            if (result.acceptanceCriteria() != null && !result.acceptanceCriteria().isEmpty()) {
                stateMap.put(TaskState.ACCEPTANCE_CRITERIA, result.acceptanceCriteria());
            }
            if (result.outOfScope() != null && !result.outOfScope().isEmpty()) {
                stateMap.put(TaskState.OUT_OF_SCOPE, result.outOfScope());
            }
            if (result.constraints() != null && !result.constraints().isEmpty()) {
                stateMap.put(TaskState.CONSTRAINTS, result.constraints());
            }
            if (result.contextLinks() != null && !result.contextLinks().isEmpty()) {
                stateMap.put(TaskState.CONTEXT_LINKS, result.contextLinks());
            }
            if (result.taskBreakdown() != null && !result.taskBreakdown().isEmpty()) {
                stateMap.put(TaskState.TASK_BREAKDOWN, result.taskBreakdown());
            }
        }

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

    private String extractLastUserMessage(AgentContext ctx) {
        var messages = ctx.messages();
        if (messages.isEmpty()) return null;
        var last = messages.getLast();
        if (last.getMessageType() == MessageType.USER) {
            return last.getText();
        }
        return null;
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
