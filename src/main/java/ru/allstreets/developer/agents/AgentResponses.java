package ru.allstreets.developer.agents;

import java.util.List;

/**
 * Structured output records для LLM-агентов.
 * Используются с Spring AI .entity() для типобезопасного парсинга ответов.
 */
public final class AgentResponses {

    private AgentResponses() {
    }

    /**
     * Действия оркестратора. Запуска задачи среди них нет: запуск — это вызов инструмента
     * {@code launch_task} (с обязательным {@code repo} на уровне схемы тула), а не action.
     */
    public enum FastAction {
        HITL_ANSWER, ANSWER, STATUS, ERROR
    }

    public enum NextStep {
        DEVELOPER, TESTER, DONE
    }

    public record ReformattedText(String text) {
    }

    public record TaskTitle(String title) {
    }

    /**
     * Ответ fast mode — быстрый классификатор. Запуск задачи выполняется инструментом
     * {@code launch_task} (repo обязателен), поэтому здесь нет ни {@code repo}, ни action
     * запуска — только решение о диалоге/статусе.
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
     * <p>
     * {@code done} — отдельный успешный исход для задач, которым PR не нужен вовсе
     * (правки только в Трекере/документации): без него законный «выполнено без PR»
     * не выражался и падал как «решение не определено» (инцидент 427edb3c).
     */
    public record PostValidationDecision(
            String prUrl,
            String done,
            String reroute,
            String failed,
            String summary
    ) {
    }
}
