package ru.allstreets.developer.config;

import io.github.asekka.springai.agents.core.AgentContext;
import io.github.asekka.springai.agents.core.AgentResult;
import io.github.asekka.springai.agents.graph.AgentGraph;
import io.github.asekka.springai.agents.graph.Edge;
import io.github.asekka.springai.agents.graph.ErrorPolicy;
import io.github.asekka.springai.agents.graph.RetryPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.allstreets.developer.agents.AnalystNode;
import ru.allstreets.developer.agents.DeveloperNode;
import ru.allstreets.developer.agents.PostValidationNode;
import ru.allstreets.developer.agents.ReporterNode;
import ru.allstreets.developer.agents.TesterNode;
import ru.allstreets.developer.checkpoint.JpaCheckpointStore;
import ru.allstreets.developer.opencode.OpenCodeTransientException;
import ru.allstreets.developer.state.TaskState;

import java.time.Duration;

@Configuration
public class AgentFlowConfig {

    /**
     * Число попыток узла при транзиентной ошибке OpenCode (stall): 1 основная + 2 ретрая.
     */
    private static final int OPENCODE_RETRY_ATTEMPTS = 3;

    @Bean
    public AgentGraph agentGraph(
            AnalystNode analyst,
            TesterNode tester,
            DeveloperNode developer,
            ReporterNode reporter,
            PostValidationNode postValidation,
            JpaCheckpointStore checkpointStore
    ) {
        return AgentGraph.builder()
                .checkpointStore(checkpointStore)
                .addNode("analyst", analyst)
                .addNode("tester", tester)
                .addNode("developer", developer)
                .addNode("reporter", reporter)
                .addNode("post_validation", postValidation)
                // analyst → роутинг по ФЛАГАМ задачи, а не по nextStep: если нужны тесты —
                // всегда сначала tester (TDD), затем tester → developer. Защита от ошибки
                // аналитика, который ставит nextStep=developer, игнорируя requiresTesting
                // (инцидент ec0a2004: developer запускался раньше tester).
                //
                // reporter — единственное исключение: запуском репортёра управляет АНАЛИТИК явным
                // nextStep=reporter (текст в Трекере — не код, флагов разработки/тестов у него нет).
                .addEdge(Edge.onResult("analyst", AgentFlowConfig::analystGoesToReporter, "reporter"))
                .addEdge(Edge.onResult("analyst", AgentFlowConfig::analystGoesToTester, "tester"))
                .addEdge(Edge.onResult("analyst", AgentFlowConfig::analystGoesToDeveloper, "developer"))
                .addEdge(Edge.onResult("analyst", AgentFlowConfig::analystGoesToPostValidation, "post_validation"))
                // developer → post_validation (всегда — валидация и PR)
                .addEdge(Edge.onResult(
                        "developer",
                        (ctx, result) -> !result.hasError(),
                        "post_validation"
                ))
                // tester → developer (тесты написаны, теперь реализация)
                .addEdge(Edge.onResult(
                        "tester",
                        (ctx, result) -> !result.hasError(),
                        "developer"
                ))
                // post_validation → reroute (LLM-driven via REROUTE_TARGET, до 3 раз).
                // Само-возврат post_validation → post_validation убран: «PR без URL» — это fail,
                // а не повод крутить узел (инцидент 0f9e5fa2).
                .addEdge(Edge.onResult("post_validation",
                        (ctx, result) -> shouldReroute(ctx, result, "developer"), "developer"))
                .addEdge(Edge.onResult("post_validation",
                        (ctx, result) -> shouldReroute(ctx, result, "analyst"), "analyst"))
                .addEdge(Edge.onResult("post_validation",
                        (ctx, result) -> shouldReroute(ctx, result, "tester"), "tester"))
                // post_validation → reporter: валидатор ЗАКОНЧИЛ задачу (PR или done — доработок нет),
                // и есть задача Трекера → репортёр пишет финальный итог. Репортёр — терминальный шаг.
                // При reroute REROUTE_TARGET непуст → сюда не попадаем, итог пишется на финальном проходе.
                .addEdge(Edge.onResult("post_validation",
                        AgentFlowConfig::postValidationGoesToReporter, "reporter"))
                .errorPolicy(ErrorPolicy.FAIL_FAST)
                .retryPolicy(openCodeRetryPolicy())
                .build();
    }

    /**
     * Ретрай-политика для транзиентных ошибок OpenCode (зависание стрима): до 2 повторов
     * с экспоненциальной задержкой. Ошибки, не помеченные как транзиентные, не ретраятся.
     */
    static RetryPolicy openCodeRetryPolicy() {
        return new RetryPolicy(
                OPENCODE_RETRY_ATTEMPTS,
                Duration.ofSeconds(5),
                Duration.ofSeconds(60),
                2.0,
                0.2,
                AgentFlowConfig::isOpenCodeTransient);
    }

    static boolean isOpenCodeTransient(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof OpenCodeTransientException) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    /**
     * Аналитик не принял решения о коде/тестах (nextStep=done/null) — аналитическая задача.
     */
    private static boolean isAnalystDone(AgentContext ctx) {
        String next = ctx.get(TaskState.NEXT_STEP);
        return next == null || "done".equals(next);
    }

    /**
     * analyst → reporter: задача про текст в Трекере (оформление/правки отчёта, описания, комментариев).
     * <p>
     * Запуском репортёра управляет аналитик: он единственный ставит {@code nextStep=reporter}. Это не
     * «код», поэтому флаги разработки/тестов здесь не участвуют и {@link #analystGoesToPostValidation}
     * явно исключает этот nextStep.
     */
    static boolean analystGoesToReporter(AgentContext ctx, AgentResult result) {
        return !result.hasError() && "reporter".equals(ctx.get(TaskState.NEXT_STEP));
    }

    /**
     * analyst → tester: задача требует тестов (TDD — тесты раньше реализации).
     */
    static boolean analystGoesToTester(AgentContext ctx, AgentResult result) {
        return !result.hasError() && !isAnalystDone(ctx)
                && Boolean.TRUE.equals(ctx.get(TaskState.REQUIRES_TESTING));
    }

    /**
     * analyst → developer: код нужен, но тесты не требуются.
     */
    static boolean analystGoesToDeveloper(AgentContext ctx, AgentResult result) {
        return !result.hasError() && !isAnalystDone(ctx)
                && !Boolean.TRUE.equals(ctx.get(TaskState.REQUIRES_TESTING))
                && Boolean.TRUE.equals(ctx.get(TaskState.REQUIRES_DEVELOPMENT));
    }

    /**
     * analyst → post_validation: аналитическая задача либо нечего делать.
     */
    static boolean analystGoesToPostValidation(AgentContext ctx, AgentResult result) {
        if (result.hasError()) return false;
        if (analystGoesToReporter(ctx, result)) return false;
        if (isAnalystDone(ctx)) return true;
        return !Boolean.TRUE.equals(ctx.get(TaskState.REQUIRES_DEVELOPMENT))
                && !Boolean.TRUE.equals(ctx.get(TaskState.REQUIRES_TESTING));
    }

    private static boolean shouldReroute(AgentContext ctx, AgentResult result, String target) {
        if (result.hasError()) return false;
        String rerouteTarget = ctx.get(TaskState.REROUTE_TARGET);
        Integer rc = ctx.get(TaskState.REWORK_COUNT);
        int reworkCount = rc != null ? rc : 0;
        return target.equals(rerouteTarget) && reworkCount < 3;
    }

    /**
     * post_validation → reporter: валидатор ЗАКОНЧИЛ задачу (нет reroute — PR создан или done),
     * и у задачи есть тикет Трекера, куда репортёр пишет финальный итог.
     */
    static boolean postValidationGoesToReporter(AgentContext ctx, AgentResult result) {
        if (result.hasError()) return false;
        String rerouteTarget = ctx.get(TaskState.REROUTE_TARGET);
        if (rerouteTarget != null && !rerouteTarget.isBlank()) return false;
        String trackerIssue = ctx.get(TaskState.TRACKER_ISSUE);
        return trackerIssue != null && !trackerIssue.isBlank();
    }
}
