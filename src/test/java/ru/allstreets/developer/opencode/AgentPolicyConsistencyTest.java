package ru.allstreets.developer.opencode;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Общие правила, которые обязаны знать ВСЕ агенты, должны быть продублированы в каждом промпте
 * ({@code opencode-config/agents/*.md}) — иначе знание есть у одного и отсутствует у другого.
 * <p>
 * Ролевая модель: аналитик создаёт задачу в Трекере и управляет запуском репортёра; репортёр пишет
 * весь текст в Трекере; тестировщик пишет тесты; разработчик — код; валидатор проверяет и не пишет
 * в Трекер. Раньше правило было размазано по двум промптам, а остальные агенты его не знали —
 * агент, не знающий границ, делает чужую работу.
 */
class AgentPolicyConsistencyTest {

    private static final Path AGENTS_DIR = Path.of("opencode-config", "agents");

    private static final List<String> AGENTS =
            List.of("analyst", "reporter", "tester", "developer", "validator");

    private static final String ROLE_MODEL_HEADING = "РОЛЕВАЯ МОДЕЛЬ";
    private static final String ANALYST_CREATES = "создаёт и связывает";
    private static final String ANALYST_LAUNCHES_REPORTER = "nextStep=reporter";
    private static final String REPORTER_OWNS_TEXT = "весь текст в Трекере";
    private static final String VALIDATOR_DOES_NOT_WRITE = "в Трекер не пишет";
    private static final String DEV_TEST_STAY_OUT = "Трекер не трогает";

    @Test
    void everyAgentKnowsRoleModel() throws Exception {
        for (String agent : AGENTS) {
            String prompt = Files.readString(AGENTS_DIR.resolve(agent + ".md"));

            assertTrue(prompt.contains(ROLE_MODEL_HEADING),
                    agent + ".md должен содержать раздел «" + ROLE_MODEL_HEADING + "»");
            assertTrue(prompt.contains(ANALYST_CREATES),
                    agent + ".md должен знать: задачу в Трекере создаёт аналитик");
            assertTrue(prompt.contains(ANALYST_LAUNCHES_REPORTER),
                    agent + ".md должен знать: запуском репортёра управляет аналитик");
            assertTrue(prompt.contains(REPORTER_OWNS_TEXT),
                    agent + ".md должен знать: весь текст в Трекере ведёт репортёр");
            assertTrue(prompt.contains(VALIDATOR_DOES_NOT_WRITE),
                    agent + ".md должен знать: валидатор в Трекер не пишет");
            assertTrue(prompt.contains(DEV_TEST_STAY_OUT),
                    agent + ".md должен знать: developer и tester Трекер не трогают");
        }
    }

    @Test
    void analystKnowsReporterNextStep() throws Exception {
        // Аналитик — единственный, кто ставит nextStep=reporter (управляет запуском репортёра).
        String prompt = Files.readString(AGENTS_DIR.resolve("analyst.md"));

        assertTrue(prompt.contains("\"reporter\""),
                "analyst.md должен описывать nextStep=reporter как запуск репортёра");
    }

    @Test
    void validatorKnowsDoneOutcome() throws Exception {
        // Исход «выполнено, PR не нужен» появился в решении валидатора (инцидент 427edb3c):
        // промпт агента должен его описывать так же, как промпт узла PostValidationNode.
        String prompt = Files.readString(AGENTS_DIR.resolve("validator.md"));

        assertTrue(prompt.contains("\"done\""),
                "validator.md должен описывать исход done (задача выполнена без PR)");
    }
}
