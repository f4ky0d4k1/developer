package ru.allstreets.developer.opencode;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Прогресс прогона: tool-вызовы (из {@code tool}-партов), шаги и токены (из завершённых
 * assistant-сообщений) должны попадать в {@link TaskProgressRegistry} — раньше
 * recordToolCall/recordStepFinish не вызывались, и steps/tool_calls в деталях задачи были 0.
 */
class OpenCodeClientProgressTest {

    @Test
    void runAgent_recordsStepsToolCallsAndTokens() {
        OpenCodeApi api = mock(OpenCodeApi.class);
        OpenCodeRunRepository runRepo = mock(OpenCodeRunRepository.class);
        TaskProgressRegistry progress = mock(TaskProgressRegistry.class);

        when(runRepo.findByTaskIdAndAgentNameAndStatusInOrderByStartedAtDesc(anyString(), anyString(), any()))
                .thenReturn(List.of());
        when(api.health()).thenReturn(new OpenCodeApi.HealthInfo(true, "1.20.0"));
        when(api.createSession(anyString())).thenReturn("ses_1");

        String[] parentId = new String[1];
        doAnswer(inv -> {
            parentId[0] = inv.getArgument(2);
            return null;
        }).when(api).promptAsync(anyString(), anyString(), anyString(), anyString(), anyString());

        when(api.listMessagesPage(anyString(), anyString(), anyInt(), any()))
                .thenAnswer(inv -> new OpenCodeApi.MessagesPage(List.of(stepWithTools(parentId[0])), null));

        var client = new OpenCodeClient(api, runRepo, progress,
                new ru.allstreets.developer.metrics.TaskMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                30, 1, 30);
        var result = client.runAgent("developer", "промпт", "/work/slot-0", "task-1");

        assertEquals("success", result.status());
        verify(progress).recordToolCall("task-1", "read");
        verify(progress).recordToolCall("task-1", "bash");
        // шаг: input+output+reasoning = 10+20+0 = 30 токенов, cost 0.001, finish=stop
        verify(progress).recordStepFinish("task-1", 30L, 0.001, "stop");
    }

    private static OpenCodeApi.MessageEnvelope stepWithTools(String parentId) {
        var info = new OpenCodeApi.MessageInfo("msg_step1", "assistant",
                new OpenCodeApi.TimeInfo(1L, 2L), null, "stop", parentId, 0.001,
                new OpenCodeApi.Tokens(10, 20, 0));
        var parts = List.of(
                new OpenCodeApi.Part("step-start", null, null),
                new OpenCodeApi.Part("tool", null, "read"),
                new OpenCodeApi.Part("tool", null, "bash"),
                new OpenCodeApi.Part("step-finish", null, null),
                new OpenCodeApi.Part("text", "готово", null));
        return new OpenCodeApi.MessageEnvelope(info, parts);
    }
}
