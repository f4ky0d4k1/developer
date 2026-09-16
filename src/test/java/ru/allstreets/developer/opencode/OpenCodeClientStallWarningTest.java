package ru.allstreets.developer.opencode;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import ru.allstreets.developer.metrics.TaskMetrics;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * При ретрае после зависания (stall-guard оборвал прогон, статус ABORTED) свежий прогон
 * должен получать промпт с предупреждением — иначе модель повторяет тот же зависающий шаг.
 */
class OpenCodeClientStallWarningTest {

    @Test
    void runAgent_prependsStallWarning_whenPreviousRunAborted() {
        OpenCodeApi api = mock(OpenCodeApi.class);
        OpenCodeRunRepository runRepo = mock(OpenCodeRunRepository.class);
        TaskProgressRegistry progress = mock(TaskProgressRegistry.class);

        var aborted = new OpenCodeRunEntity();
        aborted.setStatus(OpenCodeRunStatus.ABORTED);
        aborted.setError("OpenCode агент завис: сессия idle без прогресса 300с");

        // withStallWarning ищет ABORTED-ран, resumeOrStart — RUNNING/STARTING.
        when(runRepo.findByTaskIdAndAgentNameAndStatusInOrderByStartedAtDesc(anyString(), anyString(), any()))
                .thenAnswer(inv -> {
                    List<?> statuses = inv.getArgument(2);
                    return statuses.contains(OpenCodeRunStatus.ABORTED) ? List.of(aborted) : List.of();
                });

        when(api.health()).thenReturn(new OpenCodeApi.HealthInfo(true, "1.20.0"));
        when(api.createSession(anyString())).thenReturn("ses_1");

        String[] parentId = new String[1];
        List<String> prompts = new ArrayList<>();
        doAnswer(inv -> {
            parentId[0] = inv.getArgument(2);
            prompts.add(inv.getArgument(4));
            return null;
        }).when(api).promptAsync(anyString(), anyString(), anyString(), anyString(), anyString());

        when(api.listMessagesPage(anyString(), anyString(), anyInt(), any()))
                .thenAnswer(inv -> new OpenCodeApi.MessagesPage(List.of(completed(parentId[0], "готово")), null));

        var client = new OpenCodeClient(api, runRepo, progress,
                new TaskMetrics(new SimpleMeterRegistry()), 30, 1, 30);
        var result = client.runAgent("analyst", "проанализируй задачу", "/work/slot-0", "task-1");

        assertEquals("success", result.status());
        assertEquals(1, prompts.size());
        String sent = prompts.getFirst();
        assertTrue(sent.contains("предыдущий прогон"), sent);
        assertTrue(sent.contains("зависание"), sent);
        assertTrue(sent.contains("проанализируй задачу"), "исходный промпт должен сохраниться: " + sent);
    }

    @Test
    void runAgent_doesNotWarn_whenNoAbortedRun() {
        OpenCodeApi api = mock(OpenCodeApi.class);
        OpenCodeRunRepository runRepo = mock(OpenCodeRunRepository.class);
        TaskProgressRegistry progress = mock(TaskProgressRegistry.class);

        when(runRepo.findByTaskIdAndAgentNameAndStatusInOrderByStartedAtDesc(anyString(), anyString(), any()))
                .thenReturn(List.of());

        when(api.health()).thenReturn(new OpenCodeApi.HealthInfo(true, "1.20.0"));
        when(api.createSession(anyString())).thenReturn("ses_1");

        String[] parentId = new String[1];
        List<String> prompts = new ArrayList<>();
        doAnswer(inv -> {
            parentId[0] = inv.getArgument(2);
            prompts.add(inv.getArgument(4));
            return null;
        }).when(api).promptAsync(anyString(), anyString(), anyString(), anyString(), anyString());

        when(api.listMessagesPage(anyString(), anyString(), anyInt(), any()))
                .thenAnswer(inv -> new OpenCodeApi.MessagesPage(List.of(completed(parentId[0], "готово")), null));

        var client = new OpenCodeClient(api, runRepo, progress,
                new TaskMetrics(new SimpleMeterRegistry()), 30, 1, 30);
        client.runAgent("analyst", "проанализируй задачу", "/work/slot-0", "task-1");

        assertEquals("проанализируй задачу", prompts.getFirst(), "без ABORTED-рана промпт не должен меняться");
    }

    private static OpenCodeApi.MessageEnvelope completed(String parentId, String text) {
        var info = new OpenCodeApi.MessageInfo("msg_assist", "assistant",
                new OpenCodeApi.TimeInfo(1L, 2L), null, "stop", parentId, null, null);
        return new OpenCodeApi.MessageEnvelope(info, List.of(new OpenCodeApi.Part("text", text, null)));
    }
}
