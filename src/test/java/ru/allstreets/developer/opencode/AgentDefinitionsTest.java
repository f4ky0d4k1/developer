package ru.allstreets.developer.opencode;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Консистентность: каждый агент, которого Java зовёт через {@code runAgent("name", ...)},
 * должен иметь определение {@code opencode-config/agents/name.md}. Иначе opencode принимает
 * {@code prompt_async} (204), но падает в фоне («prompt_async failed … Die(UnknownError)»),
 * сессия молчит → ложный stall (инцидент 0f9e5fa2: звали {@code post_validation}, а агент —
 * {@code validator}).
 */
class AgentDefinitionsTest {

    private static final Pattern RUN_AGENT = Pattern.compile("runAgent\\(\"([a-zA-Z0-9_]+)\"");

    @Test
    void everyAgentNameUsedInCodeHasDefinition() throws Exception {
        Path repoRoot = Path.of("").toAbsolutePath();
        Path agentsDir = repoRoot.resolve(Path.of("opencode-config", "agents"));
        Set<String> used = new TreeSet<>();

        try (Stream<Path> files = Files.walk(repoRoot.resolve(Path.of("src", "main", "java")))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = RUN_AGENT.matcher(Files.readString(file));
                while (m.find()) {
                    used.add(m.group(1));
                }
            }
        }

        assertFalse(used.isEmpty(), "не нашли ни одного runAgent(...) — тест сломан");
        for (String agent : used) {
            assertTrue(Files.exists(agentsDir.resolve(agent + ".md")),
                    "нет " + agentsDir.resolve(agent + ".md") + " для runAgent(\"" + agent + "\")");
        }
    }
}
