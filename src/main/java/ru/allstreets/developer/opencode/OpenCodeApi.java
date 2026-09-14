package ru.allstreets.developer.opencode;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.resilience4j.retry.annotation.Retry;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.util.TimeValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Низкоуровневый HTTP-клиент к {@code opencode serve} sidecar (порт 4096).
 * <p>
 * Только транспорт: ни бизнес-логики, ни опроса, ни персистентности. Каждый вызов
 * короткий (connect ~5с, read ~15с) — долгих блокирующих запросов больше нет, долгая
 * работа агента выполняется асинхронно через {@code prompt_async} и наблюдается
 * повторяемыми {@code GET}-опросами ({@code getMessage}).
 * <p>
 * Транспорт — Apache HttpClient 5 с пулом соединений и контролем keep-alive
 * (замена {@code SimpleClientHttpRequestFactory}, у которого не было ни пула, ни
 * keep-alive). {@code @Retry} допустим <b>только</b> на идемпотентных {@code GET}-методах
 * и только на сетевых ошибках ({@code ResourceAccessException}); {@code promptAsync}
 * неидемпотентен и повторяется исключительно после проверки через {@code getMessage}.
 * <p>
 * Парсинг — прямые Java records + защитный {@code FAIL_ON_UNKNOWN_PROPERTIES=false}
 * (forward-compatible при обновлении образа sidecar). HTTP-статус проверяется до
 * парсинга тела: успех и ошибка — разные схемы JSON, Jackson не должен их путать.
 */
@Component
public class OpenCodeApi {

    private static final Logger log = LoggerFactory.getLogger(OpenCodeApi.class);

    private static final String DIRECTORY_HEADER = "x-opencode-directory";

    /**
     * Размер страницы {@code GET /session/:id/message?limit=&before=}. Sidecar отдаёт
     * всю историю сессии, если не передать {@code limit} — payload растёт неограниченно
     * и на длинной сессии обрывается на транспорте (Premature end of Content-Length).
     * Пагинация: без {@code before} страница возвращает {@code limit} новейших сообщений
     * и курсор {@code X-Next-Cursor} для следующей (более старой) страницы. Клиент
     * ({@code OpenCodeClient#collectRunMessages}) ходит окнами от новых к старым, пока не
     * дойдёт до нашего промпта ({@code info.id == messageId}), поэтому payload каждого
     * запроса ограничен этой константой.
     */
    static final int MESSAGE_PAGE_SIZE = 20;

    /**
     * Таймаут неактивности SSE-соединения. Heartbeat sidecar'а — 10с, поэтому 30с безопасно.
     */
    private static final int SSE_SOCKET_TIMEOUT_SECONDS = 30;

    private final RestClient api;
    private final ObjectMapper mapper;
    private final String model;
    private final String baseUrl;
    private final CloseableHttpClient sseHttpClient;

    public OpenCodeApi(
            @SuppressWarnings("HttpUrlsUsage") @Value("${opencode.base-url:http://opencode:4096}") String baseUrl,
            @Value("${opencode.model:deepseek/deepseek-v4-pro}") String model
    ) {
        this.model = model;
        this.baseUrl = baseUrl;
        this.mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(50)
                .setMaxConnPerRoute(20)
                .build();

        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .evictIdleConnections(TimeValue.ofSeconds(30))
                // sidecar закрывает reused-соединение на POST после GET — NoHttpResponse.
                // Для локального sidecar keep-alive не даёт выгоды, отключаем (Connection: close).
                .setDefaultHeaders(java.util.List.of(new org.apache.hc.core5.http.message.BasicHeader("Connection", "close")))
                .build();

        // Отдельный клиент для SSE: БЕЗ Connection: close — это одно долгоживущее соединение
        // (subscribe до конца прогона), а не короткий вызов. Таймауты задаются в ConnectionConfig
        // (setConnectTimeout/setSocketTimeout на RequestConfig помечены deprecated в httpclient5 5.4).
        ConnectionConfig sseConnectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(5, TimeUnit.SECONDS)
                .setSocketTimeout(SSE_SOCKET_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build();
        PoolingHttpClientConnectionManager sseConnectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDefaultConnectionConfig(sseConnectionConfig)
                .setMaxConnTotal(20)
                .setMaxConnPerRoute(20)
                .build();
        this.sseHttpClient = HttpClients.custom()
                .setConnectionManager(sseConnectionManager)
                .evictIdleConnections(TimeValue.ofSeconds(30))
                .build();

        HttpComponentsClientHttpRequestFactory requestFactory = new HttpComponentsClientHttpRequestFactory(httpClient);
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        // 60с, а не 15с: длинный GET отдаёт большой JSON, быстрый read timeout рвал чтение тела.
        requestFactory.setReadTimeout(Duration.ofSeconds(60));

        this.api = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .requestInterceptor(new ru.allstreets.developer.config.LoggingClientHttpRequestInterceptor())
                .build();

        log.info("OpenCodeApi инициализирован: baseUrl={}, model={}, пул соединений: 50 total / 20 per route", baseUrl, model);
    }

    // ---------------------------------------------------------------------
    // Публичные операции
    // ---------------------------------------------------------------------

    /**
     * Health-gate: fail fast (~2с) перед началом любого прогона.
     */
    @Retry(name = "opencodeApi")
    public HealthInfo health() {
        RawResponse resp = exchange(HttpMethod.GET, "/global/health", null, null);
        if (!resp.is2xx()) {
            throw openCodeError("health", resp);
        }
        JsonNode node = parseJson(resp.body(), "health");
        return new HealthInfo(
                node.path("healthy").asBoolean(false),
                node.has("version") ? node.path("version").asText(null) : null);
    }

    /**
     * Создать сессию, привязанную к рабочей директории. Возвращает {@code sessionId}.
     * Тело пустое: с opencode 1.18.x поле {@code title} больше не принимается
     * (400 BadRequest), заголовок генерируется самим сервером.
     */
    public String createSession(String cwd) {
        RawResponse resp = exchange(HttpMethod.POST, uriBuilder -> uriBuilder.path("/session")
                .queryParam("directory", cwd).build(), cwd, mapper.createObjectNode());
        if (!resp.is2xx()) {
            throw openCodeError("createSession", resp);
        }
        JsonNode session = parseJson(resp.body(), "createSession");
        String id = session.path("id").asText(null);
        if (id == null || id.isBlank()) {
            throw new OpenCodeApiException("OpenCode не вернул session id при создании сессии", resp.status(), null);
        }
        return id;
    }

    /**
     * Отправить промпт асинхронно (ожидает 204 No Content). {@code messageId} генерируем
     * сами — это ключ идемпотентности, по которому позже опрашиваем результат.
     * Неидемпотентная операция: <b>без {@code @Retry}</b>.
     */
    public void promptAsync(String sessionId, String cwd, String messageId, String agent, String prompt) {
        ObjectNode body = mapper.createObjectNode();
        body.put("messageID", messageId);
        body.put("agent", agent);
        String[] modelParts = model.split("/", 2);
        ObjectNode modelNode = mapper.createObjectNode();
        modelNode.put("providerID", modelParts[0]);
        modelNode.put("modelID", modelParts.length > 1 ? modelParts[1] : "");
        body.set("model", modelNode);
        ArrayNode parts = body.putArray("parts");
        parts.addObject().put("type", "text").put("text", prompt);

        RawResponse resp = exchange(HttpMethod.POST, "/session/" + sessionId + "/prompt_async", cwd, body);
        if (resp.status() != 204 && !resp.is2xx()) {
            throw openCodeError("promptAsync", resp);
        }
    }

    /**
     * Получить сообщение по id. Возвращает {@code null}, если сервер ещё не создал
     * сообщение (404) — это не ошибка, а сигнал «ждать дальше». Сетевая ошибка
     * ретраится (идемпотентный GET); 5xx от sidecar — нет (не retriable, п.1.2 плана).
     */
    @Retry(name = "opencodeApi")
    public MessageEnvelope getMessage(String sessionId, String cwd, String messageId) {
        RawResponse resp = exchange(HttpMethod.GET,
                "/session/" + sessionId + "/message/" + messageId, cwd, null);
        if (resp.status() == 404) {
            return null;
        }
        if (!resp.is2xx()) {
            throw openCodeError("getMessage", resp);
        }
        if (resp.body() == null || resp.body().isBlank()) {
            throw new OpenCodeApiException("OpenCode: пустой ответ на getMessage", 0, null);
        }
        try {
            return mapper.readValue(resp.body(), MessageEnvelope.class);
        } catch (JsonProcessingException e) {
            throw new OpenCodeApiException("OpenCode: не удалось распарсить ответ на getMessage: " + e.getMessage(), 0, null);
        }
    }

    /**
     * Одна страница списка сообщений сессии. Ответ — массив {@code [{info, parts}]}
     * плюс курсор следующей (более старой) страницы в {@code X-Next-Cursor}, если есть.
     * Без {@code before} страница содержит {@code limit} новейших сообщений.
     */
    @Retry(name = "opencodeApi")
    public MessagesPage listMessagesPage(String sessionId, String cwd, int limit, String before) {
        RawResponse resp = exchange(HttpMethod.GET,
                b -> {
                    var ub = b.path("/session/" + sessionId + "/message").queryParam("limit", limit);
                    if (before != null && !before.isBlank()) {
                        ub = ub.queryParam("before", before);
                    }
                    return ub.build();
                },
                cwd, null);
        if (!resp.is2xx()) {
            throw openCodeError("listMessages", resp);
        }
        if (resp.body() == null || resp.body().isBlank()) {
            return new MessagesPage(List.of(), null);
        }
        try {
            List<MessageEnvelope> items = mapper.readValue(resp.body(), new TypeReference<>() {
            });
            return new MessagesPage(items, resp.nextCursor());
        } catch (JsonProcessingException e) {
            throw new OpenCodeApiException("OpenCode: не удалось распарсить список сообщений: " + e.getMessage(), 0, null);
        }
    }

    /**
     * Статус всех сессий — health-gate перед опросом.
     */
    @Retry(name = "opencodeApi")
    public Map<String, SessionStatus> sessionStatuses() {
        RawResponse resp = exchange(HttpMethod.GET, "/session/status", null, null);
        if (!resp.is2xx()) {
            throw openCodeError("sessionStatuses", resp);
        }
        JsonNode root = parseJson(resp.body(), "sessionStatuses");
        var result = new java.util.LinkedHashMap<String, SessionStatus>();
        root.fields().forEachRemaining(entry ->
                result.put(entry.getKey(), mapper.convertValue(entry.getValue(), SessionStatus.class)));
        return result;
    }

    /**
     * Статус одной сессии ({@code idle}/{@code busy}/{@code retry}) или {@code null},
     * если сессии нет в списке. Используется для детекции зависания агента.
     */
    public SessionStatus sessionStatus(String sessionId) {
        return sessionStatuses().get(sessionId);
    }

    /**
     * Отменить работу сессии.
     */
    public boolean abort(String sessionId, String cwd) {
        RawResponse resp = exchange(HttpMethod.POST, "/session/" + sessionId + "/abort", cwd, null);
        if (!resp.is2xx()) {
            log.warn("[OpenCodeApi] abort сессии {} вернул статус {}", sessionId, resp.status());
            return false;
        }
        if (resp.body() == null || resp.body().isBlank()) {
            return true;
        }
        JsonNode node = parseJson(resp.body(), "abort");
        return node.isBoolean() ? node.asBoolean() : !node.isNull();
    }

    // ---------------------------------------------------------------------
    // SSE (GET /event)
    // ---------------------------------------------------------------------

    /**
     * Открыть событийный поток sidecar'а ({@code GET /event}, SSE). Возвращает блокирующий
     * источник событий; соединение держится до {@link EventSource#close()} или обрыва.
     * Используется как «пробуждение» в цикле опроса вместо слепого {@code sleep}.
     * <p>
     * <b>Важно:</b> поток live-only (без replay) — при резюме уже прошедшие события не
     * приходят, поэтому состояние прогона по-прежнему читается из
     * {@link #listMessagesPage} (durable), а SSE лишь ускоряет реакцию.
     */
    public EventSource openEvents(String cwd) throws IOException {
        HttpGet get = new HttpGet(baseUrl + "/event");
        get.setHeader(DIRECTORY_HEADER, cwd);
        get.setHeader("Accept", "text/event-stream");
        // executeOpen — недипрекейтед-путь для долгоживущего стрима (execute() закрыл бы ответ).
        ClassicHttpResponse response = sseHttpClient.executeOpen(null, get, null);
        int code = response.getCode();
        if (code < 200 || code >= 300) {
            response.close();
            throw new IOException("OpenCode /event вернул HTTP " + code);
        }
        return new SseEventSource(response);
    }

    /**
     * Блокирующий источник SSE-событий. {@link #next()} возвращает событие, {@code null}
     * при таймауте неактивности (здоровая пауза) и бросает {@link IOException} при обрыве
     * соединения (EOF) или транспортной ошибке.
     */
    public interface EventSource extends AutoCloseable {
        SseEvent next() throws IOException;

        @Override
        void close();
    }

    /**
     * Событие шины sidecar'а: {@code {id, type, properties}}. {@code properties} несёт
     * {@code sessionID}/{@code assistantMessageID} (но не {@code parentID}).
     */
    public record SseEvent(String id, String type, JsonNode properties) {
    }

    private final class SseEventSource implements EventSource {

        private final ClassicHttpResponse response;
        private final BufferedReader reader;

        private SseEventSource(ClassicHttpResponse response) throws IOException {
            this.response = response;
            this.reader = new BufferedReader(new InputStreamReader(response.getEntity().getContent(), StandardCharsets.UTF_8));
        }

        @Override
        public SseEvent next() throws IOException {
            StringBuilder data = null;
            String line;
            try {
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        if (data != null) {
                            return parse(data.toString());
                        }
                        continue;
                    }
                    if (line.startsWith("data:")) {
                        if (data == null) {
                            data = new StringBuilder();
                        } else {
                            data.append('\n');
                        }
                        data.append(line.substring("data:".length()).trim());
                    }
                    // event:/id:/retry: — не используем (event всегда "message", id пустой)
                }
            } catch (SocketTimeoutException e) {
                // Таймаут неактивности — это не обрыв: heartbeat ходит каждые 10с, но на
                // долгой паузе (idle-агент) можем дождаться таймаута. Возвращаем null.
                return null;
            }
            // EOF — соединение закрыто.
            if (data != null) {
                return parse(data.toString());
            }
            throw new IOException("SSE поток закрыт (EOF)");
        }

        private SseEvent parse(String raw) throws IOException {
            try {
                JsonNode node = OpenCodeApi.this.mapper.readTree(raw);
                String id = node.has("id") && !node.path("id").isNull() ? node.path("id").asText() : null;
                String type = node.path("type").asText(null);
                JsonNode props = node.get("properties");
                return new SseEvent(id, type, props != null && !props.isNull() ? props : OpenCodeApi.this.mapper.createObjectNode());
            } catch (JsonProcessingException e) {
                throw new IOException("Не-JSON SSE-событие: " + e.getMessage(), e);
            }
        }

        @Override
        public void close() {
            try {
                reader.close();
            } catch (IOException ignored) {
                // ignore
            }
            try {
                response.close();
            } catch (IOException ignored) {
                // ignore
            }
        }
    }

    // ---------------------------------------------------------------------
    // Транспорт
    // ---------------------------------------------------------------------

    /**
     * Единая точка HTTP-обмена. Никогда не бросает на 4xx/5xx — статус возвращается
     * вызывающему, который решает, как трактовать тело (успех и ошибка — разные схемы).
     */
    private RawResponse exchange(HttpMethod method, String uri, String cwd, Object requestBody) {
        return exchange(method, b -> b.path(uri).build(), cwd, requestBody);
    }

    private RawResponse exchange(HttpMethod method, Function<UriBuilder, URI> uriFn, String cwd, Object requestBody) {
        try {
            RestClient.RequestBodySpec request = api.method(method)
                    .uri(uriFn)
                    .accept(MediaType.APPLICATION_JSON);
            if (cwd != null) {
                request = request.header(DIRECTORY_HEADER, cwd);
            }
            if (requestBody != null) {
                request = request.contentType(MediaType.APPLICATION_JSON);
                // Сериализуем тело в строку заранее: так RestClient шлёт Content-Length,
                // а не Transfer-Encoding: chunked. sidecar (Bun) не принимает chunked POST
                // (закрывает соединение без ответа → NoHttpResponseException).
                request = request.body(toJson(requestBody));
            }
            var response = request.retrieve().toEntity(String.class);
            return new RawResponse(response.getStatusCode().value(), response.getBody(),
                    response.getHeaders().getFirst("X-Next-Cursor"));
        } catch (HttpStatusCodeException e) {
            return new RawResponse(e.getStatusCode().value(), e.getResponseBodyAsString(), null);
        }
    }

    private JsonNode parseJson(String raw, String op) {
        if (raw == null || raw.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            return mapper.readTree(raw);
        } catch (JsonProcessingException e) {
            throw new OpenCodeApiException("OpenCode: не-JSON ответ на " + op + ": " + e.getMessage(), 0, null);
        }
    }

    private String toJson(Object body) {
        if (body instanceof String s) {
            return s;
        }
        try {
            return mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new OpenCodeApiException("OpenCode: не удалось сериализовать тело запроса: " + e.getMessage(), 0, null);
        }
    }

    private OpenCodeApiException openCodeError(String op, RawResponse resp) {
        OpenCodeError parsed = null;
        try {
            parsed = resp.body() != null && !resp.body().isBlank()
                    ? mapper.readValue(resp.body(), OpenCodeError.class) : null;
        } catch (JsonProcessingException ignored) {
            // тело не в схеме OpenCodeError (HTML-прокси и т.п.) — оставляем null
        }
        String message = parsed != null && parsed.data() != null && parsed.data().message() != null
                ? parsed.data().message()
                : (resp.body() != null ? resp.body() : "HTTP " + resp.status());
        return new OpenCodeApiException(
                "OpenCode [" + op + "] HTTP " + resp.status() + ": " + message,
                resp.status(), parsed);
    }

    // ---------------------------------------------------------------------
    // Модель ответов (Java records, FAIL_ON_UNKNOWN_PROPERTIES=false)
    // ---------------------------------------------------------------------

    private record RawResponse(int status, String body, String nextCursor) {
        @SuppressWarnings("BooleanMethodIsAlwaysInverted")
        boolean is2xx() {
            return status >= 200 && status < 300;
        }
    }

    /**
     * Страница списка сообщений: {@code items} + {@code nextCursor} следующей (более
     * старой) страницы, либо {@code null}, если страниц больше нет.
     */
    public record MessagesPage(List<MessageEnvelope> items, String nextCursor) {
    }

    /**
     * Ответ {@code GET /global/health}.
     */
    public record HealthInfo(boolean healthy, String version) {
    }

    /**
     * Ответ {@code GET /session/:id/message/:messageID}.
     */
    public record MessageEnvelope(MessageInfo info, List<Part> parts) {
        /**
         * Только assistant-сообщение считается завершённым ответом агента.
         */
        public boolean isAssistant() {
            return info != null && "assistant".equals(info.role());
        }

        /**
         * Завершено: у AssistantMessage присутствует {@code time.completed}.
         */
        public boolean isCompleted() {
            return isAssistant() && info.time() != null && info.time().completed() != null;
        }

        /**
         * Ошибка в сообщении.
         */
        public boolean hasError() {
            return info != null && info.error() != null;
        }

        /**
         * Весь накопленный текст агента из text-частей.
         */
        public String text() {
            if (parts == null) return "";
            StringBuilder sb = new StringBuilder();
            for (Part p : parts) {
                if ("text".equals(p.type()) && p.text() != null) {
                    sb.append(p.text());
                }
            }
            return sb.toString();
        }

        /**
         * Типы партов сообщения — для диагностики: видно, был ли ответ reasoning-only,
         * обошёлся ли без text-парта и т.п. (см. инциденты с «пустым» решением аналитика).
         */
        public String partTypes() {
            if (parts == null || parts.isEmpty()) return "[]";
            StringBuilder sb = new StringBuilder("[");
            for (Part p : parts) {
                if (sb.length() > 1) sb.append(',');
                sb.append(p.type());
            }
            return sb.append(']').toString();
        }
    }

    /**
     * {@code UserMessage | AssistantMessage}. {@code role} — дискриминатор.
     * Для assistant-сообщений есть {@code time.completed}, {@code error}, {@code finish},
     * {@code parentID} (id user-сообщения-промпта), а также {@code cost}/{@code tokens}
     * (используются для прогресса: tokens/steps).
     */
    public record MessageInfo(
            String id,
            String role,
            TimeInfo time,
            MessageError error,
            String finish,
            String parentID,
            Double cost,
            Tokens tokens
    ) {
        /**
         * Суммарное число токенов шага (input + output + reasoning).
         */
        public long totalTokens() {
            if (tokens == null) {
                return 0;
            }
            return nz(tokens.input()) + nz(tokens.output()) + nz(tokens.reasoning());
        }

        private static long nz(Integer v) {
            return v != null ? v : 0;
        }
    }

    /**
     * Токены assistant-сообщения (шага): {@code {input, output, reasoning, cache}}.
     */
    public record Tokens(Integer input, Integer output, Integer reasoning) {
    }

    /**
     * {@code time.completed != null} → сообщение завершено.
     */
    public record TimeInfo(long created, Long completed) {
    }

    public record MessageError(String name, ErrorData data) {
    }

    /**
     * {@code ref} = server-side correlation ID ("err_XXXXX") для поиска в docker logs.
     */
    public record ErrorData(String message, String ref) {
    }

    /**
     * Part — union из 12 типов; нам нужны {@code text} (ответ) и {@code tool} (имя инструмента
     * для прогресса).
     */
    public record Part(String type, String text, String tool) {
    }

    /**
     * Error-ответ (4xx/5xx): {@code {name, data}}.
     */
    public record OpenCodeError(String name, ErrorData data) {
    }

    /**
     * {@code type = "idle" | "busy" | "retry"}.
     */
    public record SessionStatus(String type, Integer attempt, String message, Long next) {
        /**
         * Агент работает: обрабатывает prompt или выполняет инструмент.
         */
        public boolean isBusy() {
            return "busy".equals(type) || "retry".equals(type);
        }
    }

    /**
     * Ошибка вызова sidecar: HTTP-статус + распарсенный {@link OpenCodeError} (если тело
     * соответствовало схеме). Не сетевая — {@code @Retry} её не ловит.
     */
    public static class OpenCodeApiException extends RuntimeException {
        private final int status;
        private final OpenCodeError error;

        public OpenCodeApiException(String message, int status, OpenCodeError error) {
            super(message);
            this.status = status;
            this.error = error;
        }

        public int status() {
            return status;
        }

        public OpenCodeError error() {
            return error;
        }

        /**
         * {@code ref} ("err_XXXXX") для поиска в docker logs opencode, если есть.
         */
        public String errorRef() {
            return error != null && error.data() != null ? error.data().ref() : null;
        }
    }
}
