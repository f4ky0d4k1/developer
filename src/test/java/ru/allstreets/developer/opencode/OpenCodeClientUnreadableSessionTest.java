package ru.allstreets.developer.opencode;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import ru.allstreets.developer.metrics.TaskMetrics;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Если сессию НЕ удаётся прочитать (read/сетевая ошибка опроса) дольше stall-timeout —
 * прогон абортится как stall (OpenCodeTransientException → ретрай узла с предупреждением),
 * а не крутится до 30-мин deadline (инцидент «завис намертво»: Premature end of Content-Length).
 */
class OpenCodeClientUnreadableSessionTest {

    @Test
    void unreadableSession_abortsAsStall_notHangsToDeadline() {
        OpenCodeApi api = mock(OpenCodeApi.class);
        OpenCodeRunRepository runRepo = mock(OpenCodeRunRepository.class);
        TaskProgressRegistry progress = mock(TaskProgressRegistry.class);

        when(runRepo.findByTaskIdAndAgentNameAndStatusInOrderByStartedAtDesc(anyString(), anyString(), any()))
                .thenReturn(List.of());
        when(api.health()).thenReturn(new OpenCodeApi.HealthInfo(true, "1.20.0"));
        when(api.createSession(anyString())).thenReturn("ses_1");
        // sidecar не сообщает сессию busy (пустой статус) → stall-детектор должен сработать.
        when(api.sessionStatuses()).thenReturn(Map.of());

        doAnswer(inv -> null).when(api)
                .promptAsync(anyString(), anyString(), anyString(), anyString(), anyString());

        // Сессию стабильно не прочитать (Premature end of Content-Length и т.п.).
        when(api.listMessagesPage(anyString(), anyString(), anyInt(), any()))
                .thenThrow(new ResourceAccessException("I/O error on GET .../message: Premature end of Content-Length"));

        var client = new OpenCodeClient(api, runRepo, progress,
                new TaskMetrics(new SimpleMeterRegistry()), 30, 1, 1);

        assertThrows(OpenCodeTransientException.class,
                () -> client.runAgent("analyst", "промпт", "/work/slot-0", "task-1"),
                "нечитаемая сессия должна абортиться как stall, а не висеть до deadline");
    }
}
