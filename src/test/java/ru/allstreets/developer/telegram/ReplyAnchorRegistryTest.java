package ru.allstreets.developer.telegram;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * In-memory реестр reply-anchor: {@code chatId → последнее входящее message_id},
 * {@code taskId → message_id задачи}. Приоритет — anchor задачи, затем последнее
 * входящее чата, иначе {@code null}. {@code forgetTask} очищает anchor задачи,
 * чтобы реестр не тёк после close/cancel.
 */
class ReplyAnchorRegistryTest {

    private static final long CHAT = 1L;

    private final ReplyAnchorRegistry registry = new ReplyAnchorRegistry();

    @Test
    void noData_returnsNull() {
        assertNull(registry.replyToFor(CHAT, null));
        assertNull(registry.replyToFor(CHAT, "task-1"));
        assertNull(registry.replyToFor(999L, "task-1"));
    }

    @Test
    void recordIncoming_isUsedAsFallback() {
        registry.recordIncoming(CHAT, 100L);

        assertEquals(Long.valueOf(100L), registry.replyToFor(CHAT, null));
    }

    @Test
    void latestIncomingWins() {
        registry.recordIncoming(CHAT, 100L);
        registry.recordIncoming(CHAT, 200L);

        assertEquals(Long.valueOf(200L), registry.replyToFor(CHAT, null));
    }

    @Test
    void incomingIsPerChat() {
        registry.recordIncoming(CHAT, 100L);
        registry.recordIncoming(2L, 200L);

        assertEquals(Long.valueOf(100L), registry.replyToFor(CHAT, null));
        assertEquals(Long.valueOf(200L), registry.replyToFor(2L, null));
    }

    @Test
    void taskAnchor_hasPriorityOverNewerIncoming() {
        registry.recordIncoming(CHAT, 100L);
        registry.anchorTask("task-1", CHAT);
        // Пока задача живёт, в чат приходят новые сообщения — anchor задачи не смещается.
        registry.recordIncoming(CHAT, 200L);

        assertEquals(Long.valueOf(100L), registry.replyToFor(CHAT, "task-1"));
    }

    @Test
    void unknownTask_fallsBackToLastIncoming() {
        registry.recordIncoming(CHAT, 100L);
        registry.anchorTask("task-1", CHAT);

        assertEquals(Long.valueOf(100L), registry.replyToFor(CHAT, "other-task"));
    }

    @Test
    void anchorTask_withoutIncoming_hasNoAnchor() {
        registry.anchorTask("task-1", CHAT);

        assertNull(registry.replyToFor(CHAT, "task-1"));
    }

    @Test
    void forgetTask_clearsTaskAnchorAndFallsBackToIncoming() {
        registry.recordIncoming(CHAT, 100L);
        registry.anchorTask("task-1", CHAT);
        registry.forgetTask("task-1");

        assertEquals(Long.valueOf(100L), registry.replyToFor(CHAT, "task-1"));
    }

    @Test
    void forgetTask_withoutIncoming_returnsNull() {
        registry.anchorTask("task-1", CHAT);
        registry.forgetTask("task-1");

        assertNull(registry.replyToFor(CHAT, "task-1"));
    }

    @Test
    void nullOrBlankTaskId_usesIncomingFallback() {
        registry.recordIncoming(CHAT, 100L);

        assertEquals(Long.valueOf(100L), registry.replyToFor(CHAT, null));
        assertEquals(Long.valueOf(100L), registry.replyToFor(CHAT, ""));
        assertEquals(Long.valueOf(100L), registry.replyToFor(CHAT, "   "));
    }
}
