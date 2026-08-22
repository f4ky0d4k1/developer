package ru.allstreets.developer.humanloop;

import org.springframework.stereotype.Component;

import ru.allstreets.developer.telegram.TelegramGateway;

/**
 * Сервис для human-in-the-loop взаимодействия.
 * Агент вызывает askHuman() — вопрос уходит в ТГ,
 * метод блокируется до ответа пользователя.
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
     * Задать вопрос человеку и дождаться ответа.
     * Блокирует текущий поток.
     *
     * @param chatId         ID чата ТГ
     * @param question       текст вопроса
     * @param timeoutSeconds тайм-аут (по умолчанию 5 минут)
     * @return ответ человека или null при тайм-ауте
     */
    public String askHuman(String taskId, long chatId, String question, long timeoutSeconds) {
        telegram.sendMessage(chatId, "❓ [" + taskId.substring(0, 8) + "] " + question);
        String answer = registry.requestInput(taskId, chatId, question, timeoutSeconds);
        if (answer == null) {
            telegram.sendMessage(chatId, "⏱️ Время ожидания ответа истекло. Продолжаю без уточнения.");
        }
        return answer;
    }

    public String askHuman(String taskId, long chatId, String question) {
        return askHuman(taskId, chatId, question, 300);
    }
}
