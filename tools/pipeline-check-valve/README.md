# Pipeline check-valve tooling

Batch: 2026-10-24-pipeline-check-valve. Sources 4e5c504d / facet-arrow correction 9ffb294f, integrated b70e92aa on claude/floating-roof-tanks-81d68b. No main merge/version bump. Checkpoint 18/unit 11 requires fresh-world checks.

CheckValveRender.java is an adaptation of tools/pipeline-shutoff-valve/ShutoffValveRender.java. It draws actual CheckValveGeometry with the production line/reducer/arrow helpers, both signs of X/Y/Z at 0.001, 0.05 and 1 m, plus a 0.25 m preview and item/reducer details. It neither loads Minecraft nor solves process state. No source was detached from the product.

The check-valve renderer uses a per-pixel depth buffer for triangles. Average-depth painter ordering could hide slightly offset paint when its centre lay behind the average depth of a larger casting face. This correction affects the offline tool only. Production geometry separately fits paint to actual casting facets and keeps the seam clear of the arrow band.

From the owned worktree, use Java 21 (PowerShell):

```powershell
$taskJdk = 'C:/Program Files/Java/jdk-21.0.11/bin'
$taskTool = 'D:/Minecraft/Modding/1.21/CreateChemE/tools/pipeline-check-valve'
$taskOutput = 'D:/Minecraft/Modding/1.21/CreateChemE/documentation/2026-10-24-pipeline-check-valve/renders'
& "$taskJdk/javac.exe" -encoding UTF-8 -cp 'build/classes/java/main;build/resources/main' -d "$taskTool/classes" "$taskTool/CheckValveRender.java"
& "$taskJdk/java.exe" '-Djava.awt.headless=true' -cp "$taskTool/classes;build/classes/java/main;build/resources/main" CheckValveRender $taskOutput
```

Focused verification (no active client): gradlew test --tests '*PipelineCheckValveTest' --tests '*PipelineShutoffValveTest' --tests '*RetainedSolverTest' --tests '*PressureValveContinuationTest' jar --max-workers=8. 13 tests pass. Regenerable compiled renderer classes stay here; logs and JUnit results are compressed under logs/. Images remain in the canonical batch folder. Existing cold-regime investigation tools/documents were left unchanged.

`integrate.py capture` / `merge` reuse the local-file preservation procedure: capture target hashes, reject overlapping paths, fast-forward from the owned feature branch and verify all bytes/status again. They never stash/stage/commit the target's local edits. Required merge gates retain five known baseline failures (1372 quick/four, 185 slow/one), with no new failure. Integration logs/XML/manifests and startup/reset evidence are in `integration/` and a separate compressed merge log archive. runClient PID 29920/session 42383 was left running; no agent world-load input was issued, but the session loaded a format-17 world and the existing reset path discarded fluid state, as recorded in MERGE_REVIEW.md.

## Index record (moved from tools/INDEX.md on 2026-10-26)

Purpose: Actual-mesh renders, focused/full merge evidence and local-file-preserving integration

Batch: 2026-10-24-pipeline-check-valve

Status: Archived 2026-10-24. Index: [../INDEX.md](../INDEX.md).

### Entry points

`README.md`, `CheckValveRender.java`, `integrate.py`, `integration/`, `logs/`

### History

- Archived 2026-10-24 (integrated `b70e92aa`; 13 focused tests, five known full-suite failures/no new failure; ten local files preserved; target jar/client running; format-reset evidence retained)

Large files (AGENTS.md, batch 2026-11-15-tools-large-files): the 295 images, Gradle results, worlds and other large files of this folder are git-ignored here; their copies are in the main checkout's `research/tools/pipeline-check-valve/`, at the same relative paths.
