package ru.allstreets.developer.telegram;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import ru.allstreets.developer.agents.AgentResponses;
import ru.allstreets.developer.checkpoint.TaskEntity;
import ru.allstreets.developer.humanloop.HumanInputRegistry;
import ru.allstreets.developer.mcp.TaskMcpTools;

import java.util.ArrayList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Оркестратор чата: маппинг decision и ограничение контекста по задачам чата.
 * Логики запуска здесь нет — запуск вынесен в инструмент {@code launch_task}.
 * Ответ берётся через {@code .content()} (JSON), а не {@code .entity()} — structured output
 * несовместим с tool calling (spring-ai #4799, #6327).
 */
class ConversationAgentTest {

    private record Mocks(ChatClient client, ChatClient.ChatClientRequestSpec request) {
    }

    private static Mocks orchestratorClientReturning(String json) {
        ChatClient orchestrator = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec response = mock(ChatClient.CallResponseSpec.class);
        when(orchestrator.prompt()).thenReturn(request);
        when(request.user(anyString())).thenReturn(request);
        when(request.toolContext(anyMap())).thenReturn(request);
        when(request.call()).thenReturn(response);
        when(response.content()).thenReturn(json);
        return new Mocks(orchestrator, request);
    }

    @Test
    void processMessage_mapsDecisionFields() {
        Mocks m = orchestratorClientReturning(
                "{\"action\":\"STATUS\",\"taskId\":\"abc12345\",\"text\":\"статус\",\"description\":null,\"options\":null}");

        var agent = new ConversationAgent(m.client(), mock(ChatClient.class),
                chatMemoryMock(), taskRegistryMock(Page.empty()),
                humanInputRegistryMock(), mock(TaskMcpTools.class));

        var decision = agent.processMessage(1L, "user", "статус?");

        assertEquals(AgentResponses.OrchestratorAction.STATUS, decision.action());
        assertEquals("abc12345", decision.taskId());
        assertEquals("статус", decision.text());
    }

    @Test
    void processMessage_mapsOptions() {
        Mocks m = orchestratorClientReturning(
                "{\"action\":\"ANSWER\",\"taskId\":null,\"text\":\"в каком репо?\",\"description\":null,\"options\":[\"owner/a\",\"owner/b\"]}");

        var agent = new ConversationAgent(m.client(), mock(ChatClient.class),
                chatMemoryMock(), taskRegistryMock(Page.empty()),
                humanInputRegistryMock(), mock(TaskMcpTools.class));

        var decision = agent.processMessage(1L, "user", "проверь тикет");

        assertEquals(java.util.List.of("owner/a", "owner/b"), decision.options());
    }

    @Test
    void processMessage_parsesUnescapedNewlinesInText() {
        // Модель отдала «JSON» с реальным переносом строки внутри text (не \\n, а 0x0A) —
        // строгий парсер это отвергает. Должны извлечь text, а не вернуть сырой объект.
        String json = "{\"action\":\"ANSWER\",\"taskId\":null,\"text\":\"строка с\nпереносом\",\"description\":null,\"options\":null}";
        Mocks m = orchestratorClientReturning(json);

        var agent = new ConversationAgent(m.client(), mock(ChatClient.class),
                chatMemoryMock(), taskRegistryMock(Page.empty()),
                humanInputRegistryMock(), mock(TaskMcpTools.class));

        var decision = agent.processMessage(1L, "user", "привет");

        assertEquals(AgentResponses.OrchestratorAction.ANSWER, decision.action());
        assertEquals("строка с\nпереносом", decision.text(),
                "text должен быть извлечён из JSON с неэкранированным переносом");
    }

    @Test
    void processMessage_doesNotLeakUnparseableJsonToChat() {
        // Модель вернула битый «JSON» (начинается с '{', не парсится). Он НЕ должен уйти в чат
        // как ANSWER — вместо этого повторяем через фолбэк-модель.
        Mocks m = orchestratorClientReturning("{\"action\":\"ANSWER\",\"text\":\"oops");
        Mocks fallback = orchestratorClientReturning(
                "{\"action\":\"ANSWER\",\"taskId\":null,\"text\":\"извините, ошибка\",\"description\":null,\"options\":null}");

        var agent = new ConversationAgent(m.client(), fallback.client(),
                chatMemoryMock(), taskRegistryMock(Page.empty()),
                humanInputRegistryMock(), mock(TaskMcpTools.class));

        var decision = agent.processMessage(1L, "user", "привет");

        assertEquals(AgentResponses.OrchestratorAction.ANSWER, decision.action());
        assertEquals("извините, ошибка", decision.text(), "сырой битый JSON не должен попасть в чат");
    }

    @Test
    void processMessage_boundsTaskContextToPage() {
        // В чате 50 задач, но в контекст идёт только страница (10) + пометка «+40 ещё».
        var tasks = new ArrayList<TaskEntity>();
        for (int i = 0; i < 10; i++) {
            tasks.add(new TaskEntity("t" + i, "RUNNING", "desc", "title " + i, 1L));
        }
        Page<TaskEntity> page = new PageImpl<>(tasks, PageRequest.of(0, 10), 50);

        Mocks m = orchestratorClientReturning(
                "{\"action\":\"ANSWER\",\"taskId\":null,\"text\":\"ok\",\"description\":null,\"options\":null}");

        var agent = new ConversationAgent(m.client(), mock(ChatClient.class),
                chatMemoryMock(), taskRegistryMock(page),
                humanInputRegistryMock(), mock(TaskMcpTools.class));

        agent.processMessage(1L, "user", "статус");

        var captor = ArgumentCaptor.forClass(String.class);
        verify(m.request()).user(captor.capture());
        String prompt = captor.getValue();

        assertTrue(prompt.contains("title 9"), "первая страница задач в контексте: " + prompt);
        assertTrue(prompt.contains("(+40 more"), "должно быть указано, что есть ещё задачи: " + prompt);
        assertFalse(prompt.contains("title 10"), "задачи за пределами страницы не должны попадать: " + prompt);
    }

    @Test
    @SuppressWarnings("unchecked")
    void processMessage_passesChatIdInToolContext() {
        Mocks m = orchestratorClientReturning(
                "{\"action\":\"ANSWER\",\"taskId\":null,\"text\":\"ok\",\"description\":null,\"options\":null}");

        var agent = new ConversationAgent(m.client(), mock(ChatClient.class),
                chatMemoryMock(), taskRegistryMock(Page.empty()),
                humanInputRegistryMock(), mock(TaskMcpTools.class));

        agent.processMessage(77L, "DiMa", "привет");

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(m.request()).toolContext(captor.capture());
        assertEquals(77L, ((Number) captor.getValue().get("chatId")).longValue(),
                "ConversationAgent должен прокидывать chatId в ToolContext для cross-chat проверок");
        assertEquals("dima", captor.getValue().get("username"));
    }

    private static ChatMemoryService chatMemoryMock() {
        ChatMemoryService chatMemory = mock(ChatMemoryService.class);
        when(chatMemory.getHistoryText(anyLong())).thenReturn("");
        return chatMemory;
    }

    private static ActiveTaskRegistry taskRegistryMock(Page<TaskEntity> page) {
        ActiveTaskRegistry taskRegistry = mock(ActiveTaskRegistry.class);
        when(taskRegistry.getChatTasksPage(anyLong(), anyInt(), anyInt())).thenReturn(page);
        return taskRegistry;
    }

    private static HumanInputRegistry humanInputRegistryMock() {
        HumanInputRegistry humanInputRegistry = mock(HumanInputRegistry.class);
        when(humanInputRegistry.getPendingQuestionsForChat(anyLong())).thenReturn(Map.of());
        return humanInputRegistry;
    }
}
