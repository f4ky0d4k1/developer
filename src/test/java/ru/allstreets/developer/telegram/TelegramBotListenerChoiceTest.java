package ru.allstreets.developer.telegram;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import ru.allstreets.developer.agents.AgentResponses;
import ru.allstreets.developer.checkpoint.TaskRepository;
import ru.allstreets.developer.humanloop.HumanInputRegistry;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Вопрос классификатора с вариантами → inline-кнопки. В callback_data кладётся только короткий
 * {@code choice:<token>:<index>} (лимит Telegram 64 байта), а текст варианта резолвится по
 * индексу из короткоживущего {@code choiceStore} при тапе. Тап возвращается в классификатор
 * как «Выбрано: &lt;вариант&gt;».
 */
class TelegramBotListenerChoiceTest {

    private static final long CHAT_ID = 1L;
    private static final String BOT = "AllstreetsAIbackendBot";

    private TelegramGateway telegram;
    private ConversationAgent conversationAgent;
    private ChatMemoryService chatMemory;
    private TelegramBotListener listener;

    @BeforeEach
    void setUp() {
        telegram = mock(TelegramGateway.class);
        conversationAgent = mock(ConversationAgent.class);
        chatMemory = mock(ChatMemoryService.class);
        listener = new TelegramBotListener(telegram, mock(TaskLauncher.class), conversationAgent,
                chatMemory, mock(HumanInputRegistry.class), mock(ActiveTaskRegistry.class),
                mock(TaskRepository.class), mock(ReplyAnchorRegistry.class));
        ReflectionTestUtils.setField(listener, "allowedChatIdsRaw", String.valueOf(CHAT_ID));
        ReflectionTestUtils.setField(listener, "botUsername", BOT);
        listener.init();
    }

    @Test
    void answerWithLongOptions_sendsKeyboardWithShortCallbackData() {
        when(conversationAgent.processMessage(anyLong(), anyString(), anyString()))
                .thenReturn(new ConversationAgent.Decision(AgentResponses.FastAction.ANSWER, null,
                        "В каком репозитории?", null, List.of(
                        "Права есть у администратора (роль ADMIN) и модератора (роль MODERATOR)",
                        "Только у администратора")));

        var msg = new TelegramGateway.Message(501L,
                new TelegramGateway.User(2L, false, "Дима", "dmitry"),
                new TelegramGateway.Chat(CHAT_ID, "private"), "проверь тикет", 0L, List.of(), null);
        when(telegram.getUpdates(anyInt(), anyInt()))
                .thenReturn(new TelegramGateway.TelegramUpdates(true, List.of(new TelegramGateway.Update(1, msg, null))));

        listener.poll();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<List<Map<String, String>>>> kbCaptor = ArgumentCaptor.forClass(List.class);
        verify(telegram).sendMessageWithKeyboard(eq(CHAT_ID), eq("В каком репозитории?"),
                kbCaptor.capture(), isNull());
        for (var row : kbCaptor.getValue()) {
            for (var btn : row) {
                String data = btn.get("callback_data");
                assertTrue(data.getBytes(StandardCharsets.UTF_8).length <= 64,
                        "callback_data должен влезать в 64 байта, а получился " + data);
                assertTrue(data.matches("choice:[0-9a-f]{8}:\\d+"), "формат choice:<token>:<index>, а был " + data);
            }
        }
    }

    @Test
    void choiceCallback_resolvesOptionByIndex_andRoutesToClassifier() {
        var registered = listener.choiceKeyboard(List.of("owner/a", "owner/b"));
        String callbackData = registered.keyboard().get(1).get(0).get("callback_data");

        var callback = new TelegramGateway.CallbackQuery("cb1",
                new TelegramGateway.User(2L, false, "Дима", "dmitry"),
                new TelegramGateway.Message(501L, null,
                        new TelegramGateway.Chat(CHAT_ID, "group"), "question", 0L, List.of(), null),
                callbackData);
        when(telegram.getUpdates(anyInt(), anyInt()))
                .thenReturn(new TelegramGateway.TelegramUpdates(true,
                        List.of(new TelegramGateway.Update(1, null, callback))));
        when(conversationAgent.processMessage(anyLong(), anyString(), anyString()))
                .thenReturn(new ConversationAgent.Decision(AgentResponses.FastAction.ANSWER, null, "запускаю", null, null));

        listener.poll();

        verify(conversationAgent).processMessage(eq(CHAT_ID), eq("dmitry"), eq("Выбрано: owner/b"));
    }

    @Test
    void choiceCallback_unknownToken_answersGracefullyAndSkipsClassifier() {
        var callback = new TelegramGateway.CallbackQuery("cb1",
                new TelegramGateway.User(2L, false, "Дима", "dmitry"),
                new TelegramGateway.Message(501L, null,
                        new TelegramGateway.Chat(CHAT_ID, "group"), "question", 0L, List.of(), null),
                "choice:deadbeef:0");
        when(telegram.getUpdates(anyInt(), anyInt()))
                .thenReturn(new TelegramGateway.TelegramUpdates(true,
                        List.of(new TelegramGateway.Update(1, null, callback))));

        listener.poll();

        verify(conversationAgent, never()).processMessage(anyLong(), anyString(), anyString());
        verify(telegram).answerCallbackQuery(eq("cb1"), anyString());
    }

    @Test
    void choiceKeyboard_skipsBlankOptions_andUsesTokenIndex() {
        var registered = listener.choiceKeyboard(
                java.util.Arrays.asList("owner/a", "", null, "owner/b"));

        assertEquals(2, registered.keyboard().size());
        assertTrue(registered.keyboard().get(0).get(0).get("callback_data")
                .matches("choice:[0-9a-f]{8}:0"));
        assertTrue(registered.keyboard().get(1).get(0).get("callback_data")
                .matches("choice:[0-9a-f]{8}:1"));
        assertEquals("owner/a", registered.keyboard().get(0).get(0).get("text"));
        assertEquals("owner/b", registered.keyboard().get(1).get(0).get("text"));
    }

    @Test
    void choiceKeyboard_allBlank_returnsNull() {
        assertEquals(null, listener.choiceKeyboard(java.util.Arrays.asList("", null, "   ")));
    }

    @Test
    void choiceKeyboard_longOptions_fitTelegramLimit() {
        var registered = listener.choiceKeyboard(List.of(
                "Права есть у администратора (роль ADMIN), модератора (роль MODERATOR) и супервайзера",
                "Только у администратора"));

        for (var row : registered.keyboard()) {
            for (var btn : row) {
                assertTrue(btn.get("callback_data").getBytes(StandardCharsets.UTF_8).length <= 64,
                        "callback_data должен влезать в 64 байта");
            }
        }
    }
}
