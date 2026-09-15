package ru.allstreets.developer.telegram;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Заголовок сообщений задачи: {@code 📋 Название (id8)} — чтобы в чате с несколькими
 * задачами было видно, к какой относится сообщение.
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
}
