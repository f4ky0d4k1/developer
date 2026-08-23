package ru.allstreets.developer.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.util.Map;

/**
 * Расширение OpenAiChatOptions с поддержкой DeepSeek-специфичного параметра thinking.
 * DeepSeek V4-Flash/Pro включают thinking mode по умолчанию, что тратит reasoning tokens
 * и игнорирует temperature/top_p. Для fast-классификатора thinking не нужен.
 * <p>
 * Spring AI 1.0.0 OpenAiChatOptions не имеет поля thinking, поэтому наследуемся
 * и добавляем @JsonProperty("thinking") — Jackson сериализует его в request body.
 */
@Getter
public class DeepSeekChatOptions extends org.springframework.ai.openai.OpenAiChatOptions {

    @JsonProperty("thinking")
    private final Map<String, String> thinking;

    public DeepSeekChatOptions(org.springframework.ai.openai.OpenAiChatOptions base, Map<String, String> thinking) {
        // Копируем все поля из base через builder
        super();
        // OpenAiChatOptions не имеет copy-конструктора, используем builder pattern через merge
        // Но проще — создадим через toBuilder если есть, иначе установим поля напрямую
        this.thinking = thinking;
        // Копируем ключевые поля
        this.setModel(base.getModel());
        this.setTemperature(base.getTemperature());
        this.setMaxTokens(base.getMaxTokens());
        this.setTopP(base.getTopP());
    }

}
