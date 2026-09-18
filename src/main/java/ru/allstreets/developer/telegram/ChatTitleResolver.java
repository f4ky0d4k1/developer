package ru.allstreets.developer.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Резолвер человекочитаемого заголовка Telegram-чата (BACKEND-441).
 * <p>
 * Заголовки в БД не хранятся, поэтому тянем их из Bot API ({@link TelegramGateway#getChatTitle(long)})
 * и кэшируем с TTL (по умолчанию 10 минут), чтобы список задач по всем чатам не дёргал API
 * на каждый чат/запрос. Fail-open: любой {@code null}/пустой ответ не кэшируется — при следующем
 * вызове снова спрашиваем gateway; {@link #titleOrFallback(long)} никогда не возвращает {@code null},
 * а при недоступности API отдаёт числовой chatId.
 */
@Component
public class ChatTitleResolver {

    private static final Logger log = LoggerFactory.getLogger(ChatTitleResolver.class);

    private final TelegramGateway gateway;
    private final long ttlMillis;
    private final ConcurrentHashMap<Long, CacheEntry> cache = new ConcurrentHashMap<>();

    public ChatTitleResolver(TelegramGateway gateway,
                             @Value("${telegram.chat-title-ttl-ms:600000}") long ttlMillis) {
        this.gateway = gateway;
        this.ttlMillis = ttlMillis > 0 ? ttlMillis : 600_000L;
    }

    /**
     * Заголовок чата или {@code String.valueOf(chatId)}, если API недоступен/не отдал title.
     */
    public String titleOrFallback(long chatId) {
        long now = System.currentTimeMillis();
        CacheEntry cached = cache.get(chatId);
        if (cached != null && now - cached.atMillis() < ttlMillis) {
            return cached.title();
        }

        String title = gateway.getChatTitle(chatId);
        if (title != null && !title.isBlank()) {
            cache.put(chatId, new CacheEntry(title, now));
            return title;
        }
        // null/blank НЕ кэшируем: API мог временно отвалиться — попробуем в следующий раз.
        log.debug("ChatTitleResolver: заголовок chatId={} недоступен, fallback на chatId", chatId);
        return String.valueOf(chatId);
    }

    private record CacheEntry(String title, long atMillis) {
    }
}
