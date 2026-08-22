package ru.allstreets.developer.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Локальные GitHub tools для ConversationAgent.
 * Только чтение — репозитории, файлы, PR, issues, ветки.
 * Agent собеседник использует их, чтобы отвечать на вопросы о проектах.
 */
@Component
public class GithubMcpTools {

    private static final Logger log = LoggerFactory.getLogger(GithubMcpTools.class);

    private final RestClient api;

    public GithubMcpTools(@Value("${github.token:}") String token) {
        this.api = RestClient.builder()
                .baseUrl("https://api.github.com")
                .defaultHeader("Authorization", "Bearer " + token)
                .defaultHeader("Accept", "application/vnd.github.v3+json")
                .build();
    }

    @Tool(description = "Get repository info: description, language, stars, forks, default branch, last update. " +
            "repo format: owner/name. If not sure, use the default project repo.")
    public String getRepoInfo(
            @ToolParam(description = "Repository in format owner/name") String repo
    ) {
        String target = normalizeRepo(repo);
        if (target == null) return "Repository not specified. Provide repo in format owner/name.";
        log.info("MCP getRepoInfo: repo={}", target);
        try {
            JsonNode info = api.get()
                    .uri("/repos/" + target)
                    .retrieve()
                    .body(JsonNode.class);
            if (info == null) return "Repository not found: " + target;

            return """
                    repo: %s
                    description: %s
                    language: %s
                    default_branch: %s
                    stars: %d
                    forks: %d
                    open_issues: %d
                    visibility: %s
                    updated_at: %s
                    pushed_at: %s
                    html_url: %s
                    """.formatted(
                    info.path("full_name").asText(),
                    info.path("description").asText("N/A"),
                    info.path("language").asText("N/A"),
                    info.path("default_branch").asText("main"),
                    info.path("stargazers_count").asInt(0),
                    info.path("forks_count").asInt(0),
                    info.path("open_issues_count").asInt(0),
                    info.path("visibility").asText("N/A"),
                    info.path("updated_at").asText("N/A"),
                    info.path("pushed_at").asText("N/A"),
                    info.path("html_url").asText("N/A")
            );
        } catch (Exception e) {
            return "Failed to get repo info: " + e.getMessage();
        }
    }

    @Tool(description = "List files and directories at a given path in a GitHub repository branch. " +
            "Returns name, type (file/dir), size and path for each entry.")
    public String getRepoFiles(
            @ToolParam(description = "Repository in format owner/name") String repo,
            @ToolParam(description = "Branch name (e.g. main, master)") String branch,
            @ToolParam(description = "Directory path (use empty string or . for root)") String path
    ) {
        String target = normalizeRepo(repo);
        if (target == null) return "Repository not specified. Provide repo in format owner/name.";
        String cleanPath = (path == null || path.isBlank() || path.equals(".")) ? "" : path;
        log.info("MCP getRepoFiles: repo={}, branch={}, path={}", target, branch, cleanPath);
        try {
            var uriBuilder = org.springframework.web.util.UriComponentsBuilder
                    .fromPath("/repos/" + target + "/contents/" + cleanPath)
                    .queryParam("ref", branch);
            JsonNode contents = api.get()
                    .uri(uriBuilder.build().toUri())
                    .retrieve()
                    .body(JsonNode.class);
            if (contents == null) return "No files found";

            if (contents.isArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode item : contents) {
                    sb.append(item.path("type").asText()).append("\t")
                            .append(item.path("name").asText()).append("\t")
                            .append(item.path("size").asInt()).append(" bytes\n");
                }
                return sb.toString();
            } else {
                return "type: " + contents.path("type").asText() + "\n"
                        + "name: " + contents.path("name").asText() + "\n"
                        + "size: " + contents.path("size").asInt() + " bytes\n"
                        + "Use getRepoFile to read content.";
            }
        } catch (Exception e) {
            return "Failed to list files: " + e.getMessage();
        }
    }

    @Tool(description = "Read file content from a GitHub repository. Returns raw file text.")
    public String getRepoFile(
            @ToolParam(description = "Repository in format owner/name") String repo,
            @ToolParam(description = "Branch name") String branch,
            @ToolParam(description = "File path (e.g. src/main/java/Main.java)") String path
    ) {
        String target = normalizeRepo(repo);
        if (target == null) return "Repository not specified. Provide repo in format owner/name.";
        log.info("MCP getRepoFile: repo={}, branch={}, path={}", target, branch, path);
        try {
            JsonNode file = api.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/repos/" + target + "/contents/{path}")
                            .queryParam("ref", branch)
                            .build(path))
                    .retrieve()
                    .body(JsonNode.class);
            if (file == null) return "File not found: " + path;

            String encoding = file.path("encoding").asText("base64");
            String content = file.path("content").asText("");
            if ("base64".equalsIgnoreCase(encoding) && !content.isBlank()) {
                byte[] decoded = java.util.Base64.getMimeDecoder().decode(content);
                return new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
            }
            return content;
        } catch (Exception e) {
            return "Failed to read file: " + e.getMessage();
        }
    }

    @Tool(description = "List open pull requests in a repository. Returns number, title, branch, author, URL.")
    public String listPullRequests(
            @ToolParam(description = "Repository in format owner/name") String repo
    ) {
        String target = normalizeRepo(repo);
        if (target == null) return "Repository not specified. Provide repo in format owner/name.";
        log.info("MCP listPullRequests: repo={}", target);
        try {
            JsonNode prs = api.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/repos/" + target + "/pulls")
                            .queryParam("state", "open")
                            .queryParam("per_page", 20)
                            .queryParam("sort", "updated")
                            .queryParam("direction", "desc")
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
            if (prs == null || !prs.isArray()) return "No open PRs";

            StringBuilder sb = new StringBuilder();
            for (JsonNode pr : prs) {
                sb.append("#").append(pr.path("number").asInt())
                        .append(" [").append(pr.path("head").path("ref").asText()).append("]")
                        .append(" by ").append(pr.path("user").path("login").asText())
                        .append(": ").append(pr.path("title").asText())
                        .append("\n  ").append(pr.path("html_url").asText()).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "Failed to list PRs: " + e.getMessage();
        }
    }

    @Tool(description = "List branches in a repository. Returns branch names and last commit SHA.")
    public String listBranches(
            @ToolParam(description = "Repository in format owner/name") String repo
    ) {
        String target = normalizeRepo(repo);
        if (target == null) return "Repository not specified. Provide repo in format owner/name.";
        log.info("MCP listBranches: repo={}", target);
        try {
            JsonNode branches = api.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/repos/" + target + "/branches")
                            .queryParam("per_page", 50)
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
            if (branches == null || !branches.isArray()) return "No branches found";

            StringBuilder sb = new StringBuilder();
            for (JsonNode b : branches) {
                sb.append(b.path("name").asText())
                        .append(" → ").append(b.path("commit").path("sha").asText(), 0, Math.min(7, b.path("commit").path("sha").asText().length()))
                        .append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "Failed to list branches: " + e.getMessage();
        }
    }

    @Tool(description = "List recent commits in a repository branch. Returns SHA, message, author, date.")
    public String getRecentCommits(
            @ToolParam(description = "Repository in format owner/name") String repo,
            @ToolParam(description = "Branch name") String branch,
            @ToolParam(description = "Max commits to return (1-30)") int limit
    ) {
        String target = normalizeRepo(repo);
        if (target == null) return "Repository not specified. Provide repo in format owner/name.";
        int maxLimit = Math.clamp(limit, 1, 30);
        log.info("MCP getRecentCommits: repo={}, branch={}, limit={}", target, branch, maxLimit);
        try {
            JsonNode commits = api.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/repos/" + target + "/commits")
                            .queryParam("sha", branch)
                            .queryParam("per_page", maxLimit)
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
            if (commits == null || !commits.isArray()) return "No commits found";

            StringBuilder sb = new StringBuilder();
            for (JsonNode c : commits) {
                String sha = c.path("sha").asText("");
                sb.append(sha, 0, Math.min(7, sha.length()))
                        .append(" [").append(c.path("commit").path("author").path("date").asText()).append("]")
                        .append(" by ").append(c.path("commit").path("author").path("name").asText())
                        .append(": ").append(c.path("commit").path("message").asText().split("\n")[0])
                        .append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "Failed to get commits: " + e.getMessage();
        }
    }

    @Tool(description = "List open issues in a repository. Returns number, title, labels, assignee.")
    public String listIssues(
            @ToolParam(description = "Repository in format owner/name") String repo
    ) {
        String target = normalizeRepo(repo);
        if (target == null) return "Repository not specified. Provide repo in format owner/name.";
        log.info("MCP listIssues: repo={}", target);
        try {
            JsonNode issues = api.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/repos/" + target + "/issues")
                            .queryParam("state", "open")
                            .queryParam("per_page", 20)
                            .queryParam("sort", "updated")
                            .queryParam("direction", "desc")
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
            if (issues == null || !issues.isArray()) return "No open issues";

            StringBuilder sb = new StringBuilder();
            for (JsonNode issue : issues) {
                if (issue.has("pull_request")) continue;
                sb.append("#").append(issue.path("number").asInt())
                        .append(" [").append(issue.path("state").asText()).append("]")
                        .append(": ").append(issue.path("title").asText());
                JsonNode labels = issue.path("labels");
                if (labels.isArray() && !labels.isEmpty()) {
                    sb.append(" {");
                    for (int i = 0; i < labels.size(); i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(labels.get(i).path("name").asText());
                    }
                    sb.append("}");
                }
                sb.append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "Failed to list issues: " + e.getMessage();
        }
    }

    @Tool(description = "Get README content of a repository. Returns raw markdown text.")
    public String getReadme(
            @ToolParam(description = "Repository in format owner/name") String repo,
            @ToolParam(description = "Branch name (e.g. main)") String branch
    ) {
        String target = normalizeRepo(repo);
        if (target == null) return "Repository not specified. Provide repo in format owner/name.";
        log.info("MCP getReadme: repo={}, branch={}", target, branch);
        try {
            JsonNode readme = api.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/repos/" + target + "/readme")
                            .queryParam("ref", branch)
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
            if (readme == null) return "No README found";

            String content = readme.path("content").asText("");
            String encoding = readme.path("encoding").asText("base64");
            if ("base64".equalsIgnoreCase(encoding) && !content.isBlank()) {
                byte[] decoded = java.util.Base64.getMimeDecoder().decode(content);
                return new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
            }
            return content;
        } catch (Exception e) {
            return "Failed to get README: " + e.getMessage();
        }
    }

    private String normalizeRepo(String repo) {
        if (repo == null || repo.isBlank()) return null;
        String cleaned = repo.replace("https://github.com/", "").replace(".git", "");
        if (!cleaned.contains("/")) return null;
        return cleaned;
    }
}
