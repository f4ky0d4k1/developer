package ru.allstreets.developer.agents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ru.allstreets.developer.opencode.OpenCodeClient;
import ru.allstreets.developer.opencode.OpenCodeSessionPool;
import ru.allstreets.developer.opencode.OpenCodeTransientException;
import ru.allstreets.developer.telegram.TelegramGateway;

/**
 * Запуск агента-валидатора через OpenCode. Валидатор получает полный контекст задачи,
 * инспектирует worktree (git log/diff/файлы) и решает: создать PR либо вернуть работу
 * к analyst/tester/developer. Тесты валидатор НЕ запускает — их пишет tester и доводит
 * до зелёного developer (у обоих есть полное окружение).
 */
@Component
public class ValidatorService {

    private static final Logger log = LoggerFactory.getLogger(ValidatorService.class);

    private final OpenCodeClient openCode;
    private final OpenCodeSessionPool sessionPool;
    private final TelegramGateway telegram;

    public ValidatorService(OpenCodeClient openCode, OpenCodeSessionPool sessionPool, TelegramGateway telegram) {
        this.openCode = openCode;
        this.sessionPool = sessionPool;
        this.telegram = telegram;
    }

    /**
     * Запускает OpenCode-валидатора с готовым промптом (полный контекст задачи). Возвращает
     * сырой текстовый ответ агента (внутри ожидается JSON: prUrl/reroute/failed/summary)
     * или {@code null} при ошибке.
     */
    public String run(String prompt, long chatIdLong, String repoUrl, String taskId) {
        int slot = sessionPool.acquire(600);
        if (slot < 0) {
            telegram.sendMessage(chatIdLong, "❌ Таймаут ожидания слота OpenCode");
            return null;
        }
        try {
            sessionPool.prepareSlot(slot, repoUrl);
            String workDir = sessionPool.getSlotWorkDir(slot);

            var ocResult = openCode.runAgent("validator", prompt, workDir, taskId);
            String output = ocResult.output() != null ? ocResult.output() : "";
            log.info("Post-validation: валидатор завершён. output: {} символов", output.length());
            return output;
        } catch (OpenCodeTransientException e) {
            // Транзиентный стопор sidecar (stall) — пробрасываем, чтобы граф ретраил узел
            // (инцидент 0f9e5fa2: PR не создавался, задача падала без ретрая).
            log.warn("Post-validation: транзиентная ошибка OpenCode (ретрай графом): {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("Post-validation: ошибка OpenCode: {}", e.getMessage(), e);
            telegram.sendMessage(chatIdLong, "❌ Ошибка OpenCode: " + e.getMessage());
            return null;
        } finally {
            sessionPool.cleanupSlot(slot);
            sessionPool.release(slot);
        }
    }
}
