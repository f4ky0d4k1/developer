package ru.allstreets.developer.mcp;

import org.junit.jupiter.api.Test;
import ru.allstreets.developer.opencode.OpenCodeApi;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link SystemMcpTools#getSystemInfo} отдаёт оркестратору версию приложения, версию
 * OpenCode-сайдкара и список возможностей (деградирует, если сайдкар недоступен).
 */
class SystemMcpToolsTest {

    @Test
    void getSystemInfo_includesVersionsAndCapabilities() {
        OpenCodeApi api = mock(OpenCodeApi.class);
        when(api.health()).thenReturn(new OpenCodeApi.HealthInfo(true, "1.18.18"));
        var tools = new SystemMcpTools("developer", "0.0.1-SNAPSHOT", api);

        String info = tools.getSystemInfo();

        assertTrue(info.contains("version: 0.0.1-SNAPSHOT"), info);
        assertTrue(info.contains("opencode: 1.18.18"), info);
        assertTrue(info.contains("capabilities:"), info);
    }

    @Test
    void getSystemInfo_degradesWhenSidecarUnavailable() {
        OpenCodeApi api = mock(OpenCodeApi.class);
        when(api.health()).thenThrow(new RuntimeException("sidecar down"));
        var tools = new SystemMcpTools("developer", "1.0", api);

        String info = tools.getSystemInfo();

        assertTrue(info.contains("opencode: unavailable"), info);
        assertTrue(info.contains("version: 1.0"), info);
    }
}
