package ru.allstreets.developer.agents;

import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * Утилита для LLM-вызовов с structured output и fallback.
 * Попытка 1: основная модель .entity() (structured output) — с Resilience4j retry
 * Попытка 2: fallback модель .entity() (structured output) — с retry
 */
@Component
public class StructuredOutputHelper {

    private static final Logger log = LoggerFactory.getLogger(StructuredOutputHelper.class);

    private final Retry llmRetry;

    public StructuredOutputHelper(RetryRegistry retryRegistry) {
        this.llmRetry = retryRegistry.retry("llm");
    }

    /**
     * Вызвать LLM с structured output и fallback.
     * Попытка 1: основная модель .entity() — с Resilience4j retry
     * Попытка 2: fallback модель .entity() — с retry
     */
    public <T> T callWithFallback(ChatClient chatClient, ChatClient fallbackChatClient,
                                  String prompt, Class<T> targetClass) {
        // Попытка 1: основная модель .entity() — с retry
        try {
            T result = Retry.decorateSupplier(llmRetry, () ->
                    chatClient.prompt()
                            .user(prompt)
                            .call()
                            .entity(targetClass)
            ).get();
            if (result != null) {
                return result;
            }
            log.warn("StructuredOutput: .entity() вернул null для {}, fallback", targetClass.getSimpleName());
        } catch (Exception e) {
            log.warn("StructuredOutput: .entity() failed для {}: {}, fallback",
                    targetClass.getSimpleName(), e.getMessage());
        }

        // Попытка 2: fallback модель .entity() — с retry
        if (fallbackChatClient == null) {
            log.error("StructuredOutput: fallbackChatClient null — нет fallback для {}", targetClass.getSimpleName());
            return null;
        }

        try {
            T result = Retry.decorateSupplier(llmRetry, () ->
                    fallbackChatClient.prompt()
                            .user(prompt)
                            .call()
                            .entity(targetClass)
            ).get();
            if (result != null) {
                log.info("StructuredOutput: fallback .entity() успешен для {}", targetClass.getSimpleName());
                return result;
            }
            log.error("StructuredOutput: fallback .entity() вернул null для {}", targetClass.getSimpleName());
            return null;
        } catch (Exception e) {
            log.error("StructuredOutput: fallback .entity() failed для {}: {}",
                    targetClass.getSimpleName(), e.getMessage());
            return null;
        }
    }
}
