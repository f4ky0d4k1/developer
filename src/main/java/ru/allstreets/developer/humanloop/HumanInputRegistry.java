package ru.allstreets.developer.humanloop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ru.allstreets.developer.checkpoint.PendingInputEntity;
import ru.allstreets.developer.checkpoint.PendingInputRepository;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Реестр pending HITL-вопросов — per-task, <b>DB-backed</b>.
 * <p>
 * Не блокирует потоки: вопрос приостанавливает граф через
 * {@code AgentResult.interrupted(...)} и сохраняет checkpoint — поток из пула
 * возвращается сразу. Ответ пользователя запускает {@code TaskLauncher.resumeWithAnswer(...)},
 * который вызывает {@code graph.resume(runId, answerMessage)} — продолжение той же
 * OpenCode-сессии в том же зарезервированном слоте (см. AnalystNode), без перезапуска.
 * <p>
 * Источник истины — БД ({@code agent_pending_inputs}), а не память: иначе после рестарта
 * (деплой) pending теряется и ответ пользователя не может возобновить задачу (инцидент 427edb3c:
 * «нет chatId для pending задачи — resume невозможен», задача навсегда висит в HITL).
 */
@Component
public class HumanInputRegistry {

    private static final Logger log = LoggerFactory.getLogger(HumanInputRegistry.class);

    private final PendingInputRepository pendingRepo;

    public HumanInputRegistry(PendingInputRepository pendingRepo) {
        this.pendingRepo = pendingRepo;
    }

    /**
     * Зарегистрировать вопрос, ожидающий ответа. Не блокирует — граф уже
     * приостановлен через interrupt, вызывающий агент вернул управление немедленно.
     */
    public void registerPending(String taskId, long chatId, String question) {
        pendingRepo.save(new PendingInputEntity(taskId, chatId, question));
        log.info("HumanInput: зарегистрирован pending-вопрос для taskId={} chatId={}", taskId, chatId);
    }

    /**
     * chatId, для которого зарегистрирован pending-вопрос задачи, или null (читается из БД).
     */
    public Long getChatIdForPending(String taskId) {
        return pendingRepo.findById(taskId).map(PendingInputEntity::getChatId).orElse(null);
    }

    public boolean hasPendingInputs(long chatId) {
        return !pendingRepo.findByChatId(chatId).isEmpty();
    }

    public Map<String, String> getPendingQuestionsForChat(long chatId) {
        var result = new LinkedHashMap<String, String>();
        for (var e : pendingRepo.findByChatId(chatId)) {
            result.put(e.getTaskId(), e.getQuestion());
        }
        return result;
    }

    /**
     * Снять pending-вопрос после того, как ответ получен и запущен resume задачи.
     * Ничего не блокирует и не завершает — это делает {@code TaskLauncher.resumeWithAnswer}.
     */
    public void provideAnswer(String taskId) {
        if (pendingRepo.existsById(taskId)) {
            log.info("HumanInput: ответ получен для taskId={}", taskId);
            pendingRepo.deleteById(taskId);
        } else {
            log.warn("HumanInput: нет pending запроса для taskId={}", taskId);
        }
    }

    public void cancel(String taskId) {
        if (pendingRepo.existsById(taskId)) {
            pendingRepo.deleteById(taskId);
        }
    }
}
