package ru.allstreets.developer.telegram;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code message_thread_id} во всех исходящих путях (BACKEND-443, T3).
 * <p>
 * Разбор тела {@code /sendMessage} с явным threadId: при известной теме задачи
 * поле {@code message_thread_id} обязано присутствовать (иначе сообщение утечёт
 * в General — AC «запрет утечки в General»); при неизвестной теме — отсутствовать.
 * Тело reply_parameters и parse_mode при этом не должны ломаться.
 */
class TelegramGatewayThreadIdTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> replyParameters(Map<String, Object> body) {
        return (Map<String, Object>) body.get("reply_parameters");
    }

    @Test
    void body_withThreadId_containsMessageThreadId() {
        Map<String, Object> body = TelegramGateway.buildSendMessageBody(1L, "текст", null, null, 42L);

        assertEquals(1L, ((Number) body.get("chat_id")).longValue());
        assertEquals("текст", body.get("text"));
        assertNotNull(body.get("message_thread_id"), "известная тема обязана попасть в тело");
        assertEquals(42L, ((Number) body.get("message_thread_id")).longValue());
    }

    @Test
    void body_withoutThreadId_omitsMessageThreadId() {
        Map<String, Object> body = TelegramGateway.buildSendMessageBody(1L, "текст", null, null, null);

        assertFalse(body.containsKey("message_thread_id"),
                "без известной темы поле не добавляется (General)");
    }

    @Test
    void body_withThreadIdAndReplyAndParseMode_keepsAll() {
        Map<String, Object> body = TelegramGateway.buildSendMessageBody(1L, "текст", "HTML", 7L, 42L);

        assertEquals("HTML", body.get("parse_mode"));
        assertNotNull(replyParameters(body), "reply_parameters не должны теряться при topic-scoped отправке");
        assertEquals(7L, ((Number) replyParameters(body).get("message_id")).longValue());
        assertEquals(42L, ((Number) body.get("message_thread_id")).longValue());
    }

    @Test
    void body_zeroThreadId_isTreatedAsKnownTopic() {
        // message_thread_id=0 — валидная «есть тема», проверяем различие null/non-null.
        Map<String, Object> body = TelegramGateway.buildSendMessageBody(1L, "текст", null, null, 0L);

        assertTrue(body.containsKey("message_thread_id"));
        assertEquals(0L, ((Number) body.get("message_thread_id")).longValue());
    }

    @Test
    void body_fiveArg_withoutReplyAndParseMode_hasNoExtras() {
        Map<String, Object> body = TelegramGateway.buildSendMessageBody(2L, "plain", null, null, 99L);

        assertFalse(body.containsKey("parse_mode"));
        assertFalse(body.containsKey("reply_parameters"));
        assertEquals(99L, ((Number) body.get("message_thread_id")).longValue());
    }
}
