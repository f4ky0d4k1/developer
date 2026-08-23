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

/**
 * Управление git слотами для параллельных задач.
 * Каждый слот — отдельный clone репо на ветке main.
 * Spring НЕ управляет ветками — агенты OpenCode сами создают/переключают ветки через git.
 */
@Component
public class WorktreeManager {

    private static final Logger log = LoggerFactory.getLogger(WorktreeManager.class);

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
     */
    public void prepareSlot(int slotIndex, String repoUrl) {
        Path slotDir = getSlotWorkDir(slotIndex);
        log.info("Подготовка слота {} → {} (repo: {})", slotIndex, slotDir, repoUrl);

        try {
            if (Files.exists(slotDir) && Files.isDirectory(slotDir)) {
                Path gitDir = slotDir.resolve(".git");
                if (Files.exists(gitDir)) {
                    // Уже клонировано — обновляем main (с retry на случай TLS ошибок)
                    runCommand(slotDir, "git", "config", "http.sslVerify", "false");
                    runCommandWithRetry(slotDir, 3, "git", "fetch", "origin");
                    runCommand(slotDir, "git", "checkout", "main");
                    runCommandWithRetry(slotDir, 3, "git", "pull", "origin", "main");
                    linkOpencodeConfig(slotDir);
                    log.info("Слот {} обновлён на main", slotIndex);
                    return;
                }
            }

            Files.createDirectories(slotDir);

            if (repoUrl != null && !repoUrl.isBlank()) {
                runCommand(slotDir.getParent(), "git", "clone",
                        "-c", "http.sslVerify=false",
                        authenticatedUrl(repoUrl), slotDir.toString());
                runCommand(slotDir, "git", "config", "http.sslVerify", "false");
            } else {
                log.warn("repoUrl не задан, слот {} — пустая директория", slotIndex);
            }

            log.info("Слот {} подготовлен", slotIndex);
            linkOpencodeConfig(slotDir);

        } catch (Exception e) {
            log.error("Ошибка подготовки слота {}: {}", slotIndex, e.getMessage(), e);
            throw new RuntimeException("Не удалось подготовить слот: " + e.getMessage(), e);
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
                runCommand(slotDir, "git", "checkout", "main");
                runCommand(slotDir, "git", "clean", "-fd", "-e", ".opencode");
                log.info("Слот {} очищен (на main)", slotIndex);
            }
        } catch (Exception e) {
            log.warn("Ошибка очистки слота {}: {}", slotIndex, e.getMessage());
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
