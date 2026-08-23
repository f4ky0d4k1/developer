package ru.allstreets.developer.config;

import io.github.asekka.springai.agents.core.AgentContext;
import io.github.asekka.springai.agents.core.AgentResult;
import io.github.asekka.springai.agents.graph.AgentGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.allstreets.developer.checkpoint.CheckpointService;
import ru.allstreets.developer.checkpoint.TaskLockService;
import ru.allstreets.developer.checkpoint.TaskRepository;
import ru.allstreets.developer.state.TaskState;

@Component
public class AgentGraphRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentGraphRunner.class);

    private final AgentGraph graph;
    private final CheckpointService checkpointService;
    private final TaskLockService taskLockService;
    private final TaskRepository taskRepo;

    public AgentGraphRunner(AgentGraph graph, CheckpointService checkpointService,
                            TaskLockService taskLockService, TaskRepository taskRepo) {
        this.graph = graph;
        this.checkpointService = checkpointService;
        this.taskLockService = taskLockService;
        this.taskRepo = taskRepo;
    }

    /**
     * Запуск агентного графа с checkpoint сохранением.
     * Сохраняет состояние после каждого узла.
     * При успешном завершении — очищает checkpoint.
     */
    public AgentResult run(AgentContext ctx) {
        String taskId = ctx.get(TaskState.TASK_ID);
        String runId = taskId != null ? taskId : java.util.UUID.randomUUID().toString();

        // Per-task lock — только один агент над задачей
        if (taskId != null && !taskLockService.tryLock(taskId)) {
            log.warn("AgentGraph: задача {} уже выполняется другим агентом — отмена", taskId);
            return AgentResult.failed(io.github.asekka.springai.agents.core.AgentError.of(
                    "graph", new IllegalStateException("Task already locked: " + taskId)));
        }

        log.info("Запуск AgentGraph для задачи: {}", runId);

        // Сохраняем начальный checkpoint
        checkpointService.saveCheckpoint(runId, "start", ctx, "RUNNING");

        try {
            var result = graph.invoke(ctx);

            if (!result.hasError()) {
                checkpointService.saveCheckpoint(runId, "completed", ctx, "COMPLETED");
                log.info("AgentGraph завершён успешно. Очистка checkpoint.");
                checkpointService.cleanup(runId);
            } else {
                checkpointService.saveCheckpoint(runId, "failed", ctx, "FAILED");
                log.warn("AgentGraph завершён с ошибкой. Checkpoint сохранён для возобновления.");
            }

            return result;

        } catch (Exception e) {
            log.error("AgentGraph упал с ошибкой. Checkpoint сохранён для возобновления: {}", e.getMessage(), e);
            checkpointService.saveCheckpoint(runId, "crashed", ctx, "RUNNING");
            throw e;
        } finally {
            if (taskId != null) {
                taskLockService.unlock(taskId);
                taskLockService.cleanup(taskId);
            }
        }
    }

    /**
     * Возобновление агентного графа из checkpoint после краха.
     *
     * @param runId ID запуска для восстановления
     * @return результат выполнения или null если checkpoint не найден
     */
    public AgentResult resume(String runId) {
        if (runId == null) {
            log.warn("AgentGraphRunner.resume: runId is null, отмена");
            return null;
        }

        log.info("Возобновление AgentGraph из checkpoint: {}", runId);

        if (!taskLockService.tryLock(runId)) {
            log.warn("AgentGraph: задача {} уже выполняется — resume отменён", runId);
            return AgentResult.failed(io.github.asekka.springai.agents.core.AgentError.of(
                    "graph", new IllegalStateException("Task already locked: " + runId)));
        }

        AgentContext restoredCtx = checkpointService.restoreCheckpoint(runId);
        if (restoredCtx == null) {
            log.warn("Невозможно возобновить — checkpoint не найден для runId={}", runId);
            taskLockService.unlock(runId);
            taskLockService.cleanup(runId);
            return null;
        }

        String taskDesc = taskRepo.findById(runId)
                .map(t -> t.getDescription() != null ? t.getDescription() : "")
                .orElse("");
        if (taskDesc.isBlank()) {
            log.warn("AgentGraphRunner.resume: пустое описание задачи для runId={}, используем empty context", runId);
        }
        restoredCtx = AgentContext.of(taskDesc).withState(restoredCtx.state())
                .with(TaskState.TASK_ID, runId);

        String lastNode = checkpointService.getLastNodeName(runId);
        log.info("Возобновление с узла: {}", lastNode);

        try {
            var result = graph.invoke(restoredCtx);

            if (!result.hasError()) {
                checkpointService.cleanup(runId);
                log.info("Возобновлённый AgentGraph завершён успешно.");
            } else {
                checkpointService.saveCheckpoint(runId, "failed", restoredCtx, "FAILED");
            }

            return result;

        } catch (Exception e) {
            log.error("Возобновлённый AgentGraph упал: {}", e.getMessage(), e);
            checkpointService.saveCheckpoint(runId, "crashed", restoredCtx, "RUNNING");
            throw e;
        } finally {
            taskLockService.unlock(runId);
            taskLockService.cleanup(runId);
        }
    }
}
