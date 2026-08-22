package ru.allstreets.developer.config;

import io.github.asekka.springai.agents.core.Agent;
import io.github.asekka.springai.agents.core.AgentContext;
import io.github.asekka.springai.agents.core.AgentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ru.allstreets.developer.checkpoint.CheckpointService;
import ru.allstreets.developer.state.TaskState;

/**
 * Декоратор над Agent — сохраняет checkpoint в БД после выполнения каждого узла.
 * При resume — AgentGraphRunner.resume() запускает граф с начала,
 * но агенты могут проверить state и пропустить уже выполненную работу.
 */
public class CheckpointingAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(CheckpointingAgent.class);

    private final Agent delegate;
    private final String nodeName;
    private final CheckpointService checkpointService;

    public CheckpointingAgent(Agent delegate, String nodeName, CheckpointService checkpointService) {
        this.delegate = delegate;
        this.nodeName = nodeName;
        this.checkpointService = checkpointService;
    }

    @Override
    public AgentResult execute(AgentContext ctx) {
        String taskId = ctx.get(TaskState.TASK_ID);
        log.debug("CheckpointingAgent[{}]: запуск узла для task={}", nodeName, taskId);

        AgentResult result = delegate.execute(ctx);

        if (taskId != null && !result.hasError()) {
            checkpointService.saveCheckpoint(taskId, nodeName, ctx, "RUNNING");
            log.info("CheckpointingAgent[{}]: checkpoint сохранён для task={}", nodeName, taskId);
        }

        return result;
    }
}
