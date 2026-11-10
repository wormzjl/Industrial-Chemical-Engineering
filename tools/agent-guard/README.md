# agent-guard

PreToolUse hook that blocks the git and Gradle commands `AGENTS.md` forbids, for Claude Code and Codex alike.

Batch: `documentation/2026-11-10-agent-instructions-review/` (finding 3).

## What it blocks

| Command | AGENTS.md rule |
|---|---|
| bare `git stash`, `git stash pop`, `git stash push` without `-m` | Git and worktrees: no bare stash; tagged entry + `git stash apply` |
| `git add -f` / `--force` | Git and worktrees: never stage ignored files |
| Gradle with `--rerun-tasks`, `--no-build-cache`, `cleanTest` | No repeated tests: do not force a result again |
| `rm -r build` / `Remove-Item -Recurse build` | No repeated tests: no deleted `build/` |

Text inside quotes, heredocs and PowerShell here-strings is ignored, so commit messages and file bodies that name these commands pass. `--rerun` on one filtered test task (the replay AGENTS.md allows) passes.

## Wiring

- Claude Code: `.claude/settings.json`, PreToolUse matcher `Bash|PowerShell`.
- Codex: `.codex/hooks.json`, PreToolUse matcher `Bash`; `commandWindows` runs it through PowerShell. Codex asks the owner to review and trust a project hook once before it runs.
- Both resolve the script from `git rev-parse --show-toplevel`, so a session started in a subfolder or another worktree finds its own copy. A checkout without the script (a branch before this batch) runs nothing and allows the call.
- On a match the script prints a PreToolUse `permissionDecision: "deny"` JSON on stdout and exits 0. Both tools honour it; an exit code would not survive `powershell -Command`, which turns 2 into 1.

## Run

- Self-test: `node tools/agent-guard/guard.test.js` (prints `ok: N blocked, M allowed`).
- By hand: `echo '{"tool_input":{"command":"git stash"}}' | node tools/agent-guard/guard.js`.

## Entry points

- `guard.js`: `check(command)` returns the block reason or `null`; run as a script it reads the hook JSON on stdin.
- `guard.test.js`: blocked and allowed command lists.

## History

- 2026-11-10: created; checked through bash (Claude Code), `cmd.exe /d /s /c` and `powershell -Command` (Codex on Windows) spawns.
