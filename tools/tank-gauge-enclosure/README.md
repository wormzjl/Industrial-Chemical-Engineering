# Tank level-gauge enclosure preview

Batch: `documentation/2026-10-25-tank-gauge-enclosure/`.

Run `run.ps1 -SourceRoot <compiled-feature-worktree>`. The standalone Java2D rasterizer consumes actual clipped `ShapeRasterizer.surface` polygons, cached dynamic band/collar faces, and the tank's actual WTI-composition/water appearance reducer. Twelve views cover filled/empty small-tank gauges and top/bottom pipe connections on medium and maximum tanks, including low and side angles.

The glass texture is read from the feature worktree's exact `equipment_shell.json` material mapping and `gauge_glass.png` custom resource; no Minecraft glass texture is used. Opaque geometry writes the depth buffer first; liquid and glass triangles then sort back-to-front and alpha-blend against the opaque depth buffer. Native face winding is culled. Game lighting and translucent depth behavior are approximated; this is offline drawing evidence, not an in-game check or fluid simulation.

`GaugeEnclosureRender.java` is adapted from the retained `tank-indicator-ports/TankIndicatorRender.java`; the historical tool is untouched. `GaugePalette.java` reuses actual published-colour mixing without a flash. `assemble.py` builds `gauge-enclosure-sheet.png` from `renders/`. No tracked probe was detached, so no removing-commit/reattachment patch applies. No Gradle task, test exclusion or game/client is introduced.

The custom pane is a deterministic code-native graphic, generated from editable `GlassTexture.java` with Java2D. Recreate it with JDK21: `javac -d classes GlassTexture.java`, then `java -Djava.awt.headless=true -cp classes GlassTexture <feature-worktree>/src/main/resources/assets/icheme/textures/block/gauge_glass.png`. The 64x256 RGBA image has a clear neutral centre and restrained edge highlights; no image-generation service or Minecraft glass texture is used.

Implementation `db936513` on `codex/tank-gauge-enclosure`;8 focused checks/package pass. Final64x256 custom pane alpha2..55 with continuous UVs. `verification.json`, `results/`, `verification.log`, `final-package.log` and `preview.log` retain evidence; compressed under `logs/`. No merge/game run/version bump.

Integration2026-10-25:8b2112cf into claude/floating-roof-tanks-81d68b, preserving updatedfffbd514 target. Full quick1419/0, slow187/0 andpackage pass; combined12-view preview passes. Target guard/final state, logs andXML in integration/; compressed logs/gauge-integration-logs-2026-10-25.tar.gz. No client run.

## Index record (moved from tools/INDEX.md on 2026-10-26)

Purpose: Custom glass source, native glass/pipework views and focused geometry evidence

Batch: 2026-10-25-tank-gauge-enclosure

Status: Concluded 2026-10-26 (batch in 0.12.0). Index: [../INDEX.md](../INDEX.md).

### Entry points

`README.md`, `run.ps1`, `GlassTexture.java`, native render source, `logs/`

### History

- In progress since 2026-10-25
- integrated8b2112cf, full suites green

### Notes

- status corrected 2026-10-26: the batch reached `main`; the folder is kept as its evidence.

Large files (AGENTS.md, batch 2026-11-15-tools-large-files): the 306 images, Gradle results, worlds and other large files of this folder are git-ignored here; their copies are in the main checkout's `research/tools/tank-gauge-enclosure/`, at the same relative paths.
