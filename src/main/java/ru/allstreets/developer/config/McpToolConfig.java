package ru.allstreets.developer.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Конфигурация ChatClient бинов для Spring AI.
 * MCP инструменты (GitHub, Yandex Tracker, Grafana) теперь работают через OpenCode sidecar.
 * Системные промпты загружаются из src/main/resources/prompts/.
 */
@Configuration
public class McpToolConfig {

    private static final Logger log = LoggerFactory.getLogger(McpToolConfig.class);

    private final ResourceLoader resourceLoader;

    public McpToolConfig(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    /**
     * Загрузка текстового ресурса из classpath.
     */
    private String loadPrompt(String path) {
        try {
            Resource resource = resourceLoader.getResource("classpath:" + path);
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Не удалось загрузить промпт {}: {}", path, e.getMessage());
            throw new RuntimeException("Не удалось загрузить промпт: " + path, e);
        }
    }

    /**
     * Primary OpenAiChatModel для GLM API.
     * GLM использует /chat/completions (без /v1/), поэтому переопределяем auto-config bean.
     */
    @Bean
    @org.springframework.context.annotation.Primary
    public org.springframework.ai.openai.OpenAiChatModel openAiChatModel(
            @org.springframework.beans.factory.annotation.Value("${spring.ai.openai.api-key}") String apiKey,
            @org.springframework.beans.factory.annotation.Value("${spring.ai.openai.base-url:https://api.deepseek.com}") String baseUrl,
            @org.springframework.beans.factory.annotation.Value("${spring.ai.openai.chat.options.model:deepseek-v4-pro}") String model,
            @org.springframework.beans.factory.annotation.Value("${spring.ai.openai.chat.options.temperature:0.3}") double temperature
    ) {
        log.info("Primary OpenAiChatModel: model={}, baseUrl={}", model, baseUrl);
        var openAiApi = org.springframework.ai.openai.api.OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .completionsPath("/chat/completions")
                .build();
        return new org.springframework.ai.openai.OpenAiChatModel(
                openAiApi,
                org.springframework.ai.openai.OpenAiChatOptions.builder()
                        .model(model)
                        .temperature(temperature)
                        .build(),
                org.springframework.ai.model.tool.ToolCallingManager.builder().build(),
                new org.springframework.retry.support.RetryTemplate(),
                io.micrometer.observation.ObservationRegistry.NOOP
        );
    }

    /**
     * ChatClient с MCP tool callbacks для аналитика.
     * Системный промпт: prompts/analyst-system.txt
     */
    @Bean("analystChatClient")
    public ChatClient analystChatClient(ChatClient.Builder builder) {
        log.info("analystChatClient: создан без MCP tools (аналитик работает через OpenCode)");
        return builder
                .defaultSystem(loadPrompt("prompts/analyst-system.md"))
                .build();
    }

    /**
     * ChatClient для self-learning (генерация инсайтов из фидбека).
     * Системный промпт: prompts/learning-system.txt
     */
    @Bean("learningChatClient")
    public ChatClient learningChatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem(loadPrompt("prompts/learning-system.md"))
                .build();
    }

    /**
     * ChatClient с MCP tool callbacks для post-validation оркестратора.
     * LLM сама решает: создать PR, вернуться к разработчику/аналитику/тестировщику.
     * Системный промпт: prompts/post-validation-system.md
     */
    @Bean("postValidationChatClient")
    public ChatClient postValidationChatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem(loadPrompt("prompts/post-validation-system.md"))
                .build();
    }

    /**
     * ChatClient для fast mode — быстрый классификатор на дешёвой модели.
     * Системный промпт: prompts/conversation-fast.md.
     */
    @Bean("fastChatClient")
    public ChatClient fastChatClient(
            @org.springframework.beans.factory.annotation.Value("${fast-model.model:deepseek-v4-flash}") String fastModel,
            @org.springframework.beans.factory.annotation.Value("${fast-model.api-key:}") String apiKey,
            @org.springframework.beans.factory.annotation.Value("${fast-model.base-url:https://api.deepseek.com}") String baseUrl,
            ru.allstreets.developer.mcp.TelegramMcpTools telegramTools,
            ru.allstreets.developer.mcp.GithubMcpTools githubTools,
            ru.allstreets.developer.mcp.TaskMcpTools taskTools
    ) {
        log.info("Fast ChatClient: model={}, baseUrl={}, tools=telegram+github+task", fastModel, baseUrl);
        var openAiApi = org.springframework.ai.openai.api.OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .completionsPath("/chat/completions")
                .build();
        var chatModel = new org.springframework.ai.openai.OpenAiChatModel(
                openAiApi,
                org.springframework.ai.openai.OpenAiChatOptions.builder()
                        .model(fastModel)
                        .temperature(0.1)
                        .build(),
                org.springframework.ai.model.tool.ToolCallingManager.builder().build(),
                new org.springframework.retry.support.RetryTemplate(),
                io.micrometer.observation.ObservationRegistry.NOOP
        );
        return ChatClient.builder(chatModel)
                .defaultSystem(loadPrompt("prompts/conversation-fast.md"))
                .defaultTools(telegramTools, githubTools, taskTools)
                .build();
    }

    /**
     * ChatClient с дешёвой моделью для fallback structured output.
     * Без MCP tools, без системного промпта — только JSON extraction из текста.
     */
    @Bean("fallbackChatClient")
    public ChatClient fallbackChatClient(
            @org.springframework.beans.factory.annotation.Value("${fallback-model.model:deepseek-v4-flash}") String fallbackModel,
            @org.springframework.beans.factory.annotation.Value("${fallback-model.api-key:}") String apiKey,
            @org.springframework.beans.factory.annotation.Value("${fallback-model.base-url:https://api.deepseek.com}") String baseUrl
    ) {
        log.info("Fallback ChatClient: model={}, baseUrl={}", fallbackModel, baseUrl);
        var openAiApi = org.springframework.ai.openai.api.OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .completionsPath("/chat/completions")
                .build();
        var chatModel = new org.springframework.ai.openai.OpenAiChatModel(
                openAiApi,
                org.springframework.ai.openai.OpenAiChatOptions.builder()
                        .model(fallbackModel)
                        .temperature(0.1)
                        .build(),
                org.springframework.ai.model.tool.ToolCallingManager.builder().build(),
                new org.springframework.retry.support.RetryTemplate(),
                io.micrometer.observation.ObservationRegistry.NOOP
        );
        return ChatClient.builder(chatModel).build();
    }
}
