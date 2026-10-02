package ru.allstreets.developer.telegram;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.allstreets.developer.checkpoint.TaskEntity;
import ru.allstreets.developer.checkpoint.TaskRepository;
import ru.allstreets.developer.humanloop.HumanInputRegistry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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

    /**
     * Пачки сообщений по чатам, копящиеся в debounce-окне до единой классификации.
     */
    private final Map<Long, PendingBatch> pendingBatches = new ConcurrentHashMap<>();

    /**
     * Короткоживущее хранилище вариантов вопросов классификатора: {@code token → список вариантов}.
     * В callback_data кнопки кладётся только {@code choice:<token>:<index>} (лимит Telegram 64 байта),
     * а текст варианта резолвится отсюда по индексу. Запись удаляется после тапа (или при фолбэке).
     */
    private final Map<String, List<String>> choiceStore = new ConcurrentHashMap<>();

    private ScheduledExecutorService flushExecutor;

    @Value("${telegram.polling-timeout:30}")
    private int pollingTimeout;

    @Value("${telegram.batch-debounce-ms:2000}")
    private long batchDebounceMs;

    @Value("${telegram.allowed-chat-ids:}")
    private String allowedChatIdsRaw;

    @Value("${telegram.mention-free-chat-ids:}")
    private String mentionFreeChatIdsRaw;

    @Value("${telegram.bot-username:}")
    private String botUsername;

    private Set<Long> allowedChatIds;
    private Set<Long> mentionFreeChatIds;

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
        if (mentionFreeChatIdsRaw == null || mentionFreeChatIdsRaw.isBlank()) {
            mentionFreeChatIds = Set.of();
        } else {
            mentionFreeChatIds = Arrays.stream(mentionFreeChatIdsRaw.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(Long::parseLong)
                    .collect(Collectors.toSet());
            log.info("Telegram mention-free чаты активированы — бот отвечает на все сообщения: {}", mentionFreeChatIds);
        }
        // Debounce-флашер на отдельном потоке: poll() блокируется в long-polling getUpdates
        // до polling-timeout, поэтому флаш «по времени» нельзя вешать на тот же scheduler-поток.
        // В юнит-тестах @Value не применяется → batchDebounceMs == 0 → флашер не стартует,
        // а пачки флашатся синхронно в конце poll().
        if (batchDebounceMs > 0) {
            flushExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "telegram-batch-flush");
                t.setDaemon(true);
                return t;
            });
            flushExecutor.scheduleWithFixedDelay(this::flushDueBatches, 250, 250, TimeUnit.MILLISECONDS);
        }
    }

    @PreDestroy
    void shutdown() {
        if (flushExecutor != null) {
            flushExecutor.shutdownNow();
        }
    }

    private boolean isChatAllowed(long chatId) {
        return !allowedChatIds.isEmpty() && allowedChatIds.contains(chatId);
    }

    private boolean isMentionFreeChat(long chatId) {
        return !mentionFreeChatIds.isEmpty() && mentionFreeChatIds.contains(chatId);
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
     * Нажатие inline-кнопки: {@code close:<taskId>} — закрыть задачу (освобождает слот);
     * {@code choice:<вариант>} — выбор из вариантов вопроса классификатора.
     */
    private void handleCallbackQuery(TelegramGateway.CallbackQuery cq) {
        long chatId = (cq.message() != null && cq.message().chat() != null) ? cq.message().chat().id() : 0;
        Integer threadId = cq.message() != null ? cq.message().message_thread_id() : null;
        String topicTaskId = resolveTopicTask(chatId, threadId);
        String data = cq.data();
        log.info("TG callback: chat={} thread={} data={}", chatId, threadId, data);
        if (data == null || data.isBlank()) {
            telegram.answerCallbackQuery(cq.id(), null);
            return;
        }
        try {
            if (data.startsWith("close:")) {
                String taskId = data.substring("close:".length());
                boolean closed = taskLauncher.close(taskId, chatId);
                telegram.answerCallbackQuery(cq.id(), closed ? "Задача закрыта" : "Не удалось закрыть");
            } else if (data.startsWith("choice:")) {
                handleChoiceCallback(chatId, topicTaskId, cq, data.substring("choice:".length()));
            } else {
                telegram.answerCallbackQuery(cq.id(), "Ок");
            }
        } catch (Exception e) {
            log.error("TG callback: ошибка data={}: {}", data, e.getMessage(), e);
            telegram.answerCallbackQuery(cq.id(), "Ошибка");
        }
    }

    /**
     * Выбор варианта из кнопок: резолвим {@code choice:<token>:<index>} → текст варианта
     * (из {@link #choiceStore}), синтезируем сообщение «Выбрано: &lt;вариант&gt;» и прогоняем
     * классификатор заново — он по истории чата (включая свой вопрос с кнопками) свяжет выбор
     * с исходным запросом и выполнит действие (launch_task / ответ).
     */
    private void handleChoiceCallback(long chatId, String topicTaskId, TelegramGateway.CallbackQuery cq, String payload) {
        String option = resolveChoice(payload);
        if (option == null) {
            log.warn("TG callback: choice не разрешился payload='{}' chat={} — варианты истекли", payload, chatId);
            telegram.answerCallbackQuery(cq.id(), "Варианты устарели — напишите заново");
            return;
        }
        telegram.answerCallbackQuery(cq.id(), option);
        String username = cq.from() != null ? cq.from().username() : null;
        String synthetic = "Выбрано: " + option;
        log.info("TG callback: choice='{}' chat={} — возвращаю в классификатор", option, chatId);
        chatMemory.recordUserMessage(chatId, synthetic);
        try {
            var decision = conversationAgent.processMessage(chatId, username, synthetic);
            log.info("TG callback: классификатор на choice решил action={} chat={}",
                    decision.action(), chatId);
            applyDecision(chatId, topicTaskId, decision);
        } catch (Exception e) {
            log.error("TG callback: ошибка обработки choice chat={}: {}", chatId, e.getMessage(), e);
        }
    }

    /**
     * Резолв {@code choice:<token>:<index>} → текст варианта. Удаляет запись из {@link #choiceStore}
     * (варианты одноразовые). {@code null} — токен неизвестен/истёк или индекс вне диапазона.
     */
    private String resolveChoice(String payload) {
        if (payload == null) return null;
        int sep = payload.lastIndexOf(':');
        if (sep <= 0 || sep == payload.length() - 1) return null;
        String token = payload.substring(0, sep);
        int index;
        try {
            index = Integer.parseInt(payload.substring(sep + 1));
        } catch (NumberFormatException e) {
            return null;
        }
        List<String> options = choiceStore.get(token);
        if (options == null || index < 0 || index >= options.size()) return null;
        choiceStore.remove(token);
        return options.get(index);
    }

    /**
     * Применить решение классификатора — общий путь для обычного сообщения и для выбора кнопкой.
     */
    private void applyDecision(long chatId, String topicTaskId, ConversationAgent.Decision decision) {
        switch (decision.action()) {
            case HITL_ANSWER -> {
                if (decision.taskId() != null && decision.text() != null) {
                    log.info("TG poll: HITL ответ для задачи {} — resume", decision.taskId());
                    taskLauncher.resumeWithAnswer(decision.taskId(), decision.text());
                } else {
                    log.warn("TG poll: HITL_ANSWER без taskId/answer — игнор");
                }
            }
            case STATUS -> sendToChat(chatId, formatActiveTasks(chatId), topicTaskId);
            case ANSWER -> {
                if (hasText(decision.text())) {
                    if (hasOptions(decision)) {
                        var registered = choiceKeyboard(decision.options());
                        if (registered != null) {
                            boolean sent = telegram.sendMessageWithKeyboard(chatId, decision.text(),
                                    registered.keyboard(), topicTaskId);
                            if (!sent) {
                                choiceStore.remove(registered.token());
                            }
                        }
                    } else {
                        telegram.sendMarkdownMessage(chatId, decision.text(), topicTaskId);
                    }
                }
            }
            case ERROR -> {
                log.error("TG poll: ConversationAgent error: {}", decision.description());
                sendToChat(chatId, "⚠️ Ошибка: " + decision.description(), topicTaskId);
            }
        }
    }

    /**
     * Отправка в тему задачи, если она известна (иначе — прежний путь в General).
     * Реюз резолвера {@code taskId → thread_id} в {@link TelegramGateway}.
     */
    private void sendToChat(long chatId, String text, String topicTaskId) {
        if (topicTaskId != null && !topicTaskId.isBlank()) {
            telegram.sendMessage(chatId, text, topicTaskId);
        } else {
            telegram.sendMessage(chatId, text);
        }
    }

    /**
     * Задача-корень, которой принадлежит forum-тема входящего сообщения, по паре
     * {@code (chat_id, message_thread_id)}. {@code null} — General или тема неизвестна.
     */
    private String resolveTopicTask(long chatId, Integer threadId) {
        if (threadId == null) {
            return null;
        }
        try {
            return taskRepo.findByNotifyChatIdAndThreadIdAndParentTaskIdIsNull(chatId, threadId.longValue())
                    .map(TaskEntity::getTaskId)
                    .orElse(null);
        } catch (Exception e) {
            log.debug("TG poll: задача темы (chat={}, thread={}) недоступна: {}", chatId, threadId, e.getMessage());
            return null;
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

            // Канальные посты приходят как channel_post (бот — админ канала), не message.
            var msg = update.message() != null ? update.message() : update.channel_post();
            if (msg == null) {
                log.debug("TG poll: update_id={} — message=null и channel_post=null", update.update_id());
                continue;
            }
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
            // 2-arg — General-scope (обратная совместимость), 3-arg — topic-scoped (BACKEND-443).
            replyAnchors.recordIncoming(chat.id(), msg.message_id());
            Integer threadId = msg.message_thread_id();
            if (threadId != null) {
                replyAnchors.recordIncoming(chat.id(), threadId.longValue(), msg.message_id());
            }
            // Роутинг по (chat_id, message_thread_id): кому принадлежит тема сообщения.
            String topicTaskId = resolveTopicTask(chat.id(), threadId);

            // Записываем в sliding window память чата
            chatMemory.recordUserMessage(chat.id(), text);

            // Pre-filtering: команды без LLM
            if (text.startsWith("/status")) {
                sendToChat(chat.id(), formatActiveTasks(chat.id()), topicTaskId);
                continue;
            }

            if (text.startsWith("/start")) {
                sendToChat(chat.id(),
                        "Привет! Я агент-разработчик. Упомяни меня (@" + botUsername + ") чтобы поставить задачу.",
                        topicTaskId);
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
            // Чат из mention-free списка — бот отвечает на ВСЕ сообщения, @mention не нужен.
            boolean isMentionFreeChat = isMentionFreeChat(chat.id());
            // Канал (бот — админ): реагируем на ВСЕ посты — алерты/уведомления, @mention не нужен.
            boolean isChannel = "channel".equals(chat.type());
            if (!hasMention && !hasPendingQuestions && !isPrivateChat && !isReplyToBot && !isMentionFreeChat && !isChannel) {
                log.debug("TG poll: chatId={} — нет @mention, нет pending-вопросов и не reply боту, пропуск LLM вызова",
                        chat.id());
                continue;
            }
            if (!hasMention) {
                log.info("TG poll: chatId={} — нет @mention, но {} → пропуск к ConversationAgent",
                        chat.id(), isPrivateChat ? "личный чат"
                                : isReplyToBot ? "reply на сообщение бота"
                                  : isMentionFreeChat ? "mention-free чат"
                                    : isChannel ? "канал"
                                      : "есть pending-вопросы");
            }

            // Буферизуем сообщение в пачку (debounce) вместо немедленной классификации:
            // несколько сообщений, пришедших подряд, уходят оркестратору одной пачкой
            // (иначе каждое форварнутое сообщение → отдельный LLM-вызов и отдельный реворк).
            String rawMeta = buildRawMeta(msg, topicTaskId, threadId);
            long now = System.currentTimeMillis();
            pendingBatches.compute(chat.id(), (k, existing) -> {
                PendingBatch batch = existing != null ? existing : new PendingBatch(chat.id(), topicTaskId, username);
                batch.add(rawMeta, now + batchDebounceMs);
                return batch;
            });
        }
        flushDueBatches();
    }

    /**
     * Собрать сырой контекст одного сообщения (reply-to/entities/тема) для классификатора.
     * В батче несколько таких блоков склеиваются через пустую строку.
     */
    private String buildRawMeta(TelegramGateway.Message msg, String topicTaskId, Integer threadId) {
        StringBuilder rawMeta = new StringBuilder();
        rawMeta.append("Сообщение: ").append(msg.text());
        if (msg.reply_to_message() != null) {
            var replied = msg.reply_to_message();
            rawMeta.append("\n[Reply-to message_id=").append(replied.message_id());
            if (replied.from() != null) {
                rawMeta.append(" from=").append(replied.from().username());
            }
            if (replied.text() != null) {
                rawMeta.append(" text=\"")
                        .append(replied.text().length() > 200 ? replied.text().substring(0, 200) + "..." : replied.text())
                        .append("\"");
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
        if (threadId != null) {
            rawMeta.append("\n[Forum topic: message_thread_id=").append(threadId);
            if (topicTaskId != null) {
                rawMeta.append(", task=").append(topicTaskId, 0, Math.min(8, topicTaskId.length()));
            }
            rawMeta.append("]");
        }
        return rawMeta.toString();
    }

    /**
     * Флашнуть все пачки с истёкшим debounce-окном: каждая уходит оркестратору одним вызовом.
     * Потокобезопасно — атомарный {@code remove(key, batch)} гарантирует, что пачку
     * обработает ровно один поток (poll-тред или debounce-флашер).
     */
    void flushDueBatches() {
        flushDueBatches(System.currentTimeMillis());
    }

    /**
     * Флашнуть пачки с истёкшим к моменту {@code now} debounce-окном. Перегрузка с явным
     * «сейчас» — для тестов (позволяет «прокрутить» время без sleep).
     */
    void flushDueBatches(long now) {
        for (var entry : pendingBatches.entrySet()) {
            PendingBatch batch = entry.getValue();
            if (batch.deadline <= now && pendingBatches.remove(entry.getKey(), batch)) {
                flushBatch(batch);
            }
        }
    }

    private void flushBatch(PendingBatch batch) {
        String combined = String.join("\n\n", batch.parts);
        log.info("TG poll: флашим пачку из {} сообщений для chat={}", batch.parts.size(), batch.chatId);
        try {
            var decision = conversationAgent.processMessage(batch.chatId, batch.username, combined);
            log.info("TG poll: ConversationAgent решил action={} taskId={} for chatId={} (пачка)",
                    decision.action(), decision.taskId(), batch.chatId);
            applyDecision(batch.chatId, batch.topicTaskId, decision);
        } catch (Exception e) {
            log.error("TG poll: ошибка обработки пачки сообщений: {}", e.getMessage(), e);
        }
    }

    /**
     * Пачка сообщений одного чата, копящаяся в debounce-окне до единой классификации.
     */
    private static final class PendingBatch {
        final long chatId;
        final String topicTaskId;
        final String username;
        final List<String> parts = new ArrayList<>();
        volatile long deadline;

        PendingBatch(long chatId, String topicTaskId, String username) {
            this.chatId = chatId;
            this.topicTaskId = topicTaskId;
            this.username = username;
        }

        void add(String part, long deadline) {
            parts.add(part);
            this.deadline = deadline;
        }
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static boolean hasOptions(ConversationAgent.Decision decision) {
        return decision.options() != null && !decision.options().isEmpty();
    }

    /**
     * Inline-кнопки для вопроса с выбором. В callback_data кладётся только короткий
     * {@code choice:<token>:<index>} (лимит Telegram 64 байта), а тексты вариантов хранятся
     * в {@link #choiceStore} до тапа (index → текст). Package-private для тестов.
     *
     * @return {@code null}, если после отброса пустых вариантов не осталось ни одной кнопки.
     */
    RegisteredChoice choiceKeyboard(List<String> options) {
        List<String> filtered = options.stream().filter(o -> o != null && !o.isBlank()).toList();
        if (filtered.isEmpty()) return null;
        String token = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        var keyboard = new ArrayList<List<Map<String, String>>>();
        for (int i = 0; i < filtered.size(); i++) {
            keyboard.add(List.of(TelegramGateway.button(filtered.get(i), "choice:" + token + ":" + i)));
        }
        choiceStore.put(token, filtered);
        return new RegisteredChoice(token, keyboard);
    }

    /**
     * Зарегистрированная клавиатура выбора: {@code token} — короткоживущий ключ в
     * {@link #choiceStore}, {@code keyboard} — кнопки с {@code choice:<token>:<index>}.
     */
    record RegisteredChoice(String token, List<List<Map<String, String>>> keyboard) {
    }
}
