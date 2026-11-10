# AGENTS.md review against the official Claude Code guidance (2026-11-10)

Source: code.claude.com/docs/en/memory ("Write effective instructions", "AGENTS.md", ".claude/rules/") and /en/sub-agents. Reviewed: `AGENTS.md` at 18700c480 (170 lines, 20,341 characters, about 5k tokens, loaded in every session).

## What already matches

- Loading: the repo has no `CLAUDE.md`, `.claude/CLAUDE.md` or `CLAUDE.local.md`, so Claude Code reads `AGENTS.md` directly (v2.1.277+). Codex reads the same file.
- Size: 170 lines, under the 200-line target. Headers and bullets, no rationale paragraphs (0.11.2 consolidation).
- Concreteness: rules name files, tasks, classes and numbers. Every path, class and Gradle task the file names exists (checked: 20 paths, 6 classes, `slowGates`/`columnGates`/`equipmentGates`/`runMcpClient`, `Package#scientificRevision`, `MaterialCatalog#fluidRevision`).
- GUI rules already live in a skill (`.claude/skills/gui-creation`), the pattern the docs recommend for multi-step procedures.

## Findings

1. **Procedures that only matter at one moment load in every session.** The docs: keep CLAUDE.md to facts needed every session; move multi-step procedures to a skill and part-of-codebase rules to path-scoped rules. Candidates, about 60 lines (35 %):
   - "Merging to main" + "Code guide" + "Tooling cleanup" (lines 140-170): a merge-time checklist. Skill `merge-prep`.
   - "Translations" (102-111): lang edits and the pre-merge translation commit. Skill `translation-commit`, plus one line kept in AGENTS.md ("during a batch change only en_us.json").
   - "New equipment" (96-100): only when adding a `Kind`/`Equipment` constant. Skill or path rule on `EquipmentShape.java`, `ConstructorColumn.java`, `TopologyCompiler.java`.
   - "Small physics simplifications" and the campaign half of "Gradle, measurement" (61-72): only in solver batches.
2. **Codex does not read `.claude/rules/` or skills.** Whatever moves out must stay reachable for Codex: keep a one-line pointer per moved section in AGENTS.md ("Before a merge, read `.claude/skills/merge-prep/SKILL.md`"), as the GUI section does today. Path-scoped rules give Codex nothing, so prefer skills with pointers.
3. **Hard prohibitions are prose, not enforcement.** The docs: to block an action regardless of what Claude decides, use a PreToolUse hook. Mechanical rules fit `~/.claude/hooks/scratch-guard.js` or a project hook: bare `git stash`/`git stash pop`, `git add -f`, `--rerun-tasks`, `--no-build-cache`, `cleanTest`, edits to `documentation/`/`tools/` in the main checkout. A hook does not cover Codex; the prose stays.
4. **Duplication with auto memory.** About 15 MEMORY.md entries restate AGENTS.md rules (lean measurement, tooling cleanup, localization, docs organization, saves, engine schedule, thermo validity, no-save-compat). MEMORY.md loads only its first 200 lines/25 KB and is at about 130 lines. Duplicates cost context and drift (the no-save-compat entry predates the facts/state rule). Proposal: prune those entries to a pointer to the AGENTS.md section (`/consolidate-memory`).
5. **Subagents may start without project instructions.** The docs: subagents that start without project instructions start without AGENTS.md. A brief must then name the AGENTS.md sections that apply. Add one line to the global delegation rule: "every brief names the AGENTS.md sections the task touches".
6. **A local CLAUDE.md would silently drop AGENTS.md.** A `CLAUDE.local.md` or `CLAUDE.md` in the repo or above it makes Claude read only those files. Add a line under "Git and worktrees": "Do not add CLAUDE.md or CLAUDE.local.md; AGENTS.md is the project file."
7. **Soft wording, low priority.** A few lines are not verifiable: "Every fix improves the existing structure", "Keep these as lightweight game-level continuations", "about every 5 s". They are owner principles; leave them unless adherence fails.
8. **Built-in git guidance overlaps.** AGENTS.md and the Claude Code system prompt both give stash rules; they agree today. No change.

## Proposal for the owner

- A (recommended): findings 2, 3, 5, 6 now; move finding 1's merge checklist and translation procedure to two skills with pointers (AGENTS.md about 115 lines); prune memory duplicates (finding 4).
- B: only the one-line additions (5, 6) and the hooks (3); AGENTS.md stays as it is.
- C: no change; run `/doctor prompt-audit` from a terminal session for a second opinion.

Code guide: no change (no code changed). No tests run (documents only).
