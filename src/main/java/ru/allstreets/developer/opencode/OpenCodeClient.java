package ru.allstreets.developer.opencode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class OpenCodeClient {

    private static final Logger log = LoggerFactory.getLogger(OpenCodeClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final String model;
    private final int timeoutSeconds;

    public OpenCodeClient(
            @Value("${opencode.model:deepseek/deepseek-v4-pro}") String model,
            @Value("${opencode.timeout-seconds:300}") int timeoutSeconds
    ) {
        this.model = model;
        this.timeoutSeconds = timeoutSeconds;
    }

    public OpenCodeResult runAgent(String agentName, String prompt, String cwd) {
        log.info("Запуск OpenCode агента: {} в {} (промпт: {} символов)", agentName, cwd, prompt.length());

        // Проверка окружения
        try {
            ProcessBuilder checkPb = new ProcessBuilder("docker", "exec", "-w", cwd, "opencode",
                    "sh", "-c", "ls -la .opencode 2>&1; echo '---'; ls .opencode/agents/ 2>&1; echo '---'; pwd");
            checkPb.redirectErrorStream(true);
            Process checkProc = checkPb.start();
            String checkOut = new String(checkProc.getInputStream().readAllBytes());
            checkProc.waitFor();
            log.info("[OpenCode:{}] окружение в {}: {}", agentName, cwd, checkOut.trim().replace("\n", " | "));
        } catch (Exception e) {
            log.warn("[OpenCode:{}] не удалось проверить окружение: {}", agentName, e.getMessage());
        }

        List<String> command = List.of(
                "docker", "exec", "-w", cwd,
                "opencode",
                "opencode", "run",
                "--agent", agentName,
                "--model", model,
                "--format", "json",
                "--",
                prompt
        );
        log.info("[OpenCode:{}] команда: {}", agentName, String.join(" ", command.stream().limit(12).toList()) + " ...");

        Process process;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            process = pb.start();
        } catch (Exception e) {
            throw new RuntimeException("Ошибка запуска OpenCode агента " + agentName + ": " + e.getMessage(), e);
        }
        return runAgentProcess(agentName, process);
    }

    private OpenCodeResult runAgentProcess(String agentName, Process process) {
        StringBuilder agentText = new StringBuilder();
        List<String> toolCalls = new ArrayList<>();
        String sessionId = null;
        String error = null;
        long totalTokens = 0;
        double cost = 0;

        int lineCount = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineCount++;
                if (lineCount <= 5) {
                    log.info("[OpenCode:{}] вывод #{}: {}", agentName, lineCount,
                            line.length() > 300 ? line.substring(0, 300) + "..." : line);
                }
                try {
                    JsonNode event = mapper.readTree(line);
                    String type = event.path("type").asText("");

                    if (sessionId == null) {
                        sessionId = event.path("sessionID").asText(null);
                    }

                    JsonNode part = event.path("part");

                    switch (type) {
                        case "text" -> {
                            String text = part.path("text").asText("");
                            agentText.append(text);
                            log.info("[OpenCode:{}] text: {}", agentName,
                                    text.length() > 200 ? text.substring(0, 200) + "..." : text);
                        }
                        case "tool_use" -> {
                            String toolName = part.path("tool").asText("unknown");
                            String input = part.path("input").toString();
                            log.info("[OpenCode:{}] tool_use: {} input: {}", agentName, toolName,
                                    input.length() > 300 ? input.substring(0, 300) + "..." : input);
                            toolCalls.add(toolName);
                        }
                        case "tool_start" -> {
                            String toolName = part.path("tool").asText("unknown");
                            log.info("[OpenCode:{}] tool_start: {}", agentName, toolName);
                            toolCalls.add(toolName);
                        }
                        case "tool_finish" -> {
                            String toolName = part.path("tool").asText("unknown");
                            String output = part.path("output").asText("");
                            log.info("[OpenCode:{}] tool_finish: {} output: {}", agentName, toolName,
                                    output.length() > 300 ? output.substring(0, 300) + "..." : output);
                        }
                        case "step_finish" -> {
                            JsonNode tokens = part.path("tokens");
                            if (!tokens.isMissingNode()) {
                                totalTokens += tokens.path("total").asLong(0);
                            }
                            cost += part.path("cost").asDouble(0);
                            String reason = part.path("reason").asText("");
                            log.info("[OpenCode:{}] step_finish: reason={}, tokens={}, cost={}",
                                    agentName, reason, totalTokens, String.format("%.4f", cost));
                        }
                        case "error" -> {
                            error = part.path("message").asText("Unknown error");
                            log.error("[OpenCode:{}] error: {}", agentName, error);
                        }
                        default -> log.debug("[OpenCode:{}] event: {}", agentName, type);
                    }
                } catch (Exception parseEx) {
                    log.debug("[OpenCode:{}] non-JSON line: {}", agentName,
                            line.length() > 200 ? line.substring(0, 200) + "..." : line);
                }
            }
        } catch (Exception e) {
            log.error("[OpenCode:{}] ошибка чтения вывода: {}", agentName, e.getMessage(), e);
        }

        boolean finished;
        try {
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Прерван запуск OpenCode агента: " + agentName, e);
        }

        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("Таймаут OpenCode (" + timeoutSeconds + "с) для агента: " + agentName);
        }

        int exitCode = process.exitValue();
        log.info("Агент {} завершил работу. exit={}, session={}, tokens={}, cost={}, toolCalls={}, текст={} символов",
                agentName, exitCode, sessionId, totalTokens, String.format("%.4f", cost),
                toolCalls, agentText.length());

        if (exitCode != 0 && error == null) {
            error = "OpenCode exit code: " + exitCode;
        }

        return new OpenCodeResult(
                exitCode == 0 ? "success" : "error",
                agentText.toString(),
                null,
                null,
                toolCalls,
                error
        );
    }

    public record OpenCodeResult(
            String status,
            String output,
            String diff,
            String commitHash,
            List<String> files,
            String error
    ) {
    }
}
