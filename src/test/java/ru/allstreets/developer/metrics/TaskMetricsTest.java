package ru.allstreets.developer.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Метрики несут только низкокардинальные теги и корректно считают счётчики/таймеры.
 */
class TaskMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final TaskMetrics metrics = new TaskMetrics(registry);

    @Test
    void runFinished_incrementsCounterAndRecordsTimer() {
        metrics.runFinished("developer", "success", Duration.ofSeconds(5));
        metrics.runFinished("developer", "success", Duration.ofSeconds(7));

        assertEquals(2.0, registry.get("agent.runs")
                .tags("agent", "developer", "status", "success").counter().count());
        var timer = registry.get("agent.run.duration")
                .tags("agent", "developer", "status", "success").timer();
        assertEquals(2, timer.count());
        assertEquals(12.0, timer.totalTime(TimeUnit.SECONDS), 0.001);
    }

    @Test
    void toolStepsTokensCostErrors_areRecorded() {
        metrics.toolCall("analyst", "read");
        metrics.toolCall("analyst", "bash");
        metrics.steps("analyst", 13);
        metrics.tokens("analyst", "input", 100);
        metrics.tokens("analyst", "output", 50);
        metrics.cost("analyst", "deepseek-v4-pro", 0.25);
        metrics.error("developer", "timeout");

        assertEquals(1.0, registry.get("agent.tool.calls")
                .tags("agent", "analyst", "tool", "read").counter().count());
        assertEquals(13.0, registry.get("agent.steps").tag("agent", "analyst").counter().count());
        assertEquals(100.0, registry.get("agent.tokens")
                .tags("agent", "analyst", "kind", "input").counter().count());
        assertEquals(0.25, registry.get("agent.cost.usd")
                .tags("agent", "analyst", "model", "deepseek-v4-pro").counter().count());
        assertEquals(1.0, registry.get("agent.errors")
                .tags("agent", "developer", "reason", "timeout").counter().count());
    }

    @Test
    void nullAgent_isTaggedUnknown() {
        metrics.error(null, "stall");

        assertEquals(1.0, registry.get("agent.errors")
                .tags("agent", "unknown", "reason", "stall").counter().count());
    }
}
