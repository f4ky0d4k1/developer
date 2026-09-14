package ru.allstreets.developer.config;

import io.github.asekka.springai.agents.graph.RetryPolicy;
import org.junit.jupiter.api.Test;
import ru.allstreets.developer.opencode.OpenCodeTransientException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ретрай узла графа при транзиентной ошибке OpenCode (stall): 1 попытка + 2 ретрая,
 * ретраится только {@link OpenCodeTransientException} (в т.ч. обёрнутая в cause-chain),
 * «настоящие» ошибки не ретраятся.
 */
class AgentFlowConfigRetryTest {

    @Test
    void retryPolicy_allowsTwoRetries() {
        RetryPolicy policy = AgentFlowConfig.openCodeRetryPolicy();
        assertEquals(3, policy.maxAttempts(), "1 основная попытка + 2 ретрая");
    }

    @Test
    void retryPolicy_retriesTransientStall() {
        RetryPolicy policy = AgentFlowConfig.openCodeRetryPolicy();
        assertTrue(policy.retryOn().test(new OpenCodeTransientException("завис")));
        // узел оборачивает транзиентную ошибку в RuntimeException — ретраим по cause-chain
        assertTrue(policy.retryOn().test(
                new RuntimeException("Ошибка тестировщика", new OpenCodeTransientException("завис"))));
    }

    @Test
    void retryPolicy_doesNotRetryFatalErrors() {
        RetryPolicy policy = AgentFlowConfig.openCodeRetryPolicy();
        assertFalse(policy.retryOn().test(new RuntimeException("Ошибка тестировщика: агент вернул ошибку")));
        assertFalse(policy.retryOn().test(new IllegalStateException("Таймаут ожидания слота OpenCode")));
    }
}
