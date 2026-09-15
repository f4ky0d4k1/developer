package ru.allstreets.developer.opencode;

/**
 * Свободных слотов OpenCode нет. Бросается из сервисов, которые не могут вернуть
 * {@code AgentResult.interrupt(...)} напрямую с точки захвата слота — узел ловит это
 * и уходит в HITL_SLOT (см. {@code SlotUnavailableHandler}).
 */
public class SlotUnavailableException extends RuntimeException {

    public SlotUnavailableException(String message) {
        super(message);
    }
}
