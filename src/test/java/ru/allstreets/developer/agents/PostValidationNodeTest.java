package ru.allstreets.developer.agents;

import io.github.asekka.springai.agents.core.AgentContext;
import io.github.asekka.springai.agents.core.AgentResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import ru.allstreets.developer.checkpoint.TaskRepository;
import ru.allstreets.developer.state.TaskState;
import ru.allstreets.developer.telegram.TelegramGateway;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Пост-валидация: fail-fast границы (нельзя выпускать PR без required-разработки/тестов),
 * PR сбрасывает устаревший {@code REROUTE_TARGET}, а LLM-решение валидатора маршрутизирует
 * куда угодно (analyst/tester/developer). Тесты здесь НЕ запускаются — это делают tester/developer.
 */
class PostValidationNodeTest {

    private ValidatorService validator;
    private StructuredOutputHelper structuredOutput;
    private PostValidationNode node;

    @BeforeEach
    void setUp() {
        TaskRepository taskRepo = mock(TaskRepository.class);
        validator = mock(ValidatorService.class);
        structuredOutput = mock(StructuredOutputHelper.class);
        when(taskRepo.findById(anyString())).thenReturn(Optional.empty());
        node = new PostValidationNode(
                mock(ChatClient.class), mock(ChatClient.class), mock(TelegramGateway.class),
                structuredOutput, taskRepo, validator, "agent-generated", "test",
                mock(SlotUnavailableHandler.class));
    }

    private AgentContext baseCtx() {
        return AgentContext.of("задача")
                .with(TaskState.TG_CHAT_ID, "1")
                .with(TaskState.TASK_ID, "task-1")
                .with(TaskState.SPEC, "ТЗ")
                .with(TaskState.GIT_BRANCH, "feature/x")
                .with(TaskState.AGENT_ROLE, "developer")
                .with(TaskState.TARGET_REPO, "owner/repo");
    }

    private void validatorReturnsDecision(AgentResponses.PostValidationDecision decision) {
        when(validator.run(anyString(), anyLong(), any(), anyString())).thenReturn("raw output");
        when(structuredOutput.callWithFallback(any(), any(), anyString(),
                eq(AgentResponses.PostValidationDecision.class))).thenReturn(decision);
    }

    @Test
    void requiresDevelopmentNotDone_reroutesDeveloper_withoutCallingValidator() {
        var ctx = baseCtx()
                .with(TaskState.REQUIRES_DEVELOPMENT, true)
                .with(TaskState.DEVELOPMENT_DONE, false);

        AgentResult result = node.execute(ctx);

        assertEquals("developer", result.stateUpdates().get(TaskState.REROUTE_TARGET));
        assertEquals(1, result.stateUpdates().get(TaskState.REWORK_COUNT));
        assertEquals("post_validation", result.stateUpdates().get(TaskState.AGENT_ROLE));
        verifyNoInteractions(validator);
    }

    @Test
    void requiresTestsNotWritten_reroutesTester_withoutCallingValidator() {
        var ctx = baseCtx()
                .with(TaskState.REQUIRES_DEVELOPMENT, true)
                .with(TaskState.DEVELOPMENT_DONE, true)
                .with(TaskState.REQUIRES_TESTING, true)
                .with(TaskState.TESTS_WRITTEN, false);

        AgentResult result = node.execute(ctx);

        assertEquals("tester", result.stateUpdates().get(TaskState.REROUTE_TARGET));
        verifyNoInteractions(validator);
    }

    @Test
    void requirementsSatisfied_runsValidatorAndAppliesPr() {
        var ctx = baseCtx()
                .with(TaskState.REQUIRES_DEVELOPMENT, true)
                .with(TaskState.DEVELOPMENT_DONE, true)
                .with(TaskState.REQUIRES_TESTING, true)
                .with(TaskState.TESTS_WRITTEN, true);
        validatorReturnsDecision(new AgentResponses.PostValidationDecision(
                "https://github.com/x/y/pull/1", null, null, null, "ok"));

        AgentResult result = node.execute(ctx);

        assertEquals(Boolean.TRUE, result.stateUpdates().get(TaskState.PR_CREATED));
        assertEquals("", result.stateUpdates().get(TaskState.REROUTE_TARGET));
        verify(validator).run(anyString(), anyLong(), any(), eq("task-1"));
    }

    @Test
    void validatorReturnsReroute_movesToChosenNode() {
        var ctx = baseCtx()
                .with(TaskState.REQUIRES_DEVELOPMENT, true)
                .with(TaskState.DEVELOPMENT_DONE, true);
        validatorReturnsDecision(new AgentResponses.PostValidationDecision(
                null, null, "analyst", null, "ТЗ неверно"));

        AgentResult result = node.execute(ctx);

        assertEquals("analyst", result.stateUpdates().get(TaskState.REROUTE_TARGET));
        assertEquals(1, result.stateUpdates().get(TaskState.REWORK_COUNT));
    }

    @Test
    void prCreated_clearsStaleRerouteTarget() {
        var decision = new AgentResponses.PostValidationDecision(
                "https://github.com/x/y/pull/29", null, null, null, "ok");

        AgentResult result = node.applyDecision(decision, 1, 1L, "task-1");

        assertEquals("", result.stateUpdates().get(TaskState.REROUTE_TARGET),
                "после PR устаревший REROUTE_TARGET должен быть сброшен");
    }

    @Test
    void unresolvedDecision_failsInsteadOfSelfLoop() {
        var decision = new AgentResponses.PostValidationDecision(null, null, null, null, "");

        AgentResult result = node.applyDecision(decision, 1, 1L, "task-1");

        assertTrue(result.hasError(), "неопределённое решение — fail, а не само-возврат в post_validation");
    }

    @Test
    void doneWithoutPr_completesSuccessfully() {
        // Инцидент 427edb3c: трекерная задача без изменений кода — PR не нужен вовсе, но «выполнено»
        // должно быть валидным успехом, а не «решение не определено» (валидатор так и написал).
        var decision = new AgentResponses.PostValidationDecision(
                null, "Отчёт в BACKEND-437 приведён к фактической реализации", null, null, "ok");

        AgentResult result = node.applyDecision(decision, 1, 1L, "task-1");

        assertFalse(result.hasError(), "выполнено без PR — успех, а не ошибка");
        assertEquals("", result.stateUpdates().get(TaskState.REROUTE_TARGET));
    }

    @Test
    void validatorPrompt_requiresPrLabel() {
        var ctx = baseCtx()
                .with(TaskState.REQUIRES_DEVELOPMENT, true)
                .with(TaskState.DEVELOPMENT_DONE, true);
        validatorReturnsDecision(new AgentResponses.PostValidationDecision(
                "https://github.com/x/y/pull/1", null, null, null, "ok"));

        node.execute(ctx);

        var promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(validator).run(promptCaptor.capture(), anyLong(), any(), anyString());
        assertTrue(promptCaptor.getValue().contains("agent-generated"),
                "промпт валидатора должен требовать метку PR из GITHUB_PR_LABEL: " + promptCaptor.getValue());
        assertTrue(promptCaptor.getValue().contains("base — test"),
                "промпт валидатора должен указывать base-ветку из конфига: " + promptCaptor.getValue());
        assertTrue(promptCaptor.getValue().contains("В Трекер НЕ пиши"),
                "валидатор в Трекер не пишет — текст ведёт репортёр: " + promptCaptor.getValue());
    }
}
