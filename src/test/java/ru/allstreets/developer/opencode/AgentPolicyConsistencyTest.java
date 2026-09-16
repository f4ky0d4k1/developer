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
 * Разделение ответственности за Трекер: задачу создаёт аналитик, результат и доработки описывает
 * валидатор. Раньше правило было только у analyst/validator, а developer/tester о Трекере не знали.
 */
class AgentPolicyConsistencyTest {

    private static final Path AGENTS_DIR = Path.of("opencode-config", "agents");

    private static final List<String> AGENTS = List.of("analyst", "developer", "tester", "validator");

    private static final String TRACKER_HEADING = "РАЗДЕЛЕНИЕ ОТВЕТСТВЕННОСТИ ЗА TRACKER";
    private static final String ANALYST_CREATES = "создаёт и связывает АНАЛИТИК";
    private static final String VALIDATOR_DESCRIBES = "описывает ВАЛИДАТОР";
    private static final String DEV_TEST_STAY_OUT = "Трекер не трогают";

    @Test
    void everyAgentKnowsTrackerResponsibilitySplit() throws Exception {
        for (String agent : AGENTS) {
            String prompt = Files.readString(AGENTS_DIR.resolve(agent + ".md"));

            assertTrue(prompt.contains(TRACKER_HEADING),
                    agent + ".md должен содержать раздел «" + TRACKER_HEADING + "»");
            assertTrue(prompt.contains(ANALYST_CREATES),
                    agent + ".md должен знать: задачу в Трекере создаёт аналитик");
            assertTrue(prompt.contains(VALIDATOR_DESCRIBES),
                    agent + ".md должен знать: результат/доработки описывает валидатор");
            assertTrue(prompt.contains(DEV_TEST_STAY_OUT),
                    agent + ".md должен знать: developer и tester Трекер не трогают");
        }
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
