package ru.allstreets.developer.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Контракт MCP-тулов forum-тем (BACKEND-443, T8).
 * <p>
 * Оркестратор обязан отдавать sidecar-агентам три инструмента:
 * {@code getForumTopics} (список известных системе тем чата — источник истины БД,
 * у Telegram нет метода листинга), {@code getForumTopicInfo} (детали темы задачи) и
 * {@code reassignTaskTopic} (перепривязка задачи/цепочки с согласованным обновлением
 * {@code thread_id}/{@code root_thread_id}). Тест фиксирует наличие класса и
 * зарегистрированных {@link Tool}-методов; поведение — в {@code TelegramTopicServiceTest}.
 */
class TelegramTopicMcpToolsContractTest {

    @Test
    void declaresRequiredForumTopicTools() throws Exception {
        Class<?> tools = Class.forName("ru.allstreets.developer.mcp.TelegramTopicMcpTools");

        Set<String> toolMethods = Arrays.stream(tools.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Tool.class))
                .map(Method::getName)
                .collect(Collectors.toSet());

        assertTrue(toolMethods.containsAll(Set.of("getForumTopics", "getForumTopicInfo", "reassignTaskTopic")),
                "не найдены MCP-тулы forum-тем: " + toolMethods);
    }

    @Test
    void reassignTool_acceptsTaskIdAndTopic() throws Exception {
        Class<?> tools = Class.forName("ru.allstreets.developer.mcp.TelegramTopicMcpTools");
        Method reassign = Arrays.stream(tools.getDeclaredMethods())
                .filter(m -> m.getName().equals("reassignTaskTopic"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("нет метода reassignTaskTopic"));

        assertTrue(reassign.getParameterCount() >= 2,
                "reassignTaskTopic должен принимать taskId и новый thread_id");
    }
}
