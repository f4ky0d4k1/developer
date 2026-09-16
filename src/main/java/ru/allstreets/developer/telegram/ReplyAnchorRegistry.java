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
 * Два уровня:
 * <ul>
 *   <li>{@code chatId → последнее принятое message_id} — fallback для немедленных
 *       ответов (/status, /start, ANSWER, STATUS, ERROR);</li>
 *   <li>{@code taskId → message_id} — anchor задачи, зафиксированный в момент запуска,
 *       чтобы асинхронные сообщения задачи (прогресс, HITL, результат) не «прилипали»
 *       к более новым сообщениям чата.</li>
 * </ul>
 * Приоритет в {@link #replyToFor}: anchor задачи, затем последнее входящее чата, иначе {@code null}.
 * <p>
 * Хранение только в памяти: схему БД и миграции не трогаем. После рестарта anchor теряется —
 * сообщение уходит без reply-to, без ошибки. Потокобезопасен ({@link ConcurrentHashMap}).
 * {@link #forgetTask} вызывается при close/cancel, чтобы реестр не тёк.
 */
@Component
public class ReplyAnchorRegistry {

    private static final Logger log = LoggerFactory.getLogger(ReplyAnchorRegistry.class);

    private final Map<Long, Long> lastIncomingByChat = new ConcurrentHashMap<>();
    private final Map<String, Long> anchorByTask = new ConcurrentHashMap<>();

    /**
     * Запомнить последнее принятое сообщение чата. Вызывается листенером на каждое
     * принятое whitelist-сообщение.
     */
    public void recordIncoming(long chatId, long messageId) {
        lastIncomingByChat.put(chatId, messageId);
        log.debug("ReplyAnchor: входящее чата {} → message_id={}", chatId, messageId);
    }

    /**
     * Привязать задачу к последнему входящему сообщению чата на момент запуска.
     * Если входящих ещё не было — anchor не создаётся (сообщение уйдёт без reply-to).
     */
    public void anchorTask(String taskId, long chatId) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        Long incoming = lastIncomingByChat.get(chatId);
        if (incoming == null) {
            log.debug("ReplyAnchor: нет входящего для chatId={} — anchor задачи {} не создан", chatId, taskId);
            return;
        }
        anchorByTask.put(taskId, incoming);
        log.debug("ReplyAnchor: задача {} → message_id={}", taskId, incoming);
    }

    /**
     * message_id, к которому привязать исходящее сообщение: приоритет — anchor задачи,
     * затем последнее входящее чата, иначе {@code null} (отправлять без reply-to).
     */
    public Long replyToFor(long chatId, String taskId) {
        if (taskId != null && !taskId.isBlank()) {
            Long taskAnchor = anchorByTask.get(taskId);
            if (taskAnchor != null) {
                return taskAnchor;
            }
        }
        return lastIncomingByChat.get(chatId);
    }

    /**
     * Очистить anchor задачи (close/cancel), чтобы реестр не тёк.
     */
    public void forgetTask(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        anchorByTask.remove(taskId);
    }
}
