package ru.allstreets.developer.humanloop;

import org.springframework.stereotype.Component;

import ru.allstreets.developer.telegram.TelegramGateway;

/**
 * Сервис для human-in-the-loop взаимодействия.
 * <p>
 * Non-blocking: агент вызывает {@code askHuman()} — вопрос уходит в ТГ,
 * регистрируется pending-запрос, после чего агент возвращает
 * {@code AgentResult.interrupted(...)} и граф сохраняет checkpoint.
 * Поток освобождается немедленно.
 * <p>
 * Ответ пользователя запускает {@code TaskLauncher.resumeWithAnswer(taskId, answer)},
 * который вызывает {@code graph.resume(runId, UserMessage(answer))} —
 * граф входит в тот же узел, агент читает ответ из messages и продолжает.
 */
@Component
public class HumanLoopService {

    private final HumanInputRegistry registry;
    private final TelegramGateway telegram;

    public HumanLoopService(HumanInputRegistry registry, TelegramGateway telegram) {
        this.registry = registry;
        this.telegram = telegram;
    }

    /**
     * Отправить вопрос в ТГ и зарегистрировать pending-запрос.
     * Не блокирует — вызывающий агент должен вернуть {@code AgentResult.interrupted(...)}.
     *
     * @param taskId   ID задачи
     * @param chatId   ID чата ТГ
     * @param question текст вопроса
     */
    public void askHuman(String taskId, long chatId, String question) {
        telegram.sendMessage(chatId, "❓ [" + taskId.substring(0, 8) + "] " + question);
        registry.registerPending(taskId, chatId, question);
    }
}
