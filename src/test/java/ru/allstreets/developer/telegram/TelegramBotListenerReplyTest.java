package ru.allstreets.developer.telegram;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Прямой ответ (reply) на сообщение бота должен считаться обращением к боту без @mention
 * (иначе в группах бот «не реагирует на ответы»). Whitelist/trigger-users это не отменяет —
 * проверка идёт только на то, что reply указывает ИМЕННО на нашего бота.
 */
class TelegramBotListenerReplyTest {

    private static final String BOT = "AllstreetsAIbackendBot";

    private static TelegramGateway.Message replyMessage(String replyFromUser, boolean replyIsBot) {
        var replyFrom = new TelegramGateway.User(1L, replyIsBot, "Bot", replyFromUser);
        var replied = new TelegramGateway.Message(100L, replyFrom,
                new TelegramGateway.Chat(-1L, "group"), "bot message", 0L, List.of(), null);
        return new TelegramGateway.Message(101L,
                new TelegramGateway.User(2L, false, "Дима", "dmitry"),
                new TelegramGateway.Chat(-1L, "group"), "да", 0L, List.of(), replied);
    }

    @Test
    void replyToOurBot_isRecognized() {
        assertTrue(TelegramBotListener.isReplyToBot(replyMessage(BOT, true), BOT));
    }

    @Test
    void replyToOtherBot_isIgnored() {
        assertFalse(TelegramBotListener.isReplyToBot(replyMessage("SomeOtherBot", true), BOT));
    }

    @Test
    void replyToHuman_isNotBot() {
        assertFalse(TelegramBotListener.isReplyToBot(replyMessage("dmitry", false), BOT));
    }

    @Test
    void noReply_isNotReplyToBot() {
        var msg = new TelegramGateway.Message(101L,
                new TelegramGateway.User(2L, false, "Дима", "dmitry"),
                new TelegramGateway.Chat(-1L, "group"), "да", 0L, List.of(), null);

        assertFalse(TelegramBotListener.isReplyToBot(msg, BOT));
    }

    @Test
    void blankBotUsername_acceptsAnyBotReply() {
        assertTrue(TelegramBotListener.isReplyToBot(replyMessage("whatever_bot", true), ""));
    }
}
