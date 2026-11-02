# 2026-11-02-docs-large-files

Purpose: keep large files out of `documentation/` and git-ignore them (owner 2026-11-02: "Keep large files like screenshots out of the documentations folder and gitignore them. Make this a rule in agent.md").

Status: In progress since 2026-11-02 (branch `claude/docs-file-size-rules-5d5496`); not merged. Index: [../INDEX.md](../INDEX.md).

## Documents

- This README. The rule is in `AGENTS.md` (section Documentation, research and tools) and `documentation/README.md`.

## Code areas

- `.gitignore` (the `/documentation/**` patterns). No code, so code guide: no change. A documentation-only merge.

## History

- 2026-11-02: rule, ignore patterns and move. `documentation/` went from 4266 tracked files (327 MB) to 951 (13 MB); the largest left is 0.86 MB.
- 2026-11-02: moved 3315 files of 30 batches to `research/<batch>/` in the main checkout, at the same relative paths: images, PDFs, `.mca` region files, the `*-logs/` folders of the fluid scheduling and fluid follow-up batches, and `wp2-settling-probe.json` (2.6 MB). SHA-256 of both copies identical, no collisions. 46 markdown links in 7 documents now point to `research/`.

## Notes

- The files had to leave the index in the same change. A tracked file that matches an ignore pattern fails the merge check `git ls-files -i -c --exclude-standard`.
- History keeps the old blobs: the 703 MB pack does not shrink, and a clone still downloads them. Only a history rewrite would change that; it was not done.
- The links work in the main checkout. A worktree has no `research/`, so they do not resolve there or on GitHub.
- The three frozen rigs (`f1-rig`, `f2-rig`, `wp5-rig`) lost their `.mca` files to `research/`. Their maintained copy is `tools/fluid-in-game-rig/`.
- Open: `tools/` has 1100 images and PDFs (176 MB), not covered by the owner's request.
- Open: `claude/mod-logos-curseforge-7ef03a` adds 156 images under `documentation/`. Move them before that branch merges, or the merge check fails.
- `V3_LITERATURE_CDU_MILESTONE_1.md` cites `evidence/tjl-thesis-figure3-2.png`, which was already missing.
