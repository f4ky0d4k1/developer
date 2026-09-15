package ru.allstreets.developer.opencode;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import ru.allstreets.developer.metrics.TaskMetrics;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Агент сам проверил окружение и остановился с «ENV-ERROR: …» — это провал прогона
 * (reason=env-error), а не «успех» с текстом ошибки (иначе задача молча «завершается»,
 * хотя тесты/сборка не состоялись из-за нехватки окружения).
 */
class OpenCodeClientEnvErrorTest {

    @Test
    void envErrorOutput_isFailedRun() throws Exception {
        OpenCodeApi api = mock(OpenCodeApi.class);
        OpenCodeRunRepository runRepo = mock(OpenCodeRunRepository.class);
        TaskProgressRegistry progress = mock(TaskProgressRegistry.class);
        TaskMetrics metrics = new TaskMetrics(new SimpleMeterRegistry());

        when(runRepo.findByTaskIdAndAgentNameAndStatusInOrderByStartedAtDesc(anyString(), anyString(), any()))
                .thenReturn(List.of());
        when(api.health()).thenReturn(new OpenCodeApi.HealthInfo(true, "1.18.31"));
        when(api.createSession(anyString())).thenReturn("ses_1");
        when(api.openEvents(anyString())).thenReturn(null);

        AtomicReference<String> messageId = new AtomicReference<>();
        doAnswer(inv -> {
            messageId.set(inv.getArgument(2));
            return null;
        }).when(api).promptAsync(anyString(), anyString(), anyString(), anyString(), anyString());

        when(api.listMessagesPage(anyString(), anyString(), anyInt(), any()))
                .thenAnswer(inv -> new OpenCodeApi.MessagesPage(List.of(assistantReply(messageId.get())), null));

        var client = new OpenCodeClient(api, runRepo, progress, metrics, 30, 1, 120);

        var result = client.runAgent("tester", "промпт", "/work/slot-0", "task-1");

        assertEquals("error", result.status());
        assertTrue(result.error() != null && result.error().contains("ENV-ERROR"),
                "ожидали env-error: " + result.error());
    }

    private static OpenCodeApi.MessageEnvelope assistantReply(String parentId) {
        return new OpenCodeApi.MessageEnvelope(
                new OpenCodeApi.MessageInfo(
                        "msg_assist", "assistant",
                        new OpenCodeApi.TimeInfo(1L, 2L),
                        null, "stop", parentId, null, null),
                List.of(new OpenCodeApi.Part("text", "ENV-ERROR: JDK 21 не найден (java -version упал)", null)));
    }
}
