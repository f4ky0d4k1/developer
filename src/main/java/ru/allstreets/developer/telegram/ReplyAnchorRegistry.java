package ru.allstreets.developer.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory реестр reply-anchor для исходящих сообщений Telegram: к какому
 * входящему {@code message_id} привязывать ответ бота.
 * <p>
 * Anchor topic-scoped (BACKEND-443): и последнее входящее, и anchor задачи хранятся
 * в рамках пары {@code (chatId, threadId)}, где {@code threadId == null} — General.
 * Это не даёт {@code reply_parameters} утащить ответ в чужую forum-тему (утечка).
 * <p>
 * Два уровня в рамках scope:
 * <ul>
 *   <li>{@code (chatId, threadId) → последнее принятое message_id} — fallback для
 *       немедленных ответов (/status, /start, ANSWER, STATUS, ERROR);</li>
 *   <li>{@code (chatId, threadId, taskId) → message_id} — anchor задачи, зафиксированный
 *       в момент запуска, чтобы асинхронные сообщения задачи не «прилипали» к более
 *       новым сообщениям чата.</li>
 * </ul>
 * Приоритет в {@link #replyToFor}: anchor задачи в её scope, затем последнее входящее
 * того же scope, иначе {@code null}.
 * <p>
 * Хранение только в памяти: схему БД и миграции не трогаем. После рестарта anchor
 * теряется — сообщение уходит без reply-to, без ошибки. Потокобезопасен
 * ({@link ConcurrentHashMap}). {@link #forgetTask} вызывается при close/cancel,
 * чтобы реестр не тёк.
 */
@Component
public class ReplyAnchorRegistry {

    private static final Logger log = LoggerFactory.getLogger(ReplyAnchorRegistry.class);

    private record Scope(long chatId, Long threadId) {
    }

    private record AnchorKey(long chatId, Long threadId, String taskId) {
    }

    private final Map<Scope, Long> lastIncomingByScope = new ConcurrentHashMap<>();
    private final Map<AnchorKey, Long> anchorByTask = new ConcurrentHashMap<>();

    /**
     * Запомнить последнее принятое сообщение чата в General-scope.
     */
    public void recordIncoming(long chatId, long messageId) {
        recordIncoming(chatId, null, messageId);
    }

    /**
     * Запомнить последнее принятое сообщение в рамках {@code (chatId, threadId)}.
     * {@code threadId == null} — General. Вызывается листенером на каждое принятое
     * whitelist-сообщение.
     */
    public void recordIncoming(long chatId, Long threadId, long messageId) {
        lastIncomingByScope.put(new Scope(chatId, threadId), messageId);
        log.debug("ReplyAnchor: входящее chat={} thread={} → message_id={}", chatId, threadId, messageId);
    }

    /**
     * Привязать задачу к последнему входящему сообщению General-scope на момент запуска.
     */
    public void anchorTask(String taskId, long chatId) {
        anchorTask(taskId, chatId, null);
    }

    /**
     * Привязать задачу к последнему входящему сообщению её scope {@code (chatId, threadId)}.
     * Если входящих в этом scope ещё не было — anchor не создаётся.
     */
    public void anchorTask(String taskId, long chatId, Long threadId) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        Long incoming = lastIncomingByScope.get(new Scope(chatId, threadId));
        if (incoming == null) {
            log.debug("ReplyAnchor: нет входящего chat={} thread={} — anchor задачи {} не создан",
                    chatId, threadId, taskId);
            return;
        }
        anchorByTask.put(new AnchorKey(chatId, threadId, taskId), incoming);
        log.debug("ReplyAnchor: задача {} (chat={}, thread={}) → message_id={}",
                taskId, chatId, threadId, incoming);
    }

    /**
     * message_id в General-scope: приоритет — anchor задачи, затем последнее входящее чата.
     */
    public Long replyToFor(long chatId, String taskId) {
        return replyToFor(chatId, null, taskId);
    }

    /**
     * message_id в рамках {@code (chatId, threadId)}: приоритет — anchor задачи в этом же
     * scope, затем последнее входящее того же scope, иначе {@code null}. Anchor из другого
     * топика НЕ используется (утечка в чужую тему).
     */
    public Long replyToFor(long chatId, Long threadId, String taskId) {
        if (taskId != null && !taskId.isBlank()) {
            Long taskAnchor = anchorByTask.get(new AnchorKey(chatId, threadId, taskId));
            if (taskAnchor != null) {
                return taskAnchor;
            }
        }
        return lastIncomingByScope.get(new Scope(chatId, threadId));
    }

    /**
     * Очистить anchor задачи во всех scope (close/cancel), чтобы реестр не тёк.
     */
    public void forgetTask(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        anchorByTask.keySet().removeIf(key -> taskId.equals(key.taskId()));
    }
}
