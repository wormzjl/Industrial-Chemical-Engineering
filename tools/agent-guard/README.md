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
| Gradle `test`, `holdGates`, `columnGates`, `equipmentGates` or `nativeSmoke` with no `--tests` after it; `check`, `build`, `slowGates` | No repeated tests: a whole task runs only through the final lane (`lane.sh` passes its filters in a file, `-PlaneTests`, which also passes) |
| `--tests` with `*`, `*.*`, `*Test`, `com.wormzjl.icheme.*` or `com.wormzjl.icheme.<package>.*` | No repeated tests: a filter names test classes, not a package tree |
| `-PholdFuzzSeeds` or `LANE_SEEDS` of more than 200 seeds; `lane.sh base`; `lane.sh head --all`, unless the command carries `LANE_FULL=owner` | Measurement: the campaign runs its watch seeds; the full lane and the full campaign only when the owner asks |
| `cargo build`, `test`, `clippy`, `run`, `bench` outside `src/main/rust`, or with `-j`, `--jobs` or `CARGO_BUILD_JOBS` above 4 | Native code: a cargo build uses at most 4 jobs (`src/main/rust/.cargo/config.toml` sets 4, so cargo runs from that folder: the session's folder, or a `cd` earlier in the command) |

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

- `guard.js`: `check(command, cwd)` returns the block reason or `null`; run as a script it reads the hook JSON on stdin and takes `cwd` from it (else its own folder).
- `guard.test.js`: blocked and allowed command lists; an entry `[command, cwd]` runs in that folder.

## History

- 2026-11-10: created; checked through bash (Claude Code), `cmd.exe /d /s /c` and `powershell -Command` (Codex on Windows) spawns.
- 2026-11-13: the cargo rule (batch 2026-11-13-codebase-review, Rust part 2b; plan pitfall 18).
- 2026-11-16: the test rules (batch 2026-11-16-final-lane-cost; owner: "harder enforcement of test only relevant part policy"): whole test tasks, filters over a package tree, campaigns over 200 seeds, the full lane without the owner's word. A quoted `--tests` filter is read, not ignored.
