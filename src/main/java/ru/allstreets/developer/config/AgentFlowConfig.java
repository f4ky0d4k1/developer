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
            PostValidationNode postValidation,
            JpaCheckpointStore checkpointStore
    ) {
        return AgentGraph.builder()
                .checkpointStore(checkpointStore)
                .addNode("analyst", analyst)
                .addNode("tester", tester)
                .addNode("developer", developer)
                .addNode("post_validation", postValidation)
                // analyst → роутинг через NEXT_STEP (LLM-driven)
                // "developer" — задача требует кодинга
                .addEdge(Edge.onResult(
                        "analyst",
                        (ctx, result) -> !result.hasError() && "developer".equals(ctx.get(TaskState.NEXT_STEP)),
                        "developer"
                ))
                // "tester" — сначала тесты (TDD)
                .addEdge(Edge.onResult(
                        "analyst",
                        (ctx, result) -> !result.hasError() && "tester".equals(ctx.get(TaskState.NEXT_STEP)),
                        "tester"
                ))
                // "done" или null — аналитическая задача, сразу к пост-валидации
                .addEdge(Edge.onResult(
                        "analyst",
                        (ctx, result) -> {
                            if (result.hasError()) return false;
                            String next = ctx.get(TaskState.NEXT_STEP);
                            return next == null || "done".equals(next);
                        },
                        "post_validation"
                ))
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
                // post_validation → reroute (LLM-driven via REROUTE_TARGET, до 3 раз)
                .addEdge(Edge.onResult("post_validation",
                        (ctx, result) -> shouldReroute(ctx, result, "post_validation"), "post_validation"))
                .addEdge(Edge.onResult("post_validation",
                        (ctx, result) -> shouldReroute(ctx, result, "developer"), "developer"))
                .addEdge(Edge.onResult("post_validation",
                        (ctx, result) -> shouldReroute(ctx, result, "analyst"), "analyst"))
                .addEdge(Edge.onResult("post_validation",
                        (ctx, result) -> shouldReroute(ctx, result, "tester"), "tester"))
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

    private static boolean shouldReroute(AgentContext ctx, AgentResult result, String target) {
        if (result.hasError()) return false;
        String rerouteTarget = ctx.get(TaskState.REROUTE_TARGET);
        Integer rc = ctx.get(TaskState.REWORK_COUNT);
        int reworkCount = rc != null ? rc : 0;
        return target.equals(rerouteTarget) && reworkCount < 3;
    }
}
