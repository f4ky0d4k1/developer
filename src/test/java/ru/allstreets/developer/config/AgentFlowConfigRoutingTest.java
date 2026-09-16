package ru.allstreets.developer.config;

import io.github.asekka.springai.agents.core.AgentContext;
import io.github.asekka.springai.agents.core.AgentError;
import io.github.asekka.springai.agents.core.AgentResult;
import org.junit.jupiter.api.Test;
import ru.allstreets.developer.state.TaskState;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Роутинг аналитика — детерминированно по флагам задачи, а не по {@code nextStep}: если
 * требуются тесты, граф идёт в {@code tester} (TDD) раньше {@code developer}, даже когда
 * аналитик ошибочно поставил {@code nextStep=developer} (инцидент ec0a2004: developer
 * запускался первым, tester — только по guard'у пост-валидации).
 */
class AgentFlowConfigRoutingTest {

    private AgentContext ctx(Boolean requiresDev, Boolean requiresTest, String nextStep) {
        AgentContext c = AgentContext.of("задача")
                .with(TaskState.TASK_ID, "task-1")
                .with(TaskState.TG_CHAT_ID, "1");
        if (requiresDev != null) c = c.with(TaskState.REQUIRES_DEVELOPMENT, requiresDev);
        if (requiresTest != null) c = c.with(TaskState.REQUIRES_TESTING, requiresTest);
        if (nextStep != null) c = c.with(TaskState.NEXT_STEP, nextStep);
        return c;
    }

    private static AgentResult ok() {
        return AgentResult.builder().text("ok").completed(true).build();
    }

    @Test
    void developerNextStepButTestsRequired_stillGoesToTesterFirst() {
        AgentContext c = ctx(true, true, "developer");

        assertTrue(AgentFlowConfig.analystGoesToTester(c, ok()));
        assertFalse(AgentFlowConfig.analystGoesToDeveloper(c, ok()));
    }

    @Test
    void developmentOnly_goesToDeveloper() {
        AgentContext c = ctx(true, false, "developer");

        assertFalse(AgentFlowConfig.analystGoesToTester(c, ok()));
        assertTrue(AgentFlowConfig.analystGoesToDeveloper(c, ok()));
    }

    @Test
    void analysisOnly_goesToPostValidation() {
        AgentContext c = ctx(null, null, "done");

        assertTrue(AgentFlowConfig.analystGoesToPostValidation(c, ok()));
        assertFalse(AgentFlowConfig.analystGoesToTester(c, ok()));
        assertFalse(AgentFlowConfig.analystGoesToDeveloper(c, ok()));
    }

    @Test
    void nullNextStep_goesToPostValidation() {
        assertTrue(AgentFlowConfig.analystGoesToPostValidation(ctx(null, null, null), ok()));
    }

    @Test
    void reporterNextStep_goesToReporter_onlyAnalystLaunchesIt() {
        // Запуском репортёра управляет аналитик: nextStep=reporter, кода/тестов нет.
        AgentContext c = ctx(null, null, "reporter");

        assertTrue(AgentFlowConfig.analystGoesToReporter(c, ok()));
        assertFalse(AgentFlowConfig.analystGoesToTester(c, ok()));
        assertFalse(AgentFlowConfig.analystGoesToDeveloper(c, ok()));
        assertFalse(AgentFlowConfig.analystGoesToPostValidation(c, ok()),
                "reporter-задачу не должен перехватывать путь в post_validation");
    }

    @Test
    void errorResult_routesNowhere() {
        AgentContext c = ctx(true, true, "developer");
        AgentResult err = AgentResult.failed(AgentError.of("analyst", new RuntimeException("boom")));

        assertFalse(AgentFlowConfig.analystGoesToTester(c, err));
        assertFalse(AgentFlowConfig.analystGoesToDeveloper(c, err));
        assertFalse(AgentFlowConfig.analystGoesToPostValidation(c, err));
    }
}
