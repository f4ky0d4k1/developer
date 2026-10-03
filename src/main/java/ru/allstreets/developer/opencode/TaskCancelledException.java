package ru.allstreets.developer.opencode;

/**
 * Задача отменена управляющим потоком ({@code TaskLauncher.cancel}) — рабочий поток
 * заметил interrupt-флаг в цикле опроса и прекращает работу. Это НЕ транзиентная
 * ошибка (не {@link OpenCodeTransientException}), поэтому граф не ретраит узел:
 * {@code ErrorPolicy.FAIL_FAST} сворачивает граф, а {@code AgentGraphRunner} в
 * {@code finally} снимает advisory-lock как следствие реальной остановки потока —
 * блокировку никто не «проскакивает».
 */
public class TaskCancelledException extends RuntimeException {

    public TaskCancelledException(String message) {
        super(message);
    }
}
