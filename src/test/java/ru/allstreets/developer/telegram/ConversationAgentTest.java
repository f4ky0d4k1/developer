package ru.allstreets.developer.telegram;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import ru.allstreets.developer.agents.AgentResponses;
import ru.allstreets.developer.agents.StructuredOutputHelper;
import ru.allstreets.developer.checkpoint.TaskEntity;
import ru.allstreets.developer.humanloop.HumanInputRegistry;
import ru.allstreets.developer.mcp.TaskMcpTools;

import java.util.ArrayList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Классификатор: маппинг decision и ограничение контекста по задачам чата.
 * Логики запуска здесь нет — запуск вынесен в инструмент {@code launch_task}.
 */
class ConversationAgentTest {

    @Test
    void processMessage_mapsDecisionFields() {
        ChatClient fast = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec response = mock(ChatClient.CallResponseSpec.class);
        when(fast.prompt()).thenReturn(request);
        when(request.user(anyString())).thenReturn(request);
        when(request.toolContext(anyMap())).thenReturn(request);
        when(request.call()).thenReturn(response);
        when(response.entity(AgentResponses.FastDecision.class)).thenReturn(
                new AgentResponses.FastDecision(AgentResponses.FastAction.STATUS, "abc12345", "статус", null));

        var agent = new ConversationAgent(fast, mock(ChatClient.class),
                chatMemoryMock(), taskRegistryMock(Page.empty()),
                humanInputRegistryMock(), mock(StructuredOutputHelper.class), new DefaultResourceLoader(),
                mock(TaskMcpTools.class));

        var decision = agent.processMessage(1L, "user", "статус?");

        assertEquals(AgentResponses.FastAction.STATUS, decision.action());
        assertEquals("abc12345", decision.taskId());
        assertEquals("статус", decision.text());
    }

    @Test
    void processMessage_boundsTaskContextToPage() {
        // В чате 50 задач, но в контекст идёт только страница (10) + пометка «+40 ещё».
        var tasks = new ArrayList<TaskEntity>();
        for (int i = 0; i < 10; i++) {
            tasks.add(new TaskEntity("t" + i, "RUNNING", "desc", "title " + i, 1L));
        }
        Page<TaskEntity> page = new PageImpl<>(tasks, PageRequest.of(0, 10), 50);

        ChatClient fast = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec response = mock(ChatClient.CallResponseSpec.class);
        when(fast.prompt()).thenReturn(request);
        when(request.user(anyString())).thenReturn(request);
        when(request.toolContext(anyMap())).thenReturn(request);
        when(request.call()).thenReturn(response);
        when(response.entity(AgentResponses.FastDecision.class)).thenReturn(
                new AgentResponses.FastDecision(AgentResponses.FastAction.ANSWER, null, "ok", null));

        var agent = new ConversationAgent(fast, mock(ChatClient.class),
                chatMemoryMock(), taskRegistryMock(page),
                humanInputRegistryMock(), mock(StructuredOutputHelper.class), new DefaultResourceLoader(),
                mock(TaskMcpTools.class));

        agent.processMessage(1L, "user", "статус");

        var captor = ArgumentCaptor.forClass(String.class);
        verify(request).user(captor.capture());
        String prompt = captor.getValue();

        assertTrue(prompt.contains("title 9"), "первая страница задач в контексте: " + prompt);
        assertTrue(prompt.contains("(+40 more"), "должно быть указано, что есть ещё задачи: " + prompt);
        assertFalse(prompt.contains("title 10"), "задачи за пределами страницы не должны попадать: " + prompt);
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
