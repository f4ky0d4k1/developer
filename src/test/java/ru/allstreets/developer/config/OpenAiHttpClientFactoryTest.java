package ru.allstreets.developer.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Инцидент 14.09.2026: прямой вызов DeepSeek (Spring AI) без таймаутов висел бесконечно и
 * блокировал единственный поток Telegram-опроса — бот переставал отвечать. Здесь проверяем,
 * что фабрика всегда задаёт ограниченные (ненулевые) connect/read-таймауты.
 */
class OpenAiHttpClientFactoryTest {

    @Test
    void requestFactory_setsBoundedTimeouts() {
        HttpComponentsClientHttpRequestFactory factory =
                OpenAiHttpClientFactory.requestFactory(Duration.ofSeconds(5), Duration.ofSeconds(60));

        long connectTimeout = (long) ReflectionTestUtils.getField(factory, "connectTimeout");
        long readTimeout = (long) ReflectionTestUtils.getField(factory, "readTimeout");

        assertTrue(connectTimeout > 0, "connect timeout должен быть ограничен");
        assertTrue(readTimeout > 0, "read timeout должен быть ограничен");
    }
}
