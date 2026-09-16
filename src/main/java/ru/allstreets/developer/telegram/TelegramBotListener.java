package ru.allstreets.developer.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.allstreets.developer.checkpoint.TaskRepository;
import ru.allstreets.developer.humanloop.HumanInputRegistry;

import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Component
public class TelegramBotListener {

    private static final Logger log = LoggerFactory.getLogger(TelegramBotListener.class);

    private final TelegramGateway telegram;
    private final TaskLauncher taskLauncher;
    private final ConversationAgent conversationAgent;
    private final ChatMemoryService chatMemory;
    private final HumanInputRegistry humanInputRegistry;
    private final ActiveTaskRegistry taskRegistry;
    private final TaskRepository taskRepo;
    private final ReplyAnchorRegistry replyAnchors;
    private final AtomicInteger lastUpdateId = new AtomicInteger(0);

    @Value("${telegram.polling-timeout:30}")
    private int pollingTimeout;

    @Value("${telegram.allowed-chat-ids:}")
    private String allowedChatIdsRaw;

    @Value("${telegram.bot-username:}")
    private String botUsername;

    private Set<Long> allowedChatIds;

    public TelegramBotListener(TelegramGateway telegram, TaskLauncher taskLauncher,
                               ConversationAgent conversationAgent, ChatMemoryService chatMemory,
                               HumanInputRegistry humanInputRegistry, ActiveTaskRegistry taskRegistry,
                               TaskRepository taskRepo, ReplyAnchorRegistry replyAnchors) {
        this.telegram = telegram;
        this.taskLauncher = taskLauncher;
        this.conversationAgent = conversationAgent;
        this.chatMemory = chatMemory;
        this.humanInputRegistry = humanInputRegistry;
        this.taskRegistry = taskRegistry;
        this.taskRepo = taskRepo;
        this.replyAnchors = replyAnchors;
    }

    @jakarta.annotation.PostConstruct
    void init() {
        if (allowedChatIdsRaw == null || allowedChatIdsRaw.isBlank()) {
            allowedChatIds = Set.of();
            log.warn("Telegram whitelist (telegram.allowed-chat-ids) не задан — бот НЕ будет обрабатывать сообщения " +
                    "ни из одного чата (deny-by-default). Задайте telegram.allowed-chat-ids для включения.");
        } else {
            allowedChatIds = Arrays.stream(allowedChatIdsRaw.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(Long::parseLong)
                    .collect(Collectors.toSet());
            log.info("Telegram whitelist активирован — разрешённые chatIds: {}", allowedChatIds);
        }
    }

    private boolean isChatAllowed(long chatId) {
        return !allowedChatIds.isEmpty() && allowedChatIds.contains(chatId);
    }

    /**
     * Сообщение — прямой ответ (reply) на сообщение бота: такое обращено к боту без @mention.
     * Отличаем ИМЕННО нашего бота (username совпадает с {@code telegram.bot-username}).
     */
    static boolean isReplyToBot(TelegramGateway.Message msg, String botUsername) {
        var replied = msg.reply_to_message();
        if (replied == null || replied.from() == null) return false;
        var from = replied.from();
        if (!from.is_bot()) return false;
        if (botUsername == null || botUsername.isBlank()) return true;
        return botUsername.equalsIgnoreCase(from.username());
    }

    /**
     * Проверка @mention бота в сообщении.
     * Сначала через entities[] (точное определение), затем fallback — substring search.
     */
    private boolean hasMention(TelegramGateway.Message msg, String text) {
        if (botUsername == null || botUsername.isBlank()) {
            return true; // botUsername не задан — пропускаем всё
        }

        // Проверяем entities[] от Telegram API (точное определение)
        if (msg.entities() != null && !msg.entities().isEmpty()) {
            String lowerBot = botUsername.toLowerCase();
            for (var entity : msg.entities()) {
                if ("mention".equals(entity.type())) {
                    int start = (int) entity.offset();
                    int end = (int) (entity.offset() + entity.length());
                    if (start < text.length() && end <= text.length()) {
                        String mention = text.substring(start, end);
                        if (mention.toLowerCase().equals("@" + lowerBot)) {
                            return true;
                        }
                    }
                }
            }
        }

        // Fallback: substring search если entities отсутствуют (старые клиенты)
        return text.toLowerCase().contains("@" + botUsername.toLowerCase());
    }

    private String formatActiveTasks(long chatId) {
        var tasks = taskRegistry.getActiveTasks(chatId);
        if (tasks.isEmpty()) {
            return "📋 Нет активных задач.";
        }
        StringBuilder sb = new StringBuilder("📋 Активные задачи:\n");
        tasks.forEach((tid, status) -> {
            sb.append("• ").append(tid, 0, 8).append(" → ").append(status);
            taskRepo.findById(tid).ifPresent(task -> {
                if (task.getTitle() != null && !task.getTitle().isBlank()) {
                    sb.append(" — ").append(task.getTitle());
                }
                if (task.getCreatedAt() != null) {
                    sb.append(" (").append(task.getCreatedAt().toString(), 0, 16).append(")");
                }
            });
            sb.append("\n");
        });
        return sb.toString();
    }

    /**
     * Нажатие inline-кнопки: {@code close:<taskId>} — закрыть задачу (освобождает слот).
     */
    private void handleCallbackQuery(TelegramGateway.CallbackQuery cq) {
        long chatId = (cq.message() != null && cq.message().chat() != null) ? cq.message().chat().id() : 0;
        String data = cq.data();
        log.info("TG callback: chat={} data={}", chatId, data);
        if (data == null || data.isBlank()) {
            telegram.answerCallbackQuery(cq.id(), null);
            return;
        }
        try {
            if (data.startsWith("close:")) {
                String taskId = data.substring("close:".length());
                boolean closed = taskLauncher.close(taskId, chatId);
                telegram.answerCallbackQuery(cq.id(), closed ? "Задача закрыта" : "Не удалось закрыть");
            } else {
                telegram.answerCallbackQuery(cq.id(), "Ок");
            }
        } catch (Exception e) {
            log.error("TG callback: ошибка data={}: {}", data, e.getMessage(), e);
            telegram.answerCallbackQuery(cq.id(), "Ошибка");
        }
    }

    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.SECONDS)
    public void poll() {
        int offset = lastUpdateId.get() + 1;
        log.debug("TG poll: запрос updates offset={}, timeout={}", offset, pollingTimeout);

        var updates = telegram.getUpdates(offset, pollingTimeout);
        if (updates == null) {
            log.warn("TG poll: getUpdates вернул null (ошибка сети или API)");
            return;
        }
        if (updates.result() == null || updates.result().isEmpty()) {
            log.debug("TG poll: нет новых updates (ok={}, пустой результат)", updates.ok());
            return;
        }

        log.info("TG poll: получено {} updates", updates.result().size());

        for (var update : updates.result()) {
            lastUpdateId.set(update.update_id());
            log.info("TG poll: обработка update_id={}", update.update_id());

            if (update.callback_query() != null) {
                handleCallbackQuery(update.callback_query());
                continue;
            }

            if (update.message() == null) {
                log.debug("TG poll: update_id={} — message=null", update.update_id());
                continue;
            }

            var msg = update.message();
            log.info("TG poll: update_id={} message_id={} from={} chat={} text_len={}",
                    update.update_id(), msg.message_id(),
                    msg.from() != null ? msg.from().username() : "null",
                    msg.chat() != null ? msg.chat().id() : "null",
                    msg.text() != null ? msg.text().length() : 0);

            if (msg.text() == null || msg.text().isBlank()) {
                log.debug("TG poll: update_id={} — пустой текст", update.update_id());
                continue;
            }

            var chat = msg.chat();
            if (chat == null) {
                log.debug("TG poll: update_id={} — chat=null, пропуск", update.update_id());
                continue;
            }
            var text = msg.text();
            var from = msg.from();
            String username = from != null ? from.username() : null;

            if (!isChatAllowed(chat.id())) {
                log.warn("TG poll: chatId={} НЕ в whitelist — игнор", chat.id());
                continue;
            }

            log.info("TG poll: сообщение принято chatId={} user={}: {}",
                    chat.id(), username, text.length() > 100 ? text.substring(0, 100) + "..." : text);

            // Anchor reply-to: немедленные ответы и последующие сообщения задачи уйдут reply на источник.
            replyAnchors.recordIncoming(chat.id(), msg.message_id());

            // Записываем в sliding window память чата
            chatMemory.recordUserMessage(chat.id(), text);

            // Pre-filtering: команды без LLM
            if (text.startsWith("/status")) {
                telegram.sendMessage(chat.id(), formatActiveTasks(chat.id()));
                continue;
            }

            if (text.startsWith("/start")) {
                telegram.sendMessage(chat.id(),
                        "Привет! Я агент-разработчик. Упомяни меня (@" + botUsername + ") чтобы поставить задачу.");
                continue;
            }

            // @mention filtering: агент запускается на @bot_mention
            boolean hasMention = hasMention(msg, text);

            // Если есть pending HITL-вопросы — пропускаем без @mention
            // (пользователь отвечает агенту, не обязательно упоминать бота)
            boolean hasPendingQuestions = humanInputRegistry.hasPendingInputs(chat.id());
            // В личке (private) @mention не нужен — бот и так единственный собеседник
            boolean isPrivateChat = "private".equals(chat.type());
            // Прямой ответ (reply) на сообщение бота — тоже обращён к боту, даже без @mention.
            boolean isReplyToBot = isReplyToBot(msg, botUsername);
            if (!hasMention && !hasPendingQuestions && !isPrivateChat && !isReplyToBot) {
                log.debug("TG poll: chatId={} — нет @mention, нет pending-вопросов и не reply боту, пропуск LLM вызова",
                        chat.id());
                continue;
            }
            if (!hasMention) {
                log.info("TG poll: chatId={} — нет @mention, но {} → пропуск к ConversationAgent",
                        chat.id(), isPrivateChat ? "личный чат"
                                : isReplyToBot ? "reply на сообщение бота"
                                  : "есть pending-вопросы");
            }

            // Делегируем ConversationAgent
            try {
                // Build raw message metadata for the agent
                StringBuilder rawMeta = new StringBuilder();
                rawMeta.append("Сообщение: ").append(text);
                if (msg.reply_to_message() != null) {
                    var replied = msg.reply_to_message();
                    rawMeta.append("\n[Reply-to message_id=").append(replied.message_id());
                    if (replied.from() != null) {
                        rawMeta.append(" from=").append(replied.from().username());
                    }
                    if (replied.text() != null) {
                        rawMeta.append(" text=\"").append(replied.text().length() > 200 ? replied.text().substring(0, 200) + "..." : replied.text()).append("\"");
                    }
                    rawMeta.append("]");
                }
                if (msg.entities() != null && !msg.entities().isEmpty()) {
                    rawMeta.append("\n[Entities: ");
                    boolean first = true;
                    for (var ent : msg.entities()) {
                        if (!first) rawMeta.append(", ");
                        rawMeta.append(ent.type());
                        if ("mention".equals(ent.type())) {
                            rawMeta.append("(@").append(botUsername).append(")");
                        }
                        first = false;
                    }
                    rawMeta.append("]");
                }

                var decision = conversationAgent.processMessage(chat.id(), username, rawMeta.toString());
                log.info("TG poll: ConversationAgent решил action={} taskId={} for chatId={}",
                        decision.action(), decision.taskId(), chat.id());

                switch (decision.action()) {
                    case HITL_ANSWER -> {
                        if (decision.taskId() != null && decision.text() != null) {
                            log.info("TG poll: HITL ответ для задачи {} — resume", decision.taskId());
                            taskLauncher.resumeWithAnswer(decision.taskId(), decision.text());
                        } else {
                            log.warn("TG poll: HITL_ANSWER без taskId/answer — игнор");
                        }
                    }
                    case STATUS -> telegram.sendMessage(chat.id(), formatActiveTasks(chat.id()));
                    case ANSWER -> {
                        log.trace("ANSWER: decision.text()='{}' isBlank={}",
                                decision.text(), !hasText(decision.text()));
                        if (hasText(decision.text())) {
                            log.debug("ANSWER: отправка ответа в ТГ chatId={}", chat.id());
                            telegram.sendMessage(chat.id(), decision.text());
                            log.debug("ANSWER: ответ записан в память chatId={}", chat.id());
                        } else {
                            log.debug("ANSWER: decision.text() пустой — модель уже отправила ответ через sendMessage tool, chatId={}", chat.id());
                        }
                    }
                    case ERROR -> {
                        log.error("TG poll: ConversationAgent error: {}", decision.description());
                        telegram.sendMessage(chat.id(), "⚠️ Ошибка: " + decision.description());
                    }
                }
            } catch (Exception e) {
                log.error("TG poll: ошибка обработки сообщения: {}", e.getMessage(), e);
            }
        }
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
