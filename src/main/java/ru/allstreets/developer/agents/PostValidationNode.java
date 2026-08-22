package ru.allstreets.developer.agents;

import io.github.asekka.springai.agents.core.Agent;
import io.github.asekka.springai.agents.core.AgentContext;
import io.github.asekka.springai.agents.core.AgentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import ru.allstreets.developer.checkpoint.TaskRepository;
import ru.allstreets.developer.opencode.OpenCodeClient;
import ru.allstreets.developer.opencode.OpenCodeSessionPool;
import ru.allstreets.developer.state.TaskState;
import ru.allstreets.developer.state.ValidationReport;
import ru.allstreets.developer.telegram.TelegramGateway;

import java.util.ArrayList;
import java.util.List;

/**
 * Post-Validation — объединяет валидацию (запуск тестов) и пост-обработку (PR / reroute).
 * Если VALIDATION в state нет — запускает тесты через OpenCode.
 * Если тесты прошли и есть ветка — создаёт PR через OpenCode.
 * Если тесты упали — LLM решает куда вернуться (reroute).
 * Если аналитическая задача (нет ветки) — формирует итоговый отчёт.
 */
@Component
public class PostValidationNode implements Agent {

    private static final Logger log = LoggerFactory.getLogger(PostValidationNode.class);

    private final OpenCodeClient openCode;
    private final OpenCodeSessionPool sessionPool;
    private final ChatClient chatClient;
    private final ChatClient fallbackChatClient;
    private final TelegramGateway telegram;
    private final StructuredOutputHelper structuredOutput;
    private final TaskRepository taskRepo;

    public PostValidationNode(OpenCodeClient openCode, OpenCodeSessionPool sessionPool,
                              @Qualifier("postValidationChatClient") ChatClient chatClient,
                              @Qualifier("fallbackChatClient") ChatClient fallbackChatClient,
                              TelegramGateway telegram, StructuredOutputHelper structuredOutput,
                              TaskRepository taskRepo) {
        this.openCode = openCode;
        this.sessionPool = sessionPool;
        this.chatClient = chatClient;
        this.fallbackChatClient = fallbackChatClient;
        this.telegram = telegram;
        this.structuredOutput = structuredOutput;
        this.taskRepo = taskRepo;
    }

    @Override
    public AgentResult execute(AgentContext ctx) {
        var validation = ctx.get(TaskState.VALIDATION);
        Integer reworkCountRaw = ctx.get(TaskState.REWORK_COUNT);
        int reworkCount = reworkCountRaw != null ? reworkCountRaw : 0;
        String chatId = ctx.get(TaskState.TG_CHAT_ID);
        String branch = ctx.get(TaskState.GIT_BRANCH);
        String spec = ctx.get(TaskState.SPEC);
        String agentRole = ctx.get(TaskState.AGENT_ROLE);

        String nextStep = ctx.get(TaskState.NEXT_STEP);

        log.info("Post-validation: start. reworkCount={}, branch={}, agentRole={}, nextStep={}",
                reworkCount, branch, agentRole, nextStep);

        if (chatId == null || chatId.isBlank()) {
            log.warn("Post-validation: нет chatId в контексте, пропуск (stale checkpoint)");
            return skipResult();
        }

        long chatIdLong = Long.parseLong(chatId);

        if (isAnalysisOnly(agentRole, nextStep)) {
            return handleAnalysisOnly(spec);
        }

        String taskId = ctx.get(TaskState.TASK_ID);
        Boolean requiresDev = ctx.get(TaskState.REQUIRES_DEVELOPMENT);
        Boolean requiresTest = ctx.get(TaskState.REQUIRES_TESTING);
        Boolean devDone = ctx.get(TaskState.DEVELOPMENT_DONE);
        Boolean testsWritten = ctx.get(TaskState.TESTS_WRITTEN);

        boolean needsDev = requiresDev != null && requiresDev;
        boolean needsTest = requiresTest != null && requiresTest;
        boolean isDevDone = devDone != null && devDone;
        boolean isTestsWritten = testsWritten != null && testsWritten;

        // Проверка: если требуется разработка, но она не проведена — блокируем
        if (needsDev && !isDevDone) {
            log.warn("Post-validation: требуется разработка, но developmentDone=false. Блокировка.");
            telegram.sendMessage(chatIdLong,
                    "❌ Невозможно продолжить: требуется разработка, но она не выполнена. Возврат к разработчику.");
            return AgentResult.builder()
                    .text("Blocked: development not done")
                    .stateUpdates(java.util.Map.of(
                            TaskState.REWORK_COUNT, reworkCount + 1,
                            TaskState.REROUTE_TARGET, "developer",
                            TaskState.AGENT_ROLE, "post_validation"))
                    .completed(true)
                    .build();
        }

        // Проверка: если требуется тестирование, но тесты не написаны — блокируем
        if (needsTest && !isTestsWritten) {
            log.warn("Post-validation: требуется тестирование, но testsWritten=false. Блокировка.");
            telegram.sendMessage(chatIdLong,
                    "❌ Невозможно продолжить: требуются тесты, но они не написаны. Возврат к тестировщику.");
            return AgentResult.builder()
                    .text("Blocked: tests not written")
                    .stateUpdates(java.util.Map.of(
                            TaskState.REWORK_COUNT, reworkCount + 1,
                            TaskState.REROUTE_TARGET, "tester",
                            TaskState.AGENT_ROLE, "post_validation"))
                    .completed(true)
                    .build();
        }

        if (validation == null && needsTest) {
            telegram.sendMessage(chatIdLong, "🔬 Запускаю тесты...");
            validation = runTests(branch, chatIdLong);
            if (validation == null) {
                log.warn("Post-validation: не удалось распарсить отчёт тестов, считаем pass");
                validation = new ValidationReport(
                        ValidationReport.Status.PASS, 0, 0, 0,
                        List.of(), List.of());
            }
            // Отмечаем testingDone
            taskRepo.findById(taskId).ifPresent(task -> {
                task.setTestingDone(true);
                taskRepo.save(task);
            });
        }

        telegram.sendMessage(chatIdLong, "🔍 Post-validation: анализ результатов...");

        String openCodeOutput;
        if (validation != null && validation.isPass()) {
            openCodeOutput = createPullRequest(branch, spec, chatIdLong);
            if (openCodeOutput == null) {
                return AgentResult.failed(io.github.asekka.springai.agents.core.AgentError.of("post_validation",
                        new RuntimeException("Ошибка OpenCode при создании PR")));
            }
        } else {
            openCodeOutput = buildReroutePrompt(spec, branch, validation, reworkCount);
        }

        AgentResponses.PostValidationDecision decision = parseDecision(openCodeOutput, chatIdLong);
        if (decision == null) {
            return AgentResult.failed(io.github.asekka.springai.agents.core.AgentError.of("post_validation",
                    new RuntimeException("Пустой ответ LLM")));
        }

        return applyDecision(decision, reworkCount, chatIdLong, taskId);
    }

    private AgentResult skipResult() {
        return AgentResult.builder()
                .text("Skipped: no chat context")
                .stateUpdates(java.util.Map.of(
                        TaskState.AGENT_ROLE, "post_validation"))
                .completed(true)
                .build();
    }

    private boolean isAnalysisOnly(String agentRole, String nextStep) {
        if (!"analyst".equals(agentRole)) return false;
        return nextStep == null || "done".equals(nextStep);
    }

    private AgentResult handleAnalysisOnly(String spec) {
        log.info("Post-validation: аналитическая задача, пропускаем тесты и PR (ответ уже отправлен аналитиком)");
        String summary = (spec != null && !spec.isBlank()) ? spec : "Готово";
        return AgentResult.builder()
                .text(summary)
                .stateUpdates(java.util.Map.of(
                        TaskState.AGENT_ROLE, "post_validation"))
                .completed(true)
                .build();
    }

    private String createPullRequest(String branch, String spec, long chatIdLong) {
        int slot = sessionPool.acquire(600);
        if (slot < 0) {
            telegram.sendMessage(chatIdLong, "❌ Таймаут ожидания слота OpenCode");
            return null;
        }
        try {
            sessionPool.prepareSlot(slot);
            String workDir = sessionPool.getSlotWorkDir(slot);
            String ocPrompt = getPrPrompt(branch, spec);

            var ocResult = openCode.runAgent("post_validation", ocPrompt, workDir);
            String output = ocResult.output() != null ? ocResult.output() : "";
            log.info("Post-validation: OpenCode завершён. output: {} символов", output.length());
            return output;
        } catch (Exception e) {
            log.error("Post-validation: ошибка OpenCode: {}", e.getMessage(), e);
            telegram.sendMessage(chatIdLong, "❌ Ошибка OpenCode: " + e.getMessage());
            return null;
        } finally {
            sessionPool.cleanupSlot(slot);
            sessionPool.release(slot);
        }
    }

    private static String getPrPrompt(String branch, String spec) {
        String branchOrNa = branch != null && !branch.isBlank() ? branch : "N/A";

        return """
                Тесты прошли. Создай Pull Request через GitHub MCP инструмент.
                Если есть ветка %s — переключись на неё: git checkout %s
                
                - head: %s
                - base: main
                - title: Описание задачи
                - body: ТЗ из контекста
                
                В конце ответа выведи JSON: prUrl, reroute, failed, summary.
                
                ТЗ:
                %s
                """.formatted(branchOrNa, branchOrNa, branchOrNa,
                spec != null ? spec.substring(0, Math.min(500, spec.length())) : "N/A");
    }

    private static String getTestPrompt(String branch) {
        String branchInfo = branch != null && !branch.isBlank()
                ? "Если есть ветка %s — переключись на неё: git checkout %s".formatted(branch, branch)
                : "Работай на текущей ветке.";

        return """
                Запусти все тесты проекта и проанализируй результаты.
                %s
                
                Верни отчёт в JSON: status (pass/fail), total, passed, failed,
                failures (массив: test, type=code_bug|logic_bug|test_bug, message, details),
                coverage_gaps (массив строк).
                Не редактируй код.
                """.formatted(branchInfo);
    }

    private String buildReroutePrompt(String spec, String branch, ValidationReport validation, int reworkCount) {
        String validationSummary;
        if (validation != null) {
            validationSummary = "pass=%b, codeBugs=%b, logicBugs=%b, testBugs=%b, total=%d, passed=%d, failed=%d, failures=%s".formatted(
                    validation.isPass(),
                    validation.hasCodeBugs(),
                    validation.hasLogicBugs(),
                    validation.hasTestBugs(),
                    validation.total(),
                    validation.passed(),
                    validation.failed(),
                    validation.failures() != null ? validation.failures().toString() : "[]");
        } else {
            validationSummary = "validation=null (тесты не запускались)";
        }

        return """
                Контекст пост-валидации:
                - ТЗ: %s
                - Ветка: %s
                - Отчёт валидатора: %s
                - Счётчик доработок: %d/3
                
                Тесты упали. Реши, куда вернуться: developer, analyst или tester.
                Если лимит доработок превышен (>=3) — сообщи failed.
                
                Выведи JSON:
                ```json
                {
                  "prUrl": null,
                  "reroute": "developer",
                  "failed": null,
                  "summary": "Возврат к разработчику"
                }
                ```
                """.formatted(
                spec != null ? spec.substring(0, Math.min(500, spec.length())) : "N/A",
                branch != null ? branch : "N/A",
                validationSummary,
                reworkCount);
    }

    private AgentResponses.PostValidationDecision parseDecision(String openCodeOutput, long chatIdLong) {
        try {
            String parsePrompt = """
                    Извлеки JSON из ответа post-validation и верни как structured output.
                    Если поле отсутствует — верни null.
                    
                    Ответ:
                    %s
                    """.formatted(openCodeOutput);

            var decision = structuredOutput.callWithFallback(
                    chatClient, fallbackChatClient, parsePrompt, AgentResponses.PostValidationDecision.class);

            if (decision == null) {
                log.error("Post-validation: пустой ответ LLM");
                telegram.sendMessage(chatIdLong, "❌ Post-validation: пустой ответ от LLM");
            }

            return decision;
        } catch (Exception e) {
            log.error("Post-validation: ошибка парсинга: {}", e.getMessage(), e);
            telegram.sendMessage(chatIdLong, "❌ Ошибка post-validation: " + e.getMessage());
            return null;
        }
    }

    private AgentResult applyDecision(AgentResponses.PostValidationDecision decision, int reworkCount, long chatIdLong, String taskId) {
        log.info("Post-validation: решение — prUrl={}, reroute={}, failed={}",
                decision.prUrl(), decision.reroute(), decision.failed());
        String summary = decision.summary() != null ? decision.summary() : "";

        if (decision.prUrl() != null && !decision.prUrl().isBlank()) {
            log.info("Post-validation: PR создан — {}", decision.prUrl());
            telegram.sendMessage(chatIdLong, "✅ PR создан: " + decision.prUrl());
            taskRepo.findById(taskId).ifPresent(task -> {
                task.setPrCreated(true);
                taskRepo.save(task);
            });
            return AgentResult.builder()
                    .text(decision.prUrl())
                    .stateUpdates(java.util.Map.of(
                            TaskState.AGENT_ROLE, "post_validation",
                            TaskState.PR_CREATED, true))
                    .completed(true)
                    .build();
        }

        if (decision.reroute() != null && !decision.reroute().isBlank()) {
            int newReworkCount = reworkCount + 1;
            log.info("Post-validation: LLM решила вернуться к узлу '{}' (reworkCount={})", decision.reroute(), newReworkCount);
            telegram.sendMessage(chatIdLong,
                    "🔄 Возврат к узлу: " + decision.reroute() + " (попытка " + newReworkCount + "/3)");
            return AgentResult.builder()
                    .text(summary)
                    .stateUpdates(java.util.Map.of(
                            TaskState.REWORK_COUNT, newReworkCount,
                            TaskState.REROUTE_TARGET, decision.reroute(),
                            TaskState.AGENT_ROLE, "post_validation"))
                    .completed(true)
                    .build();
        }

        if (decision.failed() != null && !decision.failed().isBlank()) {
            log.warn("Post-validation: LLM сообщила FAILED — {}", decision.failed());
            telegram.sendMessage(chatIdLong, "❌ " + decision.failed());
            return AgentResult.failed(io.github.asekka.springai.agents.core.AgentError.of("post_validation",
                    new RuntimeException(decision.failed())));
        }

        // PR был создан, но URL не получен — просим агента вернуть ссылку
        if (reworkCount < 3) {
            log.warn("Post-validation: PR создан без URL, reroute к post_validation за ссылкой (reworkCount={})", reworkCount);
            telegram.sendMessage(chatIdLong, "⚠️ PR создан, но ссылка не получена. Запрашиваю URL...");
            return AgentResult.builder()
                    .text(summary)
                    .stateUpdates(java.util.Map.of(
                            TaskState.REWORK_COUNT, reworkCount + 1,
                            TaskState.REROUTE_TARGET, "post_validation",
                            TaskState.AGENT_ROLE, "post_validation"))
                    .completed(true)
                    .build();
        }

        log.warn("Post-validation: LLM не выдала prUrl/reroute/failed, summary={}", decision.summary());
        String fallbackMsg = decision.summary() != null ? decision.summary() : "Решение не определено";
        telegram.sendMessage(chatIdLong, "⚠️ Post-validation: " + fallbackMsg);
        return AgentResult.builder()
                .text(fallbackMsg)
                .stateUpdates(java.util.Map.of(
                        TaskState.AGENT_ROLE, "post_validation"))
                .completed(true)
                .build();
    }

    /**
     * Запуск тестов через OpenCode.
     */
    private ValidationReport runTests(String branch, long chatIdLong) {
        int slot = sessionPool.acquire(600);
        if (slot < 0) {
            log.error("Post-validation: таймаут ожидания слота OpenCode для тестов");
            telegram.sendMessage(chatIdLong, "⚠️ Не удалось получить слот OpenCode для запуска тестов");
            return null;
        }

        try {
            sessionPool.prepareSlot(slot);
            String workDir = sessionPool.getSlotWorkDir(slot);

            String prompt = getTestPrompt(branch);

            var result = openCode.runAgent("post_validation", prompt, workDir);

            if (result.error() != null && !result.error().isEmpty()) {
                log.error("Post-validation: ошибка запуска тестов: {}", result.error());
            }

            ValidationReport report = parseValidationReport(result.output());

            if (report == null) {
                log.warn("Post-validation: не удалось распарсить JSON-отчёт тестов");
                return null;
            }

            log.info("Post-validation: тесты завершены. status={}, passed={}/{}", report.status(), report.passed(), report.total());

            String summary = "📊 Тесты: %s. Прошло: %d/%d, Упало: %d".formatted(
                    report.status(), report.passed(), report.total(), report.failed());

            if (report.isPass()) {
                telegram.sendMessage(chatIdLong, summary);
            } else {
                StringBuilder msg = new StringBuilder(summary + "\n\n");
                for (var f : report.failures()) {
                    msg.append("• `").append(f.test()).append("` (")
                            .append(f.type()).append("): ")
                            .append(f.message()).append("\n");
                }
                telegram.sendMessage(chatIdLong, msg.toString());
            }

            return report;

        } catch (Exception e) {
            log.error("Post-validation: ошибка запуска тестов: {}", e.getMessage(), e);
            return null;
        } finally {
            sessionPool.cleanupSlot(slot);
            sessionPool.release(slot);
        }
    }

    private ValidationReport parseValidationReport(String output) {
        if (output == null || output.isBlank()) {
            log.warn("Пустой output от валидатора");
            return null;
        }

        try {
            String parsePrompt = """
                    Извлеки JSON-отчёт тестов из ответа валидатора и верни как structured output.
                    Если поле отсутствует — верни null или 0.
                    
                    Ответ:
                    %s
                    """.formatted(output);

            var dto = structuredOutput.callWithFallback(
                    chatClient, fallbackChatClient, parsePrompt, AgentResponses.ValidatorReportDto.class);

            if (dto == null) {
                log.warn("Post-validation: structured output вернул null для отчёта тестов");
                return null;
            }

            var status = ValidationReport.Status.valueOf(dto.status().toUpperCase());
            List<ValidationReport.Failure> failures = new ArrayList<>();
            if (dto.failures() != null) {
                for (var f : dto.failures()) {
                    var type = ValidationReport.FailureType.valueOf(f.type().toUpperCase());
                    failures.add(new ValidationReport.Failure(f.test(), type, f.message(), f.details()));
                }
            }

            List<String> coverageGaps = dto.coverageGaps() != null ? dto.coverageGaps() : List.of();

            return new ValidationReport(status, dto.total(), dto.passed(), dto.failed(), failures, coverageGaps);

        } catch (Exception e) {
            log.error("Ошибка парсинга отчёта валидатора: {}", e.getMessage());
            return null;
        }
    }
}
