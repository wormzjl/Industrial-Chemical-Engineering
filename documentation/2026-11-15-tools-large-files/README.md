# 2026-11-15 tools large files

Bring `tools/` under the large-file rule of AGENTS.md ("Documentation, research and tools"): images, world region files, profiler recordings, binaries, archived Gradle results and files over 1 MB leave the tree for the main checkout's `research/tools/<folder>/`, and `.gitignore` gets `/tools/**` lines like the `/documentation/**` ones. Documentation-only in AGENTS.md's sense (`tools/`, `.gitignore`, indexes).

**Status:** Planned (phase 1 done 2026-11-15); phase 2 waits for the owner's go-ahead and choices O1-O4.

## History

- 2026-11-15, phase 1: inventory on `main` `e9edc974b` and proposal. `tools/` 15,015 files, 515.9 MB; 76 files over 1 MB. With the recommendations 9,494 files, 450.1 MB leave the tree; about 66 MB stays. Nothing tracked changed.

## Documents

- [TOOLS_LARGE_FILES_PLAN.md](TOOLS_LARGE_FILES_PLAN.md): totals, readers, the owner choices O1-O4, the `.gitignore` lines, the phase 2 steps, one inventory table per tool folder.

## Open items

- Owner: go-ahead for phase 2 and choices O1 (world templates), O2 (benchmark inputs), O3 (neural case maps and model card), O4 (vendored numpy).
- History keeps the old blobs (`.git` about 755 MB); a history rewrite is not proposed.
