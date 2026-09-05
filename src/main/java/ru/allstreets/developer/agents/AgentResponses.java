package ru.allstreets.developer.agents;

import java.util.List;

/**
 * Structured output records для LLM-агентов.
 * Используются с Spring AI .entity() для типобезопасного парсинга ответов.
 */
public final class AgentResponses {

    private AgentResponses() {
    }

    public enum FastAction {
        LAUNCH_TASK, HITL_ANSWER, ANSWER, STATUS, ERROR
    }

    public enum NextStep {
        DEVELOPER, TESTER, DONE
    }

    public record ReformattedText(String text) {
    }

    public record TaskTitle(String title) {
    }

    /**
     * Ответ fast mode — быстрый классификатор.
     */
    public record FastDecision(
            FastAction action,
            String taskId,
            String text,
            String description
    ) {
    }

    /**
     * Ответ AnalystNode — результат работы аналитика (SDD-структурированный).
     */
    public record AnalystResult(
            String spec,
            String trackerIssue,
            boolean needsClarification,
            String clarificationQuestion,
            NextStep nextStep,
            boolean requiresDevelopment,
            boolean requiresTesting,
            String userStory,
            List<String> acceptanceCriteria,
            List<String> outOfScope,
            List<String> constraints,
            List<String> contextLinks,
            List<TaskBreakdownItem> taskBreakdown
    ) {
    }

    /**
     * Элемент декомпозиции задачи в SDD-спеке.
     */
    public record TaskBreakdownItem(
            String id,
            String description,
            java.util.List<String> files,
            int estimatedMinutes,
            java.util.List<String> dependsOn
    ) {
    }

    /**
     * Ответ PostValidationNode — решение оркестратора пост-валидации.
     */
    public record PostValidationDecision(
            String prUrl,
            String reroute,
            String failed,
            String summary
    ) {
    }

    /**
     * DTO для парсинга JSON-отчёта от OpenCode валидатора.
     */
    public record ValidatorReportDto(
            String status,
            int total,
            int passed,
            int failed,
            java.util.List<ValidatorFailureDto> failures,
            @com.fasterxml.jackson.annotation.JsonProperty("coverage_gaps") java.util.List<String> coverageGaps
    ) {
    }

    /**
     * DTO для элемента failures в отчёте валидатора.
     */
    public record ValidatorFailureDto(
            String test,
            String type,
            String message,
            String details
    ) {
    }
}
