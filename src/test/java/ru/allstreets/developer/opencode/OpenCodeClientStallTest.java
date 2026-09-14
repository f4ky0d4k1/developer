package ru.allstreets.developer.opencode;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import ru.allstreets.developer.metrics.TaskMetrics;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
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
}
