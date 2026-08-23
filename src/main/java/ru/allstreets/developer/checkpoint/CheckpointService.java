package ru.allstreets.developer.checkpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.asekka.springai.agents.core.AgentContext;
import io.github.asekka.springai.agents.core.StateBag;
import io.github.asekka.springai.agents.core.StateKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

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
            // Сериализуем state с type metadata для корректной десериализации
            // StateBag не имеет entrySet(), конвертируем через Jackson в Map
            java.util.Map<String, Object> stateMap = objectMapper.convertValue(ctx.state(),
                    new com.fasterxml.jackson.core.type.TypeReference<>() {
                    });

            var entries = new java.util.ArrayList<java.util.Map<String, Object>>();
            for (var entry : stateMap.entrySet()) {
                var wrapped = new java.util.LinkedHashMap<String, Object>();
                wrapped.put("key", entry.getKey());
                wrapped.put("value", entry.getValue());
                wrapped.put("type", entry.getValue() != null ? entry.getValue().getClass().getName() : "java.lang.Object");
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
            for (var entry : entries) {
                String key = (String) entry.get("key");
                String typeName = (String) entry.get("type");
                Object rawValue = entry.get("value");

                Object typedValue = convertValue(rawValue, typeName);
                Class<?> keyClass = resolveTypeClass(typeName, typedValue);
                stateBag = putTyped(stateBag, key, keyClass, typedValue);
            }
            log.debug("Checkpoint восстановлен: runId={}, {} ключей в state", runId, entries.size());
            return AgentContext.empty().withState(stateBag);

        } catch (Exception e) {
            log.error("Ошибка десериализации state из checkpoint runId={}: {}", runId, e.getMessage());
            return null;
        }
    }

    /**
     * Конвертация значения в правильный тип на основе type metadata.
     * Jackson десериализует числа как Integer/Long/Double — нужно привести к исходному типу.
     */
    private Object convertValue(Object rawValue, String typeName) {
        if (rawValue == null || typeName == null) return rawValue;

        try {
            Class<?> targetClass = Class.forName(typeName);
            return switch (rawValue) {
                case Number n when targetClass == Integer.class -> n.intValue();
                case Number n when targetClass == Long.class -> n.longValue();
                case Number n when targetClass == Double.class -> n.doubleValue();
                case Number n when targetClass == Float.class -> n.floatValue();
                case Boolean b when targetClass == Boolean.class -> b;
                case String s when targetClass == String.class -> s;
                default ->
                    // Для сложных объектов — конвертируем через ObjectMapper
                        objectMapper.convertValue(rawValue, targetClass);
            };
        } catch (ClassNotFoundException e) {
            log.warn("Checkpoint restore: тип {} не найден, значение как есть", typeName);
            return rawValue;
        }
    }

    /**
     * Резолв Class из typeName для создания StateKey с правильным типом.
     * Если класс не найден — fallback на runtime-класс значения.
     */
    private Class<?> resolveTypeClass(String typeName, Object typedValue) {
        if (typeName != null) {
            try {
                return Class.forName(typeName);
            } catch (ClassNotFoundException e) {
                log.warn("Checkpoint restore: тип {} не найден, fallback на runtime-класс", typeName);
            }
        }
        return typedValue != null ? typedValue.getClass() : Object.class;
    }

    /**
     * Создание StateKey с правильным типом и put в StateBag.
     * Инкапсулирует raw-type операцию — компилятор не может вывести T из Class<?>.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private StateBag putTyped(StateBag stateBag, String key, Class<?> typeClass, Object value) {
        StateKey stateKey = StateKey.of(key, (Class) typeClass);
        return stateBag.put(stateKey, value);
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
