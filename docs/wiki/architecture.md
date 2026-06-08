# Architecture

## Purpose

`jenkins-shared-library` packages reusable Jenkins Pipeline behavior as a Groovy Shared Library. Jenkins jobs call small `vars/` entrypoints with configuration maps; the library handles config merging, node selection, build execution, artifact stashing, deployment, notifications, and cleanup.

## Repository Shape

- `vars/` contains public Jenkins Shared Library entrypoints: Java web deploy, web deploy, Codeup dispatch, Codeup API helper, Git sync, and a small test entrypoint.
- `src/com/daluobai/jenkinslib/steps/` contains reusable build, deploy, Docker, Git, Jenkins, Java web, Tomcat, and web/static deployment classes.
- `src/com/daluobai/jenkinslib/api/` contains wrappers for Codeup, DingTalk, Feishu, Kubernetes, and WeCom integrations.
- `src/com/daluobai/jenkinslib/codeup/` contains safe Jenkinsfile parsing for Codeup repository dispatch.
- `src/com/daluobai/jenkinslib/utils/` contains config, merge, HTTP, JSON, file, endpoint, message, object, string, date, and template helpers.
- `src/com/daluobai/jenkinslib/constant/` contains shared enums/constants.
- `resources/config/` contains default runtime config and Maven settings.
- `resources/template/` contains Java service and shell templates used during deployment.
- `configdemo/` contains Jenkins installation scripts and sample job scripts.
- `test/` contains Groovy/JUnit tests for Codeup API behavior, Jenkinsfile parsing, and dispatch vars.

## Build And Test Setup

The Gradle build applies the Groovy plugin. Main Groovy sources include `src` and `vars`; resources come from `resources`; tests come from `test`. Declared dependencies include Hutool 5.8.42, CloudBees Groovy CPS 1.31, Groovy 4.0.21, and JUnit Jupiter 5.8.1.

## Runtime Model

The major public entrypoints follow this shape:

1. Construct reusable step/API/utility objects with the Jenkins script context.
2. Validate required caller configuration.
3. Merge caller config with defaults from `resources/config/config.json` when applicable.
4. Select build or deploy nodes by Jenkins label, commonly `buildNode`.
5. Execute fixed stage names from local stage lists or `DEPLOY_PIPELINE` config.
6. Delegate build, stash, deployment, probes, and notifications to step/helper classes.
7. Map interrupted builds to `ABORTED`, other exceptions to `FAILED`, and successful runs to `SUCCESS`.
8. Send terminal notifications and clean the workspace in `finally` blocks.

## Main Deployment Paths

`vars/deployJavaWeb.groovy` is the primary Java web deployment entrypoint. It constructs `StepsBuildMaven`, `StepsJenkins`, `StepsJavaWeb`, `StepsTomcat`, `StepsDeploy`, config utilities, and message utilities. It runs the stage order `stepsBuild`, `stepsStorage`, and `stepsDeploy`, then delegates final host deployment to `StepsDeploy.deploy`.

`vars/deployWeb.groovy` is the web/static deployment entrypoint. It constructs `StepsBuildNpm`, `StepsJenkins`, `StepsWeb`, and `MessageUtils`, then runs `stepsBuildNpm`, `stepsStorage`, and `stepsJavaWebDeployToWebServer`.

`vars/dispatchCodeupRepositories.groovy` scans Aliyun Codeup repositories with `CodeupApi`, parses `Jenkinsfile.groovy` through `JenkinsfileInvocationParser`, and dispatches only whitelisted methods, defaulting to `deployJavaWeb` and `deployWeb`.

`vars/syncGit2Git.groovy` selects a build node and delegates repository synchronization to `StepsGit.syncGit2Git`.

## Configuration Model

The default config in `resources/config/config.json` defines Docker registry credentials, Git credentials, agent credentials, Dockerfile repository defaults, shared deployment parameters, and pipeline stage config. Entrypoints merge caller-provided maps into these defaults through config utilities, then access nested config such as `SHARE_PARAM`, package settings, deploy target labels, readiness probes, and message config.

## GitNexus Graph Notes

After `gitnexus analyze`, GitNexus indexed 73 files with 155 nodes and 147 edges. The graph currently contains only `CONTAINS` and `IMPORTS` relationships for this Groovy repository and reports 0 execution flows and 0 clusters. Treat graph data as useful inventory and freshness metadata, but rely on source reads and tests for behavioral flow details.
