package ru.allstreets.developer.agents;

import io.github.asekka.springai.agents.core.AgentContext;
import io.github.asekka.springai.agents.core.AgentResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import ru.allstreets.developer.checkpoint.TaskRepository;
import ru.allstreets.developer.humanloop.HumanLoopService;
import ru.allstreets.developer.opencode.OpenCodeClient;
import ru.allstreets.developer.opencode.OpenCodeSessionPool;
import ru.allstreets.developer.state.TaskState;
import ru.allstreets.developer.telegram.TelegramGateway;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Fallback-сценарий узла аналитика: когда OpenCode-агент вернул текст без валидного JSON,
 * а structured-output разбор (primary + fallback LLM) тоже не справился — аналитик
 * использует сырой текст как спеку и завершает граф с {@code nextStep=done}, не падая.
 */
class AnalystNodeFallbackTest {

    private OpenCodeClient openCode;
    private OpenCodeSessionPool sessionPool;
    private StructuredOutputHelper structuredOutput;
    private TaskRepository taskRepo;
    private AnalystNode analyst;

    @BeforeEach
    void setUp() {
        openCode = mock(OpenCodeClient.class);
        sessionPool = mock(OpenCodeSessionPool.class);
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient fallbackChatClient = mock(ChatClient.class);
        TelegramGateway telegram = mock(TelegramGateway.class);
        HumanLoopService humanLoop = mock(HumanLoopService.class);
        structuredOutput = mock(StructuredOutputHelper.class);
        taskRepo = mock(TaskRepository.class);

        analyst = new AnalystNode(openCode, sessionPool, chatClient, fallbackChatClient,
                telegram, humanLoop, structuredOutput, taskRepo, 3);
    }

    @Test
    void invalidJsonOutput_fallsBackToRawTextAsSpec() {
        AgentContext ctx = AgentContext.of("починить баг в логине")
                .with(TaskState.TASK_ID, "task-123")
                .with(TaskState.TG_CHAT_ID, "12345")
                .with(TaskState.TARGET_REPO, "owner/repo");

        when(sessionPool.acquire(600L)).thenReturn(0);
        when(sessionPool.getSlotWorkDir(0)).thenReturn("/work");

        var raw = new OpenCodeClient.OpenCodeResult(
                "success", "извините, я не смог сформировать JSON-спеку", null, null, List.of(), null, "ses_1");
        when(openCode.runAgent(anyString(), anyString(), anyString(), anyString())).thenReturn(raw);

        // LLM-разбор (и основной, и fallback) не смог извлечь structured output из текста.
        when(structuredOutput.callWithFallback(any(), any(), anyString(), eq(AgentResponses.AnalystResult.class)))
                .thenReturn(null);
        when(taskRepo.findById("task-123")).thenReturn(Optional.empty());

        AgentResult result = analyst.execute(ctx);

        // Спекой становится сырой текст агента.
        assertEquals("извините, я не смог сформировать JSON-спеку", result.text());
        assertTrue(result.completed());
        assertEquals("done", result.stateUpdates().get(TaskState.NEXT_STEP));
        assertEquals(Boolean.FALSE, result.stateUpdates().get(TaskState.REQUIRES_DEVELOPMENT));
        assertEquals(Boolean.FALSE, result.stateUpdates().get(TaskState.REQUIRES_TESTING));
        assertEquals("извините, я не смог сформировать JSON-спеку", result.stateUpdates().get(TaskState.SPEC));
    }
}
