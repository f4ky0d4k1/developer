package ru.allstreets.developer.config;

import io.github.asekka.springai.agents.core.AgentContext;
import io.github.asekka.springai.agents.core.AgentError;
import io.github.asekka.springai.agents.core.AgentResult;
import io.github.asekka.springai.agents.graph.AgentGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;
import ru.allstreets.developer.checkpoint.TaskLockService;
import ru.allstreets.developer.state.TaskState;

import java.util.UUID;

/**
 * Тонкая обёртка над {@link AgentGraph} — только per-task блокировка вокруг
 * нативного {@code invoke}/{@code resume} библиотеки. Само сохранение/восстановление
 * checkpoint и продолжение с прерванного узла (а не с начала) делает граф через
 * {@link ru.allstreets.developer.checkpoint.JpaCheckpointStore}.
 */
@Component
public class AgentGraphRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentGraphRunner.class);

    private final AgentGraph graph;
    private final TaskLockService taskLockService;

    public AgentGraphRunner(AgentGraph graph, TaskLockService taskLockService) {
        this.graph = graph;
        this.taskLockService = taskLockService;
    }

    /**
     * Запуск нового агентного графа. Checkpoint сохраняется/удаляется графом
     * автоматически (через JpaCheckpointStore) после каждого узла.
     */
    public AgentResult run(AgentContext ctx) {
        String taskId = ctx.get(TaskState.TASK_ID);
        String runId = taskId != null ? taskId : UUID.randomUUID().toString();

        if (!taskLockService.tryLock(runId)) {
            log.warn("AgentGraph: задача {} уже выполняется другим агентом — отмена", runId);
            return AgentResult.failed(AgentError.of("graph", new IllegalStateException("Task already locked: " + runId)));
        }

        log.info("Запуск AgentGraph для задачи: {}", runId);
        try {
            return graph.invoke(ctx, runId);
        } finally {
            taskLockService.cleanup(runId);
        }
    }

    /**
     * Возобновление графа из checkpoint — продолжает ровно с того узла,
     * на котором остановились ({@code nextNode} из checkpoint), а не с начала.
     *
     * @param runId      ID запуска для восстановления
     * @param additional дополнительные сообщения (например ответ пользователя на HITL-вопрос)
     * @return результат выполнения или null если checkpoint не найден
     */
    public AgentResult resume(String runId, Message... additional) {
        if (runId == null) {
            log.warn("AgentGraphRunner.resume: runId is null, отмена");
            return null;
        }

        if (!taskLockService.tryLock(runId)) {
            log.warn("AgentGraph: задача {} уже выполняется — resume отменён", runId);
            return AgentResult.failed(AgentError.of("graph", new IllegalStateException("Task already locked: " + runId)));
        }

        log.info("Возобновление AgentGraph из checkpoint: {}", runId);
        try {
            return graph.resume(runId, additional);
        } catch (IllegalStateException e) {
            log.warn("Возобновление невозможно для runId={}: {}", runId, e.getMessage());
            return null;
        } finally {
            taskLockService.cleanup(runId);
        }
    }

    public AgentResult resume(String runId) {
        return resume(runId, new Message[0]);
    }
}
