# Impact Analysis

## GitNexus Baseline

- Repository: `jenkins-shared-library`
- Indexed commit: `2583c90`
- Analyze command: `gitnexus analyze --force`
- Analyze flags: final refresh used `--force`; no `--skip-agents-md`
- Status after analyze: up to date
- Stats: 74 files, 163 symbols/nodes, 153 relationships/edges
- Relationship types observed by MCP Cypher: `CONTAINS` 149, `IMPORTS` 4
- Clusters: 0
- Execution flows: 0
- Query warning: FTS indexes missing even after force rebuild; rely on Cypher/context/direct source reads when keyword search quality matters.

## Practical Change-Risk Map

| Area | Risk | Why |
| --- | --- | --- |
| `vars/deployJavaWeb.groovy` | High | Primary Java deployment entrypoint; affects build, stash, deploy, notifications, status mapping, and cleanup. |
| `vars/deployWeb.groovy` | High | Public web/static deployment entrypoint; affects npm builds, artifact handling, and target web deployment. |
| `vars/dispatchCodeupRepositories.groovy` | High | Can fan out deployment calls across many Codeup repositories; parser/whitelist behavior is a safety boundary. |
| `src/.../steps/StepsDeploy.groovy` | High | Central deploy coordinator; resolves target labels, fans out to nodes, delegates deploy type, probes readiness, and runs after commands. |
| `src/.../steps/StepsJavaWeb.groovy` | High | Handles service artifact backup/replacement/restart on target hosts. |
| `src/.../steps/StepsTomcat.groovy` | Medium to High | Handles Tomcat deployment path when selected. |
| `src/.../steps/StepsWeb.groovy` | Medium to High | Handles static/web artifact deployment on target hosts. |
| `src/.../steps/StepsBuildMaven.groovy` | Medium | Java build behavior, Docker build image use, Maven settings, and package output. |
| `src/.../steps/StepsBuildNpm.groovy` | Medium | Web build behavior, Docker/npm environment, and package output. |
| `src/.../codeup/JenkinsfileInvocationParser.groovy` | Medium | Dispatch safety boundary; changes need parser and dispatch tests. |
| `src/.../api/CodeupApi.groovy` | Medium | External Aliyun Codeup API contract used by dispatch. |
| `resources/config/config.json` | Medium | Default runtime behavior for multiple public entrypoints. |
| `resources/template/**` | Medium | Target-host service/shell behavior; test on representative hosts. |
| Notification utilities/APIs | Low to Medium | Usually side-effect reporting, but failures can hide deployment state from users. |

## Required Pre-Edit Workflow

Root `AGENTS.md` requires GitNexus impact analysis before editing any function, class, or method. Because the current graph lacks useful call/process edges for Groovy behavior, use GitNexus first and then verify manually:

1. Run `gitnexus_impact({ target: "<symbol>", direction: "upstream", repo: "jenkins-shared-library" })` before symbol edits.
2. If impact/context cannot resolve the symbol, record that limitation and inspect callers/usages with source reads and `rg`.
3. Read the public `vars/` entrypoint and downstream step/API/util class before changing shared deployment behavior.
4. Run focused tests for parser/API/dispatch changes.
5. For deployment behavior, validate with Jenkins runtime or a representative scripted/manual flow because unit tests do not cover all host-side effects.

## Verification Guidance

- Parser or dispatch changes: run Gradle tests around `JenkinsfileInvocationParserTest`, `DispatchCodeupRepositoriesVarTest`, and `CodeupVarTest`.
- Codeup API changes: run `CodeupApiTest` and inspect external API assumptions.
- Config/default changes: inspect affected `vars/` entrypoints and deployment step classes.
- Template changes: validate generated service/shell behavior on a target host or a safe equivalent environment.
- Build/deploy step changes: validate both source-level tests and Jenkins Pipeline behavior where possible.

## Known Limitations

The refreshed MCP graph is useful for inventory and freshness, but it does not currently expose call chains, communities, or execution flows for this repository. Treat this page's risk map as a practical supplement to GitNexus impact output, not a replacement for fresh impact checks.
