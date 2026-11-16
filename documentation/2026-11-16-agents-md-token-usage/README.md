# 2026-11-16 AGENTS.md token usage

Status: In progress since 2026-11-16. Branch `claude/agent-md-token-usage-6f4893`, from `main` at `5d4b498f0`. `AGENTS.md` and documents only: a documentation-only merge.

Purpose: `AGENTS.md` is loaded into every session of every agent. Owner, 2026-11-16: "minimize agent.md token usage".

Lane: `AGENTS.md`. No source, no test, no format, no tool.

## History

- 2026-11-16: step 1, the same rules in fewer words. 22,388 to 19,405 characters and 3,590 to 3,087 words (13 % and 14 % less). No test run (documents only).
  - Section names are unchanged: Java comments, the code guide, `COLLABORATION.md` and the tool READMEs cite them.
  - Stated once instead of twice or three times: an identity is a declared revision; conservation stays exact (now one line under "Physics and defects"); a whole test task runs only through the final lane; an agent with no skill loader reads `SKILL.md` as a file.
  - Left to the document that already holds it, with a pointer: the lane's steps and options, what `left-out.txt` and `campaign-seeds.txt` hold (`tools/merge-lane/README.md`); the commands the guard blocks (`tools/agent-guard/README.md`); the layout of `documentation/` (`documentation/README.md`). The rules on them (8 minutes, a failing test leaves the list, a holding seed joins the list, under 200 seeds) stay in `AGENTS.md`.
  - One rule changed, to the owner's rule of 2026-11-12 ("you don't need to prove the fix work by running without it again, never do this"): "To prove a gate test fails with its rule disabled, run that one test" is now "Never run a test with its fix or rule disabled to prove the fix", and the `--rerun` exception names only the wall-clock budget hold.

## Open items

- Step 2, for the owner to choose: move the sections that have a clear trigger (the save-format details, the campaign and lane procedure, the documentation folder rules, new equipment, native code) out of `AGENTS.md` into files read on demand, with one line each left behind. About 60 % less in all, at the price that those rules are in context only after the agent opens the file.
- The older tool READMEs that describe rule-off proof runs (`tools/ph-false-root/`, `tools/junction-seeding/`, `tools/crystal-junction-holds/`) are records of past batches and were left as they are.
