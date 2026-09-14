package ru.allstreets.developer.opencode;

/**
 * Транзиентная ошибка OpenCode sidecar, которую безопасно ретраить на уровне графа:
 * sidecar принял промпт, но завис (сессия idle/неизвестна, стрим не отдал ни одного
 * токена). Отличается от «настоящих» ошибок (агент сам вернул ошибку, таймаут бюджета),
 * которые ретраить бессмысленно.
 * <p>
 * Ретрай прогона = новая сессия + повторная отправка промпта (старая сессия абортится
 * и помечается ABORTED в {@link OpenCodeRunEntity}), поэтому повтор безопасен.
 */
public class OpenCodeTransientException extends RuntimeException {

    public OpenCodeTransientException(String message) {
        super(message);
    }
}
