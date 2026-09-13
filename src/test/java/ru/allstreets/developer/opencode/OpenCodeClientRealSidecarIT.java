package ru.allstreets.developer.opencode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import ru.allstreets.developer.PostgresTestBase;

import java.time.Duration;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end: {@link OpenCodeClient} против <b>реального</b> opencode sidecar
 * (контейнер {@code ghcr.io/anomalyco/opencode}) + Testcontainers PostgreSQL.
 * <p>
 * LLM (DeepSeek) мокается <b>внутри</b> opencode через переопределение
 * {@code provider.deepseek.options.baseURL} на JVM-WireMock: sidecar реально
 * совершает OpenAI-совместимый вызов {@code POST /v1/chat/completions}, а мы
 * возвращаем детерминированный SSE-стрим. Одновременно проверяем, что именно
 * уходит на вход мок-LLM (model, промпт), через {@code getAllServeEvents()}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OpenCodeClientRealSidecarIT extends PostgresTestBase {

    private static final Logger log = LoggerFactory.getLogger(OpenCodeClientRealSidecarIT.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private static WireMockServer llm;
    private static GenericContainer<?> opencode;

    @Autowired
    private OpenCodeRunRepository runRepo;

    private OpenCodeApi api;
    private TaskProgressRegistry progress;

    // Контейнер живёт весь прогон класса (stop в @AfterAll) — try-with-resources неприменим.
    @BeforeAll
    @SuppressWarnings("resource")
    static void startContainers() {
        // LLM-мок: биндим на 0.0.0.0, чтобы opencode-контейнер достал его по host.docker.internal
        llm = new WireMockServer(WireMockConfiguration.options().dynamicPort().bindAddress("0.0.0.0"));
        llm.start();

        opencode = new GenericContainer<>("ghcr.io/anomalyco/opencode")
                .withExposedPorts(4096)
                .withCommand("serve", "--port", "4096", "--hostname", "0.0.0.0")
                .withEnv("OPENCODE_EXPERIMENTAL", "1")
                .withEnv("DEEPSEEK_API_KEY", "test-key")
                .withExtraHost("host.docker.internal", "host-gateway")
                .withWorkingDirectory("/work")
                .withCopyToContainer(Transferable.of(opencodeConfig(llm.port())),
                        "/root/.config/opencode/opencode.jsonc")
                .withCopyToContainer(Transferable.of(TEST_ANALYST_AGENT),
                        "/work/.opencode/agents/analyst.md")
                .waitingFor(Wait.forHttp("/global/health").forPort(4096).forStatusCode(200))
                .withStartupTimeout(Duration.ofSeconds(120));
        opencode.start();
    }

    @AfterAll
    static void stopContainers() {
        if (opencode != null) {
            System.out.println("===== opencode container logs =====");
            System.out.println(opencode.getLogs());
            opencode.stop();
        }
        if (llm != null) {
            llm.stop();
        }
    }

    @BeforeEach
    void setUp() {
        llm.resetAll();
        api = new OpenCodeApi("http://localhost:" + opencode.getMappedPort(4096),
                "deepseek/deepseek-v4-pro");
        progress = Mockito.mock(TaskProgressRegistry.class);
    }

    private OpenCodeClient client() {
        return new OpenCodeClient(api, runRepo, progress, 60, 1, 120);
    }

    @Test
    void runAgent_completesAgainstRealSidecar() {
        stubLlm("E2E_ANALYSIS_OK");

        var result = client().runAgent("analyst", "проверочный промпт", "/work", "task-e2e");

        assertEquals("success", result.status());
        assertEquals("E2E_ANALYSIS_OK", result.output());

        var done = runRepo.findByTaskIdAndAgentNameAndStatusInOrderByStartedAtDesc(
                "task-e2e", "analyst", List.of(OpenCodeRunStatus.DONE));
        assertEquals(1, done.size());
        assertTrue(done.getFirst().isPromptSent());
    }

    @Test
    void llmReceivesModelAndPrompt() {
        stubLlm("E2E_ANALYSIS_OK");

        client().runAgent("analyst", "уникальный-маркер-промпта", "/work", "task-e2e");

        List<ServeEvent> events = llm.getAllServeEvents().stream()
                .filter(e -> e.getRequest().getUrl().contains("chat/completions"))
                .toList();

        assertFalse(events.isEmpty(), "LLM-мок не получил ни одного запроса");

        for (ServeEvent e : events) {
            String body = e.getRequest().getBodyAsString();
            log.info("=== LLM request: {} {} ===\n{}", e.getRequest().getMethod(),
                    e.getRequest().getUrl(), body);

            JsonNode root = parse(body);
            // Модель ушла как deepseek-v4-pro (providerID/modelID из opencode.model)
            assertTrue(root.path("model").asText().contains("deepseek-v4-pro"),
                    "model в запросе не совпадает: " + root.path("model").asText());
            // Промпт дошёл до LLM
            assertTrue(body.contains("уникальный-маркер-промпта"),
                    "текст промпта не найден в теле запроса к LLM");
        }
    }

    @Test
    void sessionStatus_reportsBusyWhileAgentWorks() throws Exception {
        // LLM отвечает с задержкой, чтобы застать агента в работе.
        stubLlmDelayed("E2E_OK", 8000);

        String sid = api.createSession("/work");
        String mid = "msg_" + java.util.UUID.randomUUID();
        api.promptAsync(sid, "/work", mid, "analyst", "пробный промпт");

        Thread.sleep(3000);
        var status = api.sessionStatus(sid);
        log.info("session status while working: {}", status);

        assertNotNull(status, "сессия должна присутствовать в /session/status");
        assertTrue(status.isBusy(), "во время работы сессия должна быть busy, была: " + status);
    }

    // ---------------------------------------------------------------------

    @SuppressWarnings("SameParameterValue")
    private void stubLlm(String content) {
        llm.stubFor(post(urlPathMatching(".*/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/event-stream; charset=utf-8")
                        .withBody(sseStream(content))));
    }

    @SuppressWarnings("SameParameterValue")
    private void stubLlmDelayed(String content, int delayMs) {
        llm.stubFor(post(urlPathMatching(".*/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/event-stream; charset=utf-8")
                        .withFixedDelay(delayMs)
                        .withBody(sseStream(content))));
    }

    private static String sseStream(String content) {
        String chunk1 = """
                {"id":"cmpl-1","object":"chat.completion.chunk","created":1,"model":"deepseek-v4-pro","choices":[{"index":0,"delta":{"role":"assistant","content":"%s"},"finish_reason":null}]}
                """.formatted(content).trim();
        String chunk2 = """
                {"id":"cmpl-1","object":"chat.completion.chunk","created":1,"model":"deepseek-v4-pro","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}
                """.trim();
        return "data: " + chunk1 + "\n\n"
                + "data: " + chunk2 + "\n\n"
                + "data: [DONE]\n\n";
    }

    @SuppressWarnings("HttpUrlsUsage")
    private static String opencodeConfig(int llmPort) {
        return """
                {
                  "$schema": "https://opencode.ai/config.json",
                  "provider": {
                    "deepseek": {
                      "name": "DeepSeek",
                      "options": {
                        "baseURL": "http://host.docker.internal:%d/v1",
                        "apiKey": "test-key"
                      },
                      "models": {
                        "deepseek-v4-pro": {},
                        "deepseek-v4-flash": {}
                      }
                    }
                  }
                }
                """.formatted(llmPort);
    }

    private static JsonNode parse(String body) {
        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            throw new AssertionError("LLM-запрос не является JSON: " + body, e);
        }
    }

    private static final String TEST_ANALYST_AGENT = """
            ---
            description: Тестовый аналитик (e2e)
            mode: primary
            model: deepseek/deepseek-v4-pro
            ---
            
            Ты тестовый аналитик. Ответь коротким текстом, без вызова инструментов.
            """;
}
