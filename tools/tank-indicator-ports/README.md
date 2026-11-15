# Floating-roof tank drawing and layout checks

Batch documentation/2026-10-24-floating-roof-indicator-ports; implementations 08c7127e / 031380ad on codex/floating-roof-indicator-ports. Integration pending.

- TankIndicatorAudit.java checks every 4..40 m diameter, 4..24 m shell and four headings (3108 plans), plus 60 compiled port/layout examples and the six coloured faces. It compares baseline/current binding cells; six compact/tall boxes use the native EquipmentRefresh.plan anchor and retain their world binding. No live Minecraft process state is read or advanced.
- baseline/TankDesign.java is exactly git show 35bad9b8:src/main/java/com/wormzjl/icheme/science/equipment/TankDesign.java. It loads first on the baseline classpath as a historical drawing/layout control. It is not a save migration or old-world test.
- TankIndicatorRender.java adapts tools/storage-vessel-renders/StorageRender.java, draws actual chunk/roof meshes with depth/backface culling and game directional shade, plus the exact TankGaugeGeometry unit faces and TankRenderer proportions. Baseline mode reproduces the prior five-face winding. It generates 24 whole-tank/closeup scenes. The pale-scale sprite is approximated by a flat white-concrete swatch; liquid alpha is shown opaque, no game shader claim.
- GaugePalette.java calls actual TankDisplay.mix on WTI-composition liquid and reads catalog water, without a flash. Values A4BBA875 / 40B9E4F2 are palette fixtures, not the owner's world contents. The initial fixed demonstration colours were removed after owner feedback.
- Run after compiling the owned worktree: `& D:/Minecraft/Modding/1.21/CreateChemE/tools/tank-indicator-ports/run.ps1`. Optional -SourceRoot selects another compiled checkout. Requires JDK 21 and cached Gson 2.10.1; no extra Gradle task. Run `sheets.py` with the bundled Python/Pillow to make comparison/contact sheets in the batch renders/ folder.
- build.log records compileJava/compileTestJava/jar pass; sweep-renders.log records 3108 plans, six native reframes, 60 layouts, outward faces, actual palette values and render files. logs/tank-indicator-ports-logs-2026-10-24.tar.gz retains logs, sources, node/layout controls, patch and artifact hashes.
- Existing storage-port assertions were updated; new focused method tankGaugeFacesPointOutwardsIncludingTheUnderside is in ShapeRasterizerTest. With every dev client closed, run `gradlew test --tests '*.ShapeRasterizerTest.storagePortsStandWhereThePlanPutsThem' --tests '*.ShapeRasterizerTest.tankGaugeFacesPointOutwardsIncludingTheUnderside' --tests '*.EquipmentRefreshTest' jar --max-workers=4`. These gates were compiled, not executed, while the integration client stayed running. Required merge suites remain due.
- No tracked temporary probe, harness, task or instrumentation was added or removed; no removing commit/reattach patch applies. Tool sources live only in this ignored canonical folder.

Final integration candidate 81cabbe9 includes side-access repair b853fe9f and front-gauge placement 63ca8f91. The expanded audit checks actual vacant pipe-neighbor cells and front-facing gauges; native EquipmentRefresh.plan preserves world binding for 138 local reframes. Original feature-delivery results above precede these refinements. integration/ retains the initially caught ladder/pipe overlap, corrected face-distance assertion, eight passing focused checks, final source qualification, and guarded no-overlap merge evidence.

Integration completed as 81cabbe9: eight focused checks pass; quick 1383/slow 185 have four failure messages identical to retained exact 35bad9b8 evidence. Ten local files preserved, target build passed and client PID 19828/session 42388 launched. integration/front-final-results and qualification.json retain the final source checks.

Owner-requested direct cap fix fde01628 (delivery 1ba146c4) follows integration, without another branch/merge: TankGaugeGeometry.markerFaces is hollow, and TankRenderer.column shares the draw path. GaugeCapAudit.java checks 3009 level/end-stop cases, exact annular area, no cap self-overlap and zero liquid overlap versus .1152 m2 for the solid-box control. Compile with JDK 21 against the target build/classes/java/main into an ignored local classes folder, then run com.wormzjl.icheme.science.equipment.GaugeCapAudit with those two classpaths. cap-fix-build.log/cap-audit.log and cap-fix-before/after.json record evidence and all ten preserved hashes. No tests/game input while the owner plays; restart is needed for Java changes. logs/tank-gauge-cap-flicker-logs-2026-10-24.tar.gz retains this evidence.

The original client closed normally (all dimensions saved, successful runClient exit); it was relaunched with the hollow cap fix as PID 47252/session 27137. cap-fix-client-startup.log retains the startup snapshot; no automated world or GUI input.

## Index record (moved from tools/INDEX.md on 2026-10-26)

Purpose: Actual-mesh gauge/port/layout sweeps, guarded integration and cap-overlap proof

Batch: 2026-10-24-floating-roof-indicator-ports; 2026-10-24-tank-gauge-cap-flicker

Status: Archived 2026-10-24. Index: [../INDEX.md](../INDEX.md).

### Entry points

`README.md`, `integration/`, `run.ps1`, `GaugePalette.java`, `sheets.py`, `logs/`

### History

- Archived 2026-10-24 (integrated `81cabbe9`, four baseline failures/no new, ten files preserved, client running; direct cap fix `fde01628` build/3009 cases pass)

Large files (AGENTS.md, batch 2026-11-15-tools-large-files): the 572 images, Gradle results, worlds and other large files of this folder are git-ignored here; their copies are in the main checkout's `research/tools/tank-indicator-ports/`, at the same relative paths.
