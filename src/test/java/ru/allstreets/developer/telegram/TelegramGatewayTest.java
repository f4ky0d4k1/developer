package ru.allstreets.developer.telegram;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Заголовок сообщений задачи: {@code 📋 Название (id8)} — чтобы в чате с несколькими
 * задачами было видно, к какой относится сообщение.
 * <p>
 * Плюс разбор тела {@code /sendMessage}: при наличии anchor в тело добавляется
 * {@code reply_parameters} (Bot API 7.0+), при отсутствии — тело не меняется.
 */
class TelegramGatewayTest {

    @Test
    void headerWithTitle() {
        assertEquals("📋 Вынести техучётки (ec0a2004)\nтекст",
                TelegramGateway.withTaskHeader("текст", "Вынести техучётки", "ec0a2004-1234"));
    }

    @Test
    void headerWithoutTitle_usesIdOnly() {
        assertEquals("📋 (ec0a2004)\nтекст",
                TelegramGateway.withTaskHeader("текст", null, "ec0a2004-1234"));
    }

    @Test
    void blankTitle_usesIdOnly() {
        assertEquals("📋 (ec0a2004)\nтекст",
                TelegramGateway.withTaskHeader("текст", "   ", "ec0a2004-1234"));
    }

    @Test
    void shortTaskId_usedAsIs() {
        assertEquals("📋 (abc)\nтекст", TelegramGateway.withTaskHeader("текст", null, "abc"));
    }

    @Test
    void noTaskId_textUnchanged() {
        assertEquals("текст", TelegramGateway.withTaskHeader("текст", "Задача", null));
        assertEquals("текст", TelegramGateway.withTaskHeader("текст", "Задача", ""));
    }

    @Test
    void textAlreadyWithHeader_notDuplicated() {
        String startMsg = "📋 Задача (ID: ec0a2004)\nЗапускаю агентов...";
        assertEquals(startMsg, TelegramGateway.withTaskHeader(startMsg, "Задача", "ec0a2004-1234"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> replyParameters(Map<String, Object> body) {
        return (Map<String, Object>) body.get("reply_parameters");
    }

    @Test
    void body_withAnchor_containsReplyParameters() {
        Map<String, Object> body = TelegramGateway.buildSendMessageBody(1L, "текст", "Markdown", 42L);

        assertEquals(1L, ((Number) body.get("chat_id")).longValue());
        assertEquals("текст", body.get("text"));
        assertEquals("Markdown", body.get("parse_mode"));

        Map<String, Object> reply = replyParameters(body);
        assertNotNull(reply, "при наличии anchor тело должно содержать reply_parameters");
        assertEquals(42L, ((Number) reply.get("message_id")).longValue());
        assertEquals(Boolean.TRUE, reply.get("allow_sending_without_reply"));
        assertFalse(body.containsKey("reply_to_message_id"),
                "используется Bot API 7.0+ reply_parameters, а не legacy reply_to_message_id");
    }

    @Test
    void body_withoutAnchor_hasNoReplyParameters() {
        Map<String, Object> body = TelegramGateway.buildSendMessageBody(1L, "текст", null, null);

        assertEquals(1L, ((Number) body.get("chat_id")).longValue());
        assertEquals("текст", body.get("text"));
        assertFalse(body.containsKey("reply_parameters"), "без anchor тело не должно меняться");
        assertFalse(body.containsKey("reply_to_message_id"));
        assertFalse(body.containsKey("parse_mode"), "parseMode=null не должен попадать в тело");
    }

    @Test
    void body_withAnchorAndNullParseMode_keepsReplyParameters() {
        Map<String, Object> body = TelegramGateway.buildSendMessageBody(2L, "plain", null, 7L);

        assertFalse(body.containsKey("parse_mode"));
        Map<String, Object> reply = replyParameters(body);
        assertNotNull(reply);
        assertEquals(7L, ((Number) reply.get("message_id")).longValue());
        assertTrue(Boolean.TRUE.equals(reply.get("allow_sending_without_reply")));
    }

    @Test
    void body_zeroMessageId_stillAddsReplyParameters() {
        // message_id=0 — валидное «есть anchor», проверяем именно различие null/non-null.
        Map<String, Object> body = TelegramGateway.buildSendMessageBody(1L, "текст", null, 0L);

        assertNotNull(replyParameters(body));
        assertEquals(0L, ((Number) replyParameters(body).get("message_id")).longValue());
    }
}
