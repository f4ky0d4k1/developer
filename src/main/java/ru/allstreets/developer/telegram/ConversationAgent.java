package ru.allstreets.developer.telegram;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;
import ru.allstreets.developer.agents.AgentResponses;
import ru.allstreets.developer.checkpoint.TaskEntity;
import ru.allstreets.developer.humanloop.HumanInputRegistry;
import ru.allstreets.developer.mcp.TaskMcpTools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conversation Agent — оркестратор группового чата Telegram.
 * <p>
 * Оркестратор чата: deepseek-v4-pro, умный промпт → HITL_ANSWER / ANSWER / STATUS.
 * Запуск задачи — инструмент {@code launch_task} (repo обязателен в схеме тула), не action.
 * <p>
 * Имеет доступ к чату (история, pending-вопросы), текущим задачам и инструментам —
 * анализирует сообщение, вызывает тулы и возвращает JSON-решение.
 */
@Component
public class ConversationAgent {

    private static final Logger log = LoggerFactory.getLogger(ConversationAgent.class);

    /**
     * Сколько задач чата кладём в контекст классификатора за раз (остальное — через getChatTasks).
     */
    private static final int TASKS_PAGE_SIZE = 10;

    /**
     * Жёсткое требование JSON в финальном ответе. Без structured output (.entity) модель
     * после тул-вызовов склонна отвечать прозой вместо JSON-решения — это требование
     * возвращает её в контракт. Дублирует контракт из orchestrator.md, но в последнем
     * user-сообщении (сильнее, чем системный промпт).
     */
    private static final String JSON_OUTPUT_INSTRUCTION = """
            
            ============================================================
            ФИНАЛЬНЫЙ ОТВЕТ — РОВНО ОДИН JSON-ОБЪЕКТ, без markdown-фенсов
            и без текста до/после. Свой ответ оберни в поле text:
            {"action":"ANSWER","taskId":null,"text":"твой ответ","description":null,"options":null}
            action: HITL_ANSWER | ANSWER | STATUS | ERROR.
            taskId: только для HITL_ANSWER (id из pending-вопроса), иначе null.
            text: обязателен для ANSWER и HITL_ANSWER, null для STATUS.
            options: список строк только для ANSWER с кнопками, иначе null.
            ============================================================
            """;

    private final ChatClient orchestratorChatClient;
    private final ChatClient fallbackChatClient;
    private final ChatMemoryService chatMemory;
    private final ActiveTaskRegistry taskRegistry;
    private final HumanInputRegistry humanInputRegistry;
    private final String systemPrompt;
    private final TaskMcpTools taskMcpTools;

    public ConversationAgent(@Qualifier("orchestratorChatClient") ChatClient orchestratorChatClient,
                             @Qualifier("fallbackChatClient") ChatClient fallbackChatClient,
                             ChatMemoryService chatMemory,
                             ActiveTaskRegistry taskRegistry,
                             HumanInputRegistry humanInputRegistry,
                             ResourceLoader resourceLoader,
                             TaskMcpTools taskMcpTools) {
        this.orchestratorChatClient = orchestratorChatClient;
        this.fallbackChatClient = fallbackChatClient;
        this.chatMemory = chatMemory;
        this.taskRegistry = taskRegistry;
        this.humanInputRegistry = humanInputRegistry;
        this.systemPrompt = loadSystemPrompt(resourceLoader);
        this.taskMcpTools = taskMcpTools;
    }

    private String loadSystemPrompt(ResourceLoader resourceLoader) {
        try {
            Resource resource = resourceLoader.getResource("classpath:prompts/orchestrator.md");
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Не удалось загрузить orchestrator.md: {}", e.getMessage());
            return "";
        }
    }

    public Decision processMessage(long chatId, String username, String messageText) {
        String history = chatMemory.getHistoryText(chatId);
        Page<TaskEntity> taskPage = taskRegistry.getChatTasksPage(chatId, 0, TASKS_PAGE_SIZE);
        var pendingQuestions = humanInputRegistry.getPendingQuestionsForChat(chatId);

        String activeTasksStr = formatTasksPage(taskPage);

        String pendingStr = pendingQuestions.isEmpty() ? "нет pending-вопросов"
                : pendingQuestions.entrySet().stream()
                .map(e -> "task " + e.getKey() + ": " + e.getValue())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("нет pending-вопросов");

        // Pre-fetch: если пользователь упоминает taskId и спрашивает про детали — подтягиваем getTaskDetails
        String taskDetailsPrefetch = prefetchTaskDetails(messageText);

        String contextPrompt = """
                Chat ID: %d (используй для вызова tools)
                
                История чата:
                %s
                
                Активные задачи:
                %s
                
                Pending-вопросы от агентов (ожидают ответа):
                %s
                %s
                Новое сообщение от пользователя %s:
                %s
                """.formatted(chatId, history, activeTasksStr, pendingStr,
                taskDetailsPrefetch != null ? taskDetailsPrefetch + "\n" : "",
                username, messageText);

        log.info("ConversationAgent [orchestrator]: chatId={}, history={} сообщений, pending={} вопросов",
                chatId, chatMemory.getHistory(chatId).size(), pendingQuestions.size());

        try {
            // Основной путь: тулы (launch_task/getChatHistory/…) исполняются через .call().
            // .entity() несовместим с tool calling (spring-ai #4799, #6327): тул-вызов
            // приходит с пустым content, и BeanOutputConverter парсит его как JSON →
            // «No content to map». Поэтому берём .content() (тул-луп отрабатывает) и
            // разбираем JSON-решение детерминированно, как AnalystNode.parseDecision.
            String content;
            try {
                content = orchestratorChatClient.prompt()
                        .user(contextPrompt + JSON_OUTPUT_INSTRUCTION)
                        .toolContext(Map.of(
                                "username", username != null ? username.toLowerCase() : "",
                                "chatId", chatId))
                        .call()
                        .content();
            } catch (Exception e) {
                // .call() failed — tool error, network etc — fallback без tools
                log.warn("ConversationAgent [orchestrator]: .call() failed: {} | {}, fallback без tools",
                        e.getClass().getSimpleName(), e.getMessage());
                return structuredOutputFallback(contextPrompt);
            }

            Decision decision = decisionFromContent(content);
            if (decision != null) {
                log.info("ConversationAgent [orchestrator]: action={} taskId={}", decision.action(), decision.taskId());
                return decision;
            }

            log.warn("ConversationAgent [orchestrator]: пустой ответ, fallback без tools");
            return structuredOutputFallback(contextPrompt);

        } catch (Exception e) {
            log.error("ConversationAgent [orchestrator]: ошибка: {}", e.getMessage(), e);
            return new Decision(AgentResponses.OrchestratorAction.ERROR, null, null, "Ошибка LLM: " + e.getMessage(), null);
        }
    }

    private Decision structuredOutputFallback(String prompt) {
        String fullPrompt = systemPrompt + "\n\n" + prompt;
        log.info("ConversationAgent [orchestrator]: structuredOutputFallback, prompt len={}", fullPrompt.length());
        try {
            String content = fallbackChatClient.prompt().user(fullPrompt + JSON_OUTPUT_INSTRUCTION).call().content();
            Decision decision = decisionFromContent(content);
            if (decision != null) {
                log.info("ConversationAgent [orchestrator]: fallback action={}", decision.action());
                return decision;
            }
            log.error("ConversationAgent [orchestrator]: обе модели вернули пустой ответ, fallback content={}",
                    preview(content));
            return new Decision(AgentResponses.OrchestratorAction.ERROR, null, null,
                    "Не удалось получить ответ от модели — попробуй ещё раз", null);
        } catch (Exception e) {
            log.error("ConversationAgent [orchestrator]: fallback ошибка: {}", e.getMessage(), e);
            return new Decision(AgentResponses.OrchestratorAction.ERROR, null, null, "Ошибка LLM: " + e.getMessage(), null);
        }
    }

    /**
     * Ответ модели → решение: валидный JSON → его action; не-JSON, но непустой текст → ANSWER
     * с этим текстом (graceful degradation — лучше показать ответ прозой, чем упасть с ошибкой);
     * пусто/null → null (сигнал «попробовать фолбэк»).
     */
    private Decision decisionFromContent(String content) {
        AgentResponses.OrchestratorDecision parsed = parseOrchestratorDecision(content);
        if (parsed != null) {
            return toDecision(parsed);
        }
        if (content != null && !content.isBlank()) {
            log.warn("ConversationAgent [orchestrator]: модель вернула {} символов прозы вместо JSON — отдаём как ANSWER: {}",
                    content.length(), preview(content));
            return new Decision(AgentResponses.OrchestratorAction.ANSWER, null, content, null, null);
        }
        return null;
    }

    /**
     * Однострочное превью ответа модели для логов — чтобы при разборе инцидента был виден
     * реальный текст (JSON или проза), а не только длина. Полный ответ — в HTTP-трейсе интерцептора.
     */
    private static String preview(String text) {
        if (text == null) {
            return "null";
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 300 ? oneLine.substring(0, 300) + "…" : oneLine;
    }

    private static Decision toDecision(AgentResponses.OrchestratorDecision result) {
        return new Decision(result.action(),
                result.taskId() != null ? result.taskId() : "",
                result.text() != null ? result.text() : "",
                result.description() != null ? result.description() : "",
                result.options());
    }

    /**
     * Детерминированный разбор JSON-решения из финального ответа оркестратора.
     * Перебираем fenced-блоки и последний сбалансированный {@code {...}} и берём первый,
     * который парсится как {@link AgentResponses.OrchestratorDecision} с ненулевым {@code action}.
     */
    private AgentResponses.OrchestratorDecision parseOrchestratorDecision(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        for (String candidate : jsonCandidates(content)) {
            try {
                AgentResponses.OrchestratorDecision parsed = JSON_MAPPER.readValue(candidate, AgentResponses.OrchestratorDecision.class);
                if (parsed.action() != null) {
                    return parsed;
                }
            } catch (Exception e) {
                log.debug("ConversationAgent: кандидат JSON не распарсен: {}", e.getMessage());
            }
        }
        return null;
    }

    private static List<String> jsonCandidates(String text) {
        var candidates = new ArrayList<String>();
        Matcher fence = JSON_FENCE.matcher(text);
        while (fence.find()) {
            String body = fence.group(1).trim();
            if (body.startsWith("{")) {
                candidates.add(body);
            }
        }
        String balanced = lastBalancedObject(text);
        if (balanced != null) {
            candidates.add(balanced);
        }
        return candidates;
    }

    /**
     * Последний сбалансированный JSON-объект в тексте (учёт строк и экранирования).
     */
    private static String lastBalancedObject(String text) {
        int start = text.lastIndexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private static final Pattern JSON_FENCE = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```");

    private static final ObjectMapper JSON_MAPPER = JsonMapper.builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .build();

    private static final java.util.regex.Pattern TASK_ID_PATTERN =
            java.util.regex.Pattern.compile("\\b([0-9a-fA-F]{8})\\b");
    private static final java.util.regex.Pattern DETAILS_KEYWORDS =
            java.util.regex.Pattern.compile("подробн|детал|статус|что по|как дела|что там|результат|прогресс", java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * Если пользователь упоминает taskId (8 hex chars) и спрашивает про детали/статус —
     * pre-fetch getTaskDetails и вставляем в контекст, чтобы LLM не нужно было вызывать tool.
     */
    private String prefetchTaskDetails(String messageText) {
        if (messageText == null || messageText.isBlank()) return null;

        var idMatcher = TASK_ID_PATTERN.matcher(messageText);
        if (!idMatcher.find()) return null;

        if (!DETAILS_KEYWORDS.matcher(messageText).find()) return null;

        String partialId = idMatcher.group(1);
        log.info("ConversationAgent: pre-fetch getTaskDetails для taskId={}", partialId);
        try {
            String details = taskMcpTools.getTaskDetails(partialId);
            if (details != null && !details.startsWith("Task not found")) {
                return "Детали задачи " + partialId + " (pre-fetched):\n" + details;
            }
        } catch (Exception e) {
            log.warn("ConversationAgent: pre-fetch getTaskDetails failed: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Форматирует страницу задач чата для контекста классификатора — без N+1.
     */
    private static String formatTasksPage(Page<TaskEntity> page) {
        if (page.isEmpty()) {
            return "нет задач";
        }
        StringBuilder sb = new StringBuilder();
        for (TaskEntity t : page.getContent()) {
            sb.append(shortId(t.getTaskId())).append(" → ").append(t.getStatus());
            String title = t.getTitle();
            if (title != null && !title.isBlank()) {
                sb.append(" — ").append(title);
            }
            if (t.getCreatedAt() != null) {
                sb.append(" (").append(t.getCreatedAt().toString(), 0, 16).append(")");
            }
            sb.append("\n");
        }
        long more = page.getTotalElements() - page.getNumberOfElements();
        if (more > 0) {
            sb.append("(+").append(more).append(" more — вызови getChatTasks(chatId, page, perPage))\n");
        }
        return sb.toString();
    }

    private static String shortId(String id) {
        return id != null && id.length() > 8 ? id.substring(0, 8) : id;
    }

    public record Decision(AgentResponses.OrchestratorAction action, String taskId, String text, String description,
                           java.util.List<String> options) {
    }
}
