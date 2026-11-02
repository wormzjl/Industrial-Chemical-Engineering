# documentation/

Working documents of CreateChemE: plans, reviews, results, diagnoses, handoffs and guides. Tracked since 2026-10-27 (`bba47f5a`): write them in your worktree and commit them on the task's branch; they reach `main` with the merge.

Start with [INDEX.md](INDEX.md): one short row per batch of work with its purpose and status. Everything else about a batch is in its folder's `README.md`.

## Layout

- `YYYY-MM-DD-topic/`: one folder per batch of work, dated by the day the batch started, so the folder list sorts chronologically. A batch is one coherent piece of work that is planned, reviewed and merged together (a feature, an investigation, a fix series).
- Inside a batch folder:
  - `README.md`: the batch record, with the sections Purpose, Status, Documents, Code areas, History (one line per step, newest last) and Notes (evidence and open items).
  - `<TOPIC>_PLAN.md` for plans and `<TOPIC>_REVIEW.md` for reviews and audits; results, progress logs, diagnoses and handoffs keep their descriptive names.
  - `sources/` for OCR excerpts, `archive/` for frozen probe code and raw results, `codex-branch/` for copies taken from Codex worktrees.
- Large files stay out of this folder (rule in `AGENTS.md`). Screenshots and other images, PDFs, world region files, `*-logs/` folders and any other file over 1 MB live in the main checkout at the same path under `research/`, so `documentation/<batch>/screenshots/a.png` is `research/<batch>/screenshots/a.png`. A document cited by an older path resolves the same way.
- `reference/`: living guides that span batches.
- `repository/`: repository hygiene records (this organization, deleted-branch lists, the old-name lookup, the archived one-file index).

File names were kept unchanged when the flat folder was split on 2026-09-23, so a name cited anywhere (agent memory, commit messages, Java comments, `build.gradle`) can still be found: use [repository/FILE_LOOKUP.md](repository/FILE_LOOKUP.md). The one-file index as it stood before 2026-10-26 is frozen in [repository/INDEX_2026-10-26_ARCHIVE.md](repository/INDEX_2026-10-26_ARCHIVE.md).

## Where other material lives

| Folder | Holds | Tracked |
|---|---|---|
| `documentation/` | Markdown documents per batch (this folder) | yes |
| `research/` | Datasets, literature sources, study harnesses, journals and the large files of `documentation/`; see [research/INDEX.md](../research/INDEX.md) | no |
| `tools/` | Offline scripts, training/evaluation tooling, DWSIM automation and `tools/development.gradle`; see [tools/INDEX.md](../tools/INDEX.md); log archives in `tools/<folder>/logs/` stay local | yes |
| `examples/` | Example datapack and fluid reference/benchmark scripts used by the build and tests | yes |
| `benchmarks/` | Large benchmark outputs (about 2 GB) cited by `tools/development.gradle` defaults; left in place | no |
| `BRANCH_HANDOFF.md`, `BRANCH_HANDOFF_ARTIFACTS.json` (repository root) | V4 neural branch handoff, written there by `tools/capacity-followup/build_branch_handoff.py` | no |
| `CHANGELOG.md` (repository root) | Release history and version rule | yes |

## Update rule

After every task, in the same session that finishes it:

1. Put the task's documents in the batch folder (create `YYYY-MM-DD-topic/` with a `README.md` for a new batch). Reviews go to `<batch>/<TOPIC>_REVIEW.md`, plans to `<batch>/<TOPIC>_PLAN.md`.
2. Update the batch's row in [INDEX.md](INDEX.md): the folder, the purpose in a few words and the current status with its date (Implemented / In progress since / Planned / Concluded / Abandoned or Superseded). Nothing else goes in the index: no history, documents, commits or open items.
3. Update the batch `README.md`: the status, one History line for what the task did (commits, gates, decisions), new documents and code areas, and the open items under Notes.
4. Commit the documents on the task's branch; they reach `main` with the merge. Work that never merges brings them to `main` in a documentation-only merge.
5. On every merge to `main`: add the `CHANGELOG.md` entry and bump `mod_version` in `gradle.properties` (minor per merged batch, patch for fixes-only merges). A documentation-only merge needs neither. The rule is in `AGENTS.md`.
