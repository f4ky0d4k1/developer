package ru.allstreets.developer.agents;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.asekka.springai.agents.core.Agent;
import io.github.asekka.springai.agents.core.AgentContext;
import io.github.asekka.springai.agents.core.AgentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.allstreets.developer.checkpoint.TaskRepository;
import ru.allstreets.developer.humanloop.HumanLoopService;
import ru.allstreets.developer.opencode.OpenCodeClient;
import ru.allstreets.developer.opencode.OpenCodeSessionPool;
import ru.allstreets.developer.state.TaskState;
import ru.allstreets.developer.telegram.TelegramGateway;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Аналитик — работает через OpenCode sidecar с MCP инструментами (GitHub, Tracker, Grafana).
 * OpenCode агент вызывает MCP tools напрямую (create_branch, issue_create, alerting_manage_rules).
 * Spring AI ChatClient используется только для парсинга ответа OpenCode в structured output.
 */
@Component
public class AnalystNode implements Agent {

    private static final Logger log = LoggerFactory.getLogger(AnalystNode.class);

    /**
     * Сколько раз нуджим агента в той же сессии, если он не вывел JSON-решение.
     */
    private static final int MAX_CONTINUE_ATTEMPTS = 1;

    /**
     * Нудж агенту: остановился без итогового решения — довести до JSON-блока.
     */
    private static final String CONTINUE_ANALYSIS_PROMPT = """
            Ты остановился, не выведя итоговое JSON-решение. Продолжи и в финальном ответе ОБЯЗАТЕЛЬНО
            выведи JSON-блок с полями nextStep (developer/tester/done), requiresDevelopment,
            requiresTesting и spec. Не выводи только план — доведи анализ до решения.
            """;

    private final OpenCodeClient openCode;
    private final OpenCodeSessionPool sessionPool;
    private final TelegramGateway telegram;
    private final HumanLoopService humanLoop;
    private final TaskRepository taskRepo;
    private final int maxClarifications;

    public AnalystNode(OpenCodeClient openCode, OpenCodeSessionPool sessionPool,
                       TelegramGateway telegram, HumanLoopService humanLoop,
                       TaskRepository taskRepo,
                       @Value("${opencode.max-clarifications:3}") int maxClarifications) {
        this.openCode = openCode;
        this.sessionPool = sessionPool;
        this.telegram = telegram;
        this.humanLoop = humanLoop;
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
            telegram.sendMessage(chatIdLong, "🔍 Аналитик начал работу", taskId);

            slot = sessionPool.acquireForTask(taskId, repoUrl, 600);
            if (slot < 0) {
                return AgentResult.failed(io.github.asekka.springai.agents.core.AgentError.of("analyst",
                        new RuntimeException("Таймаут ожидания слота OpenCode")));
            }

            try {
                String workDir = sessionPool.getSlotWorkDir(slot);
                String prompt = buildAnalystPrompt(ctx, taskDescription);

                var ocResult = openCode.runAgent("analyst", prompt, workDir, taskId);
                currentOutput = ocResult.output() != null ? ocResult.output() : "";
                currentSessionId = ocResult.sessionId();

                if (ocResult.error() != null && !ocResult.error().isEmpty()) {
                    log.error("Аналитик: ошибка OpenCode: {}", ocResult.error());
                    telegram.sendMessage(chatIdLong, "❌ Ошибка OpenCode: " + ocResult.error(), taskId);
                    return AgentResult.failed(io.github.asekka.springai.agents.core.AgentError.of("analyst",
                            new RuntimeException("OpenCode error: " + ocResult.error())));
                }

                // Пустой/усечённый вывод (агент «задумался вслух», провайдер вернул пустой ответ)
                // — не фейлим сразу, а нуджим агента в той же сессии довести до JSON-решения.
                // Только после N попыток без решения — провал (см. guard ниже).
                for (int attempt = 0; attempt < MAX_CONTINUE_ATTEMPTS
                        && (currentOutput.isBlank() || !hasDecisionBlock(currentOutput)); attempt++) {
                    log.warn("Аналитик: вывод без решения ({} символов), нудж {}/{} в сессии {}: {}",
                            currentOutput.length(), attempt + 1, MAX_CONTINUE_ATTEMPTS, currentSessionId,
                            preview(currentOutput));
                    try {
                        var contResult = openCode.runAgent("analyst", CONTINUE_ANALYSIS_PROMPT,
                                workDir, taskId, currentSessionId);
                        if (contResult.error() != null && !contResult.error().isEmpty()) {
                            log.warn("Аналитик: нудж завершился ошибкой: {}", contResult.error());
                            break;
                        }
                        String contOut = contResult.output() != null ? contResult.output() : "";
                        if (contOut.isBlank()) {
                            log.warn("Аналитик: нудж вернул пустой вывод");
                            continue;
                        }
                        log.info("Аналитик: ответ на нудж ({} символов): {}", contOut.length(), preview(contOut));
                        currentOutput = contOut;
                        if (contResult.sessionId() != null) {
                            currentSessionId = contResult.sessionId();
                        }
                    } catch (Exception e) {
                        log.warn("Аналитик: ошибка нуджа: {}", e.getMessage());
                        break;
                    }
                }

                log.info("Аналитик: OpenCode завершён. output: {} символов, session={}",
                        currentOutput.length(), currentSessionId);

            } catch (Exception e) {
                log.error("Аналитик: ошибка OpenCode: {}", e.getMessage(), e);
                return AgentResult.failed(io.github.asekka.springai.agents.core.AgentError.of("analyst", e));
            }
        }

        // === Детерминированный разбор JSON-решения (без второго LLM) ===
        // Решение берётся из JSON-блока в финальном ответе аналитика (см. analyst.md).
        // Невалидный/отсутствующий блок → null, дальше guard вернёт ошибку.
        AgentResponses.AnalystResult result = parseDecision(currentOutput);

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

        // Слот остаётся закреплён за задачей — освободится только при CLOSED.

        // Не даём пустому/нераспарсенному ответу молча закрыть задачу как «готово»
        // (инцидент 3c7b33db: аналитик выдал вводную фразу без решения, задача «завершилась»).
        // Контракт (analyst.md) требует JSON-блок с nextStep; без него это технический сбой.
        if (!hasDecisionBlock(currentOutput) || result == null || result.nextStep() == null) {
            log.error("Аналитик: решение не получено (decisionBlock={}, result={}, {} символов вывода) — провал. Вывод: {}",
                    hasDecisionBlock(currentOutput), result == null ? "null" : "no-nextStep", currentOutput.length(),
                    preview(currentOutput));
            telegram.sendMessage(chatIdLong,
                    "❌ Аналитик не вернул решение (пустой/нераспарсенный ответ) — задача не завершена");
            return AgentResult.failed(io.github.asekka.springai.agents.core.AgentError.of("analyst",
                    new IllegalStateException("Analyst produced no decision (missing nextStep)")));
        }

        String spec = (result.spec() != null && !result.spec().isBlank()) ? result.spec() : currentOutput;
        String trackerIssueId = "N/A".equalsIgnoreCase(result.trackerIssue()) ? null : result.trackerIssue();
        String nextStep = result.nextStep().name().toLowerCase();
        boolean requiresDev = result.requiresDevelopment();
        boolean requiresTest = result.requiresTesting();

        log.info("Аналитик: tracker={}, nextStep={}, requiresDev={}, requiresTest={}, session={}",
                trackerIssueId, nextStep, requiresDev, requiresTest, currentSessionId);

        String tgMessage = "📋 Анализ завершён.";
        if (trackerIssueId != null) {
            tgMessage += "\n📌 Tracker: " + trackerIssueId + " — https://tracker.yandex.ru/" + trackerIssueId;
        }
        if (!spec.isBlank()) {
            int maxLen = 4000;
            tgMessage += "\n\n" + (spec.length() > maxLen ? spec.substring(0, maxLen) + "..." : spec);
        }
        // Спека — свободный текст (пути, `_`, `[`, backticks): Markdown на нём ломается,
        // шлём без parse_mode, ссылку Telegram сделает кликабельной сам.
        telegram.sendPlainMessage(chatIdLong, tgMessage, taskId);

        var stateMap = new java.util.HashMap<io.github.asekka.springai.agents.core.StateKey<?>, Object>();
        stateMap.put(TaskState.SPEC, spec);
        stateMap.put(TaskState.TRACKER_ISSUE, trackerIssueId != null ? trackerIssueId : "");
        stateMap.put(TaskState.AGENT_ROLE, "analyst");
        stateMap.put(TaskState.NEXT_STEP, nextStep);
        stateMap.put(TaskState.ANALYSIS_DONE, true);
        stateMap.put(TaskState.REQUIRES_DEVELOPMENT, requiresDev);
        stateMap.put(TaskState.REQUIRES_TESTING, requiresTest);

        // SDD-поля для передачи разработчику (result гарантированно не null — см. guard выше)
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

        // Перенос контекста предыдущей задачи при ретрае через новую задачу (чекпоинта нет).
        String priorContext = ctx.get(TaskState.PRIOR_CONTEXT);
        if (priorContext != null && !priorContext.isBlank()) {
            ctxSb.append("\n### Контекст предыдущей задачи (повторный запуск — НЕ начинай с нуля, ")
                    .append("не создавай дубль Tracker):\n")
                    .append(truncate(priorContext, 4000)).append("\n");
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

    /**
     * Однострочный превью вывода агента для логов — чтобы при провале решения в логе был
     * виден реальный ответ модели (в т.ч. reasoning-only/обрыв), а не только длина.
     */
    private static String preview(String text) {
        if (text == null) {
            return "null";
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 1000 ? oneLine.substring(0, 1000) + "…" : oneLine;
    }

    /**
     * Контракт аналитика: в ответе обязателен JSON-блок с полем {@code nextStep}
     * (см. {@code opencode-config/agents/analyst.md}). Детерминированная проверка —
     * чтобы пустой/усечённый ответ не мог молча закрыть задачу как «готово».
     */
    private static boolean hasDecisionBlock(String output) {
        return output != null && DECISION_BLOCK.matcher(output).find();
    }

    private static final java.util.regex.Pattern DECISION_BLOCK =
            java.util.regex.Pattern.compile("(?i)\"?nextStep\"?\\s*[:=]");

    /**
     * Детерминированный разбор JSON-решения из финального ответа аналитика (без второго LLM).
     * <p>
     * Модель оборачивает анализ в несколько code-фенсов (```yaml, ```java и т.п.) и добавляет
     * JSON-решение в конце. Брать первый фенс нельзя — это не JSON (инцидент b9c0e7ae: парсился
     * ```yaml). Перебираем все фенсы и сбалансированные `{...}` и берём первый, который парсится
     * как решение с {@code nextStep}.
     */
    private AgentResponses.AnalystResult parseDecision(String output) {
        for (String candidate : jsonCandidates(output)) {
            try {
                AgentResponses.AnalystResult parsed =
                        JSON_MAPPER.readValue(candidate, AgentResponses.AnalystResult.class);
                if (parsed.nextStep() != null) {
                    return parsed;
                }
            } catch (Exception e) {
                log.debug("Аналитик: кандидат JSON не распарсен: {}", e.getMessage());
            }
        }
        log.warn("Аналитик: JSON-блок решения не найден в выводе");
        return null;
    }

    /**
     * Кандидаты на JSON-решение: тела всех fenced-блоков, начинающиеся с `{`, затем последний
     * сбалансированный `{...}` (решение обычно в конце ответа).
     */
    private static java.util.List<String> jsonCandidates(String text) {
        if (text == null) {
            return java.util.List.of();
        }
        var candidates = new java.util.ArrayList<String>();
        Matcher fence = JSON_FENCE.matcher(text);
        while (fence.find()) {
            String body = fence.group(1).trim();
            if (body.startsWith("{")) {
                candidates.add(body);
            }
        }
        String balanced = lastBalancedObject(text);
        if (balanced != null) {
            candidates.add(balanced);
        }
        return candidates;
    }

    /**
     * Последний сбалансированный JSON-объект в тексте (учёт строк и экранирования).
     */
    private static String lastBalancedObject(String text) {
        int start = text.lastIndexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private static final Pattern JSON_FENCE = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```");

    private static final ObjectMapper JSON_MAPPER = JsonMapper.builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .build();

    private static String toRepoUrl(String repo) {
        if (repo == null || repo.isBlank()) return null;
        if (repo.startsWith("https://")) return repo.endsWith(".git") ? repo : repo + ".git";
        return "https://github.com/" + repo + ".git";
    }
}
