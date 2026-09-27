package ru.allstreets.developer.telegram;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Topic-scoped reply-anchor (BACKEND-443, T4).
 * <p>
 * Anchor обязан храниться и выбираться по паре {@code (chatId, threadId)} и не
 * переноситься между топиками: сообщение, отправленное в чужой топик (или в General),
 * не должно получить {@code reply_parameters} на сообщение из топика задачи — иначе
 * Telegram утащит ответ в чужую тему (утечка). {@code null} threadId — это General.
 */
class ReplyAnchorRegistryTopicScopeTest {

    private static final long CHAT = 1L;
    private static final long TOPIC_A = 10L;
    private static final long TOPIC_B = 20L;
    private static final String TASK = "task-1";

    private final ReplyAnchorRegistry registry = new ReplyAnchorRegistry();

    @Test
    void incoming_isScopedByChatAndThread() {
        registry.recordIncoming(CHAT, TOPIC_A, 100L);
        registry.recordIncoming(CHAT, TOPIC_B, 200L);

        assertEquals(Long.valueOf(100L), registry.replyToFor(CHAT, TOPIC_A, null));
        assertEquals(Long.valueOf(200L), registry.replyToFor(CHAT, TOPIC_B, null));
    }

    @Test
    void generalScope_isSeparateFromTopics() {
        registry.recordIncoming(CHAT, TOPIC_A, 100L);
        registry.recordIncoming(CHAT, null, 300L);

        assertEquals(Long.valueOf(300L), registry.replyToFor(CHAT, null, null));
    }

    @Test
    void taskAnchor_isUsedInItsOwnTopic() {
        registry.recordIncoming(CHAT, TOPIC_A, 100L);
        registry.anchorTask(TASK, CHAT, TOPIC_A);

        assertEquals(Long.valueOf(100L), registry.replyToFor(CHAT, TOPIC_A, TASK));
    }

    @Test
    void taskAnchor_isNotLeakedIntoAnotherTopic() {
        registry.recordIncoming(CHAT, TOPIC_A, 100L);
        registry.anchorTask(TASK, CHAT, TOPIC_A);
        registry.recordIncoming(CHAT, TOPIC_B, 200L);

        // Задача живёт в TOPIC_A — отправка в TOPIC_B не имеет права взять её anchor.
        assertEquals(Long.valueOf(200L), registry.replyToFor(CHAT, TOPIC_B, TASK));
    }

    @Test
    void taskAnchor_isNotLeakedIntoGeneral() {
        registry.recordIncoming(CHAT, TOPIC_A, 100L);
        registry.anchorTask(TASK, CHAT, TOPIC_A);
        registry.recordIncoming(CHAT, null, 300L);

        assertEquals(Long.valueOf(300L), registry.replyToFor(CHAT, null, TASK));
    }

    @Test
    void unknownTask_fallsBackToSameScopeIncoming() {
        registry.recordIncoming(CHAT, TOPIC_A, 100L);

        assertEquals(Long.valueOf(100L), registry.replyToFor(CHAT, TOPIC_A, "other-task"));
    }

    @Test
    void forgetTask_clearsAnchorAndFallsBackToIncoming() {
        registry.recordIncoming(CHAT, TOPIC_A, 100L);
        registry.anchorTask(TASK, CHAT, TOPIC_A);
        registry.forgetTask(TASK);

        assertEquals(Long.valueOf(100L), registry.replyToFor(CHAT, TOPIC_A, TASK));
    }

    @Test
    void latestIncomingPerScopeWins() {
        registry.recordIncoming(CHAT, TOPIC_A, 100L);
        registry.recordIncoming(CHAT, TOPIC_A, 101L);

        assertEquals(Long.valueOf(101L), registry.replyToFor(CHAT, TOPIC_A, null));
    }

    @Test
    void noData_returnsNull() {
        assertNull(registry.replyToFor(CHAT, TOPIC_A, TASK));
        assertNull(registry.replyToFor(CHAT, null, null));
    }
}
