package ru.allstreets.developer.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.allstreets.developer.opencode.OpenCodeApi;

/**
 * Системный инструмент оркестратора: текущая версия системы и её возможности.
 * Данные, а не логика — отвечает на «какая версия / что ты умеешь».
 */
@Component
public class SystemMcpTools {

    private static final Logger log = LoggerFactory.getLogger(SystemMcpTools.class);

    private static final String CAPABILITIES = """
            - Разработка кода (фичи, багфикс, рефакторинг) → PR в GitHub
            - Анализ проекта (аудит, ревью, поиск проблем)
            - Написание и запуск тестов
            - Задачи в Yandex Tracker (создание/поиск/обновление)
            - Grafana: логи (Loki), метрики (Prometheus), алерты, трейсы (Tempo)
            - PostgreSQL: SQL-запросы (readonly/full) через MCP
            """;

    private final String appName;
    private final String appVersion;
    private final OpenCodeApi openCodeApi;

    public SystemMcpTools(
            @Value("${info.app.name:developer}") String appName,
            @Value("${info.app.version:unknown}") String appVersion,
            OpenCodeApi openCodeApi) {
        this.appName = appName;
        this.appVersion = appVersion;
        this.openCodeApi = openCodeApi;
    }

    @Tool(description = "Get the current version of the system (application and OpenCode sidecar) and the list of " +
            "its capabilities. Use when the user asks about the version, build, or what the system can do.")
    public String getSystemInfo() {
        return "system: " + appName + "\n"
                + "version: " + appVersion + "\n"
                + "opencode: " + openCodeVersion() + "\n"
                + "\ncapabilities:\n" + CAPABILITIES;
    }

    private String openCodeVersion() {
        try {
            return openCodeApi.health().version();
        } catch (Exception e) {
            log.debug("getSystemInfo: версия OpenCode недоступна: {}", e.getMessage());
            return "unavailable";
        }
    }
}
