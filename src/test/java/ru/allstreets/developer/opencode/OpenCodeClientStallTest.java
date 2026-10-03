package ru.allstreets.developer.opencode;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import ru.allstreets.developer.metrics.TaskMetrics;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Регрессия: когда sidecar принимает промпт, но assistant-ответ так и не появляется и сессия
 * становится idle (в {@code /session/status} её нет → {@code sessionIsBusy} = null), прогон
 * должен быть прерван как «зависший» за stallTimeout с выбросом {@link OpenCodeTransientException}
 * (её ретраит граф), а не висеть до полного timeout (инцидент 0f9e5fa2 — зависал 1800с).
 */
class OpenCodeClientStallTest {

    @Test
    void idleSessionWithNoReply_throwsTransientStall() throws Exception {
        OpenCodeApi api = mock(OpenCodeApi.class);
        OpenCodeRunRepository runRepo = mock(OpenCodeRunRepository.class);
        TaskProgressRegistry progress = mock(TaskProgressRegistry.class);
        TaskMetrics metrics = new TaskMetrics(new SimpleMeterRegistry());

        when(runRepo.findByTaskIdAndAgentNameAndStatusInOrderByStartedAtDesc(anyString(), anyString(), any()))
                .thenReturn(List.of());
        when(api.health()).thenReturn(new OpenCodeApi.HealthInfo(true, "1.18.18"));
        when(api.createSession(anyString())).thenReturn("ses_1");
        when(api.openEvents(anyString())).thenReturn(null);
        // сообщений прогона нет (только user-промпт, assistant-ответа не появляется)
        when(api.listMessagesPage(anyString(), anyString(), anyInt(), any()))
                .thenReturn(new OpenCodeApi.MessagesPage(List.of(), null));
        // сессии нет в /session/status -> sessionIsBusy = null
        when(api.sessionStatuses()).thenReturn(java.util.Map.of());

        var client = new OpenCodeClient(api, runRepo, progress, metrics, 30, 1, 2);

        long t0 = System.nanoTime();
        OpenCodeTransientException ex = assertThrows(OpenCodeTransientException.class,
                () -> client.runAgent("developer", "промпт", "/work/slot-0", "task-1"));
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertTrue(ex.getMessage() != null && ex.getMessage().contains("завис"),
                "ожидали причину stall: " + ex.getMessage());
        assertTrue(elapsedMs < 10000,
                "должен абортить за stall (~2-4с), а не ждать timeout 30с: " + elapsedMs + "ms");
    }

    @Test
    void busySessionWithNoProgress_throwsStallAfterBusyStallTimeout() throws Exception {
        OpenCodeApi api = mock(OpenCodeApi.class);
        OpenCodeRunRepository runRepo = mock(OpenCodeRunRepository.class);
        TaskProgressRegistry progress = mock(TaskProgressRegistry.class);
        TaskMetrics metrics = new TaskMetrics(new SimpleMeterRegistry());

        when(runRepo.findByTaskIdAndAgentNameAndStatusInOrderByStartedAtDesc(anyString(), anyString(), any()))
                .thenReturn(List.of());
        when(api.health()).thenReturn(new OpenCodeApi.HealthInfo(true, "1.18.18"));
        when(api.createSession(anyString())).thenReturn("ses_1");
        when(api.openEvents(anyString())).thenReturn(null);
        // Сессия busy — модель «думает», но нового шага/текста не появляется (зависший LLM-запрос).
        when(api.sessionStatus("ses_1")).thenReturn(new OpenCodeApi.SessionStatus("busy", 0, null, null));

        AtomicReference<String> messageId = new AtomicReference<>();
        doAnswer(inv -> {
            messageId.set(inv.getArgument(2));
            return null;
        }).when(api).promptAsync(anyString(), anyString(), anyString(), anyString(), anyString());

        // Один завершённый шаг с выполненным tool-call (read) и без финального текста — дальше тишина.
        when(api.listMessagesPage(anyString(), anyString(), anyInt(), any()))
                .thenAnswer(inv -> new OpenCodeApi.MessagesPage(
                        List.of(userMessage(messageId.get()), completedToolCallMessage(messageId.get())), null));

        // busy-stall = 2с (обычный stall = 120с — не должен вмешиваться в тест).
        var client = new OpenCodeClient(api, runRepo, progress, metrics, 30, 1, 120, 2);

        long t0 = System.nanoTime();
        OpenCodeTransientException ex = assertThrows(OpenCodeTransientException.class,
                () -> client.runAgent("tester", "промпт", "/work/slot-0", "task-1"));
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertTrue(ex.getMessage() != null && ex.getMessage().contains("busy"),
                "ожидали причину busy-stall: " + ex.getMessage());
        assertTrue(elapsedMs < 10000,
                "должен абортить за busy-stall (~2-4с), а не ждать timeout 30с: " + elapsedMs + "ms");
    }

    @Test
    void retrySession_throwsStallFast_notBusyStall() throws Exception {
        OpenCodeApi api = mock(OpenCodeApi.class);
        OpenCodeRunRepository runRepo = mock(OpenCodeRunRepository.class);
        TaskProgressRegistry progress = mock(TaskProgressRegistry.class);
        TaskMetrics metrics = new TaskMetrics(new SimpleMeterRegistry());

        when(runRepo.findByTaskIdAndAgentNameAndStatusInOrderByStartedAtDesc(anyString(), anyString(), any()))
                .thenReturn(List.of());
        when(api.health()).thenReturn(new OpenCodeApi.HealthInfo(true, "1.18.18"));
        when(api.createSession(anyString())).thenReturn("ses_1");
        when(api.openEvents(anyString())).thenReturn(null);
        // Сессия в retry — sidecar повторяет упавший LLM-запрос: это зависание, а не работа.
        when(api.sessionStatus("ses_1"))
                .thenReturn(new OpenCodeApi.SessionStatus("retry", 3, "rate limit", 123L));
        // assistant-ответ так и не появляется
        when(api.listMessagesPage(anyString(), anyString(), anyInt(), any()))
                .thenReturn(new OpenCodeApi.MessagesPage(List.of(), null));

        // 7-арг конструктор: stall=2с, busyStall=дефолт(900). retry должен абортить по stall,
        // а не ждать busy-stall.
        var client = new OpenCodeClient(api, runRepo, progress, metrics, 30, 1, 2);

        long t0 = System.nanoTime();
        OpenCodeTransientException ex = assertThrows(OpenCodeTransientException.class,
                () -> client.runAgent("tester", "промпт", "/work/slot-0", "task-1"));
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertTrue(ex.getMessage() != null && ex.getMessage().contains("retry"),
                "ожидали причину retry-stall: " + ex.getMessage());
        assertTrue(elapsedMs < 10000,
                "retry должен абортить за stall (~2-4с), а не ждать busy-stall 900с: " + elapsedMs + "ms");
    }

    private static OpenCodeApi.MessageEnvelope userMessage(String id) {
        var info = new OpenCodeApi.MessageInfo(id, "user",
                new OpenCodeApi.TimeInfo(1L, null), null, null, null, null, null);
        return new OpenCodeApi.MessageEnvelope(info, List.of(new OpenCodeApi.Part("text", "промпт", null)));
    }

    private static OpenCodeApi.MessageEnvelope completedToolCallMessage(String parentId) {
        var info = new OpenCodeApi.MessageInfo("msg_step", "assistant",
                new OpenCodeApi.TimeInfo(2L, 3L), null, "tool-calls", parentId, null, null);
        var tool = new OpenCodeApi.Part("tool", null, "read", new OpenCodeApi.Part.ToolState("completed"));
        return new OpenCodeApi.MessageEnvelope(info, List.of(new OpenCodeApi.Part("step-start", null, null), tool));
    }
}
