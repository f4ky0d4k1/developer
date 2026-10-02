package ru.allstreets.developer.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.web.client.RestClient;
import ru.allstreets.developer.mcp.GithubMcpTools;
import ru.allstreets.developer.mcp.SystemMcpTools;
import ru.allstreets.developer.mcp.TaskMcpTools;
import ru.allstreets.developer.mcp.TelegramTopicMcpTools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

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
     * {@code RestClient.Builder} с ограниченными таймаутами для прямых вызовов DeepSeek API.
     * Без таймаута зависший запрос навсегда блокирует поток Telegram-опроса (инцидент 14.09.2026).
     */
    private RestClient.Builder llmRestClientBuilder(Duration connectTimeout, Duration readTimeout) {
        return RestClient.builder()
                .requestFactory(OpenAiHttpClientFactory.requestFactory(connectTimeout, readTimeout))
                // Трассировка сырого ответа DeepSeek: без неё «Error while extracting response»
                // не оставлял ни статуса, ни тела — нельзя было понять 429/5xx/битый JSON это.
                .requestInterceptor(new LoggingClientHttpRequestInterceptor());
    }

    /**
     * {@code RetryTemplate} для OpenAiChatModel с логированием каждой неудачной попытки.
     * Spring Retry сам пишет только «Retry: count=N» (DEBUG, без исключения) — здесь добавляем throwable.
     */
    private org.springframework.retry.support.RetryTemplate llmRetryTemplate() {
        org.springframework.retry.support.RetryTemplate template = new org.springframework.retry.support.RetryTemplate();
        template.registerListener(new org.springframework.retry.RetryListener() {
            @Override
            public <T, E extends Throwable> void onError(org.springframework.retry.RetryContext context,
                                                         org.springframework.retry.RetryCallback<T, E> callback,
                                                         Throwable throwable) {
                log.warn("LLM HTTP retry (Spring): попытка {} провалилась — {}: {}",
                        context.getRetryCount(),
                        throwable != null ? throwable.getClass().getSimpleName() : "?",
                        throwable != null ? throwable.getMessage() : "?");
            }
        });
        return template;
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
            @org.springframework.beans.factory.annotation.Value("${spring.ai.openai.chat.options.temperature:0.3}") double temperature,
            @Value("${llm.http.connect-timeout:5s}") Duration connectTimeout,
            @Value("${llm.http.read-timeout:60s}") Duration readTimeout
    ) {
        log.info("Primary OpenAiChatModel: model={}, baseUrl={}", model, baseUrl);
        var openAiApi = org.springframework.ai.openai.api.OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .completionsPath("/chat/completions")
                .restClientBuilder(llmRestClientBuilder(connectTimeout, readTimeout))
                .build();
        return new org.springframework.ai.openai.OpenAiChatModel(
                openAiApi,
                org.springframework.ai.openai.OpenAiChatOptions.builder()
                        .model(model)
                        .temperature(temperature)
                        .build(),
                org.springframework.ai.model.tool.ToolCallingManager.builder().build(),
                llmRetryTemplate(),
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
     * ChatClient оркестратора чата (Telegram): анализирует сообщение в контексте чата и задач,
     * вызывает инструменты (launch_task/getChatHistory/…), возвращает JSON-решение.
     * Системный промпт: prompts/orchestrator.md.
     */
    @Bean("orchestratorChatClient")
    public ChatClient orchestratorChatClient(@Value("${orchestrator-model.model:deepseek-v4-pro}") String orchestratorModel,
                                             @Value("${orchestrator-model.api-key:}") String apiKey,
                                             @Value("${orchestrator-model.base-url:https://api.deepseek.com}") String baseUrl,
                                             GithubMcpTools githubTools,
                                             TaskMcpTools taskTools,
                                             SystemMcpTools systemTools,
                                             TelegramTopicMcpTools topicTools,
                                             @Value("${llm.http.connect-timeout:5s}") Duration connectTimeout,
                                             @Value("${llm.http.read-timeout:60s}") Duration readTimeout) {
        log.info("Orchestrator ChatClient: model={}, baseUrl={}, tools=github+task+system+forum-topics (no sendMessage)",
                orchestratorModel, baseUrl);
        var openAiApi = org.springframework.ai.openai.api.OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .completionsPath("/chat/completions")
                .restClientBuilder(llmRestClientBuilder(connectTimeout, readTimeout))
                .build();
        var chatModel = new org.springframework.ai.openai.OpenAiChatModel(
                openAiApi,
                org.springframework.ai.openai.OpenAiChatOptions.builder()
                        .model(orchestratorModel)
                        .temperature(0.1)
                        // reasoning-модель тратит токены на reasoning_content ДО content и склонна
                        // зацикливаться (reasoning по 8-30К токенов): при 2048/8192 content не оставался.
                        // 32768 — запас, чтобы рассуждения уложились и на JSON-ответ остались токены.
                        .maxTokens(32768)
                        .build(),
                org.springframework.ai.model.tool.ToolCallingManager.builder().build(),
                llmRetryTemplate(),
                io.micrometer.observation.ObservationRegistry.NOOP
        );
        return ChatClient.builder(chatModel)
                .defaultSystem(loadPrompt("prompts/orchestrator.md"))
                .defaultTools(githubTools, taskTools, systemTools, topicTools)
                .build();
    }

    /**
     * ChatClient с дешёвой моделью для fallback structured output.
     * Без MCP tools, без системного промпта — только JSON extraction из текста.
     */
    @Bean("fallbackChatClient")
    public ChatClient fallbackChatClient(
            @org.springframework.beans.factory.annotation.Value("${fallback-model.model:deepseek-v4-pro}") String fallbackModel,
            @org.springframework.beans.factory.annotation.Value("${fallback-model.api-key:}") String apiKey,
            @org.springframework.beans.factory.annotation.Value("${fallback-model.base-url:https://api.deepseek.com}") String baseUrl,
            @Value("${llm.http.connect-timeout:5s}") Duration connectTimeout,
            @Value("${llm.http.read-timeout:60s}") Duration readTimeout
    ) {
        log.info("Fallback ChatClient: model={}, baseUrl={}", fallbackModel, baseUrl);
        var openAiApi = org.springframework.ai.openai.api.OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .completionsPath("/chat/completions")
                .restClientBuilder(llmRestClientBuilder(connectTimeout, readTimeout))
                .build();
        var chatModel = new org.springframework.ai.openai.OpenAiChatModel(
                openAiApi,
                org.springframework.ai.openai.OpenAiChatOptions.builder()
                        .model(fallbackModel)
                        .temperature(0.1)
                        // reasoning-модель тратит токены на reasoning_content ДО content (см. orchestrator).
                        .maxTokens(32768)
                        .build(),
                org.springframework.ai.model.tool.ToolCallingManager.builder().build(),
                llmRetryTemplate(),
                io.micrometer.observation.ObservationRegistry.NOOP
        );
        return ChatClient.builder(chatModel).build();
    }
}
