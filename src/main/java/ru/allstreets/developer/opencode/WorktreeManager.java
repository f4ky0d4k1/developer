package ru.allstreets.developer.opencode;

import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Управление git слотами для параллельных задач.
 * Каждый слот — отдельный clone репо на ветке main.
 * Spring НЕ управляет ветками — агенты OpenCode сами создают/переключают ветки через git.
 */
@Component
public class WorktreeManager {

    private static final Logger log = LoggerFactory.getLogger(WorktreeManager.class);

    /**
     * Проектные конфиги OpenCode, которые репозиторий может тащить с собой.
     */
    private static final List<String> PROJECT_CONFIG_FILES = List.of("opencode.json", "opencode.jsonc");

    /**
     * Нейтральная заглушка проектного конфига. Наш конфиг и агенты и так применяются сидекаром
     * (global {@code /root/.config/opencode/opencode.jsonc} + {@code /work/.opencode/agents}),
     * поэтому от проектного файла репозитория нам нужно лишь убрать битые ссылки на секреты.
     */
    static final String SAFE_PROJECT_CONFIG =
            "{\n  \"$schema\": \"https://opencode.ai/config.json\"\n}\n";

    private final String baseWorkDir;
    private final String githubToken;
    @Getter
    private final int slotCount;

    public WorktreeManager(
            @Value("${opencode.work-dir:/work}") String baseWorkDir,
            @Value("${github.token:}") String githubToken,
            @Value("${opencode.slots:1}") int slotCount
    ) {
        this.baseWorkDir = baseWorkDir;
        this.githubToken = githubToken;
        this.slotCount = slotCount;
    }

    private String authenticatedUrl(String repoUrl) {
        if (repoUrl == null || repoUrl.isBlank() || githubToken == null || githubToken.isBlank()) {
            return repoUrl;
        }
        return repoUrl.replace("https://", "https://x-access-token:" + githubToken + "@");
    }

    /**
     * Получить путь к worktree для слота.
     */
    public Path getSlotWorkDir(int slotIndex) {
        return Paths.get(baseWorkDir, "slot-" + slotIndex);
    }

    /**
     * Подготовить слот: clone репо на main, если ещё не существует.
     * Spring НЕ переключает ветки — агенты OpenCode сами создают ветки через git.
     * <p>
     * Fail-fast: без целевого репозитория агент работать не может (аналитик читает код,
     * разработчик/тестировщик правят), поэтому пустой {@code repoUrl} — сразу ошибка, а не
     * запуск агента в пустой директории (именно так задача жгла бюджет 300с вхолостую).
     * Если слот существует, но не является git-репозиторием (остался от прошлых запусков
     * или повреждён) — чистим и клонируем заново.
     */
    public void prepareSlot(int slotIndex, String repoUrl) {
        Path slotDir = getSlotWorkDir(slotIndex);
        log.info("Подготовка слота {} → {} (repo: {})", slotIndex, slotDir, repoUrl);

        if (repoUrl == null || repoUrl.isBlank()) {
            throw new IllegalStateException(
                    "Не задан целевой репозиторий (TARGET_REPO) для слота " + slotIndex
                            + " — агент не может работать без кода репозитория");
        }

        try {
            if (isGitRepo(slotDir) && isSameRepo(slotDir, repoUrl)) {
                // Уже клонирован НУЖНЫЙ репозиторий — снимаем прошлую подмену конфига и обновляем
                // remote-ссылки (с retry на случай TLS ошибок). Ветки НЕ сбрасываем принудительно:
                // если в дереве осталась незакоммиченная работа агента, `git checkout main` упадёт —
                // не фейлим задачу, оставляем агенту (по промпту он сам закоммитит/stash и разрешит конфликт).
                clearProjectConfigOverride(slotDir);
                runCommand(slotDir, "git", "config", "http.sslVerify", "false");
                runCommandWithRetry(slotDir, 3, "git", "fetch", "origin");
                try {
                    runCommand(slotDir, "git", "checkout", "main");
                    runCommandWithRetry(slotDir, 3, "git", "pull", "origin", "main");
                } catch (RuntimeException e) {
                    log.warn("Слот {} не удалось обновить до main (грязное дерево?) — оставляю агенту: {}",
                            slotIndex, e.getMessage());
                }
                linkOpencodeConfig(slotDir);
                applyOpencodeProjectConfig(slotDir);
                log.info("Слот {} подготовлен", slotIndex);
                return;
            }

            // Слот не пригоден: либо не git-репозиторий, либо содержит ЧУЖОЙ репозиторий
            // (инцидент 5d8aabf5: задаче f4ky0d4k1/developer достался слот с клоном allstreets-spring).
            if (Files.exists(slotDir)) {
                log.warn("Слот {} не пригоден (не git-репо или чужой репозиторий, origin={}) — очищаю перед клоном",
                        slotIndex, originOf(slotDir));
                deleteRecursively(slotDir);
            }
            Files.createDirectories(slotDir);

            runCommand(slotDir.getParent(), "git", "clone",
                    "-c", "http.sslVerify=false",
                    authenticatedUrl(repoUrl), slotDir.toString());
            runCommand(slotDir, "git", "config", "http.sslVerify", "false");

            if (!isGitRepo(slotDir)) {
                throw new IllegalStateException(
                        "Слот " + slotIndex + " не является git-репозиторием после клонирования: " + slotDir);
            }

            log.info("Слот {} подготовлен", slotIndex);
            linkOpencodeConfig(slotDir);
            applyOpencodeProjectConfig(slotDir);

        } catch (RuntimeException e) {
            log.error("Ошибка подготовки слота {}: {}", slotIndex, e.getMessage(), e);
            throw e;
        } catch (Exception e) {
            log.error("Ошибка подготовки слота {}: {}", slotIndex, e.getMessage(), e);
            throw new RuntimeException("Не удалось подготовить слот: " + e.getMessage(), e);
        }
    }

    private static boolean isGitRepo(Path dir) {
        return Files.isDirectory(dir) && Files.exists(dir.resolve(".git"));
    }

    /**
     * Слот уже содержит ТОТ ЖЕ репозиторий, что запрошен? Сверяем origin слота с {@code repoUrl}
     * по нормализованной форме (без схемы/userinfo/.git, регистронезависимо). Иначе слот
     * «протекает» чужим репозиторием между задачами (инцидент 5d8aabf5).
     */
    private boolean isSameRepo(Path slotDir, String repoUrl) {
        String origin = originOf(slotDir);
        if (origin == null) {
            return false;
        }
        return normalizeRepoUrl(origin).equals(normalizeRepoUrl(repoUrl));
    }

    /**
     * origin-URL слота из git config, либо {@code null}, если его нет/не прочитать.
     */
    private String originOf(Path slotDir) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "config", "--get", "remote.origin.url");
            pb.directory(slotDir.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String out = new String(process.getInputStream().readAllBytes()).trim();
            return process.waitFor() == 0 && !out.isBlank() ? out : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Нормализация URL для сравнения: отбрасываем схему, userinfo (токен в {@code authenticatedUrl}),
     * суффикс {@code .git} и слэши; сравниваем регистронезависимо.
     */
    private static String normalizeRepoUrl(String url) {
        if (url == null) {
            return null;
        }
        String s = url.trim().replace('\\', '/');
        int scheme = s.indexOf("://");
        if (scheme >= 0) {
            s = s.substring(scheme + 3);
        }
        int at = s.lastIndexOf('@');
        if (at >= 0) {
            s = s.substring(at + 1);
        }
        if (s.endsWith(".git")) {
            s = s.substring(0, s.length() - 4);
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s.toLowerCase();
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    // Windows: git-объекты могут быть read-only — Files.delete падает с AccessDenied.
                    if (!p.toFile().setWritable(true)) {
                        log.warn("Не удалось снять read-only с {} — удаление может не пройти", p);
                    }
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        }
    }

    /**
     * Очистка слота после завершения задачи.
     * Сбрасывает на main и удаляет локальные ветки.
     */
    public void cleanupSlot(int slotIndex) {
        Path slotDir = getSlotWorkDir(slotIndex);
        log.info("Очистка слота {}", slotIndex);

        try {
            if (Files.exists(slotDir)) {
                clearProjectConfigOverride(slotDir);
                runCommand(slotDir, "git", "checkout", "main");
                runCommand(slotDir, "git", "clean", "-fd", "-e", ".opencode");
                log.info("Слот {} очищен (на main)", slotIndex);
            }
        } catch (Exception e) {
            log.warn("Ошибка очистки слота {}: {}", slotIndex, e.getMessage());
        }
    }

    /**
     * Полный сброс слота: удаляем worktree целиком (включая клон репозитория).
     * Используется для ручного восстановления задачи, застрявшей в чужом/повреждённом
     * worktree — на следующем {@code prepareSlot} слот клонируется заново.
     */
    public void resetSlot(int slotIndex) {
        Path slotDir = getSlotWorkDir(slotIndex);
        log.info("Полный сброс слота {}: {}", slotIndex, slotDir);
        try {
            if (Files.exists(slotDir)) {
                deleteRecursively(slotDir);
            }
        } catch (IOException e) {
            throw new RuntimeException("Не удалось сбросить слот " + slotIndex + ": " + e.getMessage(), e);
        }
    }

    private void linkOpencodeConfig(Path slotDir) {
        try {
            Path link = slotDir.resolve(".opencode");
            Path target = Paths.get(baseWorkDir, ".opencode");
            log.info("linkOpencodeConfig: slotDir={}, link={}, link.exists={}, target={}, target.exists={}",
                    slotDir, link, Files.exists(link), target, Files.exists(target));
            if (Files.exists(link)) {
                log.info("linkOpencodeConfig: .opencode уже существует в {}, пропуск", slotDir);
                return;
            }
            if (!Files.exists(target)) {
                log.warn("linkOpencodeConfig: target {} не существует! Симлинк не будет создан.", target);
                return;
            }
            Files.createSymbolicLink(link, target);
            log.info("linkOpencodeConfig: симлинк .opencode создан в {} → {}", slotDir, target);
        } catch (IOException e) {
            log.warn("linkOpencodeConfig: не удалось создать симлинк .opencode в {}: {}", slotDir, e.getMessage());
        }
    }

    /**
     * Нейтрализовать проектный конфиг OpenCode в слоте.
     * <p>Репозиторий может тащить свой {@code opencode.json}/{@code opencode.jsonc} со ссылкой
     * {@code {file:./.secrets/...}} на несуществующий в слоте секрет — тогда {@code createSession}
     * падает с HTTP 400 ещё до старта агента (проектный конфиг перекрывает global). Подменяем содержимое
     * на {@link #SAFE_PROJECT_CONFIG}: наш конфиг применяется сидекаром как global.
     * <p><b>Не коммитить:</b> если файл отслеживается git — помечаем {@code --skip-worktree}
     * (локальные изменения не попадут в {@code git add}/commit); если не отслеживается — пишем путь
     * в {@code .git/info/exclude} (локальный, не версионируется).
     */
    private void applyOpencodeProjectConfig(Path slotDir) {
        for (String name : PROJECT_CONFIG_FILES) {
            Path file = slotDir.resolve(name);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                Files.writeString(file, SAFE_PROJECT_CONFIG);
                protectFromCommit(slotDir, name);
                log.info("Проектный opencode-конфиг нейтрализован (репо мог тащить битые ссылки): {}", file);
            } catch (Exception e) {
                log.warn("Не удалось нейтрализовать проектный opencode-конфиг {}: {}", file, e.getMessage());
            }
        }
    }

    /**
     * Локальная подмена не должна попасть в коммит агента.
     */
    private void protectFromCommit(Path slotDir, String relPath) {
        try {
            if (isTracked(slotDir, relPath)) {
                runCommand(slotDir, "git", "update-index", "--skip-worktree", "--", relPath);
            } else {
                Path exclude = slotDir.resolve(".git").resolve("info").resolve("exclude");
                Files.createDirectories(exclude.getParent());
                String line = "/" + relPath;
                String existing = Files.exists(exclude) ? Files.readString(exclude) : "";
                if (!existing.contains(line)) {
                    Files.writeString(exclude,
                            existing + (existing.isEmpty() || existing.endsWith("\n") ? "" : "\n") + line + "\n");
                }
            }
        } catch (Exception e) {
            log.warn("Не удалось защитить {} от коммита: {}", relPath, e.getMessage());
        }
    }

    /**
     * Снять прошлую подмену (skip-worktree + restore), чтобы fetch/checkout/pull не конфликтовали.
     */
    private void clearProjectConfigOverride(Path slotDir) {
        for (String name : PROJECT_CONFIG_FILES) {
            if (!Files.exists(slotDir.resolve(name))) {
                continue;
            }
            try {
                if (isTracked(slotDir, name)) {
                    runCommand(slotDir, "git", "update-index", "--no-skip-worktree", "--", name);
                    runCommand(slotDir, "git", "checkout", "--", name);
                }
            } catch (Exception e) {
                log.warn("Не удалось снять подмену opencode-конфига {}: {}", name, e.getMessage());
            }
        }
    }

    private boolean isTracked(Path slotDir, String relPath) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "ls-files", "--error-unmatch", "--", relPath);
            pb.directory(slotDir.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            process.getInputStream().readAllBytes();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void runCommand(Path cwd, String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(cwd.toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            String output = new String(process.getInputStream().readAllBytes());
            throw new RuntimeException("Command failed: " + String.join(" ", cmd) + "\n" + output);
        }
    }

    @SuppressWarnings("SameParameterValue")
    private void runCommandWithRetry(Path cwd, int maxRetries, String... cmd) throws IOException, InterruptedException {
        RuntimeException lastError = null;
        for (int i = 0; i <= maxRetries; i++) {
            try {
                runCommand(cwd, cmd);
                return;
            } catch (RuntimeException e) {
                lastError = e;
                log.warn("Команда не удалась (попытка {}/{}): {} — {}", i + 1, maxRetries + 1,
                        String.join(" ", cmd), e.getMessage().length() > 200 ? e.getMessage().substring(0, 200) : e.getMessage());
                if (i < maxRetries) {
                    Thread.sleep(2000L * (i + 1));
                }
            }
        }
        throw lastError != null ? lastError
                : new RuntimeException("Команда не выполнена: " + String.join(" ", cmd));
    }

}
