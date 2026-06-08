# Processes

GitNexus reported 0 execution flows after the refresh. The flows below are reconstructed from public `vars/` entrypoints, direct source reads, `rg`, and MCP graph inventory.

## Java Web Deployment

Entrypoint: `vars/deployJavaWeb.groovy`

1. Construct Maven build, Jenkins, Java web, Tomcat, deploy, config, and message helpers.
2. Find build nodes with label `buildNode`; fail if none are available.
3. Fill `customConfig.SHARE_PARAM.appName` from `currentBuild.projectName` when blank.
4. On the selected build node, merge config and store it in `globalParameterMap`.
5. Send the start notification.
6. Run `stepsBuild`: optionally apply configured build environment, then call Maven build behavior.
7. Run `stepsStorage`: stash the build artifact through Jenkins helper behavior.
8. Run `stepsDeploy`: send restart notification and call `StepsDeploy.deploy`.
9. `StepsDeploy` resolves deploy labels, enters each target node, and delegates to Java service or Tomcat deployment config.
10. Run readiness probes and optional after-run commands.
11. Convert interrupted builds to `ABORTED`, other exceptions to `FAILED`, and normal completion to `SUCCESS`.
12. Send terminal notification and delete the workspace.

## Web Deployment

Entrypoint: `vars/deployWeb.groovy`

1. Construct npm build, Jenkins, web deploy, and message helpers.
2. Find build nodes with label `buildNode`; fail if none are available.
3. Fill `SHARE_PARAM.appName` from the Jenkins project name when blank.
4. Merge config, store `globalParameterMap`, and send the start notification.
5. Run `stepsBuildNpm` through `StepsBuildNpm`.
6. Run `stepsStorage` to stash build artifacts.
7. Run `stepsJavaWebDeployToWebServer` through `StepsWeb.deploy`.
8. Map build status, send terminal notification, and delete the workspace.

## Codeup Repository Dispatch

Entrypoint: `vars/dispatchCodeupRepositories.groovy`

1. Require `token` and `organizationId`.
2. Default `domain` to `CodeupApi.DEFAULT_DOMAIN`, `ref` to `master`, and allowed methods to `deployJavaWeb` plus `deployWeb`.
3. Optionally validate `allowedRepositoryNames` and build a whitelist of local shared-library closures.
4. Reject unsupported method names before scanning repositories.
5. List repositories through `CodeupApi.listRepositories`.
6. Optionally filter repositories by name.
7. Recursively inspect repository files and parse Jenkinsfiles with `JenkinsfileInvocationParser`.
8. Reject unsupported Jenkinsfile shapes and collect rejected/failed summaries.
9. Dispatch parsed calls unless `dryRun` is true.
10. If `failAtEnd` is true and failures were collected, fail after scanning all repositories.
11. Return a summary map with scanned, dispatched, rejected, and failed counts/details.

## Git Synchronization

Entrypoint: `vars/syncGit2Git.groovy`

1. Construct Jenkins, web, message, and Git helpers.
2. Find a build node by `buildNode` label.
3. Run the Git sync stage on the selected build node.
4. Call `StepsGit.syncGit2Git(orgGitUrl, orgCredentialsId, targetGitUrl, targetCredentialsId)`.
5. Map status, send terminal notification when message config is present, and clean the workspace.

## Operational Notes

Deployment flows depend on Jenkins runtime APIs, configured credentials, node labels, Docker availability, target host shell behavior, and bundled templates. A green Gradle test run validates parser/API behavior, but deployment changes still need representative Jenkins or scripted dry-run validation.
