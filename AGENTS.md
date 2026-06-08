<!-- gitnexus:start -->
# GitNexus — Code Intelligence

This project is indexed by GitNexus as **jenkins-shared-library** (163 symbols, 153 relationships, 0 execution flows). Use the GitNexus MCP tools to understand code, assess impact, and navigate safely.

> If any GitNexus tool warns the index is stale, run `npx gitnexus analyze` in terminal first.

## Always Do

- **MUST run impact analysis before editing any symbol.** Before modifying a function, class, or method, run `gitnexus_impact({target: "symbolName", direction: "upstream"})` and report the blast radius (direct callers, affected processes, risk level) to the user.
- **MUST run `gitnexus_detect_changes()` before committing** to verify your changes only affect expected symbols and execution flows.
- **MUST warn the user** if impact analysis returns HIGH or CRITICAL risk before proceeding with edits.
- When exploring unfamiliar code, use `gitnexus_query({query: "concept"})` to find execution flows instead of grepping. It returns process-grouped results ranked by relevance.
- When you need full context on a specific symbol — callers, callees, which execution flows it participates in — use `gitnexus_context({name: "symbolName"})`.

## Never Do

- NEVER edit a function, class, or method without first running `gitnexus_impact` on it.
- NEVER ignore HIGH or CRITICAL risk warnings from impact analysis.
- NEVER rename symbols with find-and-replace — use `gitnexus_rename` which understands the call graph.
- NEVER commit changes without running `gitnexus_detect_changes()` to check affected scope.

## Resources

| Resource | Use for |
|----------|---------|
| `gitnexus://repo/jenkins-shared-library/context` | Codebase overview, check index freshness |
| `gitnexus://repo/jenkins-shared-library/clusters` | All functional areas |
| `gitnexus://repo/jenkins-shared-library/processes` | All execution flows |
| `gitnexus://repo/jenkins-shared-library/process/{name}` | Step-by-step execution trace |

## CLI

| Task | Read this skill file |
|------|---------------------|
| Understand architecture / "How does X work?" | `.claude/skills/gitnexus/gitnexus-exploring/SKILL.md` |
| Blast radius / "What breaks if I change X?" | `.claude/skills/gitnexus/gitnexus-impact-analysis/SKILL.md` |
| Trace bugs / "Why is X failing?" | `.claude/skills/gitnexus/gitnexus-debugging/SKILL.md` |
| Rename / extract / split / refactor | `.claude/skills/gitnexus/gitnexus-refactoring/SKILL.md` |
| Tools, resources, schema reference | `.claude/skills/gitnexus/gitnexus-guide/SKILL.md` |
| Index, status, clean, wiki CLI commands | `.claude/skills/gitnexus/gitnexus-cli/SKILL.md` |

<!-- gitnexus:end -->

<!-- gitnexus-init-wiki:start -->
## GitNexus / Project Wiki

This repository is indexed with GitNexus and has a project wiki generated from GitNexus MCP graph context.

For architecture questions, onboarding, debugging, refactoring, impact analysis,
or cross-module changes, use GitNexus MCP/tools and the project wiki before
making assumptions from plain text search.

Project wiki entry:
- `docs/wiki/README.md`

How to use it:
- Start with the wiki entry page for architecture and module overview.
- Read module-specific wiki pages only when the task touches that module.
- Treat wiki pages as orientation docs, not the final source of truth.
- Verify implementation details against source code and GitNexus graph results.
- Before editing important symbols, run GitNexus impact/context analysis when available.
- If GitNexus reports a stale index, run `gitnexus analyze` before relying on graph results.
- GitNexus may refresh its native `AGENTS.md` context block whenever `gitnexus analyze` runs.
- This workflow does not retain GitNexus-generated or GitNexus-modified `CLAUDE.md` unless explicitly requested.
- After major architecture, module-boundary, or core-flow changes, refresh the index and regenerate this wiki through GitNexus MCP.
<!-- gitnexus-init-wiki:end -->

