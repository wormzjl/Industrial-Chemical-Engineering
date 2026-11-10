---
name: merge-prep
description: The IChemE merge-to-main checklist - CHANGELOG entry, mod_version proposal, translation commit, code guide update and tooling cleanup. Use when the owner says to prepare, review or perform a merge of a batch branch into main, and before calling a batch ready to merge. Not for documentation-only merges.
---

# Merge preparation (IChemE)

Moved from `AGENTS.md` ("Merging to main", "Code guide", "Tooling cleanup") on 2026-11-10. The rules are unchanged. `AGENTS.md` keeps the pointer; an agent with no skill loader reads this file as a plain file.

Do not start merge preparation until the owner says so.

## Merging to main

Every merge to `main` includes, in the same merge:

- A `CHANGELOG.md` entry in Keep a Changelog format: one line per batch, with its commits and batch folder. Branches record their entry under `[Unreleased]`. The merge turns it into the new version heading.
- A `mod_version` bump in `gradle.properties`:
  - patch for fixes, cleanups and consolidation;
  - minor only for a new feature or a solver or model batch.
  - Propose the version and let the owner choose. Do not jump versions fast.
- The translation commit, when the batch changed `en_us.json`: follow `.claude/skills/translation-commit/SKILL.md`.
- A code guide update (see below).
- The tooling cleanup (see below).

A documentation-only merge changes only `documentation/`, `tools/`, `AGENTS.md`, `.gitignore`, `.claude/skills/`, `.agents/skills/` or the agent hook files (`.claude/settings.json`, `.codex/hooks.json`). It needs none of these.

## Code guide

- The code guide is at `documentation/reference/code-guide/`. `README.md` gives what the mod calculates and the code map. There is one chapter per subsystem.
- Before every merge, update the chapters whose code the batch changed: classes, flows, contracts, defaults, formats and versions, Gradle tasks and gates. Set each touched chapter's snapshot line to the last code commit it describes, and the date. A file cannot name the commit that contains it. Add a chapter and a README row for a new subsystem. Take facts from the code on the merging branch, not from plans.
- A batch that changes no code the guide covers records "code guide: no change" in its review.

## Tooling cleanup

- Before a batch merges, sort everything it created for measurement or investigation. Keep in the code only tests that a gate task runs, code the product needs, and harnesses that a documented Gradle task runs.
- `src/main` holds only code the game reaches. Move code that only tests use to `src/test`, with the data only it reads, whichever batch wrote it. A gate test that needs it does not keep it in `src/main`, and neither does a small link from production code: remove the link. `perl tools/merge-lane/main-reach.pl .` lists such classes; check a method by its callers.
- Move everything else (one-off probes, campaign and analysis scripts, in-game rigs, instrumentation, baseline patches, run configurations and Gradle switches that only served them) to `tools/<tool-folder>/` on the batch's branch, with a row in `tools/INDEX.md`. Do not leave it in `documentation/` batch folders or in `tmp/`.
- The folder's row in `tools/INDEX.md` holds only the folder, the purpose in a few words, the batch and the status with its date.
- Each tool folder has a `README.md` with its purpose, the batch it served, how to run it, its entry points and its history. For code removed from a tracked path, the README also gives the removing commit and how to re-attach it: source files plus a patch against that commit, so that `git show <sha>:<path>` and the stored copy agree.
- The batch review names the tool folders.
- A cleanup does not widen or narrow a gate suite. After it, run only what it can affect (see "No repeated tests" in `AGENTS.md`), record that in the batch review, and add a `CHANGELOG.md` line for the detached code.

## Before the merge commit

- `git ls-files -i -c --exclude-standard` is empty.
- The final lane has run once for the batch on the final code (`AGENTS.md`, "Gradle, measurement and verification").
