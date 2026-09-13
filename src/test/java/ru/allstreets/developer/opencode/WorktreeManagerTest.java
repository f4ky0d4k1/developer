package ru.allstreets.developer.opencode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link WorktreeManager#prepareSlot}:
 * <ul>
 *   <li>не запускает агента в пустой директории без репозитория (fail-fast по TARGET_REPO);</li>
 *   <li>нейтрализует проектный {@code opencode.json} репозитория (в нём могла быть ссылка
 *       {@code {file:./.secrets/...}} → createSession HTTP 400) и делает подмену некоммитируемой.</li>
 * </ul>
 */
class WorktreeManagerTest {

    @TempDir
    Path workDir;

    private static final String TRACKED_REPO_CONFIG =
            "{\"provider\":{\"deepseek\":{\"options\":{\"apiKey\":\"{file:./.secrets/yandex-token}\"}}}}";

    private WorktreeManager manager() {
        return new WorktreeManager(workDir.toString(), "", 2);
    }

    @Test
    void prepareSlot_withoutRepo_failsFast() {
        var ex = assertThrows(IllegalStateException.class, () -> manager().prepareSlot(0, null));
        assertTrue(ex.getMessage().contains("TARGET_REPO"), "сообщение должно объяснять причину: " + ex.getMessage());

        assertThrows(IllegalStateException.class, () -> manager().prepareSlot(0, ""));
        assertThrows(IllegalStateException.class, () -> manager().prepareSlot(0, "   "));
    }

    @Test
    void repoProjectConfig_isNeutralized_andNeverCommitted() throws Exception {
        assumeTrue(gitAvailable(), "git недоступен");
        Path repo = createSourceRepo(TRACKED_REPO_CONFIG);

        manager().prepareSlot(0, repo.toString());

        Path slot = workDir.resolve("slot-0");
        Path config = slot.resolve("opencode.json");
        String content = Files.readString(config);

        assertEquals(WorktreeManager.SAFE_PROJECT_CONFIG, content, "битый конфиг должен быть нейтрализован");
        assertFalse(content.contains(".secrets"), "ссылка на несуществующий секрет не должна остаться");
        assertTrue(git(slot, "ls-files").contains("opencode.json"), "файл остаётся отслеживаемым");

        // Ключевая гарантия: локальная подмена не видна git и не попадёт в коммит агента.
        assertEquals("", git(slot, "status", "--porcelain"), "рабочее дерево должно быть чистым");
        git(slot, "add", "-A");
        assertFalse(git(slot, "diff", "--cached", "--name-only").contains("opencode.json"),
                "нейтрализованный конфиг не должен стейджиться");
    }

    @Test
    void repoWithoutProjectConfig_nothingCreated() throws Exception {
        assumeTrue(gitAvailable(), "git недоступен");
        Path repo = createSourceRepo(null);

        manager().prepareSlot(0, repo.toString());

        assertFalse(Files.exists(workDir.resolve("slot-0").resolve("opencode.json")));
    }

    @Test
    void reuseSlot_keepsConfigNeutralized_andStaysClean() throws Exception {
        assumeTrue(gitAvailable(), "git недоступен");
        Path repo = createSourceRepo(TRACKED_REPO_CONFIG);
        WorktreeManager m = manager();

        m.prepareSlot(0, repo.toString());
        m.prepareSlot(0, repo.toString());

        Path slot = workDir.resolve("slot-0");
        assertEquals(WorktreeManager.SAFE_PROJECT_CONFIG, Files.readString(slot.resolve("opencode.json")));
        assertEquals("", git(slot, "status", "--porcelain"));
    }

    private Path createSourceRepo(String opencodeJson) throws Exception {
        Path repo = workDir.resolve("source-repo");
        Files.createDirectories(repo);
        git(repo, "init", "-b", "main");
        git(repo, "config", "user.email", "test@test.local");
        git(repo, "config", "user.name", "test");
        if (opencodeJson != null) {
            Files.writeString(repo.resolve("opencode.json"), opencodeJson);
        }
        Files.writeString(repo.resolve("README.md"), "# repo\n");
        git(repo, "add", "-A");
        git(repo, "commit", "-m", "init");
        return repo;
    }

    private static String git(Path cwd, String... args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(cwd.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        int code = p.waitFor();
        if (code != 0) {
            throw new AssertionError("git " + String.join(" ", args) + " → " + code + "\n" + out);
        }
        return out.trim();
    }

    private static boolean gitAvailable() {
        try {
            return new ProcessBuilder("git", "--version").start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
