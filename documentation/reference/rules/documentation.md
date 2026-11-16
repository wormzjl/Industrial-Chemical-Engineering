# Rules: Documentation, research and tools

The full rules of the `AGENTS.md` section "Documentation, research and tools", with the same force. `AGENTS.md` keeps the core and says when to read this file.

- `documentation/` and `tools/` are tracked: write and commit them in your own worktree, on the task's branch, never in the main checkout. `research/` is git-ignored; its one copy is the main checkout's `D:/Minecraft/Modding/1.21/CreateChemE/research/`, and research material is written there.
- The folders (`documentation/README.md` gives the layout):
  - `documentation/`: one folder per batch, `YYYY-MM-DD-topic/` (the date the batch started), plus `reference/` and `repository/`; indexed in `documentation/INDEX.md`.
  - `research/`: datasets, literature, study harnesses, journals and the large files of `documentation/`, by batch; indexed in `research/INDEX.md`.
  - `tools/`: offline scripts and harnesses, one folder per tool; indexed in `tools/INDEX.md`. `tools/development.gradle` stays at that path.
- Add no top-level folder for documents or scripts. Leave `examples/` where it is.
- A review or audit is `documentation/<batch>/<TOPIC>_REVIEW.md`; a plan is `documentation/<batch>/<TOPIC>_PLAN.md`.
- After every task, in the same session:
  - Put its documents in its batch folder; a new batch gets a new dated folder with a `README.md`.
  - Update the batch's row in `documentation/INDEX.md`. A row holds only the folder, the purpose in a few words and the status with its date: Implemented (date it reached `main`), In progress since, Planned, Concluded (research only), or Abandoned / Superseded.
  - Write the rest in the batch's `README.md`: the status, one history line per task (commits, gates, decisions), the documents, the code areas and the open items.
  - Update `tools/INDEX.md`, or the main checkout's `research/INDEX.md`, if they changed.
  - Commit the documents and tool folders on the task's branch.
- Work that never merges (research only, abandoned) brings its documents and tool folders to `main` in a documentation-only merge.
- Index files change row by row. In a merge conflict in `documentation/INDEX.md` or `tools/INDEX.md`, keep every row of both sides. Patch the main checkout's `research/INDEX.md` in place; never copy it whole.
- Every file in `documentation/` and `tools/` is under 1 MB: text documents, scripts and small inputs. Images, PDFs, world region files and `*-logs/` folders are git-ignored there. Store them, and any other file over 1 MB, in the main checkout at the same path under `research/` (`documentation/<batch>/shots/a.png` goes to `research/<batch>/shots/a.png`) and link them there. A new large file type gets a `.gitignore` line.
- Logs stay git-ignored (`logs/`, `*.log`). Store them compressed in the main checkout, `tools/<tool-folder>/logs/<name>-logs-<date>.tar.gz`; never stage these archives.
