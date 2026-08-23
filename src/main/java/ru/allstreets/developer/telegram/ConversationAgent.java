package ru.allstreets.developer.telegram;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import ru.allstreets.developer.agents.AgentResponses;
import ru.allstreets.developer.agents.StructuredOutputHelper;
import ru.allstreets.developer.checkpoint.TaskRepository;
import ru.allstreets.developer.humanloop.HumanInputRegistry;
import ru.allstreets.developer.mcp.TaskMcpTools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Conversation Agent — оркестратор группового чата Telegram.
 * <p>
 * Fast mode: deepseek-v4-flash (дешёвая), умный промпт → LAUNCH_TASK / HITL_ANSWER / ANSWER / STATUS.
 * <p>
 * Видит sliding window истории чата и активные задачи с pending-вопросами.
 */
@Component
public class ConversationAgent {

    private static final Logger log = LoggerFactory.getLogger(ConversationAgent.class);

    private final ChatClient fastChatClient;
    private final ChatClient fallbackChatClient;
    private final ChatMemoryService chatMemory;
    private final ActiveTaskRegistry taskRegistry;
    private final HumanInputRegistry humanInputRegistry;
    private final StructuredOutputHelper structuredOutput;
    private final ObjectMapper objectMapper;
    private final String systemPrompt;
    private final TaskRepository taskRepo;
    private final TaskMcpTools taskMcpTools;

    public ConversationAgent(@Qualifier("fastChatClient") ChatClient fastChatClient,
                             @Qualifier("fallbackChatClient") ChatClient fallbackChatClient,
                             ChatMemoryService chatMemory,
                             ActiveTaskRegistry taskRegistry,
                             HumanInputRegistry humanInputRegistry,
                             StructuredOutputHelper structuredOutput,
                             ObjectMapper objectMapper,
                             ResourceLoader resourceLoader,
                             TaskRepository taskRepo,
                             TaskMcpTools taskMcpTools) {
        this.fastChatClient = fastChatClient;
        this.fallbackChatClient = fallbackChatClient;
        this.chatMemory = chatMemory;
        this.taskRegistry = taskRegistry;
        this.humanInputRegistry = humanInputRegistry;
        this.structuredOutput = structuredOutput;
        this.objectMapper = objectMapper;
        this.systemPrompt = loadSystemPrompt(resourceLoader);
        this.taskRepo = taskRepo;
        this.taskMcpTools = taskMcpTools;
    }

    private String loadSystemPrompt(ResourceLoader resourceLoader) {
        try {
            Resource resource = resourceLoader.getResource("classpath:prompts/conversation-fast.md");
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Не удалось загрузить conversation-fast.md: {}", e.getMessage());
            return "";
        }
    }

    public Decision processMessage(long chatId, String username, String messageText) {
        String history = chatMemory.getHistoryText(chatId);
        var activeTasks = taskRegistry.getActiveTasks(chatId);
        var pendingQuestions = humanInputRegistry.getPendingQuestionsForChat(chatId);

        String activeTasksStr = activeTasks.isEmpty() ? "нет активных задач"
                : activeTasks.entrySet().stream()
                .map(e -> {
                    String info = taskRepo.findById(e.getKey())
                            .map(t -> {
                                String title = t.getTitle() != null && !t.getTitle().isBlank() ? t.getTitle() : "";
                                String date = t.getCreatedAt() != null ? t.getCreatedAt().toString().substring(0, 16) : "";
                                return title.isEmpty() ? "" : " — " + title + (date.isEmpty() ? "" : " (" + date + ")");
                            })
                            .orElse("");
                    return e.getKey().substring(0, 8) + " → " + e.getValue() + info;
                })
                .reduce((a, b) -> a + "\n" + b)
                .orElse("нет активных задач");

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

        log.info("ConversationAgent [fast]: chatId={}, history={} сообщений, pending={} вопросов",
                chatId, chatMemory.getHistory(chatId).size(), pendingQuestions.size());

        try {
            // Вызов с tools — .content() чтобы tool calling работал
            String content;
            try {
                content = fastChatClient.prompt()
                        .user(contextPrompt)
                        .call()
                        .content();
            } catch (IllegalStateException e) {
                // LLM вызвала несуществующий tool (например LAUNCH_TASK как tool) — fallback без tools
                log.warn("ConversationAgent [fast]: tool error: {}, fallback без tools", e.getMessage());
                return structuredOutputFallback(contextPrompt);
            }

            if (content == null || content.isBlank()) {
                log.warn("ConversationAgent [fast]: пустой ответ LLM");
                return new Decision(Action.ERROR, null, null, "Пустой ответ LLM");
            }

            log.info("ConversationAgent [fast]: raw content (len={}): {}", content.length(),
                    content.length() > 500 ? content.substring(0, 500) + "..." : content);

            // Парсим JSON из ответа
            String json = structuredOutput.extractJson(content);
            AgentResponses.FastDecision fastResult;
            if (json != null) {
                try {
                    fastResult = objectMapper.readValue(json, AgentResponses.FastDecision.class);
                } catch (Exception e) {
                    log.warn("ConversationAgent [fast]: JSON parse failed: {}, fallback", e.getMessage());
                    fastResult = null;
                }
            } else {
                log.warn("ConversationAgent [fast]: JSON не найден в ответе");
                fastResult = null;
            }

            if (fastResult == null) {
                // Fallback: dешёвая модель без tools
                log.info("ConversationAgent [fast]: fallback на дешёвой модели");
                fastResult = structuredOutput.callWithFallback(
                        fallbackChatClient, null, contextPrompt,
                        AgentResponses.FastDecision.class);
            }

            if (fastResult == null) {
                log.warn("ConversationAgent [fast]: пустой ответ после fallback");
                return new Decision(Action.ERROR, null, null, "Пустой ответ LLM");
            }

            Action action = parseAction(fastResult.action());
            log.info("ConversationAgent [fast]: action={} taskId={} description='{}'",
                    fastResult.action(), fastResult.taskId(),
                    fastResult.description() != null ? (fastResult.description().length() > 80 ? fastResult.description().substring(0, 80) + "..." : fastResult.description()) : "null");

            return new Decision(action,
                    fastResult.taskId() != null ? fastResult.taskId() : "",
                    fastResult.text() != null ? fastResult.text() : "",
                    fastResult.description() != null ? fastResult.description() : "");

        } catch (Exception e) {
            log.error("ConversationAgent [fast]: ошибка: {}", e.getMessage(), e);
            return new Decision(Action.ERROR, null, null, "Ошибка LLM: " + e.getMessage());
        }
    }

    private Action parseAction(String actionStr) {
        if (actionStr == null) return Action.ANSWER;
        try {
            return Action.valueOf(actionStr.toUpperCase().replace("-", "_"));
        } catch (IllegalArgumentException e) {
            log.warn("ConversationAgent: неизвестный action '{}', defaulting to ANSWER", actionStr);
            return Action.ANSWER;
        }
    }

    private Decision structuredOutputFallback(String prompt) {
        String fullPrompt = systemPrompt + "\n\n" + prompt;
        AgentResponses.FastDecision result = structuredOutput.callWithFallback(
                fallbackChatClient, null, fullPrompt, AgentResponses.FastDecision.class);
        if (result == null) {
            return new Decision(Action.ERROR, null, null, "Пустой ответ LLM (fallback)");
        }
        Action action = parseAction(result.action());
        return new Decision(action,
                result.taskId() != null ? result.taskId() : "",
                result.text() != null ? result.text() : "",
                result.description() != null ? result.description() : "");
    }

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

    public enum Action {LAUNCH_TASK, HITL_ANSWER, ANSWER, STATUS, ERROR}

    public record Decision(Action action, String taskId, String text, String description) {
    }
}
