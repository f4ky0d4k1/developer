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
import ru.allstreets.developer.agents.AgentResponses;

import java.util.Map;

@Component
public class TelegramGateway {

    private static final Logger log = LoggerFactory.getLogger(TelegramGateway.class);

    private final RestClient api;
    private final RateLimiter rateLimiter;
    private final ChatMemoryService chatMemory;
    private final ActiveTaskRegistry taskRegistry;
    private final ChatClient fastChatClient;
    private final ReplyAnchorRegistry replyAnchors;

    public TelegramGateway(@Value("${telegram.bot-token}") String botToken,
                           RateLimiterRegistry rateLimiterRegistry,
                           ChatMemoryService chatMemory,
                           ActiveTaskRegistry taskRegistry,
                           @Qualifier("fallbackChatClient") ChatClient fastChatClient,
                           ReplyAnchorRegistry replyAnchors) {
        this.api = RestClient.builder()
                .baseUrl("https://api.telegram.org/bot" + botToken)
                .requestInterceptor(new ru.allstreets.developer.config.LoggingClientHttpRequestInterceptor())
                .build();
        this.rateLimiter = rateLimiterRegistry.rateLimiter("telegram");
        this.chatMemory = chatMemory;
        this.taskRegistry = taskRegistry;
        this.fastChatClient = fastChatClient;
        this.replyAnchors = replyAnchors;
    }

    public void sendMessage(long chatId, String text) {
        sendMessage(chatId, text, null);
    }

    public void sendMessage(long chatId, String text, String taskId) {
        String outgoing = withTaskHeader(text, titleOf(taskId), taskId);
        log.info("Отправка в ТГ chatId={}: {}", chatId, outgoing.length() > 100 ? outgoing.substring(0, 100) + "..." : outgoing);
        try {
            String escaped = escapeMarkdownUnderscores(outgoing);
            sendWithRetry(chatId, escaped, "Markdown", anchorFor(chatId, taskId));
            log.debug("sendMessage: успешно отправлено chatId={}", chatId);
            chatMemory.recordBotMessage(chatId, outgoing, taskId);
        } catch (Exception e) {
            log.error("Ошибка отправки в ТГ chatId={}: {} | type={}", chatId, e.getMessage(), e.getClass().getName(), e);
        }
    }

    /**
     * Anchor reply-to для исходящего сообщения. Fail-open: любая проблема реестра
     * не мешает доставке — возвращаем {@code null} (отправка без reply-to).
     */
    private Long anchorFor(long chatId, String taskId) {
        try {
            return replyAnchors.replyToFor(chatId, taskId);
        } catch (Exception e) {
            log.debug("reply-to: anchor недоступен chatId={} taskId={}: {}", chatId, taskId, e.getMessage());
            return null;
        }
    }

    /**
     * Разбор тела {@code /sendMessage}: при наличии anchor добавляется {@code reply_parameters}
     * (Bot API 7.0+), при его отсутствии тело не меняется. Package-private static — для тестов
     * без HTTP.
     */
    static Map<String, Object> buildSendMessageBody(long chatId, String text, String parseMode, Long replyToMessageId) {
        var body = new java.util.HashMap<String, Object>();
        body.put("chat_id", chatId);
        body.put("text", text);
        if (parseMode != null) {
            body.put("parse_mode", parseMode);
        }
        if (replyToMessageId != null) {
            body.put("reply_parameters", Map.of(
                    "message_id", replyToMessageId,
                    "allow_sending_without_reply", true));
        }
        return body;
    }

    private String titleOf(String taskId) {
        if (taskId == null || taskId.isBlank()) return null;
        try {
            return taskRegistry.titleOf(taskId);
        } catch (Exception e) {
            log.debug("sendMessage: title для task={} недоступен: {}", taskId, e.getMessage());
            return null;
        }
    }

    /**
     * Заголовок к сообщению задачи: {@code 📋 <название> (<id8>)}. Название опускается,
     * если неизвестно; при пустом {@code taskId} текст не меняется.
     */
    static String withTaskHeader(String text, String title, String taskId) {
        if (taskId == null || taskId.isBlank()) return text;
        // Стартовое сообщение задачи само несёт «📋 title (ID: …)» — не дублируем.
        if (text != null && text.stripLeading().startsWith("📋")) return text;
        String shortId = taskId.length() > 8 ? taskId.substring(0, 8) : taskId;
        String label = (title != null && !title.isBlank()) ? title + " " : "";
        return "📋 " + label + "(" + shortId + ")\n" + text;
    }

    /**
     * Отправить сообщение <b>без разметки</b> (parse_mode отсутствует). Для свободного
     * текста — спек, отчётов, путей, кода — где спецсимволы `_ * [ ] ` ломают legacy
     * Markdown (инцидент: «сбитое» форматирование анализа). URL в тексте Telegram делает
     * кликабельным сам, поэтому ссылку достаточно передать голым адресом.
     */
    public void sendPlainMessage(long chatId, String text, String taskId) {
        String outgoing = withTaskHeader(text, titleOf(taskId), taskId);
        log.info("Отправка plain в ТГ chatId={}: {}", chatId, outgoing.length() > 100 ? outgoing.substring(0, 100) + "..." : outgoing);
        try {
            sendWithRetry(chatId, outgoing, null, anchorFor(chatId, taskId));
            chatMemory.recordBotMessage(chatId, outgoing, taskId);
        } catch (Exception e) {
            log.error("Ошибка отправки plain в ТГ chatId={}: {} | type={}", chatId, e.getMessage(), e.getClass().getName(), e);
        }
    }

    /**
     * Отправить сообщение с inline-клавиатурой (кнопки). {@code inlineKeyboard} — список рядов,
     * каждый ряд — список кнопок вида {@code Map.of("text", label, "callback_data", data)}.
     */
    public void sendMessageWithKeyboard(long chatId, String text,
                                        java.util.List<java.util.List<java.util.Map<String, String>>> inlineKeyboard,
                                        String taskId) {
        String outgoing = withTaskHeader(text, titleOf(taskId), taskId);
        log.info("Отправка кнопок в ТГ chatId={} ({} рядов)", chatId, inlineKeyboard.size());
        Long replyTo = anchorFor(chatId, taskId);
        var body = new java.util.HashMap<>(buildSendMessageBody(chatId, outgoing, "Markdown", replyTo));
        body.put("reply_markup", Map.of("inline_keyboard", inlineKeyboard));
        try {
            api.post().uri("/sendMessage").body(body).retrieve().toEntity(String.class);
            chatMemory.recordBotMessage(chatId, outgoing, taskId);
        } catch (org.springframework.web.client.HttpClientErrorException.BadRequest e) {
            if (replyTo != null && isReplyRelated(e)) {
                log.warn("sendMessageWithKeyboard: Telegram отклонил reply_parameters, повтор без reply-to chatId={}", chatId);
                body.remove("reply_parameters");
                try {
                    api.post().uri("/sendMessage").body(body).retrieve().toEntity(String.class);
                    chatMemory.recordBotMessage(chatId, outgoing, taskId);
                } catch (Exception retry) {
                    log.error("Ошибка отправки кнопок в ТГ chatId={}: {}", chatId, retry.getMessage(), retry);
                }
            } else {
                log.error("Ошибка отправки кнопок в ТГ chatId={}: {}", chatId, e.getMessage(), e);
            }
        } catch (Exception e) {
            log.error("Ошибка отправки кнопок в ТГ chatId={}: {}", chatId, e.getMessage(), e);
        }
    }

    /**
     * Кнопка inline-клавиатуры.
     */
    public static java.util.Map<String, String> button(String label, String callbackData) {
        return Map.of("text", label, "callback_data", callbackData);
    }

    /**
     * Подтвердить нажатие кнопки (убирает «часики» в клиенте).
     */
    public void answerCallbackQuery(String callbackQueryId, String text) {
        try {
            var body = new java.util.HashMap<String, Object>();
            body.put("callback_query_id", callbackQueryId);
            if (text != null && !text.isBlank()) {
                body.put("text", text);
            }
            api.post().uri("/answerCallbackQuery").body(body).retrieve().toEntity(String.class);
        } catch (Exception e) {
            log.warn("Ошибка answerCallbackQuery {}: {}", callbackQueryId, e.getMessage());
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

    private void sendWithRetry(long chatId, String text, String parseMode, Long replyTo) {
        Runnable sendCall = () -> {
            log.trace("sendMessage: HTTP POST /sendMessage chatId={} textLen={} parseMode={} replyTo={}",
                    chatId, text.length(), parseMode, replyTo);
            var body = buildSendMessageBody(chatId, text, parseMode, replyTo);
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
            if (replyTo != null && isReplyRelated(e)) {
                // Telegram не принял reply_parameters — доставка важнее: повторяем без reply-to.
                log.warn("sendMessage: Telegram отклонил reply_parameters ({}), повтор без reply-to chatId={}",
                        e.getMessage(), chatId);
                sendWithRetry(chatId, text, parseMode, null);
            } else if (parseMode != null && e.getMessage().contains("can't parse entities")) {
                log.warn("sendMessage: Markdown parse error, переформатирую через LLM chatId={}", chatId);
                String fixed = reformatForTelegram(text);
                if (fixed != null && !fixed.isBlank()) {
                    sendWithRetry(chatId, fixed, "Markdown", replyTo);
                } else {
                    log.warn("sendMessage: LLM переформатирование не удалось, отправляю plain text chatId={}", chatId);
                    sendWithRetry(chatId, text, null, replyTo);
                }
            } else {
                throw e;
            }
        }
    }

    /**
     * BadRequest вызван именно reply_parameters (сообщение-источник недоступно и т.п.)?
     */
    private static boolean isReplyRelated(org.springframework.web.client.HttpClientErrorException.BadRequest e) {
        String message = e.getMessage();
        return message != null && message.toLowerCase().contains("reply");
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
            var result = fastChatClient.prompt().user(prompt).call().entity(AgentResponses.ReformattedText.class);
            return result != null ? result.text() : null;
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
            Message message,
            CallbackQuery callback_query
    ) {
    }

    /**
     * Нажатие inline-кнопки.
     */
    public record CallbackQuery(
            String id,
            User from,
            Message message,
            String data
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
