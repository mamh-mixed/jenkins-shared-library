<!-- gitnexus-init-wiki:generated -->
# jenkins-shared-library Wiki

This wiki was generated from GitNexus MCP graph data, direct repository reads, official documentation checks, and the current agent model. It was not generated with `gitnexus wiki`.

## Index Status

- Repository: `jenkins-shared-library`
- Path: `C:\workspace\code\github\daluobai-devops\jenkins-shared-library`
- Indexed commit: `2583c90` (`2583c901b70da2961ac90c24e7f6d309c024e896` from MCP registry)
- Indexed at: `2026-05-25 11:38:29` local CLI output
- Analyze command: `gitnexus analyze --force` via the resolved global GitNexus shim after `nvm use 22.22.0`
- Analyze flags: final refresh used `--force`; no `--skip-agents-md`
- Status after analyze: up to date
- Stats after analyze: 74 files, 163 symbols/nodes, 153 relationships/edges, 0 clusters, 0 execution flows
- MCP graph note: Cypher reported 149 `CONTAINS` and 4 `IMPORTS` relationships. `gitnexus_query` warned that FTS indexes are still reported missing even after a force rebuild, so keyword search remains degraded.

## Pages

- [Architecture](architecture.md) - repository layout, runtime model, deployment paths, and GitNexus graph limits.
- [Modules](modules.md) - public `vars/` entrypoints, step classes, APIs, utilities, resources, and tests.
- [Processes](processes.md) - operational flows reconstructed from entrypoint source because the graph reports 0 execution flows.
- [Impact Analysis](impact-analysis.md) - practical change-risk guide and verification expectations.

## Project Summary

This repository is a Groovy Jenkins Shared Library. Jenkins jobs call public scripts under `vars/`, while reusable implementation classes live under `src/com/daluobai/jenkinslib`. The library focuses on configuration-driven CI/CD for Java web services, web/static assets, Git repository synchronization, and Aliyun Codeup repository dispatch.

The main pattern is: merge caller configuration with `resources/config/config.json`, choose Jenkins nodes by label, execute a fixed stage sequence, delegate build/deploy work to step classes, send notifications, and clean up the workspace.

## Source Of Truth

This wiki is orientation documentation. Source code, tests, Jenkins runtime behavior, and fresh GitNexus MCP results remain the source of truth. Before changing important functions/classes/methods, follow the root `AGENTS.md` requirement to run GitNexus impact/context analysis and then verify manually when graph edges are sparse.

## Verified Sources

- GitNexus installation docs were checked: docs state Node.js `>=18.0.0` and recommend `npx gitnexus analyze` or global `npm install -g gitnexus` for regular use.
- GitNexus GitHub/package metadata was checked. Live `npm view gitnexus@latest` reported version `1.6.5`, bin `dist/cli/index.js`, and stricter engine `node >=22.0.0`.
- OpenCode MCP/config/skills docs were checked for global config and local MCP server conventions.
- Local config already contained a `gitnexus` local MCP command in `C:\Users\wuzhao\.config\opencode\opencode.json`, and this OpenCode session exposed GitNexus MCP tools successfully.

## Maintenance Commands

Use Node.js >=22 for current `gitnexus@latest`.

```powershell
nvm list
nvm use 22.22.0
gitnexus analyze
gitnexus status
gitnexus list
```

After major architecture, module-boundary, or core-flow changes, refresh the index and regenerate these wiki pages through GitNexus MCP. Use `gitnexus analyze --force` if MCP query warns that indexes are stale or FTS indexes are missing.
