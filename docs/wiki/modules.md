# Modules

## Public Vars Entrypoints

| Entrypoint | Purpose |
| --- | --- |
| `deployJavaWeb(Map customConfig)` | Main Java web build, stash, deploy, notification, and cleanup pipeline. |
| `deployWeb(Map customConfig)` | Web/static build, stash, deploy, notification, and cleanup pipeline. |
| `dispatchCodeupRepositories(Map config = [:])` | Scans Codeup repositories, parses supported Jenkinsfiles, and dispatches whitelisted deploy calls. |
| `syncGit2Git(Map customConfig)` | Synchronizes one Git repository to another using configured credentials. |
| `codeup()` | Returns a `CodeupApi` instance bound to the Jenkins script context. |

## Steps Package

- `StepsBuildMaven` handles Java/Maven build preparation and Docker-backed package creation.
- `StepsBuildNpm` handles web/npm build preparation and Docker-backed asset creation.
- `StepsJenkins` provides Jenkins helpers such as node lookup by label and artifact stashing.
- `StepsDeploy` resolves deployment labels, fans out to deployment nodes, delegates to Java service or Tomcat deployment, and runs readiness probes or after-run commands.
- `StepsJavaWeb` handles Java service artifact replacement, backup, cleanup, and restart behavior.
- `StepsTomcat` handles Tomcat-style deployment when configured.
- `StepsWeb` handles web/static artifact deployment.
- `StepsDocker` wraps Docker operations such as registry login.
- `StepsGit` contains Git synchronization logic used by `syncGit2Git`.

## API Package

- `CodeupApi` wraps Aliyun Codeup OpenAPI for listing repositories and reading repository files.
- `DingDingApi`, `FeishuApi`, and `WecomApi` support notification delivery through message utilities.
- `KubernetesApi` is present for Kubernetes-facing integration points.

## Codeup Package

`JenkinsfileInvocationParser` is the dispatch safety boundary. It strips supported `@Library` declarations, expects exactly one `customConfig` definition and one method call, accepts literal maps/lists/primitives, and rejects unsupported or dynamic Jenkinsfile shapes. Dispatch tests cover supported deploy calls, defaults, repository filtering, dry-run behavior, unsupported method rejection, and continuing after repository failures.

## Utils Package

- `ConfigUtils` loads configuration from host paths, bundled resources, or URLs and parses JSON into maps.
- `ConfigMergeUtils` deep-copies default config and merges Jenkins parameters into nested dot paths.
- `MessageUtils` sends start/restart/success/failure/aborted notifications through configured channels.
- `JsonUtils`, `HttpUtils`, `FileUtils`, `IoUtils`, `TemplateUtils`, and `EndpointUtils` provide I/O, HTTP, template, and endpoint helpers.
- `AssertUtils`, `ObjUtils`, `StrUtils`, `MapUtils`, and `NumberUtils` provide validation and type/value helpers used across entrypoints and steps.
- `DateUtils` provides date formatting/parsing helpers used by deployment backup naming and other utilities.

## Resources

- `resources/config/config.json` is the central default runtime configuration.
- `resources/config/settings.xml` supports Maven builds.
- `resources/template/service/JavaWeb.service` and `resources/template/shell/javaWeb/*.sh` support Java service deployment on target hosts.

## Tests

Tests live under `test/com/daluobai/jenkinslib` and focus on Codeup API parsing, Jenkinsfile invocation parsing, `codeup` var behavior, and `dispatchCodeupRepositories` behavior. For deployment step changes, add focused tests where feasible and validate manually in a representative Jenkins environment.
