package ru.allstreets.developer.opencode;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * SSE-пробуждение опроса OpenCode.
 * <p>
 * Реальный sidecar шлёт {@code server.connected}, затем изредка {@code server.heartbeat}
 * (data-события), а между ними на idle-агенте — тишина. Наш {@code next()} должен вернуться
 * (null) за ограниченное время, чтобы цикл опроса продолжал переспрашивать состояние
 * по таймеру, а не блокировался на долгий socket-timeout.
 */
class OpenCodeApiSseTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void idleStream_nextReturnsNullWithinPollTimeout() throws Exception {
        server = sseServer(false);
        OpenCodeApi api = api();

        OpenCodeApi.EventSource src = api.openEvents("/work");
        try {
            // Первое событие — server.connected (приходит сразу).
            assertNotNull(src.next());

            // Дальше idle: next() должен вернуться за разумное время (не висеть на socket-timeout),
            // чтобы цикл опроса переспросил состояние.
            ExecutorService ex = daemonExecutor();
            try {
                Future<OpenCodeApi.SseEvent> f = ex.submit(src::next);
                try {
                    OpenCodeApi.SseEvent ev = f.get(8, TimeUnit.SECONDS);
                    assertNull(ev, "idle-поток должен дать null (нет событий), а не событие");
                } catch (TimeoutException e) {
                    fail("next() висит >8с на idle-потоке — цикл опроса не переспрашивает состояние вовремя");
                }
            } finally {
                ex.shutdownNow();
            }
        } finally {
            src.close();
        }
    }

    @Test
    void messageEvent_isParsed() throws Exception {
        server = sseServer(true);
        OpenCodeApi api = api();

        OpenCodeApi.EventSource src = api.openEvents("/work");
        try {
            assertNotNull(src.next(), "первое событие server.connected");
            OpenCodeApi.SseEvent ev = src.next();
            assertNotNull(ev);
            assertEquals("message.updated", ev.type());
        } finally {
            src.close();
        }
    }

    private OpenCodeApi api() {
        return new OpenCodeApi("http://localhost:" + server.getAddress().getPort(), "deepseek/deepseek-v4-pro");
    }

    private static ExecutorService daemonExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "sse-next-test");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Локальный SSE-сервер: всегда {@code server.connected}, опционально {@code message.updated},
     * затем держим соединение открытым в тишине (idle).
     */
    private static HttpServer sseServer(boolean withMessage) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.setExecutor(command -> {
            Thread t = new Thread(command, "sse-http-test");
            t.setDaemon(true);
            t.start();
        });
        s.createContext("/event", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream os = exchange.getResponseBody();
            try {
                os.write("data: {\"type\":\"server.connected\",\"properties\":{}}\n\n".getBytes(StandardCharsets.UTF_8));
                if (withMessage) {
                    os.write("data: {\"type\":\"message.updated\",\"properties\":{}}\n\n".getBytes(StandardCharsets.UTF_8));
                }
                os.flush();
                // idle: соединение открыто, событий нет.
                while (true) {
                    Thread.sleep(1000);
                }
            } catch (Exception ignored) {
                // сервер остановлен
            } finally {
                exchange.close();
            }
        });
        s.start();
        return s;
    }
}
