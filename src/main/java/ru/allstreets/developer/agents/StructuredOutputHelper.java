package ru.allstreets.developer.agents;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * Утилита для LLM-вызовов с structured output и fallback.
 * Попытка 1: основная модель .entity() (structured output) — с Resilience4j retry
 * Попытка 2: дешёвая модель .content() + ручной маппинг через ObjectMapper — с retry
 */
@Component
public class StructuredOutputHelper {

    private static final Logger log = LoggerFactory.getLogger(StructuredOutputHelper.class);

    private final ObjectMapper objectMapper;
    private final Retry llmRetry;

    public StructuredOutputHelper(ObjectMapper objectMapper, RetryRegistry retryRegistry) {
        this.objectMapper = objectMapper;
        this.llmRetry = retryRegistry.retry("llm");
    }

    /**
     * Вызвать LLM с structured output и fallback на дешёвую модель.
     * Попытка 1: основная модель .entity() (structured output) — с retry
     * Попытка 2: дешёвая модель .content() + ручной JSON extraction — с retry
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
            log.warn("StructuredOutput: .entity() вернул null для {}, fallback на дешёвую модель", targetClass.getSimpleName());
        } catch (Exception e) {
            log.warn("StructuredOutput: .entity() failed для {}: {}, fallback на дешёвую модель",
                    targetClass.getSimpleName(), e.getMessage());
        }

        // Попытка 2: дешёвая модель .content() + ручной JSON extraction — с retry
        if (fallbackChatClient == null) {
            log.error("StructuredOutput: fallbackChatClient null — нет fallback для {}", targetClass.getSimpleName());
            return null;
        }

        try {
            String content = Retry.decorateSupplier(llmRetry, () ->
                    fallbackChatClient.prompt()
                            .user(prompt + "\n\nВерни только JSON без пояснений.")
                            .call()
                            .content()
            ).get();
            log.trace("StructuredOutput: fallback .content() = '{}' для {}",
                    content != null ? (content.length() > 300 ? content.substring(0, 300) + "..." : content) : "null",
                    targetClass.getSimpleName());
            if (content == null || content.isBlank()) {
                log.error("StructuredOutput: fallback .content() вернул null/пусто для {}", targetClass.getSimpleName());
                return null;
            }

            String json = extractJson(content);
            if (json == null) {
                log.error("StructuredOutput: JSON не найден в fallback content для {}", targetClass.getSimpleName());
                return null;
            }

            T result = objectMapper.readValue(json, targetClass);
            log.info("StructuredOutput: fallback на дешёвой модели успешен для {} → JSON='{}'",
                    targetClass.getSimpleName(),
                    json.length() > 200 ? json.substring(0, 200) + "..." : json);
            return result;

        } catch (Exception e) {
            log.error("StructuredOutput: fallback тоже failed для {}: {}",
                    targetClass.getSimpleName(), e.getMessage());
            return null;
        }
    }

    /**
     * Извлечь JSON из текста LLM (может быть в ```json блоке или просто {...}).
     */
    public String extractJson(String text) {
        if (text == null || text.isBlank()) return null;

        // ```json ... ```
        int jsonStart = text.indexOf("```json");
        if (jsonStart >= 0) {
            int contentStart = text.indexOf("\n", jsonStart) + 1;
            int contentEnd = text.indexOf("```", contentStart);
            if (contentEnd > contentStart) {
                return text.substring(contentStart, contentEnd).trim();
            }
        }

        // ``` ... ```
        jsonStart = text.indexOf("```");
        if (jsonStart >= 0) {
            int contentStart = text.indexOf("\n", jsonStart) + 1;
            int contentEnd = text.indexOf("```", contentStart);
            if (contentEnd > contentStart) {
                String content = text.substring(contentStart, contentEnd).trim();
                if (content.startsWith("{") || content.startsWith("[")) {
                    return content;
                }
            }
        }

        // Первый { ... последний }
        int braceStart = text.indexOf('{');
        int braceEnd = text.lastIndexOf('}');
        if (braceStart >= 0 && braceEnd > braceStart) {
            return text.substring(braceStart, braceEnd + 1);
        }

        // Первый [ ... последний ]
        int arrStart = text.indexOf('[');
        int arrEnd = text.lastIndexOf(']');
        if (arrStart >= 0 && arrEnd > arrStart) {
            return text.substring(arrStart, arrEnd + 1);
        }

        return null;
    }
}
