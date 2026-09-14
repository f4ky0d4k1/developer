package ru.allstreets.developer.opencode;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import ru.allstreets.developer.PostgresTestBase;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Полноценный e2e: {@link OpenCodeClient} против <b>реального</b> opencode sidecar
 * ({@code ghcr.io/anomalyco/opencode}) и <b>реальной штатной модели</b> DeepSeek
 * ({@code deepseek-v4-pro}).
 * <p>
 * В отличие от детерминированных {@link OpenCodeApiTest} / {@link OpenCodeClientTest}
 * (WireMock-стаб sidecar — там же остаются все негативные сценарии: timeout, empty,
 * ошибки, невалидный ответ), здесь проверяется реальный путь «наш клиент → opencode →
 * DeepSeek → ответ»: сессия создаётся, промпт доходит до модели, assistant-сообщение
 * завершается и содержит непустой текст, прогон персистится как {@code DONE}.
 * <p>
 * Тест <b>environment-gated</b>: запускается только при заданном {@code DEEPSEEK_API_KEY},
 * иначе JUnit его скипает — обычный {@code mvn test} не требует сети/ключа. Модель
 * переопределяется через {@code E2E_MODEL}. Ассерты только структурные: живая модель
 * недетерминирована, точный текст проверять нельзя.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
@Tag("e2e")
class OpenCodeClientRealSidecarIT extends PostgresTestBase {

    private static final Logger log = LoggerFactory.getLogger(OpenCodeClientRealSidecarIT.class);

    private static final String PROVIDER = "deepseek";

    /**
     * modelID DeepSeek; дефолт — штатная flash-модель, переопределяется через E2E_MODEL.
     */
    private static final String MODEL_ID =
            System.getenv().getOrDefault("E2E_MODEL", "deepseek-v4-pro");

    /**
     * Маркер, который просим вернуть модель, — доказательство, что промпт дошёл.
     */
    private static final String MARKER = "E2E_ANALYSIS_OK";

    /**
     * Содержимое файла для многошагового сценария: модель узнаёт его только через tool-call.
     */
    private static final String TOOL_MARKER = "E2E_TOOL_STEP_OK";

    private static GenericContainer<?> opencode;

    @Autowired
    private OpenCodeRunRepository runRepo;

    private OpenCodeApi api;
    private TaskProgressRegistry progress;

    // Контейнер живёт весь прогон класса (stop в @AfterAll) — try-with-resources неприменим.
    @BeforeAll
    @SuppressWarnings("resource")
    static void startContainer() {
        opencode = new GenericContainer<>("ghcr.io/anomalyco/opencode")
                .withExposedPorts(4096)
                .withCommand("serve", "--port", "4096", "--hostname", "0.0.0.0")
                .withEnv("OPENCODE_EXPERIMENTAL", "1")
                .withEnv("DEEPSEEK_API_KEY", System.getenv("DEEPSEEK_API_KEY"))
                .withWorkingDirectory("/work")
                .withCopyToContainer(Transferable.of(opencodeConfig()),
                        "/root/.config/opencode/opencode.jsonc")
                .withCopyToContainer(Transferable.of(testAnalystAgent()),
                        "/work/.opencode/agents/analyst.md")
                .withCopyToContainer(Transferable.of(TOOL_MARKER),
                        "/work/e2e-marker.txt")
                .waitingFor(Wait.forHttp("/global/health").forPort(4096).forStatusCode(200))
                .withStartupTimeout(Duration.ofSeconds(120));
        opencode.start();
    }

    @AfterAll
    static void stopContainer() {
        if (opencode != null) {
            System.out.println("===== opencode container logs =====");
            System.out.println(opencode.getLogs());
            opencode.stop();
        }
    }

    @BeforeEach
    void setUp() {
        api = new OpenCodeApi("http://localhost:" + opencode.getMappedPort(4096),
                PROVIDER + "/" + MODEL_ID);
        progress = Mockito.mock(TaskProgressRegistry.class);
    }

    private OpenCodeClient client() {
        // Живая модель отвечает медленнее стаба — бюджет/сталл увеличены.
        return new OpenCodeClient(api, runRepo, progress,
                new ru.allstreets.developer.metrics.TaskMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                180, 2, 180);
    }

    @Test
    void runAgent_completesAgainstRealModel() {
        log.info("E2E против реальной модели: {}/{}", PROVIDER, MODEL_ID);

        var result = client().runAgent("analyst",
                "Ответь ровно этой строкой, без пояснений и без вызова инструментов: " + MARKER,
                "/work", "task-e2e");

        assertNull(result.error(), "прогон против живой модели вернул ошибку: " + result.error());
        assertEquals("success", result.status());
        assertNotNull(result.sessionId(), "не создана/не возвращена сессия");
        assertNotNull(result.output(), "output == null");
        assertFalse(result.output().isBlank(), "живая модель вернула пустой текст");
        assertTrue(result.output().toUpperCase().contains(MARKER),
                "в ответе нет маркера — промпт не дошёл до модели? Ответ: " + result.output());

        var done = runRepo.findByTaskIdAndAgentNameAndStatusInOrderByStartedAtDesc(
                "task-e2e", "analyst", List.of(OpenCodeRunStatus.DONE));
        assertEquals(1, done.size(), "прогон не персистнулся как DONE");
        assertTrue(done.getFirst().isPromptSent());
        assertNotNull(done.getFirst().getOutput());
        assertFalse(done.getFirst().getOutput().isBlank());
    }

    /**
     * Многошаговый прогон: агент обязан сначала вызвать инструмент {@code read} (шаг с
     * {@code finish=tool-calls}, без text), и только затем дать финальный ответ. Регрессия
     * на инциденты 3c7b33db/fab06fb0: раньше брался первый (пустой/промежуточный) шаг.
     */
    @Test
    void runAgent_multiStepToolCall_returnsFinalAnswer() {
        log.info("E2E многошаговый tool-call: {}/{}", PROVIDER, MODEL_ID);

        var result = client().runAgent("analyst",
                "Прочитай файл /work/e2e-marker.txt инструментом read и ответь ровно его "
                        + "содержимым, без пояснений.",
                "/work", "task-e2e-tools");

        assertNull(result.error(), "прогон вернул ошибку: " + result.error());
        assertEquals("success", result.status());
        assertNotNull(result.output());
        assertFalse(result.output().isBlank(), "финальный шаг не собрался (взяли промежуточный tool-calls?)");
        assertTrue(result.output().toUpperCase().contains(TOOL_MARKER),
                "в ответе нет содержимого файла — финальный шаг не дождались? Ответ: " + result.output());
    }

    /**
     * Провайдер DeepSeek (тот же, что в {@code opencode-config/opencode.jsonc}). Ключ
     * подхватывается opencode из env-переменной {@code DEEPSEEK_API_KEY}, проброшенной
     * в контейнер, — как в проде.
     */
    private static String opencodeConfig() {
        return """
                {
                  "$schema": "https://opencode.ai/config.json",
                  "provider": {
                    "deepseek": {
                      "name": "DeepSeek",
                      "options": {
                        "baseURL": "https://api.deepseek.com"
                      },
                      "models": {
                        "%s": {}
                      }
                    }
                  }
                }
                """.formatted(MODEL_ID);
    }

    private static String testAnalystAgent() {
        return """
                ---
                description: Тестовый аналитик (e2e, реальная модель)
                mode: primary
                model: %s/%s
                ---
                
                Ты тестовый аналитик. Следуй инструкции пользователя буквально.
                Если просят прочитать файл — вызови инструмент read и используй его содержимое.
                Финальный ответ — одна короткая строка текста, без пояснений.
                """.formatted(PROVIDER, MODEL_ID);
    }
}
