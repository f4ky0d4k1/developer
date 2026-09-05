package ru.allstreets.developer.agents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ru.allstreets.developer.opencode.OpenCodeClient;
import ru.allstreets.developer.opencode.OpenCodeSessionPool;
import ru.allstreets.developer.telegram.TelegramGateway;

/**
 * Запуск создания Pull Request через OpenCode. Выделено из {@link PostValidationNode}
 * (SRP) — сама доработка кода/PR всегда делается агентом OpenCode, узел графа только
 * проверяет результат и решает, куда вернуться при проблеме.
 */
@Component
public class PullRequestCreationService {

    private static final Logger log = LoggerFactory.getLogger(PullRequestCreationService.class);

    private final OpenCodeClient openCode;
    private final OpenCodeSessionPool sessionPool;
    private final TelegramGateway telegram;

    public PullRequestCreationService(OpenCodeClient openCode, OpenCodeSessionPool sessionPool, TelegramGateway telegram) {
        this.openCode = openCode;
        this.sessionPool = sessionPool;
        this.telegram = telegram;
    }

    /**
     * Запускает OpenCode для создания PR. Возвращает сырой текстовый ответ агента
     * (с ожидаемым JSON внутри — prUrl/reroute/failed/summary) или {@code null} при ошибке.
     */
    public String createPullRequest(String branch, String spec, long chatIdLong, String repoUrl, String taskId) {
        int slot = sessionPool.acquire(600);
        if (slot < 0) {
            telegram.sendMessage(chatIdLong, "❌ Таймаут ожидания слота OpenCode");
            return null;
        }
        try {
            sessionPool.prepareSlot(slot, repoUrl);
            String workDir = sessionPool.getSlotWorkDir(slot);
            String ocPrompt = getPrPrompt(branch, spec);

            var ocResult = openCode.runAgent("post_validation", ocPrompt, workDir, taskId);
            String output = ocResult.output() != null ? ocResult.output() : "";
            log.info("Post-validation: OpenCode завершён. output: {} символов", output.length());
            return output;
        } catch (Exception e) {
            log.error("Post-validation: ошибка OpenCode: {}", e.getMessage(), e);
            telegram.sendMessage(chatIdLong, "❌ Ошибка OpenCode: " + e.getMessage());
            return null;
        } finally {
            sessionPool.cleanupSlot(slot);
            sessionPool.release(slot);
        }
    }

    private static String getPrPrompt(String branch, String spec) {
        String branchOrNa = branch != null && !branch.isBlank() ? branch : "N/A";

        return """
                Тесты прошли. Создай Pull Request через GitHub MCP инструмент.
                Если есть ветка %s — переключись на неё: git checkout %s
                
                - head: %s
                - base: main
                - title: Описание задачи
                - body: ТЗ из контекста
                
                В конце ответа выведи JSON: prUrl, reroute, failed, summary.
                
                ТЗ:
                %s
                """.formatted(branchOrNa, branchOrNa, branchOrNa,
                spec != null ? spec.substring(0, Math.min(500, spec.length())) : "N/A");
    }
}
