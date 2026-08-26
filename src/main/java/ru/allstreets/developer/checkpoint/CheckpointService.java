package ru.allstreets.developer.checkpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.asekka.springai.agents.core.AgentContext;
import io.github.asekka.springai.agents.core.InterruptRequest;
import io.github.asekka.springai.agents.core.StateBag;
import io.github.asekka.springai.agents.core.StateKey;
import io.github.asekka.springai.agents.graph.Checkpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.allstreets.developer.state.StateKeyRegistry;

import java.util.*;

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
     * Сохранение checkpoint графа — используется {@link JpaCheckpointStore}.
     * Сохраняет весь {@link AgentContext} (state + messages), номер итерации графа
     * и причину interrupt (HITL-пауза), если есть.
     *
     * @param runId           ID запуска графа
     * @param nextNode        узел, с которого граф должен продолжить при resume
     * @param ctx             контекст агента (state + messages)
     * @param iterations      счётчик итераций графа (для лимита maxIterations)
     * @param interruptReason причина паузы (HITL-вопрос) или null
     */
    public void saveCheckpoint(String runId, String nextNode, AgentContext ctx, int iterations, String interruptReason) {
        try {
            var entries = new ArrayList<Map<String, Object>>();
            for (StateKey<?> key : ctx.state().keys()) {
                var wrapped = new LinkedHashMap<String, Object>();
                wrapped.put("key", key.name());
                wrapped.put("value", ctx.state().get(key));
                entries.add(wrapped);
            }

            var messages = new ArrayList<Map<String, String>>();
            for (Message m : ctx.messages()) {
                var wrapped = new LinkedHashMap<String, String>();
                wrapped.put("role", m.getMessageType().getValue());
                wrapped.put("text", m.getText());
                messages.add(wrapped);
            }

            var root = new LinkedHashMap<String, Object>();
            root.put("state", entries);
            root.put("messages", messages);
            String stateJson = objectMapper.writeValueAsString(root);
            String checkpointId = runId + "-" + nextNode + "-" + UUID.randomUUID().toString().substring(0, 8);

            CheckpointEntity entity = new CheckpointEntity(checkpointId, runId, nextNode, stateJson, "RUNNING",
                    iterations, interruptReason);
            repository.save(entity);

            log.debug("Checkpoint сохранён: runId={}, nextNode={}, iterations={}, interrupt={}",
                    runId, nextNode, iterations, interruptReason != null);

        } catch (Exception e) {
            log.error("Ошибка сериализации checkpoint для runId={}: {}", runId, e.getMessage());
        }
    }

    /**
     * Загрузка последнего checkpoint для runId в формате библиотеки agent-flow-graph.
     * Используется {@link JpaCheckpointStore#load(String)} для нативного resume.
     */
    public Optional<Checkpoint> loadCheckpoint(String runId) {
        var checkpoint = repository.findTopByRunIdOrderByCreatedAtDesc(runId);
        if (checkpoint.isEmpty()) {
            return Optional.empty();
        }
        var entity = checkpoint.get();
        AgentContext ctx = deserializeContext(entity, runId);
        if (ctx == null) {
            return Optional.empty();
        }
        InterruptRequest interrupt = entity.getInterruptReason() != null
                ? InterruptRequest.of(entity.getInterruptReason())
                : null;
        return Optional.of(new Checkpoint(runId, entity.getNodeName(), ctx,
                entity.getIterations(), interrupt));
    }

    /**
     * Восстановление контекста из последнего checkpoint для указанного runId.
     * Используется только для read-only просмотра (например, чтобы достать chatId
     * перед resume) — сам resume идёт через {@link JpaCheckpointStore}/{@code AgentGraph.resume}.
     *
     * @param runId ID запуска графа
     * @return восстановленный AgentContext или null если checkpoint не найден
     */
    public AgentContext restoreCheckpoint(String runId) {
        return loadCheckpoint(runId).map(Checkpoint::context).orElse(null);
    }

    private AgentContext deserializeContext(CheckpointEntity entity, String runId) {
        try {
            String json = entity.getStateJson();
            if (json == null || json.isBlank()) {
                log.warn("Checkpoint stateJson пустой для runId={}, пропуск", runId);
                return null;
            }

            String trimmed = json.trim();
            if (trimmed.startsWith("[")) {
                // Старый формат (только state, без messages) — оставлено для совместимости
                // с checkpoint-строками, сохранёнными до перехода на JpaCheckpointStore.
                return AgentContext.empty().withState(deserializeState(json, runId));
            }
            if (!trimmed.startsWith("{")) {
                log.warn("Checkpoint stateJson неизвестного формата для runId={}, пропуск", runId);
                return null;
            }

            var root = objectMapper.readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
            String stateJson = objectMapper.writeValueAsString(root.getOrDefault("state", List.of()));
            StateBag stateBag = deserializeState(stateJson, runId);

            @SuppressWarnings("unchecked")
            var rawMessages = (List<Map<String, String>>) root.getOrDefault("messages", List.of());
            List<Message> messages = new ArrayList<>();
            for (var m : rawMessages) {
                messages.add(toMessage(m.get("role"), m.get("text")));
            }

            return AgentContext.of(messages.toArray(new Message[0])).withState(stateBag);

        } catch (Exception e) {
            log.error("Ошибка десериализации checkpoint runId={}: {}", runId, e.getMessage());
            return null;
        }
    }

    private Message toMessage(String role, String text) {
        MessageType type = role != null ? MessageType.fromValue(role) : MessageType.USER;
        return switch (type) {
            case ASSISTANT -> new AssistantMessage(text);
            case SYSTEM -> new SystemMessage(text);
            default -> new UserMessage(text);
        };
    }

    private StateBag deserializeState(String stateJson, String runId) throws Exception {
        var entries = objectMapper.readValue(stateJson,
                new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {
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
        log.debug("Checkpoint state восстановлен: runId={}, {} ключей ({} пропущено)", runId, entries.size(), skipped);
        return stateBag;
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
