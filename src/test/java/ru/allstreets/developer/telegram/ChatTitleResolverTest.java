package ru.allstreets.developer.telegram;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ChatTitleResolver} (BACKEND-441): резолвит заголовок чата через
 * {@link TelegramGateway#getChatTitle(long)} и кэширует его с TTL (по умолчанию 10 минут),
 * чтобы не дёргать Telegram Bot API на каждую задачу в списке.
 * <p>
 * Контракт:
 * <ul>
 *   <li>{@code titleOrFallback(chatId)} никогда не возвращает {@code null}: при отсутствии
 *       заголовка — {@code String.valueOf(chatId)};</li>
 *   <li>успешный заголовок кэшируется (повторный вызов не идёт в gateway);</li>
 *   <li>{@code null}/пустой результат НЕ кэшируется навсегда (следующий вызов снова
 *       спрашивает gateway);</li>
 *   <li>по истечении TTL заголовок запрашивается заново.</li>
 * </ul>
 */
class ChatTitleResolverTest {

    @Test
    void titleOrFallback_returnsGatewayTitle() {
        TelegramGateway gateway = mock(TelegramGateway.class);
        when(gateway.getChatTitle(1L)).thenReturn("Chat One");

        ChatTitleResolver resolver = new ChatTitleResolver(gateway, 600_000L);

        assertEquals("Chat One", resolver.titleOrFallback(1L));
    }

    @Test
    void titleOrFallback_cachesPositiveResultPerChat() {
        TelegramGateway gateway = mock(TelegramGateway.class);
        when(gateway.getChatTitle(1L)).thenReturn("Chat One");
        when(gateway.getChatTitle(2L)).thenReturn("Chat Two");

        ChatTitleResolver resolver = new ChatTitleResolver(gateway, 600_000L);

        assertEquals("Chat One", resolver.titleOrFallback(1L));
        assertEquals("Chat One", resolver.titleOrFallback(1L));
        assertEquals("Chat Two", resolver.titleOrFallback(2L));

        verify(gateway, times(1)).getChatTitle(1L);
        verify(gateway, times(1)).getChatTitle(2L);
    }

    @Test
    void titleOrFallback_nullFromGateway_fallsBackToChatId_andIsNotCached() {
        TelegramGateway gateway = mock(TelegramGateway.class);
        when(gateway.getChatTitle(123L)).thenReturn(null, "Later Title");

        ChatTitleResolver resolver = new ChatTitleResolver(gateway, 600_000L);

        assertEquals("123", resolver.titleOrFallback(123L), "при недоступности API — числовой chatId");
        // null не должен «залипнуть»: следующий вызов снова спрашивает gateway и берёт реальный заголовок.
        assertEquals("Later Title", resolver.titleOrFallback(123L));

        verify(gateway, times(2)).getChatTitle(123L);
    }

    @Test
    void titleOrFallback_blankFromGateway_fallsBackToChatId() {
        TelegramGateway gateway = mock(TelegramGateway.class);
        when(gateway.getChatTitle(7L)).thenReturn("   ");

        ChatTitleResolver resolver = new ChatTitleResolver(gateway, 600_000L);

        assertEquals("7", resolver.titleOrFallback(7L));
    }

    @Test
    void titleOrFallback_cacheExpiresAfterTtl() throws Exception {
        TelegramGateway gateway = mock(TelegramGateway.class);
        when(gateway.getChatTitle(1L)).thenReturn("One", "One v2");

        ChatTitleResolver resolver = new ChatTitleResolver(gateway, 50L);

        assertEquals("One", resolver.titleOrFallback(1L));
        Thread.sleep(250);
        assertEquals("One v2", resolver.titleOrFallback(1L), "после TTL заголовок должен запрашиваться заново");

        verify(gateway, times(2)).getChatTitle(1L);
    }
}
