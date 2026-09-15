package ru.allstreets.developer.opencode;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import ru.allstreets.developer.metrics.TaskMetrics;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Регрессия: долгий tool-вызов ({@code state.status=running}) — это НЕ зависание. Sidecar не
 * помечает сессию busy ({@code /session/status} отдаёт {@code {}}), поэтому раньше stall
 * ориентировался только на новые шаги/текст и длинный {@code mvn test} вызывал холостой ретрай.
 */
class OpenCodeClientLongToolTest {

    @Test
    void runningTool_isNotTreatedAsStall() throws Exception {
        OpenCodeApi api = mock(OpenCodeApi.class);
        OpenCodeRunRepository runRepo = mock(OpenCodeRunRepository.class);
        TaskProgressRegistry progress = mock(TaskProgressRegistry.class);
        TaskMetrics metrics = new TaskMetrics(new SimpleMeterRegistry());

        when(runRepo.findByTaskIdAndAgentNameAndStatusInOrderByStartedAtDesc(anyString(), anyString(), any()))
                .thenReturn(List.of());
        when(api.health()).thenReturn(new OpenCodeApi.HealthInfo(true, "1.18.31"));
        when(api.createSession(anyString())).thenReturn("ses_1");
        when(api.openEvents(anyString())).thenReturn(null);
        // sidecar не помечает сессию busy — как в проде
        when(api.sessionStatuses()).thenReturn(java.util.Map.of());

        AtomicReference<String> messageId = new AtomicReference<>();
        doAnswer(inv -> {
            messageId.set(inv.getArgument(2));
            return null;
        }).when(api).promptAsync(anyString(), anyString(), anyString(), anyString(), anyString());

        AtomicInteger polls = new AtomicInteger();
        when(api.listMessagesPage(anyString(), anyString(), anyInt(), any()))
                .thenAnswer(inv -> {
                    String mid = messageId.get();
                    if (polls.incrementAndGet() <= 5) {
                        // tool ещё выполняется — дольше stall-таймаута
                        return new OpenCodeApi.MessagesPage(
                                List.of(userMessage(mid), runningToolMessage(mid)), null);
                    }
                    return new OpenCodeApi.MessagesPage(List.of(finalMessage(mid)), null);
                });

        // stall=2с, поллинг=1с, бюджет=30с: без фикса прогон упал бы стопом на долгом tool
        var client = new OpenCodeClient(api, runRepo, progress, metrics, 30, 1, 2);
        var result = client.runAgent("validator", "промпт", "/work/slot-0", "task-1");

        assertEquals("success", result.status(), "running-tool не должен валить прогон стопом");
    }

    private static OpenCodeApi.MessageEnvelope userMessage(String id) {
        var info = new OpenCodeApi.MessageInfo(id, "user",
                new OpenCodeApi.TimeInfo(1L, null), null, null, null, null, null);
        return new OpenCodeApi.MessageEnvelope(info, List.of(new OpenCodeApi.Part("text", "промпт", null)));
    }

    private static OpenCodeApi.MessageEnvelope runningToolMessage(String parentId) {
        var info = new OpenCodeApi.MessageInfo("msg_running", "assistant",
                new OpenCodeApi.TimeInfo(2L, null), null, "tool-calls", parentId, null, null);
        var tool = new OpenCodeApi.Part("tool", null, "bash", new OpenCodeApi.Part.ToolState("running"));
        return new OpenCodeApi.MessageEnvelope(info, List.of(
                new OpenCodeApi.Part("step-start", null, null), tool));
    }

    private static OpenCodeApi.MessageEnvelope finalMessage(String parentId) {
        var info = new OpenCodeApi.MessageInfo("msg_final", "assistant",
                new OpenCodeApi.TimeInfo(3L, 4L), null, "stop", parentId, 0.0,
                new OpenCodeApi.Tokens(1, 1, 0));
        return new OpenCodeApi.MessageEnvelope(info, List.of(new OpenCodeApi.Part("text", "готово", null)));
    }
}
