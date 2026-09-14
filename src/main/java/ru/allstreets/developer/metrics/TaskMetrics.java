package ru.allstreets.developer.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Фасад метрик задач и агентов (Micrometer → Prometheus → Alloy → Grafana Cloud).
 * <p>
 * Важно: теги — только низкокардинальные ({@code agent}, {@code status}, {@code reason},
 * {@code tool}, {@code kind}, {@code model}). Никаких taskId/chatId/репо — иначе раздувание
 * TSDB. Per-task разбор — в Loki/Tempo/таблице Postgres, метрика ↔ трейс через exemplars.
 * <p>
 * Все метрики:
 * <ul>
 *   <li>{@code agent.runs{agent,status}} (counter) — число прогонов агента;</li>
 *   <li>{@code agent.run.duration{agent,status}} (timer) — длительность прогона;</li>
 *   <li>{@code agent.ttfb.duration{agent}} (timer) — время до первого текста агента;</li>
 *   <li>{@code agent.tool.calls{agent,tool}} (counter) — вызовы инструментов;</li>
 *   <li>{@code agent.steps{agent}} (counter) — число шагов;</li>
 *   <li>{@code agent.tokens{agent,kind}} (counter) — input/output/reasoning/cache_read/cache_write;</li>
 *   <li>{@code agent.cost.usd{agent,model}} (counter) — стоимость;</li>
 *   <li>{@code agent.errors{agent,reason}} (counter) — ошибки по причине;</li>
 *   <li>{@code opencode.slot.wait} (timer) — ожидание слота;</li>
 *   <li>{@code task.outcomes{outcome}} (counter) — исходы задач.</li>
 * </ul>
 * Гистограммы включены ({@code publishPercentileHistogram}) — дают медиану/p95 через
 * {@code histogram_quantile}; avg = {@code rate(_sum)/rate(_count)}.
 */
@Component
public class TaskMetrics {

    private final MeterRegistry registry;

    public TaskMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Завершение прогона агента: счётчик по статусу + таймер длительности. */
    public void runFinished(String agent, String status, Duration duration) {
        Counter.builder("agent.runs")
                .tags("agent", nz(agent), "status", nz(status))
                .register(registry).increment();
        if (duration != null) {
            Timer.builder("agent.run.duration")
                    .tags("agent", nz(agent), "status", nz(status))
                    .publishPercentileHistogram()
                    .register(registry).record(duration);
        }
    }

    /** Время до первого текста агента (отзывчивость). */
    public void firstResponse(String agent, Duration duration) {
        if (duration == null) {
            return;
        }
        Timer.builder("agent.ttfb.duration")
                .tag("agent", nz(agent))
                .publishPercentileHistogram()
                .register(registry).record(duration);
    }

    public void toolCall(String agent, String tool) {
        Counter.builder("agent.tool.calls")
                .tags("agent", nz(agent), "tool", nz(tool))
                .register(registry).increment();
    }

    public void steps(String agent, int count) {
        if (count <= 0) {
            return;
        }
        Counter.builder("agent.steps")
                .tag("agent", nz(agent))
                .register(registry).increment(count);
    }

    public void tokens(String agent, String kind, long count) {
        if (count <= 0) {
            return;
        }
        Counter.builder("agent.tokens")
                .tags("agent", nz(agent), "kind", nz(kind))
                .register(registry).increment(count);
    }

    public void cost(String agent, String model, double usd) {
        if (usd <= 0) {
            return;
        }
        Counter.builder("agent.cost.usd")
                .tags("agent", nz(agent), "model", nz(model))
                .register(registry).increment(usd);
    }

    public void error(String agent, String reason) {
        Counter.builder("agent.errors")
                .tags("agent", nz(agent), "reason", nz(reason))
                .register(registry).increment();
    }

    public void slotWait(Duration duration) {
        if (duration == null) {
            return;
        }
        Timer.builder("opencode.slot.wait")
                .publishPercentileHistogram()
                .register(registry).record(duration);
    }

    public void taskOutcome(String outcome) {
        Counter.builder("task.outcomes")
                .tag("outcome", nz(outcome))
                .register(registry).increment();
    }

    /** Регистрирует gauge'и утилизации пула слотов (idempotent — Micrometer дедупит по имени). */
    public void registerSlotGauges(java.util.function.IntSupplier activeSlots, int capacity) {
        io.micrometer.core.instrument.Gauge
                .builder("opencode.slots.active", activeSlots, java.util.function.IntSupplier::getAsInt)
                .register(registry);
        io.micrometer.core.instrument.Gauge
                .builder("opencode.slots.capacity", () -> capacity)
                .register(registry);
    }

    private static String nz(String v) {
        return v != null && !v.isBlank() ? v : "unknown";
    }
}
