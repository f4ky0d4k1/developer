package ru.allstreets.developer.opencode;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Тест низкоуровневого HTTP-клиента {@link OpenCodeApi} против стабированного
 * WireMock-сервера. Проверяет форму запросов (включая body {@code prompt_async},
 * неверная форма которого была причиной 500) и защитный парсинг ответов
 * (records, различие успех/ошибка, 404 → null, 500 → типизированная ошибка с ref).
 */
@WireMockTest
class OpenCodeApiTest {

    private OpenCodeApi api(WireMockRuntimeInfo wm) {
        return new OpenCodeApi(wm.getHttpBaseUrl(), "deepseek/deepseek-v4-pro");
    }

    @Test
    void health_returnsHealthyAndVersion(WireMockRuntimeInfo wm) {
        WireMock.stubFor(get("/global/health")
                .willReturn(okJson("{\"healthy\":true,\"version\":\"1.15.5\"}")));

        var h = api(wm).health();

        assertTrue(h.healthy());
        assertEquals("1.15.5", h.version());
    }

    @Test
    void health_unhealthy(WireMockRuntimeInfo wm) {
        WireMock.stubFor(get("/global/health")
                .willReturn(okJson("{\"healthy\":false}")));

        assertFalse(api(wm).health().healthy());
    }

    @Test
    void createSession_returnsIdAndPassesDirectory(WireMockRuntimeInfo wm) {
        WireMock.stubFor(post(urlPathEqualTo("/session"))
                .withQueryParam("directory", equalTo("/work/slot-0"))
                .willReturn(okJson("{\"id\":\"ses_abc123\"}")));

        String id = api(wm).createSession("/work/slot-0", "analyst-title");

        assertEquals("ses_abc123", id);
    }

    @Test
    void promptAsync_sends204_andShapesBody(WireMockRuntimeInfo wm) {
        WireMock.stubFor(post(urlPathEqualTo("/session/ses_1/prompt_async"))
                .willReturn(aResponse().withStatus(204)));

        api(wm).promptAsync("ses_1", "/work", "msg-1", "analyst", "привет");

        WireMock.verify(postRequestedFor(urlPathEqualTo("/session/ses_1/prompt_async"))
                .withRequestBody(matchingJsonPath("$.messageID", equalTo("msg-1")))
                .withRequestBody(matchingJsonPath("$.agent", equalTo("analyst")))
                .withRequestBody(matchingJsonPath("$.model.providerID", equalTo("deepseek")))
                .withRequestBody(matchingJsonPath("$.model.modelID", equalTo("deepseek-v4-pro")))
                .withRequestBody(matchingJsonPath("$.parts[0].type", equalTo("text")))
                .withRequestBody(matchingJsonPath("$.parts[0].text", equalTo("привет"))));
    }

    @Test
    void getMessage_parsesCompletedAssistant(WireMockRuntimeInfo wm) {
        WireMock.stubFor(get(urlPathEqualTo("/session/ses_1/message/msg_1"))
                .willReturn(okJson("""
                        {"info":{"id":"msg_1","role":"assistant",
                          "time":{"created":1694000000000,"completed":1694000060000},
                          "error":null,"finish":"stop"},
                         "parts":[{"type":"text","text":"Анализ завершён"},{"type":"step-finish"}]}
                        """)));

        var env = api(wm).getMessage("ses_1", "/work", "msg_1");

        assertTrue(env.isAssistant());
        assertTrue(env.isCompleted());
        assertEquals("Анализ завершён", env.text());
    }

    @Test
    void getMessage_404_returnsNull(WireMockRuntimeInfo wm) {
        WireMock.stubFor(get(urlPathEqualTo("/session/ses_1/message/msg_missing"))
                .willReturn(aResponse().withStatus(404).withBody("{}")));

        assertNull(api(wm).getMessage("ses_1", "/work", "msg_missing"));
    }

    @Test
    void getMessage_500_throwsTypedErrorWithRef(WireMockRuntimeInfo wm) {
        WireMock.stubFor(get(urlPathEqualTo("/session/ses_1/message/msg_1"))
                .willReturn(aResponse().withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"name\":\"UnknownError\",\"data\":{\"message\":\"Unexpected server error\",\"ref\":\"err_12345\"}}")));

        var ex = assertThrows(OpenCodeApi.OpenCodeApiException.class,
                () -> api(wm).getMessage("ses_1", "/work", "msg_1"));

        assertEquals(500, ex.status());
        assertEquals("err_12345", ex.errorRef());
        assertEquals("UnknownError", ex.error().name());
    }

    @Test
    void getMessage_notAssistant_isNotCompleted(WireMockRuntimeInfo wm) {
        // user-сообщение (наш промпт) — не ответ агента, completed отсутствует.
        WireMock.stubFor(get(urlPathEqualTo("/session/ses_1/message/msg_1"))
                .willReturn(okJson("""
                        {"info":{"id":"msg_1","role":"user","time":{"created":1694000000000}},
                         "parts":[{"type":"text","text":"промпт"}]}
                        """)));

        var env = api(wm).getMessage("ses_1", "/work", "msg_1");

        assertFalse(env.isAssistant());
        assertFalse(env.isCompleted());
    }

    @Test
    void abort_returnsTrue(WireMockRuntimeInfo wm) {
        WireMock.stubFor(post(urlPathEqualTo("/session/ses_1/abort"))
                .willReturn(okJson("true")));

        assertTrue(api(wm).abort("ses_1", "/work"));
    }
}
