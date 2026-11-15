# Pressure valve accessory and heavier lever verification

Batch documentation/2026-10-24-pressure-valve-accessory, implementation dc174f27, branch codex/pressure-valve-accessory. Integrated at 4ad6dc97 into claude/floating-roof-tanks-81d68b on 2026-10-24; no main merge/version bump.

- ValveAccessoryRender.java uses actual product polygons, flat material colours and a per-pixel depth buffer. It asserts finite coordinates and retained actuator overhang, and emits 39 model scenes as preview/detail/axis/direction sheets.
- BaselineShutoffValveGeometry.java is git show 2fe26e21:src/main/java/com/wormzjl/icheme/science/equipment/ShutoffValveGeometry.java with only the class name/constructor renamed, for the old/new handle comparison.
- ValveIconRender.java applies actual item JSON GUI transforms to actual meshes, draws 16px icons and rejects projected clipping. Adapted from tools/fluid-display-followups.
- run.ps1 compiles/runs offline tools from already built product classes. Run: & 'D:/Minecraft/Modding/1.21/CreateChemE/tools/pressure-valve-accessory/run.ps1'. It accepts -SourceRoot for another checkout. Requires pinned JDK 21 and cached Gson 2.10.1.
- Renders: documentation/2026-10-24-pressure-valve-accessory/renders/. These software views judge geometry; no Minecraft input is sent.
- build.log/test-summary.json record 3 new pressure adapter tests and 8 existing valve tests passing. geometry-build.log/LineGeometryTest.xml record 7 existing geometry gates passing after fixing sealed surfaces. Final jar passed. Only those targeted classes ran; no GameTests/automated GUI checks/full suite.
- resources.log records JSON parsing and removed standalone block references. No temporary harness, Gradle task/switch or instrumentation was added to tracked code, so no detached-code removal commit/reattach patch applies.
- logs/pressure-valve-accessory-logs-2026-10-24.tar.gz stores source diff, logs, test summaries and tool sources. Fresh-world gameplay checks remain manual; format 18/unit 11 and mod 0.11.2 retained.

Integration: integrate.py validates integration/before.json and the read-only ProcessUnit.expected.java preflight, stashes only overlapping ProcessUnit under a named entry, fast-forwards, applies the exact stash object, and verifies all ten original local files. Nine remain byte-for-byte; ProcessUnit matches the clean three-way result. The already-restored stash object is retained in integration/stash.json as a recovery backup. It is not an outstanding change to apply again. No target WIP was committed/staged.

Required source merge checks: test 1375/four known failures and slowGates 185/one known failure, exactly the b70e92aa baseline failure names. Full XML/counters and target build/startup evidence are in integration/. Merge logs are archived separately under logs/pressure-valve-merge-logs-2026-10-24.tar.gz. The source/renderer archive remains unchanged.

## Index record (moved from tools/INDEX.md on 2026-10-26)

Purpose: Production previews, focused/full verification and WIP-preserving integration

Batch: 2026-10-24-pressure-valve-accessory

Status: Archived 2026-10-24. Index: [../INDEX.md](../INDEX.md).

### Entry points

`README.md`, `run.ps1`, `integrate.py`, `integration/`, `ValveAccessoryRender.java`, `ValveIconRender.java`, `logs/`

### History

- Archived 2026-10-24 (integrated `4ad6dc97`; 18 focused checks, five known full-suite failures/no new failure, ten local files preserved including clean ProcessUnit combination; target jar/client running)

Large files (AGENTS.md, batch 2026-11-15-tools-large-files): the 283 images, Gradle results, worlds and other large files of this folder are git-ignored here; their copies are in the main checkout's `research/tools/pressure-valve-accessory/`, at the same relative paths.
