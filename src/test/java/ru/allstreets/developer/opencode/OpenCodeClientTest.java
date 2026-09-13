package ru.allstreets.developer.opencode;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import ru.allstreets.developer.PostgresTestBase;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

/**
 * Интеграционный тест {@link OpenCodeClient}: полный цикл «health gate → прогон →
 * цикл опроса» против стабированного WireMock-сервера и реального PostgreSQL
 * (Testcontainers). Проверяет ключевые свойства целевой архитектуры:
 * <ul>
 *   <li>Завершение агента детектируется опросом и персистится как DONE;</li>
 *   <li>Resume после рестарта продолжает опрос, не отправляя промпт заново;</li>
 *   <li>Недоступный sidecar → быстрый error без отправки промпта;</li>
 *   <li>Истечение бюджета → abort + статус ABORTED.</li>
 * </ul>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@WireMockTest
class OpenCodeClientTest extends PostgresTestBase {

    private static final String HEALTHY = "{\"healthy\":true,\"version\":\"1.15.5\"}";
    private static final String COMPLETED = """
            {"info":{"id":"msg_1","role":"assistant",
              "time":{"created":1694000000000,"completed":1694000060000},
              "error":null,"finish":"stop"},
             "parts":[{"type":"text","text":"Анализ завершён"}]}
            """;
    private static final String IN_PROGRESS = """
            {"info":{"id":"msg_1","role":"assistant","time":{"created":1694000000000},"error":null},
             "parts":[{"type":"text","text":"Анализ"}]}
            """;

    @Autowired
    private OpenCodeRunRepository runRepo;

    private OpenCodeApi api;
    private TaskProgressRegistry progress;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wm) {
        this.api = new OpenCodeApi(wm.getHttpBaseUrl(), "deepseek/deepseek-v4-pro");
        this.progress = Mockito.mock(TaskProgressRegistry.class);
    }

    private OpenCodeClient client() {
        return new OpenCodeClient(api, runRepo, progress, 300, 1);
    }

    @SuppressWarnings("SameParameterValue")
    private OpenCodeClient client(int timeoutSeconds) {
        return new OpenCodeClient(api, runRepo, progress, timeoutSeconds, 1);
    }

    @Test
    void runAgent_completesAndPersistsDone() {
        WireMock.stubFor(get(urlPathEqualTo("/global/health")).willReturn(okJson(HEALTHY)));
        WireMock.stubFor(post(urlPathEqualTo("/session")).willReturn(okJson("{\"id\":\"ses_1\"}")));
        WireMock.stubFor(post(urlPathEqualTo("/session/ses_1/prompt_async"))
                .willReturn(aResponse().withStatus(204)));
        WireMock.stubFor(get(urlPathMatching("/session/ses_1/message/[^/]+")).willReturn(okJson(COMPLETED)));

        var result = client().runAgent("analyst", "промпт", "/work/slot-0", "task-1");

        assertEquals("success", result.status());
        assertEquals("Анализ завершён", result.output());
        assertEquals("ses_1", result.sessionId());

        var runs = runRepo.findByTaskIdAndAgentNameAndStatusInOrderByStartedAtDesc(
                "task-1", "analyst", List.of(OpenCodeRunStatus.DONE));
        assertEquals(1, runs.size());
        assertEquals("Анализ завершён", runs.getFirst().getOutput());
        assertEquals("ses_1", runs.getFirst().getSessionId());
        assertTrue(runs.getFirst().isPromptSent());

        verify(progress).start("task-1", "analyst");
        verify(progress).recordText("task-1", "Анализ завершён");
        verify(progress).markFinished("task-1");
    }

    @Test
    void runAgent_recordsDeltaAcrossPolls() {
        WireMock.stubFor(get(urlPathEqualTo("/global/health")).willReturn(okJson(HEALTHY)));
        WireMock.stubFor(post(urlPathEqualTo("/session")).willReturn(okJson("{\"id\":\"ses_1\"}")));
        WireMock.stubFor(post(urlPathEqualTo("/session/ses_1/prompt_async"))
                .willReturn(aResponse().withStatus(204)));
        WireMock.stubFor(get(urlPathMatching("/session/ses_1/message/[^/]+"))
                .inScenario("progress")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson(IN_PROGRESS))
                .willSetStateTo("done"));
        WireMock.stubFor(get(urlPathMatching("/session/ses_1/message/[^/]+"))
                .inScenario("progress")
                .whenScenarioStateIs("done")
                .willReturn(okJson(COMPLETED)));

        var result = client().runAgent("analyst", "промпт", "/work/slot-0", "task-1");

        assertEquals("success", result.status());
        // Текст накапливается (как в реальном OpenCode): первый опрос — "Анализ",
        // второй — полный текст; delta = остаток.
        verify(progress).recordText("task-1", "Анализ");
        verify(progress).recordText("task-1", "Анализ завершён".substring("Анализ".length()));
    }

    @Test
    void runAgent_resumesExistingRun_withoutResendingPrompt() {
        var existing = new OpenCodeRunEntity("task-1", "analyst", "ses_existing", "msg_existing",
                "/work/slot-0", OpenCodeRunStatus.RUNNING);
        existing.setPromptSent(true);
        runRepo.saveAndFlush(existing);

        WireMock.stubFor(get(urlPathEqualTo("/global/health")).willReturn(okJson(HEALTHY)));
        WireMock.stubFor(get(urlPathMatching("/session/ses_existing/message/[^/]+"))
                .willReturn(okJson(COMPLETED)));

        var result = client().runAgent("analyst", "промпт", "/work/slot-0", "task-1");

        assertEquals("success", result.status());
        assertEquals("ses_existing", result.sessionId());
        // Ни создания сессии, ни повторной отправки промпта.
        WireMock.verify(0, postRequestedFor(urlPathEqualTo("/session")));
        WireMock.verify(0, postRequestedFor(urlPathMatching("/session/.*/prompt_async")));
    }

    @Test
    void runAgent_unhealthySidecar_returnsErrorWithoutPrompt() {
        WireMock.stubFor(get(urlPathEqualTo("/global/health"))
                .willReturn(okJson("{\"healthy\":false,\"version\":\"1.15.5\"}")));

        var result = client().runAgent("analyst", "промпт", "/work/slot-0", "task-1");

        assertEquals("error", result.status());
        assertTrue(result.error().contains("unhealthy"));
        WireMock.verify(0, postRequestedFor(urlPathEqualTo("/session")));
        WireMock.verify(0, postRequestedFor(urlPathMatching("/session/.*/prompt_async")));
    }

    @Test
    void runAgent_timeout_abortsAndPersistsAborted() {
        WireMock.stubFor(get(urlPathEqualTo("/global/health")).willReturn(okJson(HEALTHY)));
        WireMock.stubFor(post(urlPathEqualTo("/session")).willReturn(okJson("{\"id\":\"ses_1\"}")));
        WireMock.stubFor(post(urlPathEqualTo("/session/ses_1/prompt_async"))
                .willReturn(aResponse().withStatus(204)));
        // Сообщение никогда не появляется (404) → бюджет истекает.
        WireMock.stubFor(get(urlPathMatching("/session/ses_1/message/[^/]+"))
                .willReturn(aResponse().withStatus(404).withBody("{}")));

        var result = client(2).runAgent("analyst", "промпт", "/work/slot-0", "task-1");

        assertEquals("error", result.status());
        assertTrue(result.error().contains("Таймаут"));
        WireMock.verify(postRequestedFor(urlPathEqualTo("/session/ses_1/abort")));

        var aborted = runRepo.findByTaskIdAndAgentNameAndStatusInOrderByStartedAtDesc(
                "task-1", "analyst", List.of(OpenCodeRunStatus.ABORTED));
        assertEquals(1, aborted.size());
    }
}
