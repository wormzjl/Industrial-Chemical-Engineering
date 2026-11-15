# 2026-11-15 tools large files

Bring `tools/` under the large-file rule of AGENTS.md ("Documentation, research and tools"): images, world region files, profiler recordings, binaries, archived Gradle results and files over 1 MB leave the tree for the main checkout's `research/tools/<folder>/`, and `.gitignore` gets `/tools/**` lines like the `/documentation/**` ones. Documentation-only in AGENTS.md's sense (`tools/`, `.gitignore`, `.gitattributes`, indexes, one skill line).

**Status:** Implemented 2026-11-16 (documentation-only merge to `main`, owner: "Merge to main"; not pushed).

## History

- 2026-11-15, phase 1 (`d7d8a5f42`): inventory on `main` `e9edc974b` and proposal. `tools/` 15,015 files, 515.9 MB; 76 files over 1 MB. Nothing tracked changed.
- 2026-11-15, phase 2, owner: "Go, recommended" (O1 A, O2 A, O3 A, O4 A).
  - Copies: 9,494 files (451.3 MB) to `D:/Minecraft/Modding/1.21/CreateChemE/research/tools/<same path>`. Each copy holds the committed blob's exact bytes (8,168 text files were first copied in their CRLF working-tree form, then rewritten from `git cat-file blob`); each was verified against its blob id (`git hash-object --no-filters`) and the SHA-256 list is `research/tools/MANIFEST-2026-11-15.sha256`. Only then `git rm --cached`; the files also stay on disk in this worktree, now ignored.
  - `ce69d8cb7` ignore rules (`/tools/**` block of `.gitignore`; the rig template negation; the model card's `.gitattributes` line removed).
  - `aca3c91f8` readers: `tools/benchmark-population/research_path.py` (`locate`: the `tools/` path if present, else the same path under `research/` of this checkout, then of the main checkout), used by `harness.resolve`, `promotion_common.VALIDATION_INPUTS`, `prepare_populations.VALIDATION` and `evidence.py`'s case maps; `tools/pump-compressor-controls/requirements.txt`; the `models-and-sfx` skill says how to rebuild or copy `python-deps/`. Checked by compiling the five files and one `harness.resolve` call (330 cases) on the authoring Python; no Gradle run (no build or test reads a moved file).
  - Untrack commits: `2c7def91c` junction-holdup-prototype (3,186 files), `c065fe08f` new-units (379), `9c4691705` custom-equipment-rendering-poc (344), `f0e772977` gauge-accessories (629), `a48a06c14` accessory-refinements (43), `49426fac6` wti-pumping-rate-hold (877), `a235bfd8e` other images (338), `fa4af671c` other Gradle results (2,608), `079b3ebef` transcripts, probe outputs and retired models (23), `8734095f8` O1 test-world template (47), `fa4aff1fa` O2 benchmark inputs (6), `2048b741e` O3 neural case maps and model card (3), `4ab18d2eb` O4 python-deps (1,011). Each folder's README got one line saying where its large files are; the six links in the neural READMEs point at `../../research/tools/neural/`.
  - Changes against the plan: the session transcripts' two READMEs stay tracked (only `*.jsonl.gz` moved, ignore line `/tools/session-transcripts/**/*.jsonl.gz`); the `mcp-fixture/` ignore line names the two worlds, because `tools/unified-thermo-port-m2/probes/mcp-fixture/` is probe source; the two small retired models under `neural/retired/resources/` (1.2 MB) moved with the three large ones (nothing reads them).
  - Totals: `tools/` before 15,015 files, 515.9 MB, 76 over 1 MB; after 5,523 files, 64.6 MB, 3 over 1 MB (the rig template's region files, kept: `run-client.js` and `run-server.js` copy them). `git ls-files -i -c --exclude-standard` is empty.

## Documents

- [TOOLS_LARGE_FILES_PLAN.md](TOOLS_LARGE_FILES_PLAN.md): totals, readers, the owner choices O1-O4, the `.gitignore` lines, the phase 2 steps, one inventory table per tool folder.

## Open items

- Owner: the session hook refused the edit of the main checkout's `research/INDEX.md`. Run `bash tmp/tools-large-files/patch-research-index.sh` from this worktree in Git Bash: it adds one row in place after the `documentation/` large-files row (idempotent).
- Merged 2026-11-16 after `main`'s 0.31.1 (fluid step cost; it added no file the new rules ignore). The merge deleted the moved files from the main checkout's `tools/` working tree; the `research/tools/` copies are the canonical ones.
- History keeps the old blobs (`.git` about 755 MB); a history rewrite is not proposed.
