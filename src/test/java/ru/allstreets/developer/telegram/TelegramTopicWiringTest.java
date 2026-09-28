package ru.allstreets.developer.telegram;

import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import ru.allstreets.developer.checkpoint.TaskRepository;

import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Регрессия на циклическую зависимость бинов {@code TelegramGateway ⇄ TelegramTopicService}
 * (BACKEND-443, инцидент 27.09.2026 — приложение падало при старте с «beans form a cycle»).
 * <p>
 * Контекст из двух бинов обязан подниматься без ошибки: gateway зависит от резолвера
 * через {@code @Lazy} (прокси), а topicService — от gateway по конструктору. Если цикл
 * вернуть, {@code ApplicationContextRunner} провалится с
 * {@code UnsatisfiedDependencyException ... unresolvable circular reference}.
 */
class TelegramTopicWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(RateLimiterRegistry.class, RateLimiterRegistry::ofDefaults)
            .withBean(ChatMemoryService.class, () -> mock(ChatMemoryService.class))
            .withBean(ActiveTaskRegistry.class, () -> mock(ActiveTaskRegistry.class))
            .withBean(ReplyAnchorRegistry.class, () -> mock(ReplyAnchorRegistry.class))
            .withBean(TaskRepository.class, () -> mock(TaskRepository.class))
            .withBean("fallbackChatClient", ChatClient.class, () -> mock(ChatClient.class))
            .withUserConfiguration(TelegramGateway.class, TelegramTopicService.class)
            .withPropertyValues("telegram.bot-token=test-token");

    @Test
    void contextLoadsWithoutCircularReference() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(TelegramGateway.class)).isNotNull();
            assertThat(context.getBean(TelegramTopicService.class)).isNotNull();
            assertThat(ReflectionTestUtils.getField(context.getBean(TelegramGateway.class), "topicService"))
                    .as("@Lazy прокси резолвера обязан быть внедрён в gateway")
                    .isNotNull();
        });
    }
}
