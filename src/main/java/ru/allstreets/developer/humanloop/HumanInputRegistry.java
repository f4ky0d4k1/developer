package ru.allstreets.developer.humanloop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ru.allstreets.developer.checkpoint.PendingInputEntity;
import ru.allstreets.developer.checkpoint.PendingInputRepository;
import ru.allstreets.developer.checkpoint.TaskRepository;

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
    private final TaskRepository taskRepo;

    public HumanInputRegistry(PendingInputRepository pendingRepo, TaskRepository taskRepo) {
        this.pendingRepo = pendingRepo;
        this.taskRepo = taskRepo;
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
     * Осиротевший pending (задача удалена/закрыта/завершена) снимается и возвращает null.
     */
    public Long getChatIdForPending(String taskId) {
        var pending = pendingRepo.findById(taskId).orElse(null);
        if (pending == null) {
            return null;
        }
        if (!isLiveTask(taskId)) {
            purgeOrphan(taskId);
            return null;
        }
        return pending.getChatId();
    }

    public boolean hasPendingInputs(long chatId) {
        return !getPendingQuestionsForChat(chatId).isEmpty();
    }

    /**
     * Pending-вопросы чата, у которых задача всё ещё «живая» (RUNNING, не удалена).
     * Осиротевшие строки (задача удалена/CLOSED/COMPLETED/FAILED) снимаются здесь же —
     * иначе после закрытия задачи её вопрос вечно висел бы в очереди (инцидент 330559f5).
     */
    public Map<String, String> getPendingQuestionsForChat(long chatId) {
        var result = new LinkedHashMap<String, String>();
        for (var e : pendingRepo.findByChatId(chatId)) {
            if (isLiveTask(e.getTaskId())) {
                result.put(e.getTaskId(), e.getQuestion());
            } else {
                purgeOrphan(e.getTaskId());
            }
        }
        return result;
    }

    /**
     * Задача, ожидающая ответа на HITL-вопрос, должна существовать, быть не удалённой
     * и в статусе RUNNING. Всё остальное — осиротевший pending.
     */
    private boolean isLiveTask(String taskId) {
        return taskRepo.findById(taskId)
                .map(t -> !t.isDeleted() && "RUNNING".equals(t.getStatus()))
                .orElse(false);
    }

    private void purgeOrphan(String taskId) {
        log.warn("HumanInput: снимаю осиротевший pending-вопрос taskId={} (задача удалена/закрыта/завершена)", taskId);
        pendingRepo.deleteById(taskId);
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
