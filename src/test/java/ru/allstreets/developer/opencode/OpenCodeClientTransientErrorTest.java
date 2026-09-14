package ru.allstreets.developer.opencode;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Ошибка извлечения тела ответа ({@link RestClientException} «Error while extracting response»)
 * — транзиентная: GET-опрос идемпотентен, поэтому продолжаем опрос, а не валим задачу сразу
 * (инцидент: developer упал сразу после старта).
 */
class OpenCodeClientTransientErrorTest {

    @Test
    void runAgent_retriesAfterResponseExtractionError() {
        OpenCodeApi api = mock(OpenCodeApi.class);
        OpenCodeRunRepository runRepo = mock(OpenCodeRunRepository.class);
        TaskProgressRegistry progress = mock(TaskProgressRegistry.class);

        when(runRepo.findByTaskIdAndAgentNameAndStatusInOrderByStartedAtDesc(anyString(), anyString(), any()))
                .thenReturn(List.of());
        when(api.health()).thenReturn(new OpenCodeApi.HealthInfo(true, "1.20.0"));
        when(api.createSession(anyString())).thenReturn("ses_1");

        // Перехватываем messageId, сгенерированный внутри runAgent, чтобы собрать ответ с нужным parentID.
        String[] parentId = new String[1];
        doAnswer(inv -> {
            parentId[0] = inv.getArgument(2);
            return null;
        }).when(api).promptAsync(anyString(), anyString(), anyString(), anyString(), anyString());

        when(api.listMessagesPage(anyString(), anyString(), anyInt(), any()))
                .thenThrow(new RestClientException(
                        "Error while extracting response for type [java.lang.String] and content type [application/json]"))
                .thenAnswer(inv -> new OpenCodeApi.MessagesPage(List.of(completed(parentId[0], "готово")), null));

        var client = new OpenCodeClient(api, runRepo, progress, 30, 1, 30);
        var result = client.runAgent("developer", "промпт", "/work/slot-0", "task-1");

        assertEquals("success", result.status());
        assertEquals("готово", result.output());
        verify(api, times(2)).listMessagesPage(anyString(), anyString(), anyInt(), any());
    }

    @SuppressWarnings("SameParameterValue")
    private static OpenCodeApi.MessageEnvelope completed(String parentId, String text) {
        var info = new OpenCodeApi.MessageInfo("msg_assist", "assistant",
                new OpenCodeApi.TimeInfo(1L, 2L), null, "stop", parentId, null, null);
        return new OpenCodeApi.MessageEnvelope(info, List.of(new OpenCodeApi.Part("text", text, null)));
    }
}
