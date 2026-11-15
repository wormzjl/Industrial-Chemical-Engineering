# Gauge accessory offline verification

Batch: documentation/2026-10-24-gauge-accessories. Sources remain local and ignored; no tracked probe/task was detached.

After compiling the owned checkout, run `run.ps1 [-SourceRoot <compiled checkout>]` using JDK 21. GaugeAccessoryRender.java reuses the native instrument mesh, InstrumentSockets, LineGeometry and ShapeRasterizer. It emits 125 orthographic software views (reservoir minimum/default/maximum sizes, all headings, taps, three pipe axes and bore extremes, preview colours, under/over-range needle endpoints) under the batch renders folder. It checks dial/tick/needle winding and separate layer depths. Labels use AWT glyphs and sprites use the mod textures; these images are geometry/presentation evidence, not a Minecraft shader/lighting claim.

Run sheets.py with the bundled Python/Pillow for contact sheets. Tool adapted from tools/tank-indicator-ports/TankIndicatorRender.java, retaining its textured triangle rasterizer. Fresh MCP screenshots and compressed build/test/render/client logs stay here. run.ps1 never opens a world or starts Minecraft.

Targeted verification: `gradlew test --tests '*GaugeAccessoriesTest' --tests '*PipelineShutoffValveTest' --tests '*FluidGuiConsistencyTest' --tests '*PhysicalFluidTopologyTest' --tests '*RestResolutionTest' --tests '*IslandCertificateTest' jar`. No test invocation while a dev client runs. Full quick/slow qualification remains due before a later merge.

Final implementation e927f599. Owner narrowed highlights to constructed equipment and chose moderate native-material alpha56 idle/88 aimed with circular muted accent alpha135. Earlier neon/low-strength previews live in preview-history; MCP screenshots precede the final drawing-only refinements. GUI editor interaction remains a manual check.

Integration ff9fdfed, 2026-10-25: required quick1394/slow185 completed with the same four baseline failure messages and no new failure. integration/qualification.json stores exact comparison to retained tank integration evidence. target-before/after.json guard all ten uncommitted target files. The disposable fresh MCP fixture was moved to mcp-fixture/ before integration. runClient uses the existing target checkout; its live log is integration/run-client.log.

Client left running as PID52432/session61029 after successful renderer/asset initialization. Compressed integration evidence: logs/gauge-accessories-integration-logs-2026-10-25.tar.gz.

## Index record (moved from tools/INDEX.md on 2026-10-26)

Purpose: Instrument model/socket software renders, winding/layer audit, focused test and MCP evidence

Batch: 2026-10-24-gauge-accessories

Status: Concluded 2026-10-26 (batch in 0.12.0). Index: [../INDEX.md](../INDEX.md).

### Entry points

`README.md`, `run.ps1`, `GaugeAccessoryRender.java`, `sheets.py`, `logs/`, `preview-history/`

### History

- In progress since 2026-10-24
- feature delivered 2026-10-25, nothing tracked

### Notes

- status corrected 2026-10-26: the batch reached `main`; the folder is kept as its evidence.

Large files (AGENTS.md, batch 2026-11-15-tools-large-files): the 629 images, Gradle results, worlds and other large files of this folder are git-ignored here; their copies are in the main checkout's `research/tools/gauge-accessories/`, at the same relative paths.
