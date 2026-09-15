package ru.allstreets.developer.agents;

import io.github.asekka.springai.agents.core.AgentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ru.allstreets.developer.checkpoint.TaskEntity;
import ru.allstreets.developer.checkpoint.TaskRepository;
import ru.allstreets.developer.humanloop.HumanLoopService;
import ru.allstreets.developer.opencode.OpenCodeSessionPool;
import ru.allstreets.developer.state.TaskState;
import ru.allstreets.developer.telegram.TelegramGateway;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Обработка нехватки слотов OpenCode: вместо 10-минутного ожидания и падения узел спрашивает
 * пользователя, какие задачи закрыть, inline-кнопками (тап по задаче → close → слот
 * освобождается), и уходит в HITL-паузу ({@code HITL_SLOT}). После закрытия задач задача
 * возобновляется с того же узла и повторяет захват слота.
 */
@Component
public class SlotUnavailableHandler {

    private static final Logger log = LoggerFactory.getLogger(SlotUnavailableHandler.class);

    private final OpenCodeSessionPool sessionPool;
    private final HumanLoopService humanLoop;
    private final TaskRepository taskRepo;
    private final TelegramGateway telegram;

    public SlotUnavailableHandler(OpenCodeSessionPool sessionPool, HumanLoopService humanLoop,
                                  TaskRepository taskRepo, TelegramGateway telegram) {
        this.sessionPool = sessionPool;
        this.humanLoop = humanLoop;
        this.taskRepo = taskRepo;
        this.telegram = telegram;
    }

    /**
     * Задать пользователю вопрос об освобождении слотов (с кнопками-задачами) и уйти в HITL.
     *
     * @param role роль узла (analyst/developer/tester/validator) — для state
     */
    public AgentResult askToFreeSlots(String taskId, long chatId, String role) {
        List<String> held = sessionPool.heldTasks();

        String question = "⚠️ Свободных слотов OpenCode нет — задача " + shortId(taskId) + " ждёт.\n"
                + "Нажми на задачу, чтобы закрыть её и освободить слот (" + held.size() + " занято):";

        List<List<Map<String, String>>> keyboard = new ArrayList<>();
        for (String t : held) {
            TaskEntity e = taskRepo.findById(t).orElse(null);
            String label = shortId(t);
            if (e != null && e.getTitle() != null && !e.getTitle().isBlank()) {
                label += " — " + e.getTitle();
            }
            if (e != null) {
                label += " (" + e.getStatus() + ")";
            }
            String btnLabel = label.length() > 60 ? label.substring(0, 60) + "…" : label;
            keyboard.add(List.of(TelegramGateway.button(btnLabel, "close:" + t)));
        }
        keyboard.add(List.of(TelegramGateway.button("✅ Готово", "slot:done")));

        log.warn("Слоты исчерпаны, задача {} уходит в HITL_SLOT (держат: {})", shortId(taskId), held);

        humanLoop.registerPending(taskId, chatId, question);
        telegram.sendMessageWithKeyboard(chatId, question, keyboard, taskId);

        return AgentResult.builder()
                .text("Awaiting free OpenCode slot")
                .stateUpdates(Map.of(TaskState.AGENT_ROLE, role))
                .interrupt("HITL_SLOT")
                .completed(false)
                .build();
    }

    private static String shortId(String taskId) {
        return taskId.length() > 8 ? taskId.substring(0, 8) : taskId;
    }
}
