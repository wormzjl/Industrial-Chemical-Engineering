# 2026-11-10 agent instructions review

Status: In progress since 2026-11-10. Option A applied on `claude/delegation-rule-refinement-e61ba6`; reaches `main` with a documentation-only merge.

## History

- 2026-11-10: global delegation rule refined in `~/.claude/CLAUDE.md` (main thread / subagent / sub-thread, type named in every approval); not tracked in this repo.
- 2026-11-10: `AGENTS.md` reviewed against the official Claude Code memory and subagent guidance.
- 2026-11-10: owner chose option A, and asked that Codex uses the skills too. Merge and translation procedures moved to skills `merge-prep` and `translation-commit` (AGENTS.md 170 -> 149 lines); `.agents/skills/` symlinks give Codex the same three skills; `tools/agent-guard/` hook wired for Claude Code (`.claude/settings.json`) and Codex (`.codex/hooks.json`); "no CLAUDE.md" and brief rules added; 15 duplicate memory entries archived. Tests: `node tools/agent-guard/guard.test.js` (14 blocked, 11 allowed) and hook spawns through bash, cmd and PowerShell; no Gradle run (no code changed).

## Documents

- [AGENTS_MD_REVIEW.md](AGENTS_MD_REVIEW.md): findings and options A-C.

## Code areas

No game code. `AGENTS.md`, `.claude/skills/`, `.agents/skills/`, `.claude/settings.json`, `.codex/hooks.json`, `tools/agent-guard/`, `.gitignore`.

## Open items

- Codex asks the owner to trust the project hook in `.codex/hooks.json` once.
- Documentation-only merge to `main` on the owner's word.
