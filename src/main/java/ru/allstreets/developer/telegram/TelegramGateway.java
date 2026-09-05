package ru.allstreets.developer.telegram;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
public class TelegramGateway {

    private static final Logger log = LoggerFactory.getLogger(TelegramGateway.class);

    private final RestClient api;
    private final RateLimiter rateLimiter;
    private final ChatMemoryService chatMemory;
    private final ChatClient fastChatClient;

    public TelegramGateway(@Value("${telegram.bot-token}") String botToken,
                           RateLimiterRegistry rateLimiterRegistry,
                           ChatMemoryService chatMemory,
                           @Qualifier("fallbackChatClient") ChatClient fastChatClient) {
        this.api = RestClient.builder()
                .baseUrl("https://api.telegram.org/bot" + botToken)
                .build();
        this.rateLimiter = rateLimiterRegistry.rateLimiter("telegram");
        this.chatMemory = chatMemory;
        this.fastChatClient = fastChatClient;
    }

    public void sendMessage(long chatId, String text) {
        sendMessage(chatId, text, null);
    }

    public void sendMessage(long chatId, String text, String taskId) {
        log.info("Отправка в ТГ chatId={}: {}", chatId, text.length() > 100 ? text.substring(0, 100) + "..." : text);
        try {
            String escaped = escapeMarkdownUnderscores(text);
            sendWithRetry(chatId, escaped, "Markdown");
            log.debug("sendMessage: успешно отправлено chatId={}", chatId);
            chatMemory.recordBotMessage(chatId, text, taskId);
        } catch (Exception e) {
            log.error("Ошибка отправки в ТГ chatId={}: {} | type={}", chatId, e.getMessage(), e.getClass().getName(), e);
        }
    }

    /**
     * Экранирует underscores между буквенно-цифровыми символами (NEW_LEAD → NEW\_LEAD),
     * чтобы Telegram Markdown не интерпретировал их как italic-маркеры.
     * Markdown-форматирование вида _italic_ (подчёркивание между пробелами/границами) сохраняется.
     */
    private String escapeMarkdownUnderscores(String text) {
        if (text == null || text.isEmpty()) return text;
        // _ между word-символами → \_  (NEW_LEAD, LEAD_STAGE_CHANGE и т.д.)
        return text.replaceAll("(?<=\\w)_(?=\\w)", "\\\\_");
    }

    private void sendWithRetry(long chatId, String text, String parseMode) {
        Runnable sendCall = () -> {
            log.trace("sendMessage: HTTP POST /sendMessage chatId={} textLen={} parseMode={}", chatId, text.length(), parseMode);
            var body = new java.util.HashMap<String, Object>(Map.of(
                    "chat_id", chatId,
                    "text", text
            ));
            if (parseMode != null) {
                body.put("parse_mode", parseMode);
            }
            var response = api.post().uri("/sendMessage")
                    .body(body)
                    .retrieve()
                    .toEntity(String.class);
            log.trace("sendMessage: HTTP ответ status={} body={}",
                    response.getStatusCode().value(),
                    response.getBody() != null && response.getBody().length() > 200
                            ? response.getBody().substring(0, 200) + "..."
                            : response.getBody());
        };
        try {
            RateLimiter.decorateRunnable(rateLimiter, sendCall).run();
        } catch (org.springframework.web.client.HttpClientErrorException.BadRequest e) {
            if (parseMode != null && e.getMessage().contains("can't parse entities")) {
                log.warn("sendMessage: Markdown parse error, переформатирую через LLM chatId={}", chatId);
                String fixed = reformatForTelegram(text);
                if (fixed != null && !fixed.isBlank()) {
                    sendWithRetry(chatId, fixed, "Markdown");
                } else {
                    log.warn("sendMessage: LLM переформатирование не удалось, отправляю plain text chatId={}", chatId);
                    sendWithRetry(chatId, text, null);
                }
            } else {
                throw e;
            }
        }
    }

    private String reformatForTelegram(String text) {
        try {
            String prompt = """
                    Переформатируй текст для отправки в Telegram с parse_mode=Markdown.
                    Исправь все некорректные Markdown символы (незакрытые _, *, [, ], ` и т.д.).
                    Сохрани смысл и структуру текста. Верни только исправленный текст без пояснений.
                    
                    Текст:
                    %s
                    """.formatted(text.length() > 3000 ? text.substring(0, 3000) + "..." : text);
            return fastChatClient.prompt().user(prompt).call().content();
        } catch (Exception e) {
            log.warn("reformatForTelegram: ошибка LLM: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Получение обновлений через long-polling.
     */
    public TelegramUpdates getUpdates(int offset, int timeout) {
        try {
            log.debug("getUpdates: HTTP запрос offset={} timeout={}", offset, timeout);
            var response = api.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/getUpdates")
                            .queryParam("offset", offset)
                            .queryParam("timeout", timeout)
                            .build())
                    .retrieve();
            var result = response.body(TelegramUpdates.class);
            int count = (result != null && result.result() != null) ? result.result().size() : 0;
            log.debug("getUpdates: ответ ok={} updates={}", result != null && result.ok(), count);
            return result;
        } catch (Exception e) {
            log.error("getUpdates: ошибка offset={} timeout={}: {}", offset, timeout, e.getMessage());
            return null;
        }
    }

    public record TelegramUpdates(
            boolean ok,
            java.util.List<Update> result
    ) {
    }

    public record Update(
            int update_id,
            Message message
    ) {
    }

    public record Message(
            long message_id,
            User from,
            Chat chat,
            String text,
            long date,
            java.util.List<MessageEntity> entities,
            Message reply_to_message
    ) {
    }

    public record MessageEntity(
            String type,
            long offset,
            long length,
            User user
    ) {
    }

    public record Chat(
            long id,
            String type
    ) {
    }

    public record User(
            long id,
            boolean is_bot,
            String first_name,
            String username
    ) {
    }
}
