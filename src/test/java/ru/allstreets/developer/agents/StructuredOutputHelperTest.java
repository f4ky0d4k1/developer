package ru.allstreets.developer.agents;

import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Тест fallback-логики {@link StructuredOutputHelper}: когда LLM в ответе отдаёт
 * невалидный JSON (Spring AI {@code .entity()} бросает исключение или возвращает null),
 * помощник переключается на fallback-модель, а если и она не справилась — возвращает null.
 * Вызывающие узлы (AnalystNode и др.) по null понимают, что разбор не удался, и работают
 * с сырым текстом.
 */
class StructuredOutputHelperTest {

    private StructuredOutputHelper helper;

    private ChatClient primary;
    private ChatClient fallback;

    @BeforeEach
    void setUp() {
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ZERO)
                .build();
        helper = new StructuredOutputHelper(RetryRegistry.of(config));

        primary = mock(ChatClient.class);
        fallback = mock(ChatClient.class);
    }

    @Test
    void primaryReturnsValidResult_noFallback() {
        var expected = analystResult("spec from primary");
        stubEntity(primary, expected);

        var result = helper.callWithFallback(primary, fallback, "промпт", AgentResponses.AnalystResult.class);

        assertEquals(expected, result);
        verify(fallback, never()).prompt();
    }

    @Test
    void primaryReturnsNull_fallsBackToFallbackModel() {
        stubEntity(primary, null);
        var fallbackResult = analystResult("spec from fallback");
        stubEntity(fallback, fallbackResult);

        var result = helper.callWithFallback(primary, fallback, "промпт", AgentResponses.AnalystResult.class);

        assertEquals(fallbackResult, result);
        verify(fallback).prompt();
    }

    @Test
    void primaryThrowsInvalidJson_fallsBackToFallbackModel() {
        stubEntityThrows(primary, new IllegalStateException("model returned invalid JSON"));
        var fallbackResult = analystResult("spec from fallback");
        stubEntity(fallback, fallbackResult);

        var result = helper.callWithFallback(primary, fallback, "промпт", AgentResponses.AnalystResult.class);

        assertEquals(fallbackResult, result);
        verify(fallback).prompt();
    }

    @Test
    void bothModelsFail_returnsNull() {
        stubEntityThrows(primary, new IllegalStateException("primary: invalid JSON"));
        stubEntityThrows(fallback, new IllegalStateException("fallback: invalid JSON"));

        var result = helper.callWithFallback(primary, fallback, "промпт", AgentResponses.AnalystResult.class);

        assertNull(result);
    }

    @Test
    void fallbackNullClient_returnsNull() {
        stubEntityThrows(primary, new IllegalStateException("invalid JSON"));

        var result = helper.callWithFallback(primary, null, "промпт", AgentResponses.AnalystResult.class);

        assertNull(result);
    }

    // ---------------------------------------------------------------------

    private static void stubEntity(ChatClient client, AgentResponses.AnalystResult result) {
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec response = mock(ChatClient.CallResponseSpec.class);
        when(client.prompt()).thenReturn(request);
        when(request.user(anyString())).thenReturn(request);
        when(request.call()).thenReturn(response);
        when(response.entity(AgentResponses.AnalystResult.class)).thenReturn(result);
    }

    private static void stubEntityThrows(ChatClient client, RuntimeException ex) {
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec response = mock(ChatClient.CallResponseSpec.class);
        when(client.prompt()).thenReturn(request);
        when(request.user(anyString())).thenReturn(request);
        when(request.call()).thenReturn(response);
        when(response.entity(AgentResponses.AnalystResult.class)).thenThrow(ex);
    }

    private static AgentResponses.AnalystResult analystResult(String spec) {
        assertNotNull(spec);
        return new AgentResponses.AnalystResult(
                spec, null, false, null, AgentResponses.NextStep.DONE,
                false, false, null, null, null, null, null, null);
    }
}
