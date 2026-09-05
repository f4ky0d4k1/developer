package ru.allstreets.developer.opencode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * HTTP-клиент к {@code opencode serve} sidecar (порт 4096) — заменяет прежний
 * подход через {@code docker exec}, который требовал монтирования
 * {@code /var/run/docker.sock} в контейнер {@code developer} и не давал
 * реальной отмены работы агента по таймауту (убивался только локальный
 * {@code docker exec}-клиент, а не процесс внутри sidecar-контейнера).
 * <p>
 * Использует HTTP API форка {@code anomalyco/opencode}:
 * {@code POST /session?directory=...} — создание сессии, привязанной к рабочей директории;
 * {@code POST /session/{id}/message} — отправка промпта, ожидание ответа;
 * {@code POST /session/{id}/abort} — реальная отмена работающей сессии по таймауту.
 * Все запросы дополнительно передают заголовок {@code x-opencode-directory} —
 * сервер scoped запросы этим заголовком (см. project/session scoping в API форка).
 */
@Component
public class OpenCodeClient {

    private static final Logger log = LoggerFactory.getLogger(OpenCodeClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final RestClient api;
    private final String model;
    private final int timeoutSeconds;
    private final TaskProgressRegistry progressRegistry;

    @Autowired
    public OpenCodeClient(
            @SuppressWarnings("HttpUrlsUsage") @Value("${opencode.base-url:http://opencode:4096}") String baseUrl,
            @Value("${opencode.model:deepseek/deepseek-v4-pro}") String model,
            @Value("${opencode.timeout-seconds:300}") int timeoutSeconds,
            TaskProgressRegistry progressRegistry
    ) {
        this.model = model;
        this.timeoutSeconds = timeoutSeconds;
        this.progressRegistry = progressRegistry;

        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
        // Небольшой запас сверх timeoutSeconds — реальная остановка работы
        // делается через /session/{id}/abort, а не через обрыв соединения.
        requestFactory.setReadTimeout((int) Duration.ofSeconds(timeoutSeconds + 30L).toMillis());

        this.api = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    @CircuitBreaker(name = "opencode")
    @Retry(name = "opencode")
    public OpenCodeResult runAgent(String agentName, String prompt, String cwd, String taskId) {
        return runAgentInternal(agentName, prompt, cwd, taskId, null);
    }

    @CircuitBreaker(name = "opencode")
    @Retry(name = "opencode")
    public OpenCodeResult runAgent(String agentName, String prompt, String cwd, String taskId, String sessionId) {
        return runAgentInternal(agentName, prompt, cwd, taskId, sessionId);
    }

    private OpenCodeResult runAgentInternal(String agentName, String prompt, String cwd, String taskId, String sessionId) {
        log.info("Запуск OpenCode агента: {} в {} (промпт: {} символов, taskId={}, session={})",
                agentName, cwd, prompt.length(), taskId, sessionId);

        if (taskId != null) {
            progressRegistry.start(taskId, agentName);
        }

        String activeSessionId = sessionId;
        try {
            if (activeSessionId == null || activeSessionId.isBlank()) {
                activeSessionId = createSession(cwd, agentName);
                log.info("[OpenCode:{}] создана сессия {} для директории {}", agentName, activeSessionId, cwd);
            }

            ObjectNode body = mapper.createObjectNode();
            body.put("agent", agentName);
            body.put("model", model);
            ArrayNode parts = body.putArray("parts");
            parts.addObject().put("type", "text").put("text", prompt);

            JsonNode response = api.post()
                    .uri("/session/{id}/message", activeSessionId)
                    .header("x-opencode-directory", cwd)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);

            return parseResponse(agentName, response, activeSessionId, taskId);

        } catch (ResourceAccessException e) {
            log.error("[OpenCode:{}] таймаут/ошибка соединения после {}с: {}", agentName, timeoutSeconds, e.getMessage());
            abortQuietly(activeSessionId, cwd, agentName);
            if (taskId != null) {
                progressRegistry.recordError(taskId, "timeout/connection: " + e.getMessage());
            }
            throw new RuntimeException("Таймаут OpenCode (" + timeoutSeconds + "с) для агента: " + agentName, e);
        } catch (Exception e) {
            log.error("[OpenCode:{}] ошибка вызова: {}", agentName, e.getMessage(), e);
            abortQuietly(activeSessionId, cwd, agentName);
            if (taskId != null) {
                progressRegistry.recordError(taskId, e.getMessage());
            }
            throw new RuntimeException("Ошибка запуска OpenCode агента " + agentName + ": " + e.getMessage(), e);
        } finally {
            if (taskId != null) {
                progressRegistry.markFinished(taskId);
            }
        }
    }

    private String createSession(String cwd, String agentName) {
        ObjectNode body = mapper.createObjectNode();
        body.put("title", agentName + "-" + System.currentTimeMillis());

        JsonNode session = api.post()
                .uri(uriBuilder -> uriBuilder.path("/session").queryParam("directory", cwd).build())
                .header("x-opencode-directory", cwd)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        if (session == null || session.path("id").isMissingNode()) {
            throw new RuntimeException("OpenCode не вернул session id при создании сессии для " + agentName);
        }
        return session.path("id").asText();
    }

    private void abortQuietly(String sessionId, String cwd, String agentName) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            api.post()
                    .uri("/session/{id}/abort", sessionId)
                    .header("x-opencode-directory", cwd)
                    .retrieve()
                    .toBodilessEntity();
            log.info("[OpenCode:{}] сессия {} остановлена (abort)", agentName, sessionId);
        } catch (Exception e) {
            log.warn("[OpenCode:{}] не удалось прервать сессию {}: {}", agentName, sessionId, e.getMessage());
        }
    }

    private OpenCodeResult parseResponse(String agentName, JsonNode response, String sessionId, String taskId) {
        if (response == null) {
            return new OpenCodeResult("error", "", null, null, List.of(),
                    "OpenCode вернул пустой ответ", sessionId);
        }

        JsonNode info = response.path("info");
        JsonNode parts = response.path("parts");

        StringBuilder text = new StringBuilder();
        List<String> toolCalls = new ArrayList<>();

        if (parts.isArray()) {
            for (JsonNode part : parts) {
                String type = part.path("type").asText("");
                switch (type) {
                    case "text" -> {
                        String t = part.path("text").asText("");
                        text.append(t);
                        if (taskId != null && !t.isBlank()) {
                            progressRegistry.recordText(taskId, t);
                        }
                    }
                    case "tool", "tool-invocation", "tool_call", "tool_use" -> {
                        String toolName = part.path("tool").asText(part.path("toolName").asText("unknown"));
                        toolCalls.add(toolName);
                        if (taskId != null) {
                            progressRegistry.recordToolCall(taskId, toolName);
                        }
                    }
                    default -> log.debug("[OpenCode:{}] part type: {}", agentName, type);
                }
            }
        }

        String error = null;
        JsonNode errorNode = info.path("error");
        if (!errorNode.isMissingNode() && !errorNode.isNull()) {
            error = errorNode.has("message") ? errorNode.path("message").asText() : errorNode.toString();
        }

        if (error != null && taskId != null) {
            progressRegistry.recordError(taskId, error);
        }

        log.info("Агент {} завершил работу. session={}, toolCalls={}, текст={} символов, error={}",
                agentName, sessionId, toolCalls, text.length(), error);

        return new OpenCodeResult(
                error == null ? "success" : "error",
                text.toString(),
                null,
                null,
                toolCalls,
                error,
                sessionId
        );
    }

    public record OpenCodeResult(
            String status,
            String output,
            String diff,
            String commitHash,
            List<String> files,
            String error,
            String sessionId
    ) {
    }
}
