package ru.allstreets.developer.agents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import ru.allstreets.developer.opencode.OpenCodeClient;
import ru.allstreets.developer.opencode.OpenCodeSessionPool;
import ru.allstreets.developer.state.ValidationReport;
import ru.allstreets.developer.telegram.TelegramGateway;

import java.util.ArrayList;
import java.util.List;

/**
 * Запуск тестов проекта через OpenCode и разбор отчёта валидатора.
 * Выделено из {@link PostValidationNode} (SRP) — узел графа только оркестрирует
 * этап пост-валидации (тесты → PR/reroute), сам запуск тестов и парсинг их
 * результата — отдельная ответственность.
 */
@Component
public class TestExecutionService {

    private static final Logger log = LoggerFactory.getLogger(TestExecutionService.class);

    private final OpenCodeClient openCode;
    private final OpenCodeSessionPool sessionPool;
    private final ChatClient chatClient;
    private final ChatClient fallbackChatClient;
    private final TelegramGateway telegram;
    private final StructuredOutputHelper structuredOutput;

    public TestExecutionService(OpenCodeClient openCode, OpenCodeSessionPool sessionPool,
                                @Qualifier("postValidationChatClient") ChatClient chatClient,
                                @Qualifier("fallbackChatClient") ChatClient fallbackChatClient,
                                TelegramGateway telegram, StructuredOutputHelper structuredOutput) {
        this.openCode = openCode;
        this.sessionPool = sessionPool;
        this.chatClient = chatClient;
        this.fallbackChatClient = fallbackChatClient;
        this.telegram = telegram;
        this.structuredOutput = structuredOutput;
    }

    /**
     * Запуск тестов через OpenCode. Возвращает {@code null} при ошибке
     * (недоступен слот, ошибка OpenCode, нераспаршенный отчёт).
     */
    public ValidationReport runTests(String branch, long chatIdLong, String repoUrl, String taskId) {
        int slot = sessionPool.acquire(600);
        if (slot < 0) {
            log.error("Post-validation: таймаут ожидания слота OpenCode для тестов");
            telegram.sendMessage(chatIdLong, "⚠️ Не удалось получить слот OpenCode для запуска тестов");
            return null;
        }

        try {
            sessionPool.prepareSlot(slot, repoUrl);
            String workDir = sessionPool.getSlotWorkDir(slot);

            String prompt = getTestPrompt(branch);

            var result = openCode.runAgent("post_validation", prompt, workDir, taskId);

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
