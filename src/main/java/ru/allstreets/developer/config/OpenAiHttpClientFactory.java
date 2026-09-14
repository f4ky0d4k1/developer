package ru.allstreets.developer.config;

import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

import java.time.Duration;

/**
 * Фабрика HTTP-клиентов для прямых вызовов DeepSeek API (Spring AI {@code OpenAiChatModel}).
 * <p>
 * Без явных connect/read-таймаутов вызов LLM может висеть бесконечно (сервер принял соединение,
 * но не ответил). Тогда единственный поток Telegram-опроса ({@code @Scheduled} single-thread)
 * блокируется навсегда, и бот перестаёт отвечать на любые сообщения (инцидент 14.09.2026).
 */
public final class OpenAiHttpClientFactory {

    private OpenAiHttpClientFactory() {
    }

    public static HttpComponentsClientHttpRequestFactory requestFactory(Duration connectTimeout, Duration readTimeout) {
        var connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(20)
                .setMaxConnPerRoute(20)
                .build();
        var httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .build();
        var factory = new HttpComponentsClientHttpRequestFactory(httpClient);
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return factory;
    }
}
