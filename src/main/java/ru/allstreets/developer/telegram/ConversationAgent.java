package ru.allstreets.developer.telegram;

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
    private final String systemPrompt;
    private final TaskRepository taskRepo;
    private final TaskMcpTools taskMcpTools;

    public ConversationAgent(@Qualifier("fastChatClient") ChatClient fastChatClient,
                             @Qualifier("fallbackChatClient") ChatClient fallbackChatClient,
                             ChatMemoryService chatMemory,
                             ActiveTaskRegistry taskRegistry,
                             HumanInputRegistry humanInputRegistry,
                             StructuredOutputHelper structuredOutput,
                             ResourceLoader resourceLoader,
                             TaskRepository taskRepo,
                             TaskMcpTools taskMcpTools) {
        this.fastChatClient = fastChatClient;
        this.fallbackChatClient = fallbackChatClient;
        this.chatMemory = chatMemory;
        this.taskRegistry = taskRegistry;
        this.humanInputRegistry = humanInputRegistry;
        this.structuredOutput = structuredOutput;
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
            // .entity() с tools — native structured output + tool calling
            AgentResponses.FastDecision fastResult;
            try {
                fastResult = fastChatClient.prompt()
                        .user(contextPrompt)
                        .call()
                        .entity(AgentResponses.FastDecision.class);
            } catch (Exception e) {
                // .entity() failed — tool error, JSON parse, network etc — fallback без tools
                log.warn("ConversationAgent [fast]: .entity() failed: {} | {}, fallback без tools",
                        e.getClass().getSimpleName(), e.getMessage());
                return structuredOutputFallback(contextPrompt);
            }

            if (fastResult == null) {
                // .entity() вернул null — fallback на дешёвую модель
                log.warn("ConversationAgent [fast]: .entity() вернул null, fallback");
                fastResult = structuredOutput.callWithFallback(
                        fallbackChatClient, fallbackChatClient, contextPrompt,
                        AgentResponses.FastDecision.class);
            }

            if (fastResult == null) {
                log.warn("ConversationAgent [fast]: пустой ответ после fallback");
                return new Decision(AgentResponses.FastAction.ERROR, null, null, "Пустой ответ LLM");
            }

            log.info("ConversationAgent [fast]: action={} taskId={} description='{}'",
                    fastResult.action(), fastResult.taskId(),
                    fastResult.description() != null ? (fastResult.description().length() > 80 ? fastResult.description().substring(0, 80) + "..." : fastResult.description()) : "null");

            return new Decision(fastResult.action(),
                    fastResult.taskId() != null ? fastResult.taskId() : "",
                    fastResult.text() != null ? fastResult.text() : "",
                    fastResult.description() != null ? fastResult.description() : "");

        } catch (Exception e) {
            log.error("ConversationAgent [fast]: ошибка: {}", e.getMessage(), e);
            return new Decision(AgentResponses.FastAction.ERROR, null, null, "Ошибка LLM: " + e.getMessage());
        }
    }

    private Decision structuredOutputFallback(String prompt) {
        String fullPrompt = systemPrompt + "\n\n" + prompt;
        log.info("ConversationAgent [fast]: structuredOutputFallback, prompt len={}", fullPrompt.length());
        AgentResponses.FastDecision result = structuredOutput.callWithFallback(
                fallbackChatClient, fallbackChatClient, fullPrompt, AgentResponses.FastDecision.class);
        if (result == null) {
            log.error("ConversationAgent [fast]: callWithFallback вернул null — обе модели не смогли дать JSON");
            return new Decision(AgentResponses.FastAction.ERROR, null, null, "Пустой ответ LLM (fallback)");
        }
        return new Decision(result.action(),
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

    public record Decision(AgentResponses.FastAction action, String taskId, String text, String description) {
    }
}
