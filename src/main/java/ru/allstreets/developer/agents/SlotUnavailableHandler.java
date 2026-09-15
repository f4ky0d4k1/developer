package ru.allstreets.developer.agents;

import io.github.asekka.springai.agents.core.AgentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ru.allstreets.developer.checkpoint.TaskRepository;
import ru.allstreets.developer.humanloop.HumanLoopService;
import ru.allstreets.developer.opencode.OpenCodeSessionPool;
import ru.allstreets.developer.state.TaskState;

/**
 * Обработка нехватки слотов OpenCode: вместо 10-минутного ожидания и падения узел спрашивает
 * пользователя, какие задачи можно закрыть, и уходит в HITL-паузу ({@code HITL_SLOT}). После
 * закрытия задач (closeTask) задача возобновляется с того же узла и повторяет захват слота.
 */
@Component
public class SlotUnavailableHandler {

    private static final Logger log = LoggerFactory.getLogger(SlotUnavailableHandler.class);

    private final OpenCodeSessionPool sessionPool;
    private final HumanLoopService humanLoop;
    private final TaskRepository taskRepo;

    public SlotUnavailableHandler(OpenCodeSessionPool sessionPool, HumanLoopService humanLoop,
                                  TaskRepository taskRepo) {
        this.sessionPool = sessionPool;
        this.humanLoop = humanLoop;
        this.taskRepo = taskRepo;
    }

    /**
     * Задать пользователю вопрос об освобождении слотов и уйти в HITL-паузу.
     *
     * @param role роль узла (analyst/developer/tester/validator) — для state
     */
    public AgentResult askToFreeSlots(String taskId, long chatId, String role) {
        var held = sessionPool.heldTasks();
        StringBuilder sb = new StringBuilder();
        sb.append("⚠️ Свободных слотов OpenCode нет — задача ").append(shortId(taskId))
                .append(" ждёт. Закрой ненужные задачи, чтобы освободить слот (скажи «закрой <id>»).\n")
                .append("Слоты держат (").append(held.size()).append("):\n");
        for (String t : held) {
            var e = taskRepo.findById(t).orElse(null);
            sb.append("- ").append(shortId(t));
            if (e != null && e.getTitle() != null && !e.getTitle().isBlank()) {
                sb.append(" — ").append(e.getTitle());
            }
            sb.append(e != null ? " (" + e.getStatus() + ")" : " (нет в БД)").append("\n");
        }
        String question = sb.toString();
        log.warn("Слоты исчерпаны, задача {} уходит в HITL_SLOT (держат: {})", shortId(taskId), held);
        humanLoop.askHuman(taskId, chatId, question);

        return AgentResult.builder()
                .text("Awaiting free OpenCode slot")
                .stateUpdates(java.util.Map.of(TaskState.AGENT_ROLE, role))
                .interrupt("HITL_SLOT")
                .completed(false)
                .build();
    }

    private static String shortId(String taskId) {
        return taskId.length() > 8 ? taskId.substring(0, 8) : taskId;
    }
}
