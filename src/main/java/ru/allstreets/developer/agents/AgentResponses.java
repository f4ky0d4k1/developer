package ru.allstreets.developer.agents;

/**
 * Structured output records для LLM-агентов.
 * Используются с Spring AI .entity() для типобезопасного парсинга ответов.
 */
public final class AgentResponses {

    private AgentResponses() {
    }

    /**
     * Ответ fast mode — быстрый классификатор.
     */
    public record FastDecision(
            String action,
            String taskId,
            String text,
            String description
    ) {
    }

    /**
     * Ответ AnalystNode — результат работы аналитика.
     */
    public record AnalystResult(
            String spec,
            String branch,
            String trackerIssue,
            boolean needsClarification,
            String clarificationQuestion,
            String nextStep,
            boolean requiresDevelopment,
            boolean requiresTesting
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
