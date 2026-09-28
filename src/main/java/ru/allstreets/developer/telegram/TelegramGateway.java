package ru.allstreets.developer.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import ru.allstreets.developer.agents.AgentResponses;

import java.util.HashMap;
import java.util.Map;

@Component
public class TelegramGateway {

    private static final Logger log = LoggerFactory.getLogger(TelegramGateway.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient api;
    private final RateLimiter rateLimiter;
    private final ChatMemoryService chatMemory;
    private final ActiveTaskRegistry taskRegistry;
    private final ChatClient fastChatClient;
    private final ReplyAnchorRegistry replyAnchors;

    /**
     * Резолвер taskId → thread_id. {@code @Lazy} разрывает циклическую зависимость
     * {@link TelegramTopicService} → gateway (constructor) → topicService (field):
     * инжектится ленивый прокси, реальный бин создаётся при первом обращении, когда
     * gateway уже готов. Spring Boot 3.4 запрещает циклы бинов по умолчанию, ранняя
     * ссылка через field-injection больше не работает (инцидент 27.09.2026).
     */
    @Autowired(required = false)
    @Lazy
    private TelegramTopicService topicService;

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
            Long threadId = threadIdFor(chatId, taskId);
            sendWithRetry(chatId, escaped, "Markdown", anchorFor(chatId, taskId, threadId), threadId);
            log.debug("sendMessage: успешно отправлено chatId={} threadId={}", chatId, threadId);
            chatMemory.recordBotMessage(chatId, outgoing, taskId);
        } catch (Exception e) {
            log.error("Ошибка отправки в ТГ chatId={}: {} | type={}", chatId, e.getMessage(), e.getClass().getName(), e);
        }
    }

    /**
     * Anchor reply-to для исходящего сообщения, topic-scoped: берётся в рамках
     * пары {@code (chatId, threadId)}, чтобы ответ не утащило в чужую тему.
     * Fail-open: любая проблема реестра не мешает доставке — возвращаем {@code null}.
     */
    private Long anchorFor(long chatId, String taskId, Long threadId) {
        try {
            return replyAnchors.replyToFor(chatId, threadId, taskId);
        } catch (Exception e) {
            log.debug("reply-to: anchor недоступен chatId={} threadId={} taskId={}: {}",
                    chatId, threadId, taskId, e.getMessage());
            return null;
        }
    }

    /**
     * thread_id задачи для исходящего сообщения. {@code null} — темы нет (fallback:
     * общий чат, без {@code message_thread_id}). Fail-open.
     */
    private Long threadIdFor(long chatId, String taskId) {
        if (topicService == null || taskId == null || taskId.isBlank()) {
            return null;
        }
        try {
            return topicService.resolveThreadId(taskId);
        } catch (Exception e) {
            log.debug("thread_id: резолвер недоступен taskId={}: {}", taskId, e.getMessage());
            return null;
        }
    }

    /**
     * Разбор тела {@code /sendMessage} без forum-темы (General).
     * Package-private static — для тестов без HTTP.
     */
    static Map<String, Object> buildSendMessageBody(long chatId, String text, String parseMode, Long replyToMessageId) {
        return buildSendMessageBody(chatId, text, parseMode, replyToMessageId, null);
    }

    /**
     * Разбор тела {@code /sendMessage}: при известной forum-теме добавляется
     * {@code message_thread_id} (иначе сообщение утечёт в General), при наличии anchor —
     * {@code reply_parameters} (Bot API 7.0+). Package-private static — для тестов без HTTP.
     */
    static Map<String, Object> buildSendMessageBody(long chatId, String text, String parseMode,
                                                    Long replyToMessageId, Long threadId) {
        var body = new HashMap<String, Object>();
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
        if (threadId != null) {
            body.put("message_thread_id", threadId);
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
     * Отправить свободный текст с Markdown-разметкой агентов (спек/итог анализа).
     * Markdown конвертируется в Telegram HTML ({@code parse_mode=HTML}), чтобы списки,
     * нумерация, блоки кода и жирный отображались, а не текли как мусор.
     * При ошибке парсинга HTML — fallback на plain text (доставка важнее разметки).
     */
    public void sendMarkdownMessage(long chatId, String text, String taskId) {
        String outgoing = withTaskHeader(text, titleOf(taskId), taskId);
        String html = MarkdownToTelegramHtml.toHtml(outgoing);
        log.info("Отправка markdown в ТГ chatId={}: {}", chatId,
                html.length() > 100 ? html.substring(0, 100) + "..." : html);
        try {
            Long threadId = threadIdFor(chatId, taskId);
            sendWithRetry(chatId, html, "HTML", anchorFor(chatId, taskId, threadId), threadId);
            chatMemory.recordBotMessage(chatId, outgoing, taskId);
        } catch (Exception e) {
            log.error("Ошибка отправки markdown в ТГ chatId={}: {} | type={}",
                    chatId, e.getMessage(), e.getClass().getName(), e);
        }
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
            Long threadId = threadIdFor(chatId, taskId);
            sendWithRetry(chatId, outgoing, null, anchorFor(chatId, taskId, threadId), threadId);
            chatMemory.recordBotMessage(chatId, outgoing, taskId);
        } catch (Exception e) {
            log.error("Ошибка отправки plain в ТГ chatId={}: {} | type={}", chatId, e.getMessage(), e.getClass().getName(), e);
        }
    }

    /**
     * Отправить сообщение с inline-клавиатурой (кнопки). {@code inlineKeyboard} — список рядов,
     * каждый ряд — список кнопок вида {@code Map.of("text", label, "callback_data", data)}.
     * Текст конвертируется Markdown → Telegram HTML (как {@link #sendMarkdownMessage}).
     */
    public void sendMessageWithKeyboard(long chatId, String text,
                                        java.util.List<java.util.List<java.util.Map<String, String>>> inlineKeyboard,
                                        String taskId) {
        String outgoing = MarkdownToTelegramHtml.toHtml(withTaskHeader(text, titleOf(taskId), taskId));
        log.info("Отправка кнопок в ТГ chatId={} ({} рядов)", chatId, inlineKeyboard.size());
        Long threadId = threadIdFor(chatId, taskId);
        Long replyTo = anchorFor(chatId, taskId, threadId);
        var body = new java.util.HashMap<>(buildSendMessageBody(chatId, outgoing, "HTML", replyTo, threadId));
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
        sendWithRetry(chatId, text, parseMode, replyTo, null);
    }

    private void sendWithRetry(long chatId, String text, String parseMode, Long replyTo, Long threadId) {
        Runnable sendCall = () -> {
            log.trace("sendMessage: HTTP POST /sendMessage chatId={} textLen={} parseMode={} replyTo={} threadId={}",
                    chatId, text.length(), parseMode, replyTo, threadId);
            var body = buildSendMessageBody(chatId, text, parseMode, replyTo, threadId);
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
                sendWithRetry(chatId, text, parseMode, null, threadId);
            } else if (parseMode != null && e.getMessage().contains("can't parse entities")) {
                if ("HTML".equals(parseMode)) {
                    // HTML от конвертера должен быть валидным; на сбой — plain text, без LLM-перегонки.
                    log.warn("sendMessage: HTML parse error, отправляю plain text chatId={}", chatId);
                    sendWithRetry(chatId, text, null, replyTo, threadId);
                } else {
                    log.warn("sendMessage: Markdown parse error, переформатирую через LLM chatId={}", chatId);
                    String fixed = reformatForTelegram(text);
                    if (fixed != null && !fixed.isBlank()) {
                        sendWithRetry(chatId, fixed, "Markdown", replyTo, threadId);
                    } else {
                        log.warn("sendMessage: LLM переформатирование не удалось, отправляю plain text chatId={}", chatId);
                        sendWithRetry(chatId, text, null, replyTo, threadId);
                    }
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
     * Человекочитаемый заголовок чата: title группы/супергруппы или «Имя Фамилия»
     * личного чата. Fail-open: при любой ошибке/таймауте возвращает {@code null}
     * (вызывающий сам решает fallback), поэтому не влияет на основной поток.
     */
    public String getChatTitle(long chatId) {
        try {
            String body = api.get()
                    .uri(uriBuilder -> uriBuilder.path("/getChat")
                            .queryParam("chat_id", chatId)
                            .build())
                    .retrieve()
                    .body(String.class);
            return extractChatTitle(body);
        } catch (Exception e) {
            log.debug("getChatTitle: не удалось получить заголовок chatId={}: {}", chatId, e.getMessage());
            return null;
        }
    }

    /**
     * Разбор ответа {@code /getChat}: {@code result.title} для групп, иначе
     * {@code first_name + last_name} для личных чатов. Package-private static — для тестов.
     */
    static String extractChatTitle(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            JsonNode result = JSON.readTree(body).get("result");
            if (result == null || result.isNull()) return null;

            JsonNode title = result.get("title");
            if (title != null && !title.isNull() && !title.asText().isBlank()) {
                return title.asText();
            }

            String first = textOrNull(result.get("first_name"));
            String last = textOrNull(result.get("last_name"));
            if (first == null && last == null) return null;
            if (first == null) return last;
            return last == null ? first : first + " " + last;
        } catch (Exception e) {
            return null;
        }
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull()) return null;
        String value = node.asText();
        return value == null || value.isBlank() ? null : value;
    }

    // ------------------------------------------------------------------
    // Forum-topic API (fail-open: ошибки не валят задачу, вызывающий уходит в fallback)
    // ------------------------------------------------------------------

    /**
     * Создать forum-тему. Возвращает {@code message_thread_id} или {@code null}, если
     * Telegram отказал (нет прав {@code can_manage_topics}, 429, не форум) — это сигнал
     * вызывающему деградировать в fallback (общий чат + reply-to).
     */
    public Long createForumTopic(long chatId, String name) {
        try {
            var body = new HashMap<String, Object>();
            body.put("chat_id", chatId);
            body.put("name", name);
            String response = api.post().uri("/createForumTopic")
                    .body(body).retrieve().body(String.class);
            Long threadId = extractThreadId(response);
            log.info("createForumTopic: chatId={} name='{}' → threadId={}", chatId, name, threadId);
            return threadId;
        } catch (Exception e) {
            log.warn("createForumTopic: не удалось chatId={} name='{}': {}", chatId, name, e.getMessage());
            return null;
        }
    }

    /**
     * Переименовать forum-тему. {@code false} — тема удалена/нет прав/429.
     */
    public boolean editForumTopic(long chatId, long threadId, String name) {
        try {
            var body = new HashMap<String, Object>();
            body.put("chat_id", chatId);
            body.put("message_thread_id", threadId);
            body.put("name", name);
            api.post().uri("/editForumTopic").body(body).retrieve().toEntity(String.class);
            return true;
        } catch (Exception e) {
            log.warn("editForumTopic: chatId={} threadId={}: {}", chatId, threadId, e.getMessage());
            return false;
        }
    }

    /**
     * Закрыть forum-тему. Повторное закрытие — не ошибка (idempotent, fail-open).
     */
    public boolean closeForumTopic(long chatId, long threadId) {
        try {
            var body = new HashMap<String, Object>();
            body.put("chat_id", chatId);
            body.put("message_thread_id", threadId);
            api.post().uri("/closeForumTopic").body(body).retrieve().toEntity(String.class);
            return true;
        } catch (Exception e) {
            log.warn("closeForumTopic: chatId={} threadId={}: {}", chatId, threadId, e.getMessage());
            return false;
        }
    }

    /**
     * Закрепить сообщение. Для темы передаётся её {@code message_thread_id} —
     * id сервисного сообщения-описания темы.
     */
    public boolean pinChatMessage(long chatId, long messageId) {
        try {
            var body = new HashMap<String, Object>();
            body.put("chat_id", chatId);
            body.put("message_id", messageId);
            body.put("disable_notification", true);
            api.post().uri("/pinChatMessage").body(body).retrieve().toEntity(String.class);
            return true;
        } catch (Exception e) {
            log.warn("pinChatMessage: chatId={} messageId={}: {}", chatId, messageId, e.getMessage());
            return false;
        }
    }

    /**
     * Разбор {@code /createForumTopic}: {@code result.message_thread_id}. Package-private static — для тестов.
     */
    static Long extractThreadId(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) return null;
        try {
            JsonNode result = JSON.readTree(responseBody).get("result");
            if (result == null || result.isNull()) return null;
            JsonNode threadId = result.get("message_thread_id");
            return (threadId == null || threadId.isNull()) ? null : threadId.asLong();
        } catch (Exception e) {
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
            Message reply_to_message,
            Integer message_thread_id
    ) {
        /**
         * Совместимый конструктор без thread id (General / старые тесты).
         */
        public Message(long message_id, User from, Chat chat, String text, long date,
                       java.util.List<MessageEntity> entities, Message reply_to_message) {
            this(message_id, from, chat, text, date, entities, reply_to_message, null);
        }
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
            String type,
            Boolean is_forum
    ) {
        /**
         * Совместимый конструктор без {@code is_forum} (тесты/старые апдейты).
         */
        public Chat(long id, String type) {
            this(id, type, null);
        }
    }

    public record User(
            long id,
            boolean is_bot,
            String first_name,
            String username
    ) {
    }
}
