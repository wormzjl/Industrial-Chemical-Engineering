# Fluid GUI verification evidence

Batch: `documentation/2026-10-24-fluid-gui-consistency/`. Implementation `f44930f5` on `codex/fluid-gui-consistency`.

With every dev client closed, run in the owned worktree using JDK 21:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.11'
.\gradlew.bat test --tests com.wormzjl.icheme.client.gui.common.FluidGuiConsistencyTest --tests com.wormzjl.icheme.client.gui.common.ProcessPresentationTest --tests com.wormzjl.icheme.runtime.fluid.FluidPacketCodecTest --tests com.wormzjl.icheme.runtime.fluid.TankOutletsTest --tests com.wormzjl.icheme.runtime.fluid.TankDisplayTest --tests com.wormzjl.icheme.runtime.fluid.OpenTankRuntimeTest --tests com.wormzjl.icheme.runtime.fluid.PipePresentationTest jar --max-workers=4
```

37 checks pass. `logs/fluid-gui-consistency-logs-2026-10-24.tar.gz` contains build logs, final JUnit XML, implementation patch and package manifest/hash. It records focused checks only; it is not merge qualification. No in-game/GUI check or desktop input was performed. No temporary probe or harness was added to product code, so no removing commit/reattach patch applies.

Owner-requested integration: `35bad9b8` on `claude/floating-roof-tanks-81d68b`, 2026-10-24. `integration/` contains full quick/slow results (1382/185 cases, four failures), targeted baseline replay on b52732c9 proving all four messages identical, and the zero-new-failure comparison. Target compile/jar passed and normal runClient was left running (PID 35128). Ten local files were byte-for-byte preserved; `integrate.py` documents the locked prepare/apply preflight and fast-forward, without a stash or file rewriting. It is specific to these commits and refuses another state; do not reuse blindly. `logs/fluid-gui-merge-logs-2026-10-24.tar.gz` retains integration evidence including startup snapshot; the managed baseline checkout was archived. See the batch MERGE_REVIEW.md for limitations and manual checks.

## Index record (moved from tools/INDEX.md on 2026-10-26)

Purpose: Focused GUI/wire/accepted-flow checks, full qualification and local-edit-preserving integration

Batch: 2026-10-24-fluid-gui-consistency

Status: Archived 2026-10-24. Index: [../INDEX.md](../INDEX.md).

### Entry points

`README.md`, `integration/integrate.py`, `integration/`, `logs/`

### History

- Archived 2026-10-24 (integrated `35bad9b8`; quick 1382/slow 185 with four baseline failures/no new; target jar/client running, ten local files unchanged)

Large files (AGENTS.md, batch 2026-11-15-tools-large-files): the 288 images, Gradle results, worlds and other large files of this folder are git-ignored here; their copies are in the main checkout's `research/tools/fluid-gui-consistency/`, at the same relative paths.
