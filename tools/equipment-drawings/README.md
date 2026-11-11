# Equipment drawing tools

Batch: `2026-11-06-simulator-sizing`. Available since 2026-11-08.

These tracked tools regenerate dimensioned equipment reference drawings from the current game geometry. They do not depend on a previous agent session, ignored compiled overlays, saved player worlds or the equipment proposal workbook in `tmp/`.

## Run

From the repository root on Windows:

```powershell
# Check the authoring dependencies without compiling or writing output.
.\tools\equipment-drawings\update.ps1 -Check

# Regenerate the affected families at their native reference sizes.
.\tools\equipment-drawings\update.ps1 -Only PUMP,EXCHANGER

# Check another equipment heading.
.\tools\equipment-drawings\update.ps1 -Only LENGTHWISE_BOOT -Heading EAST

# Regenerate every constructor kind and the inline/accessory reference models.
.\tools\equipment-drawings\update.ps1
```

The Python entry point is equivalent and works on other platforms:

```text
python tools/equipment-drawings/update.py --check
python tools/equipment-drawings/update.py --only PUMP,EXCHANGER --heading NORTH
python tools/equipment-drawings/update.py --only PUMP,EXCHANGER --sprites
```

`-Only` / `--only` accepts scene IDs (such as `PUMP_OH2`, `PUMP_BB2`, `EXCHANGER_STANDARD`, `EXCHANGER_CROSSFLOW`, `LENGTHWISE_BOOT`, `MOTOR_SMALL`) or native equipment-kind names. Omit it for all references. Crosswise drums use the same native geometry turned to another heading. Scene sizes and variants are defined in `EngineeringExport.java`; add relevant min/max sizes or other variants there when a model change needs them. These references are not an exhaustive constructor-size sweep.

## Dependencies

- Java 21 and this project's Gradle wrapper. The launcher uses `JAVA_HOME` or Java on `PATH` to invoke the project's wrapper JAR directly, avoiding Windows batch-shell quoting. It compiles current main sources and its own exporter through `export.init.gradle`; it starts no client or test suite. Respect the repository's concurrent Gradle limit. Gradle runs offline, so normal project dependencies must already be cached; bootstrap the project's ordinary build dependencies separately if needed.
- Python with the packages in `requirements.txt`:

  ```text
  python -m pip install -r tools/equipment-drawings/requirements.txt
  ```

- Poppler `pdftoppm` on `PATH`, for PDF-to-PNG review.

The PowerShell launcher finds the installed Codex runtime's Python when present, then an ordinary Python on `PATH`. Use `-Python C:\path\to\python.exe` or `ICHEME_DRAWING_PYTHON` to choose an interpreter. It skips the Windows Store Python alias and does not install dependencies or change global configuration automatically. Use the same interpreter when installing requirements.

`-NoRaster` / `--no-raster` is a draft-only option. It does not satisfy the required visual verification. `-Dpi` / `--dpi` controls page rasterization (40-300; default 100).

## Outputs and verification

Every run creates a new directory under `tmp/equipment-drawings/`; prior runs are retained. `latest.json` points to the latest completed run. An explicit `-OutputDirectory` / `--output-dir` must name a new child of that folder.

- `geometry/`: current native mesh, process nozzle, DATA/FE socket, shaft and instrument-mount metadata. Tank references use a stated 50%-fill roof pose; the optional tank gauge is not installed by default.
- `sheets/equipment-drawings.pdf`: bookmarked A3 sheets with front/side/top views at a common scale, an isometric view, dimensions and the complete interface schedule.
- `sheets/pages/`: rasterized PDF pages for visual inspection.
- `sheets/index.html`, `sheets/measurements.json`: an entry point and machine-readable dimensions/coordinates.
- `report.json`, `export.log`: counts, source snapshot/fingerprint and export evidence.

The command checks finite meshes, positive envelopes, PDF headers/page counts and raster page counts. It records visual inspection as pending: inspect the affected PNG sheets and enlarged details before declaring a model change complete, then link the drawing output in the task result. Do not rerun unchanged equipment merely to repeat a previous pass.

The source fingerprint detects a changed authoring input; it is not a material revision or any saved game identity. A source change during export refuses the run. Only the documented reference sizes and chosen heading are covered.

## Current models and design proposals

The default book draws actual `ShapeRasterizer` meshes. It does not apply unimplemented enclosure, nozzle, sensor or control-system proposals. Active proposal drawings remain separate; use the fresh exported geometry as their native input and keep their proposed overlays clearly labelled. Do not replace a reviewed proposal with an unlabelled native reference or describe a proposal as implemented.

The software rasterizer uses neutral engineering material colours and approximate lighting. It does not reproduce the Minecraft texture atlas. For a texture/finish change, supplement the updated sheet with the relevant existing asset-render workflow in [simulator-sizing](../simulator-sizing/README.md), [pump-sprites](../pump-sprites/README.md) or the subsystem's renderer. Record texture/render evidence alongside the sheet. Live lighting/chunk checks still follow `AGENTS.md` and require an owner-requested client check.

### Reviewed interface proposal (historical)

The approved revision M is implemented in the native `EquipmentServices`, `EquipmentInstruments` and `InstrumentWiring` owners. The default export reads those owners, includes fitted manual mount options for review, and adds service close-ups and cable approach examples. `proposals/` preserves the separately labelled historical proposal; it must not be overlaid again on the implemented native meshes. Start with a fresh native run containing the reference IDs below (do not reuse copied geometry after a game-source change):

```text
VERTICAL,LENGTHWISE,LENGTHWISE_BOOT,VERTICAL_BLANKET,LENGTHWISE_BLANKET,LENGTHWISE_BLANKET_SMALL,GAS_BLANKETING_VALVE,COLUMN,FLOATING_ROOF_TANK,TANK_COMPACT,TANK_SLENDER,SPHERE,SPHERE_SMALL,EXCHANGER_STANDARD,EXCHANGER_CROSSFLOW,KETTLE,AIR_COOLER,PUMP_OH2,PUMP_BB2,COMPRESSOR,CENTRIFUGAL_COMPRESSOR,VERTICAL_HEATER,CABIN_HEATER,CABIN_HEATER_SINGLE,STEAM_TURBINE,INDUCED_DRAFT_COOLING_TOWER,NATURAL_DRAFT_COOLING_TOWER,MOTOR_SMALL,MOTOR_LARGE,GENERATOR_SMALL,GENERATOR_LARGE,INLINE_PUMP,INLINE_COMPRESSOR,CONTROL_VALVE,SENSOR_HOUSING,LEVEL_SENSOR_HOUSING
```

Then run, with the same Python used by the parent workflow:

```text
python tools/equipment-drawings/proposals/update.py --native-run tmp/equipment-drawings/<native-run> --output-dir tmp/equipment-drawings/<proposal-run> --prepare
python tools/equipment-drawings/proposals/update.py --output-dir tmp/equipment-drawings/<proposal-run> --prepare-wiring
python tools/equipment-drawings/proposals/update.py --output-dir tmp/equipment-drawings/<proposal-run> --mesh-export
python tools/equipment-drawings/proposals/update.py --output-dir tmp/equipment-drawings/<proposal-run> --render
python tools/equipment-drawings/proposals/audit.py tmp/equipment-drawings/<proposal-run>
```

The helper Java task calls the actual `InstrumentGeometry`, `ConduitGeometry.path` and `ShaftGeometry` owners. The proposal keeps layout, sensor policy, channel catalogue and fixed wiring in shared Python owners. Only the short probe-to-surface metallic conduit is modelled, using native `LineGeometry.conduit`; longer runs, clips and junctions are metallic surface textures, straight on barrels/supports and curved on vessel heads. Reinforced process mounts remain physical geometry; the authoring surface graph and cache are offline drawing tools, never runtime equipment code. Its atlas is `instrument-conduit.png`. Outputs include a labelled PDF/HTML book, PNG sheets, measurements, wiring paths and a reference audit. `audit.py --previous <measurements.json>` also checks retained interfaces and probe positions against a preceding proposal. Generated data and local caches stay ignored.

## Entry points and maintenance

- `update.ps1` / `update.py`: dependency checks, current-source export, drawing generation, rasterization and source manifest.
- `export.init.gradle`: isolated drawing source set and `exportEquipmentDrawings` task; production and test source sets are not widened.
- `EngineeringExport.java`: calls native `ShapeRasterizer` / equipment layout owners; reference sizes and heading selection live here.
- `rendering.py`: the orthographic triangle/depth-buffer renderer and dimension helpers reused from the equipment drawing work. It does not change mesh geometry.
- `sheets.py`: sheet layout, native interface schedules and PDF checks.

Keep this workflow and its dependencies tracked in `tools/`. Update the exporter when a native layout API changes; never fix the drawing by editing copied mesh coordinates. Keep generated meshes, figures, PDFs, classes and logs in ignored output folders.

History: 2026-11-08, promoted the native geometry exporter and reusable drawing/render helpers from the local equipment-interface drawing work. Removed session-specific class paths and a hard-coded source revision; separated current-model reference sheets from the ongoing proposal workbook. No product code or saved formats changed.

Qualification: at `c15dfcecc`, a focused EAST-heading run of `PUMP_OH2,EXCHANGER_CROSSFLOW,GEARBOX` compiled/exported current sources and produced three PDF pages and three PNGs. All three sheets were visually inspected. Local evidence is under `tmp/equipment-drawings/tool-availability-check-2/`; it is not a required input and is not included in Git. This checks the workflow, not every reference size, heading or in-game rendering path.

Proposal history: 2026-11-08, retained the reviewed enclosure/instrument/channel source catalogue and native mesh bridge. Revision K adds reinforced process mounts, a shared straight electrical-entry datum, short native-swept metallic probe links, and texture-only aligned surface routes with curved-head projection. Internal pickups remain concealed. The reference audit checks retained interfaces/positions/channel catalogues and straight external links; this is not runtime or all-size qualification.

Revision L: `proposals/surface_routes.py` defines one shared native-surface baseline and pins every probe branch to it. It owns horizontal/vertical design legs before carrier projection; a mesh path cannot pick a separate trunk for each probe. The common trunk is emitted once. Inclined structural segments use explicitly recorded embedded wiring rather than exterior diagonal decals. The drawing audit checks baseline station alignment, pinned joins, unique trunk emission and every orthogonal design leg, in addition to retained reference interfaces.

Revision M: the vertical-drum P/T/L heads share one front service row; both sphere L1 heads move to their top service platforms without changing channel identity. `surface_routes.py` connects a trunk to its mounting pad through an actual native cross-section, rather than returning axially toward another head or saddle. Small facet transitions follow joined native faces after the H/V intent is fixed; embedded joints are recorded separately. Curved bracket feet seat on their own native facets. `surface_textures.py` paints the long conduit finish directly on native carrier faces, with their original depth/occlusion and no floating ribbon geometry. Full sheets, crops and assembly views use `sensor_layouts.equipment_scene`. The reference audit additionally checks sub-width surface coverage, controller sections, top-level entries, the retained rear reservations and the exact limits on requested position changes. These remain proposal-only checks, not runtime qualification.


`renderEquipmentServices` in `export.init.gradle` supplements the sheets with actual-sprite close-ups (light/dark body paint, normal/low meshes). It reuses `tools/pump-sprites/probes/PumpRender.java`; `ServiceRender.java` supplies only native meshes, socket colours and camera framing. Run it after the native export, supplying the same `-PequipmentDrawingWork=<run-folder>` property. It starts no client. The `service_details.py` pages add mounted enclosure crops, example cable approaches, top-probe plans and reinforced-root close-ups to the native book.

Native interface implementation: the exporter now reads the shared service, probe and wiring owners, checks unresolved routes, and adds mounted-case, probe and cable approach details. The optional --sprites pass uses ServiceRender and the existing PumpRender rasterizer for light/dark paint and normal/low detail proofs. Generated artifacts remain ignored.

Readiness 2026-11-08 (`050cd109`): regenerated the accepted 32 native references, 62 rasterized pages and 18 actual-sprite proofs. All 1,522,758 exported numeric values and the 18 PNGs match the accepted `616fe7a1` output exactly; contacts and full-size representative sheets were inspected. Canonical current evidence is main `research/2026-11-06-simulator-sizing/evidence/readiness-2026-11-08/native-drawings/`. No client or second full lane was started.

Consolidation 2026-11-08 (`df24eb6ac`): `EngineeringExport` and `ServiceRender` draw sockets through `SocketGeometry.polygons(centre, outward, junctionBox, medium, detail)`, which replaced `DataPortGeometry` and `PowerPortGeometry` with the same gland. Both compile against the branch; the exports were not regenerated.

Batch still 2026-11-09 (batch `2026-11-09-batch-distiller`, W2): `EngineeringExport` emits `BATCH_STILL` (500 L, solid fuel, worm tub), `BATCH_STILL_SMALL` (100 L), `BATCH_STILL_LIQUID_COLUMN` (5 stages), `BATCH_STILL_ELECTRIC_VAPOUR` (no condenser) and `BATCH_STILL_LARGE_COLUMN` (2000 L, 8 stages); `rendering.py` colours the new COPPER, BRICK, IRON and WOOD materials. `-Only BATCH_STILL` selects all five by kind. Not yet regenerated.

Gas blanketing 2026-11-11 (batch `2026-11-11-gas-blanketing`, `4c3130472`, `09e106041`): `EngineeringExport` emits `VERTICAL_BLANKET`, `LENGTHWISE_BLANKET`, `LENGTHWISE_BLANKET_SMALL` (a 1.5 m by 4 m lying drum) and the valve alone, `GAS_BLANKETING_VALVE`; with the pressure safety valve beside it, `VERTICAL_VALVES`, `LENGTHWISE_VALVES`, `LENGTHWISE_VALVES_SMALL`, `VERTICAL_VALVES_WIDE` (3 m by 6 m), and the valve alone and on a pipe, `PRESSURE_SAFETY_VALVE`, `PRESSURE_SAFETY_VALVE_ON_PIPE`. A scene id with `_BLANKET` or `_VALVES` adds the valves' meshes (`ShapeRasterizer.blanketValve`, `reliefValve`). The owner judges the drawings.
