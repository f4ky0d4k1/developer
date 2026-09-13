package ru.allstreets.developer.agents;

import io.github.asekka.springai.agents.core.AgentContext;
import io.github.asekka.springai.agents.core.AgentResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.allstreets.developer.checkpoint.TaskRepository;
import ru.allstreets.developer.humanloop.HumanLoopService;
import ru.allstreets.developer.opencode.OpenCodeClient;
import ru.allstreets.developer.opencode.OpenCodeSessionPool;
import ru.allstreets.developer.state.TaskState;
import ru.allstreets.developer.telegram.TelegramGateway;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Ответ аналитика без валидного JSON-решения ({@code nextStep}) — не «готово», а сигнал нуджить
 * агента в той же сессии. Решение разбирается детерминированно (Jackson), без второго LLM.
 */
class AnalystNodeDecisionGuardTest {

    private static final String INTRO = "Начну с анализа задачи. Это задача типа \"task\". Сначала изучу конфигурацию.";

    private static final String DECISION =
            "{\"nextStep\":\"developer\",\"requiresDevelopment\":true,\"requiresTesting\":true,"
                    + "\"spec\":\"спека\",\"userStory\":\"user story\",\"acceptanceCriteria\":[\"критерий\"]}";

    private OpenCodeClient openCode;
    private HumanLoopService humanLoop;
    private AnalystNode analyst;

    @BeforeEach
    void setUp() {
        openCode = mock(OpenCodeClient.class);
        OpenCodeSessionPool sessionPool = mock(OpenCodeSessionPool.class);
        humanLoop = mock(HumanLoopService.class);

        analyst = new AnalystNode(openCode, sessionPool, mock(TelegramGateway.class),
                humanLoop, mock(TaskRepository.class), 3);

        when(sessionPool.acquire(600L)).thenReturn(0);
        when(sessionPool.getSlotWorkDir(0)).thenReturn("/work");
    }

    private AgentContext ctx() {
        return AgentContext.of("починить баг в логине")
                .with(TaskState.TASK_ID, "task-123")
                .with(TaskState.TG_CHAT_ID, "12345")
                .with(TaskState.TARGET_REPO, "owner/repo");
    }

    private OpenCodeClient.OpenCodeResult result(String out) {
        return new OpenCodeClient.OpenCodeResult("success", out, null, null, List.of(), null, "ses_1");
    }

    private void agentReturns(String out) {
        when(openCode.runAgent(anyString(), anyString(), anyString(), anyString())).thenReturn(result(out));
    }

    private void nudgeReturns(String out) {
        when(openCode.runAgent(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(result(out));
    }

    @Test
    void degenerateOutput_nudgeAlsoWithoutDecision_fails() {
        agentReturns(INTRO);
        nudgeReturns(INTRO);

        AgentResult result = analyst.execute(ctx());

        assertTrue(result.hasError(), "пустой ответ + безуспешный нудж должны завершаться ошибкой");
        assertFalse(result.completed());
        verify(openCode).runAgent(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void degenerateOutput_nudgedToDecision_completes() {
        agentReturns(INTRO);
        nudgeReturns(DECISION);

        AgentResult result = analyst.execute(ctx());

        assertFalse(result.hasError());
        assertTrue(result.completed());
        assertEquals("developer", result.stateUpdates().get(TaskState.NEXT_STEP));
        verify(openCode).runAgent(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void emptyOutput_nudgedToDecision_completes() {
        agentReturns("");
        nudgeReturns(DECISION);

        AgentResult result = analyst.execute(ctx());

        assertFalse(result.hasError(), "нудж должен вытащить решение из пустого ответа");
        assertTrue(result.completed());
        assertEquals("developer", result.stateUpdates().get(TaskState.NEXT_STEP));
        verify(openCode).runAgent(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void emptyOutput_nudgeAlsoEmpty_fails() {
        agentReturns("");
        nudgeReturns("");

        AgentResult result = analyst.execute(ctx());

        assertTrue(result.hasError(), "пустой ответ + безуспешный нудж — ошибка, а не silent-done");
        assertFalse(result.completed());
        verify(openCode).runAgent(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void needsClarification_takesPriority_overDone() {
        // Дилемма: модель спрашивает текстом и ставит nextStep=done — задача закрывалась без разработки.
        // Вопрос должен иметь приоритет: needsClarification уводит задачу в HITL, nextStep не важен.
        agentReturns("{\"nextStep\":\"done\",\"requiresDevelopment\":true,"
                + "\"needsClarification\":true,\"clarificationQuestion\":\"Создать задачу в Tracker?\"}");

        AgentResult result = analyst.execute(ctx());

        assertFalse(result.hasError());
        assertFalse(result.completed());
        assertTrue(result.isInterrupted(), "вопрос должен уводить задачу в HITL, а не в done");
        assertEquals("HITL_CLARIFICATION", result.interrupt().reason());
        verify(humanLoop).askHuman(eq("task-123"), eq(12345L), eq("Создать задачу в Tracker?"));
    }

    @Test
    void invalidDecisionValue_fails() {
        agentReturns("{\"nextStep\":\"banana\",\"requiresDevelopment\":true}");

        AgentResult result = analyst.execute(ctx());

        assertTrue(result.hasError(), "невалидное значение nextStep должно быть ошибкой, а не молчаливым done");
        verify(openCode, never()).runAgent(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void decisionPresent_andParsed_completes() {
        agentReturns(DECISION);

        AgentResult result = analyst.execute(ctx());

        assertFalse(result.hasError());
        assertTrue(result.completed());
        assertEquals("developer", result.stateUpdates().get(TaskState.NEXT_STEP));
        assertEquals(Boolean.TRUE, result.stateUpdates().get(TaskState.REQUIRES_DEVELOPMENT));
        assertEquals("спека", result.stateUpdates().get(TaskState.SPEC));
    }

    @Test
    void truncatedJson_fails() {
        agentReturns("{\"nextStep\": \"developer\", \"requiresDevelopment\": true");

        AgentResult result = analyst.execute(ctx());

        assertTrue(result.hasError(), "оборванный JSON должен быть ошибкой");
        verify(openCode, never()).runAgent(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void validJsonButNoNextStep_nudgedThenFails() {
        agentReturns("{\"requiresDevelopment\": true, \"spec\": \"спека\"}");
        nudgeReturns("{\"requiresDevelopment\": true, \"spec\": \"спека\"}");

        AgentResult result = analyst.execute(ctx());

        assertTrue(result.hasError(), "JSON без nextStep — не решение");
        verify(openCode).runAgent(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void explicitNextStepNull_fails() {
        agentReturns("{\"nextStep\": null, \"requiresDevelopment\": true}");

        AgentResult result = analyst.execute(ctx());

        assertTrue(result.hasError(), "nextStep=null — не решение");
    }

    @Test
    void fencedJson_completes() {
        agentReturns("```json\n" + DECISION + "\n```");

        AgentResult result = analyst.execute(ctx());

        assertFalse(result.hasError());
        assertEquals("developer", result.stateUpdates().get(TaskState.NEXT_STEP));
    }

    @Test
    void jsonDecisionAfterOtherFences_completes() {
        // Инцидент b9c0e7ae: анализ обёрнут в ```yaml, решение — в финальном ```json.
        // Раньше брался первый фенс (yaml) и Jackson падал → result=null.
        String output = """
                Сначала конфиг:
                ```yaml
                spring:
                  profiles:
                    active: test
                ```
                Ещё один блок:
                ```yaml
                foo: bar
                ```
                И решение:
                ```json
                %s
                ```
                """.formatted(DECISION);

        agentReturns(output);

        AgentResult result = analyst.execute(ctx());

        assertFalse(result.hasError(), "JSON-решение после yaml-фенсов должно парситься");
        assertEquals("developer", result.stateUpdates().get(TaskState.NEXT_STEP));
    }

    @Test
    void unknownFieldsAreIgnored() {
        agentReturns("{\"nextStep\":\"developer\",\"requiresDevelopment\":true,"
                + "\"taskType\":\"task\",\"spec\":\"спека\",\"foo\":123}");

        AgentResult result = analyst.execute(ctx());

        assertFalse(result.hasError());
        assertEquals("developer", result.stateUpdates().get(TaskState.NEXT_STEP));
    }
}
