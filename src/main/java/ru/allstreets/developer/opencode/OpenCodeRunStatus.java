package ru.allstreets.developer.opencode;

/**
 * Статусы жизненного цикла прогона OpenCode-агента.
 * Переходы: {@code STARTING -> RUNNING -> (DONE | FAILED | ABORTED)}.
 */
public enum OpenCodeRunStatus {
    /**
     * Запись создана, промпт ещё не отправлен в sidecar (или факт отправки не подтверждён).
     */
    STARTING,
    /**
     * Промпт отправлен ({@code prompt_async} вернул 204), идёт опрос результата.
     */
    RUNNING,
    /**
     * Агент завершился успешно, результат сохранён.
     */
    DONE,
    /**
     * Агент завершился с ошибкой.
     */
    FAILED,
    /**
     * Прерван по истечении бюджета времени или вручную.
     */
    ABORTED
}
