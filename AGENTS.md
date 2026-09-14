# AGENTS.md

Spring Boot (Java 21) orchestrator that runs OpenCode sidecar agents over HTTP and fronts them with a Telegram bot. Task
lifecycle = spring-agent-flow graph; Postgres = state/checkpoints; Grafana Cloud (Loki/Prometheus via Alloy) =
observability.

## Build & test

- Use the Maven wrapper: `.\mvnw.cmd ...` (Windows PowerShell) or `./mvnw ...` (CI). Don't assume `mvn` is on PATH.
- One test / a set: `.\mvnw.cmd test "-Dtest=ClassName"` (comma-separated for several).
- Full suite minus the broken context test: `.\mvnw.cmd test "-Dtest=!DeveloperApplicationTests"`.
- `DeveloperApplicationTests.contextLoads` is a bare `@SpringBootTest` that needs Postgres at host `postgres` (no
  Testcontainers) — it fails locally and is skipped in CI (`-DskipTests`). Exclude it in any full run.
- Integration tests need Docker Desktop: `PostgresTestBase` starts a shared Testcontainers Postgres (static).
  `OpenCodeClientRealSidecarIT` is a real e2e — real opencode sidecar + the real DeepSeek model (`deepseek-v4-pro`,
  override via `E2E_MODEL`); it is environment-gated on `DEEPSEEK_API_KEY` (skipped without it, so plain `mvn test`
  needs no key/network). Deterministic protocol/negative cases stay on WireMock stubs (`OpenCodeApiTest`,
  `OpenCodeClientTest`).
- Testcontainers is pinned to `2.0.5` in `pom.xml` — Boot 3.4.1's default (1.20.4) breaks Docker Desktop 29.x. Don't "
  upgrade"/revert it.
- Test stack: JUnit 5, Mockito, WireMock (sidecar stub), Testcontainers (Postgres).

## Architecture

Packages under `ru.allstreets.developer` (entrypoint `DeveloperApplication`):

- `opencode/` — HTTP client (`OpenCodeApi`), poll orchestrator (`OpenCodeClient`), session slots (
  `OpenCodeSessionPool`), git worktrees (`WorktreeManager`).
- `agents/` — graph nodes (`AnalystNode`, developer/tester/validator, `PostValidationNode`). They orchestrate the
  sidecar; the actual agent behavior lives in `opencode-config/agents/*.md`.
- `telegram/` — bot (`TelegramBotListener`), fast classifier (`ConversationAgent`), task runner (`TaskLauncher`).
- `mcp/` — MCP tools exposed to the sidecar agents (`launch_task`, `getChatProjects`, `getSystemInfo`, …).
- `checkpoint/` — spring-agent-flow checkpoint store + `CheckpointRecoveryListener` (restart recovery).
- `config/` — bean wiring (`AgentGraphRunner`, `McpToolConfig`).

Agents are NOT Java: they're prompt files in `opencode-config/agents/*.md`, executed by the `opencode` sidecar. Spring
calls `OpenCodeClient.runAgent(agentName, prompt, cwd, taskId[, sessionId])` and reads the result.

## Non-negotiable invariants (hard-won)

- **"Программно-первый"**: agent *behavior* is expressed via prompts (`opencode-config/`) and MCP tools (`mcp/`), never
  hardcoded in Java. Java = guards/invariants/boundaries (fail-fast, validation, lifecycle). Example: launching a task
  is the `launch_task` MCP tool with a required `repo`, not a Java action.
- **Analyst decision is deterministic**: `AnalystNode.parseDecision` reads the agent's final JSON block with Jackson —
  NOT a second LLM (`StructuredOutputHelper` was removed from this path). Missing/invalid `nextStep` → nudge the agent
  once in the same session, then fail. Never let empty/unparseable output silently become "done".
- **Worktree slots must not commit our override**: `WorktreeManager` replaces the target repo's `opencode.json` with a
  safe stub and marks it `git update-index --skip-worktree` (or `.git/info/exclude`). Don't reintroduce a path where
  this gets committed.
- **Recovery resumes via the executor**: `CheckpointRecoveryListener` skips already-FAILED/COMPLETED tasks and resumes
  RUNNING ones through `TaskLauncher.resumeAfterRestart` (taskExecutor + `runningTasks`), not a synchronous
  `graphRunner.resume` on the startup thread. A FAILED task's checkpoint is **kept** (not cleaned) so a manual restart
  resumes from the failed node; only COMPLETED/stale RUNNING checkpoints are cleaned.

## Conventions

- **Responsibility split (code vs prompting).** Agent *behavior* — how to determine a repo, whether/when to ask, when to
  launch — lives in prompts (`opencode-config/agents/*.md` for the sidecar agents, `src/main/resources/prompts/*.md` for
  the Java-side classifiers/nodes) and MCP tools (`mcp/`). Java only enforces invariants/boundaries. Express a
  constraint as a **tool schema** (e.g. `launch_task` with required `repo`), not as hardcoded regex/maps/actions in
  Java — hardcoding "agency" (regex `owner/name`, pending-task maps, custom questions) is duplication and gets removed.
  See `ARCHITECTURE_AUDIT.md` §25.
- **TDD.** Features and bugfixes are test-first. Acceptance criteria must be executable (a concrete
  `mvn test -Dtest=...`); the `tester` agent writes JUnit5/MockMvc/Testcontainers tests from the spec, and the developer
  implements against spec + tests. `nextStep: tester` in `analyst.md` means "tests first (TDD)". Don't accept
  implementation without tests.
- **Check IDE inspections before commit.** For every file you touched, run the IDE problem view
  (`idea_get_file_problems`) and **fix all warnings/errors** (unused imports, deprecations, raw types, etc.) before
  committing — never commit with outstanding warnings.
- **Reformat every touched file.** Apply the IDE formatter (`idea_reformat_file`) to each file you changed, so the diff
  matches project style; do this before the commit, not after.

## Config & env

- `opencode-config/opencode.jsonc` (DeepSeek provider + MCP) is mounted into the SIDECAR as global config. The developer
  image is jar-only (`COPY target/developer-*.jar`), so `opencode-config/` is NOT on disk inside the developer
  container.
- Root `opencode.json` is the developer's LOCAL config (MCP incl. `idea` remote, secret refs `{file:./.secrets/...}`).
  Personal — never commit `.secrets/` or `.env`.
- `application.yml` = app config (env-overridable via `${...}`); `.env.template` documents secrets; `docker-compose.yml`
  loads `.env`.
- HITL clarification runs through Telegram; `question: deny` in `analyst.md` because agents run async (the `question`
  tool would block).

## Deploy

- Push to `master` → `.github/workflows/deploy.yml` builds 3 images (`developer`, `opencode-with-mcp`, `grafana-alloy`)
  and deploys to a VPS via SSH. CI runs `./mvnw package -DskipTests`.
- Dev compose = `docker-compose.yml`; prod = `.deploy/docker-compose.prod.yml`.
- Прод тянет образы по **иммутабельным тегам**, которые меняются только при изменении входов сервиса:
  `DEVELOPER_TAG` (`src/main`, `pom.xml`, `Dockerfile`), `OPENCODE_TAG` (`opencode.Dockerfile`, `opencode-config`),
  `ALLOY_TAG` (`grafana-alloy`). Так `compose up -d` пересоздаёт лишь изменившийся сервис. Мутабельный `master`
  кешируется pull-through-зеркалом `dockerhub.timeweb.cloud` и оставлен только fallback'ом для ручного запуска.
- **Каждый коммит в `master` поднимает версию проекта.** В том же коммите обнови `<version>` в `pom.xml` (semver
  `MAJOR.MINOR.PATCH`); не пушь в `master` без bump'а версии. Коммит без изменения версии — ошибка.
- Commit messages are written in Russian (match existing history).

## Known gaps (don't "fix" blindly)

- None right now.

## Docs

- `ARCHITECTURE_AUDIT.md` — numbered decisions + incident fixes; read before large changes.
- `OPENCODE_ASYNC_PLAN.md` — async-OpenCode history, including the sidecar protocol quirks (body must be serialized
  String, `Connection: close`, `messageID` needs `msg_` prefix, assistant reply found via `parentID` in `listMessages`).
- `opencode-config/agents/*.md` — the actual agent prompts (behavior lives here).
