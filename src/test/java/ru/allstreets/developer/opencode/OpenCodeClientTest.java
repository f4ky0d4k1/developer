package ru.allstreets.developer.opencode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.common.FileSource;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.extension.Parameters;
import com.github.tomakehurst.wiremock.extension.ResponseTransformer;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.http.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import ru.allstreets.developer.PostgresTestBase;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

/**
 * Интеграционный тест {@link OpenCodeClient} против WireMock-стаба sidecar + реального
 * PostgreSQL. Sidecar эмулируется с учётом реального контракта opencode 1.18.x:
 * {@code prompt_async} принимает наш {@code messageID} как id user-сообщения, а ответ
 * агента — это assistant-сообщение в списке {@code GET /session/:id/message} с
 * {@code parentID == messageID}. {@link SidecarTransformer} перехватывает messageID из
 * prompt_async и возвращает assistant-сообщение с нужным parentID.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OpenCodeClientTest extends PostgresTestBase {

    private static final ObjectMapper mapper = new ObjectMapper();

    private static WireMockServer sidecar;
    private static SidecarTransformer transformer;

    @Autowired
    private OpenCodeRunRepository runRepo;

    private OpenCodeApi api;
    private TaskProgressRegistry progress;

    @BeforeAll
    static void startSidecar() {
        transformer = new SidecarTransformer();
        sidecar = new WireMockServer(WireMockConfiguration.options()
                .dynamicPort()
                .extensions(transformer));
        sidecar.start();
    }

    @AfterAll
    static void stopSidecar() {
        sidecar.stop();
    }

    @BeforeEach
    void setUp() {
        transformer.reset();
        sidecar.resetAll();
        sidecar.stubFor(get(urlPathEqualTo("/global/health")).willReturn(okJson("{\"healthy\":true}")));
        sidecar.stubFor(post(urlPathEqualTo("/session")).willReturn(okJson("{\"id\":\"ses_1\"}")));
        sidecar.stubFor(post(urlPathMatching("/session/.*/prompt_async")).willReturn(aResponse().withStatus(204)));
        // Заглушка-заглушка: реальный ответ на список строит transformer (applyGlobally).
        sidecar.stubFor(get(urlPathMatching("/session/[^/]+/message"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("[]")));

        api = new OpenCodeApi(sidecar.baseUrl(), "deepseek/deepseek-v4-pro");
        progress = Mockito.mock(TaskProgressRegistry.class);
    }

    private OpenCodeClient client() {
        return new OpenCodeClient(api, runRepo, progress, metrics(), 300, 1, 120);
    }

    @SuppressWarnings("SameParameterValue")
    private OpenCodeClient client(int timeoutSeconds) {
        return new OpenCodeClient(api, runRepo, progress, metrics(), timeoutSeconds, 1, 120);
    }

    @SuppressWarnings("SameParameterValue")
    private OpenCodeClient client(int timeoutSeconds, int stallTimeoutSeconds) {
        return new OpenCodeClient(api, runRepo, progress, metrics(), timeoutSeconds, 1, stallTimeoutSeconds);
    }

    private static ru.allstreets.developer.metrics.TaskMetrics metrics() {
        return new ru.allstreets.developer.metrics.TaskMetrics(
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    @Test
    void runAgent_completesAndPersistsDone() {
        var result = client().runAgent("analyst", "промпт", "/work/slot-0", "task-1");

        assertEquals("success", result.status());
        assertEquals("Анализ завершён", result.output());
        assertEquals("ses_1", result.sessionId());

        var runs = runRepo.findByTaskIdAndAgentNameAndStatusInOrderByStartedAtDesc(
                "task-1", "analyst", List.of(OpenCodeRunStatus.DONE));
        assertEquals(1, runs.size());
        assertEquals("Анализ завершён", runs.getFirst().getOutput());
        assertTrue(runs.getFirst().isPromptSent());

        verify(progress).start("task-1", "analyst");
        verify(progress).recordText("task-1", "Анализ завершён");
        verify(progress).markFinished("task-1");
    }

    @Test
    void runAgent_recordsDeltaAcrossPolls() {
        transformer.twoPhase = true;

        var result = client().runAgent("analyst", "промпт", "/work/slot-0", "task-1");

        assertEquals("success", result.status());
        // Первый опрос отдал префикс "Анал", второй — полный текст; delta = остаток.
        verify(progress).recordText("task-1", "Анал");
        verify(progress).recordText("task-1", "Анализ завершён".substring("Анал".length()));
    }

    @Test
    void runAgent_multiStep_ignoresIntermediateToolCallsStep() {
        transformer.multiStep = true;

        var result = client().runAgent("analyst", "промпт", "/work/slot-0", "task-1");

        // Промежуточный шаг (finish=tool-calls, без text) не должен становиться результатом:
        // ждём финальный шаг (finish=stop) и отдаём его текст. Инциденты 3c7b33db/fab06fb0.
        assertEquals("success", result.status());
        assertEquals("финальный ответ", result.output());
    }

    @Test
    void runAgent_resumesExistingRun_withoutResendingPrompt() {
        transformer.forceMessageId("msg_existing");
        var existing = new OpenCodeRunEntity("task-1", "analyst", "ses_existing", "msg_existing",
                "/work/slot-0", OpenCodeRunStatus.RUNNING);
        existing.setPromptSent(true);
        runRepo.saveAndFlush(existing);

        var result = client().runAgent("analyst", "промпт", "/work/slot-0", "task-1");

        assertEquals("success", result.status());
        assertEquals("ses_existing", result.sessionId());
        // Ни создания сессии, ни повторной отправки промпта.
        sidecar.verify(0, postRequestedFor(urlPathEqualTo("/session")));
        sidecar.verify(0, postRequestedFor(urlPathMatching("/session/.*/prompt_async")));
    }

    @Test
    void runAgent_unhealthySidecar_returnsErrorWithoutPrompt() {
        sidecar.stubFor(get(urlPathEqualTo("/global/health"))
                .willReturn(okJson("{\"healthy\":false}")));

        var result = client().runAgent("analyst", "промпт", "/work/slot-0", "task-1");

        assertEquals("error", result.status());
        assertTrue(result.error().contains("unhealthy"));
        sidecar.verify(0, postRequestedFor(urlPathEqualTo("/session")));
    }

    @Test
    void runAgent_timeout_abortsAndPersistsAborted() {
        transformer.emptyAssistant = true;

        var result = client(2).runAgent("analyst", "промпт", "/work/slot-0", "task-1");

        assertEquals("error", result.status());
        assertTrue(result.error().contains("Таймаут"));
        sidecar.verify(postRequestedFor(urlPathEqualTo("/session/ses_1/abort")));

        var aborted = runRepo.findByTaskIdAndAgentNameAndStatusInOrderByStartedAtDesc(
                "task-1", "analyst", List.of(OpenCodeRunStatus.ABORTED));
        assertEquals(1, aborted.size());
    }

    @Test
    void runAgent_hungAgent_stallsEarlyAndPersistsPartial() {
        transformer.partialAssistant = true;
        // sidecar сообщает idle, прогресса нет — агент завис (сессия не busy).
        sidecar.stubFor(get(urlPathEqualTo("/session/status"))
                .willReturn(okJson("{\"ses_1\":{\"type\":\"idle\"}}")));

        // stall=2с срабатывает раньше большого бюджета (60с).
        var result = client(60, 2).runAgent("analyst", "промпт", "/work/slot-0", "task-1");

        assertEquals("error", result.status());
        assertTrue(result.error().contains("завис"), "ожидали детекцию зависания: " + result.error());
        sidecar.verify(postRequestedFor(urlPathEqualTo("/session/ses_1/abort")));

        var aborted = runRepo.findByTaskIdAndAgentNameAndStatusInOrderByStartedAtDesc(
                "task-1", "analyst", List.of(OpenCodeRunStatus.ABORTED));
        assertEquals(1, aborted.size());
        // Партиал сохранён — работа агента не потеряна.
        assertEquals("частичный вывод", aborted.getFirst().getOutput());
    }

    @Test
    void runAgent_busySession_isNotKilledAsHung() {
        transformer.partialAssistant = true;
        // Агент долго работает (busy) — stall-детекция не должна его убивать.
        sidecar.stubFor(get(urlPathEqualTo("/session/status"))
                .willReturn(okJson("{\"ses_1\":{\"type\":\"busy\"}}")));

        // При busy зависание не детектируется → доходим до бюджета.
        var result = client(2, 2).runAgent("analyst", "промпт", "/work/slot-0", "task-1");

        assertEquals("error", result.status());
        assertTrue(result.error().contains("Таймаут"), "ожидали таймаут бюджета, не stall: " + result.error());
    }

    // ---------------------------------------------------------------------

    /**
     * Перехватывает messageID из {@code prompt_async} и возвращает assistant-сообщение
     * с {@code parentID == messageID} в ответе на список сообщений. Эмулирует реальный
     * контракт opencode 1.18.x (messageID → user-сообщение; assistant → отдельное сообщение).
     * {@code ResponseTransformer} помечен deprecated в WireMock 3.9 (миграция на новый
     * extension SPI), но недипрекейтед-эквивалента для генерации ответа нет.
     */
    @SuppressWarnings("deprecation")
    static class SidecarTransformer extends ResponseTransformer {

        private String messageId;
        private boolean twoPhase;
        private boolean emptyAssistant;
        private boolean partialAssistant;
        private boolean multiStep;
        private int listCalls;

        void reset() {
            messageId = null;
            twoPhase = false;
            emptyAssistant = false;
            partialAssistant = false;
            multiStep = false;
            listCalls = 0;
        }

        @SuppressWarnings("SameParameterValue")
        void forceMessageId(String id) {
            messageId = id;
        }

        @Override
        public Response transform(Request request, Response response, FileSource files, Parameters params) {
            String url = request.getUrl();
            String method = request.getMethod().getName();

            if ("POST".equals(method) && url.contains("prompt_async")) {
                try {
                    messageId = mapper.readTree(request.getBodyAsString()).path("messageID").asText();
                } catch (Exception ignored) {
                    // ignore — диагностический запрос без body
                }
                return response;
            }

            if ("GET".equals(method) && url.matches(".*/session/[^/]+/message(\\?.*)?")) {
                if (emptyAssistant) {
                    return jsonList(response, java.util.Collections.emptyList());
                }
                if (partialAssistant) {
                    // Ассистент есть, текст копится, но сообщение никогда не завершается.
                    return jsonList(response, List.of(assistant(messageId, "частичный вывод", false)));
                }
                if (multiStep) {
                    // Реальный контракт: отдельное assistant-сообщение на КАЖДЫЙ шаг, все с
                    // parentID == messageID. Первый шаг завершён finish=tool-calls и без text,
                    // финальный — finish=stop с текстом. Результатом должен стать финальный.
                    return jsonList(response, List.of(
                            step(messageId, "msg_step1", 1L, 2L, "tool-calls",
                                    List.of(part("step-start"), part("reasoning"), part("tool"),
                                            part("step-finish"))),
                            step(messageId, "msg_step2", 3L, 4L, "stop",
                                    List.of(part("text", "финальный ответ")))));
                }
                listCalls++;
                boolean complete = !twoPhase || listCalls > 1;
                String text = complete ? "Анализ завершён" : "Анал";
                return jsonList(response, List.of(assistant(messageId, text, complete)));
            }

            return response;
        }

        @Override
        public String getName() {
            return "sidecar-transformer";
        }

        private static Map<String, Object> assistant(String parentId, String text, boolean complete) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("id", "msg_assist");
            info.put("role", "assistant");
            Map<String, Object> time = new LinkedHashMap<>();
            time.put("created", 1L);
            if (complete) {
                time.put("completed", 2L);
            }
            info.put("time", time);
            info.put("parentID", parentId);
            if (complete) {
                info.put("finish", "stop");
            }

            Map<String, Object> part = new LinkedHashMap<>();
            part.put("type", "text");
            part.put("text", text);

            Map<String, Object> env = new LinkedHashMap<>();
            env.put("info", info);
            env.put("parts", List.of(part));
            return env;
        }

        private static Map<String, Object> step(String parentId, String id, long created, long completed,
                                                String finish, List<Map<String, Object>> parts) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("id", id);
            info.put("role", "assistant");
            Map<String, Object> time = new LinkedHashMap<>();
            time.put("created", created);
            time.put("completed", completed);
            info.put("time", time);
            info.put("parentID", parentId);
            info.put("finish", finish);

            Map<String, Object> env = new LinkedHashMap<>();
            env.put("info", info);
            env.put("parts", parts);
            return env;
        }

        private static Map<String, Object> part(String type) {
            return part(type, null);
        }

        private static Map<String, Object> part(String type, String text) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("type", type);
            if (text != null) {
                p.put("text", text);
            }
            return p;
        }

        private static Response jsonList(Response original, List<Map<String, Object>> envs) {
            try {
                return Response.Builder.like(original).body(mapper.writeValueAsString(envs)).build();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
