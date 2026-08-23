package ru.allstreets.developer.checkpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.asekka.springai.agents.core.AgentContext;
import io.github.asekka.springai.agents.core.StateBag;
import io.github.asekka.springai.agents.core.StateKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.allstreets.developer.state.StateKeyRegistry;

import java.util.List;
import java.util.UUID;

/**
 * Сервис checkpoint store — сохранение и восстановление состояния агентного графа.
 * Позволяет возобновить выполнение после краха приложения.
 * <p>
 * Сохраняет снимок state после каждого узла графа.
 * При запуске приложения проверяет незавершённые задачи и возобновляет их.
 */
@Service
public class CheckpointService {

    private static final Logger log = LoggerFactory.getLogger(CheckpointService.class);

    private final CheckpointRepository repository;
    private final ObjectMapper objectMapper;

    public CheckpointService(CheckpointRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * Сохранение checkpoint после выполнения узла графа.
     *
     * @param runId    ID запуска графа
     * @param nodeName имя выполненного узла
     * @param ctx      контекст агента (state)
     * @param status   статус: RUNNING, COMPLETED, FAILED
     */
    public void saveCheckpoint(String runId, String nodeName, AgentContext ctx, String status) {
        try {
            // Итерируем реальные StateKey из StateBag — имя ключа известно точно,
            // тип при восстановлении берётся из StateKeyRegistry (см. restoreCheckpoint),
            // поэтому рантайм-класс значения здесь не нужен.
            var entries = new java.util.ArrayList<java.util.Map<String, Object>>();
            for (StateKey<?> key : ctx.state().keys()) {
                var wrapped = new java.util.LinkedHashMap<String, Object>();
                wrapped.put("key", key.name());
                wrapped.put("value", ctx.state().get(key));
                entries.add(wrapped);
            }
            String stateJson = objectMapper.writeValueAsString(entries);
            String checkpointId = runId + "-" + nodeName + "-" + UUID.randomUUID().toString().substring(0, 8);

            CheckpointEntity entity = new CheckpointEntity(checkpointId, runId, nodeName, stateJson, status);
            repository.save(entity);

            log.debug("Checkpoint сохранён: runId={}, node={}, status={}", runId, nodeName, status);

        } catch (Exception e) {
            log.error("Ошибка сериализации state для checkpoint: {}", e.getMessage());
        }
    }

    /**
     * Восстановление контекста из последнего checkpoint для указанного runId.
     *
     * @param runId ID запуска графа
     * @return восстановленный AgentContext или null если checkpoint не найден
     */
    public AgentContext restoreCheckpoint(String runId) {
        var checkpoint = repository.findTopByRunIdOrderByCreatedAtDesc(runId);
        if (checkpoint.isEmpty()) {
            log.warn("Checkpoint не найден для runId={}", runId);
            return null;
        }

        var entity = checkpoint.get();
        log.info("Восстановление из checkpoint: runId={}, node={}, status={}",
                runId, entity.getNodeName(), entity.getStatus());

        try {
            String json = entity.getStateJson();
            if (json == null || json.isBlank()) {
                log.warn("Checkpoint stateJson пустой для runId={}, пропуск", runId);
                return null;
            }

            // Проверяем формат: ожидаем массив [{key, value, type}, ...]
            String trimmed = json.trim();
            if (!trimmed.startsWith("[")) {
                log.warn("Checkpoint stateJson не в формате массива для runId={} (начинается с '{}'), пропуск. " +
                                "Возможно старый формат — очистите таблицу agent_checkpoints.",
                        runId, trimmed.length() > 20 ? trimmed.substring(0, 20) : trimmed);
                return null;
            }

            var entries = objectMapper.readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.List<java.util.Map<String, Object>>>() {
                    });

            StateBag stateBag = StateBag.empty();
            int skipped = 0;
            for (var entry : entries) {
                String name = (String) entry.get("key");
                Object rawValue = entry.get("value");
                if (rawValue == null) continue;

                StateKey<?> key = StateKeyRegistry.byName(name);
                if (key == null) {
                    log.warn("Checkpoint restore: неизвестный ключ '{}' (устарел/переименован) для runId={} — пропуск", name, runId);
                    skipped++;
                    continue;
                }

                var javaType = objectMapper.getTypeFactory().constructType(StateKeyRegistry.genericType(name));
                Object typedValue = objectMapper.convertValue(rawValue, javaType);
                stateBag = putTyped(stateBag, key, typedValue);
            }
            log.debug("Checkpoint восстановлен: runId={}, {} ключей в state ({} пропущено)", runId, entries.size(), skipped);
            return AgentContext.empty().withState(stateBag);

        } catch (Exception e) {
            log.error("Ошибка десериализации state из checkpoint runId={}: {}", runId, e.getMessage());
            return null;
        }
    }

    /**
     * Put в StateBag с правильным типом из {@link StateKeyRegistry}.
     * Инкапсулирует raw-type операцию — компилятор не может вывести T из StateKey<?>.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private StateBag putTyped(StateBag stateBag, StateKey key, Object value) {
        return stateBag.put(key, value);
    }

    /**
     * Получение имени последнего выполненного узла для runId.
     * Используется для определения точки возобновления графа.
     */
    public String getLastNodeName(String runId) {
        return repository.findTopByRunIdOrderByCreatedAtDesc(runId)
                .map(CheckpointEntity::getNodeName)
                .orElse(null);
    }

    /**
     * Получение последнего checkpoint для runId.
     * Используется для проверки наличия checkpoint перед restart.
     */
    public CheckpointEntity getLatestCheckpoint(String runId) {
        return repository.findTopByRunIdOrderByCreatedAtDesc(runId).orElse(null);
    }

    /**
     * Очистка checkpoint после успешного завершения графа.
     */
    @org.springframework.transaction.annotation.Transactional
    public void cleanup(String runId) {
        log.info("Очистка checkpoint для runId={}", runId);
        repository.deleteByRunId(runId);
    }

    /**
     * Получение всех незавершённых задач (status = RUNNING).
     * Запускается при старте приложения для возобновления.
     */
    public List<CheckpointEntity> getUnfinishedCheckpoints() {
        return repository.findByStatus("RUNNING");
    }

    /**
     * Плановая очистка старых checkpoint (старше 7 дней).
     */
    @Scheduled(cron = "0 0 3 * * *")  // каждый день в 3:00
    @org.springframework.transaction.annotation.Transactional
    public void cleanupOldCheckpoints() {
        var cutoff = java.time.Instant.now().minus(7, java.time.temporal.ChronoUnit.DAYS);
        repository.deleteByCreatedAtBefore(cutoff);
        log.info("Плановая очистка старых checkpoint (старше 7 дней) выполнена");
    }
}
