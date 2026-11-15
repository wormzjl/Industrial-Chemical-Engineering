# custom-equipment-rendering-poc

Purpose: in-game checks and FPS lanes of the custom equipment rendering proof of concept.
Batch: `documentation/2026-10-15-custom-equipment-rendering/` (`CUSTOM_EQUIPMENT_POC_REVIEW.md`).

## Contents

- `bridge.js`: drives the langyo/minecraft-mod-mcp mod over its HTTP endpoint (`node bridge.js status | wait-ready | wait-world | shot <png> | cmds <file> | <cmd> '<json>'`). Copied from the minecraft-mcp skill (commit `03952fd33`). Pass slash commands through `cmds <file>`: Git Bash rewrites `/word` arguments into Windows paths.
- `Flatten.java`: drops the zero alpha channel of bridge screenshots (`bridge.js shot` calls it).
- `new-world.sh`: from the title screen of the `runMcpClient` lane, creates a creative superflat world without structures, then runs `setup.txt` and `scene-camera.txt`. Bridge lane only: with Sodium in `run/mcp-client/mods` the bridge's `enter_control_mode` crashes the client (log in `logs/`).
- `setup.txt`: fixed noon, no weather, no mobs. `scenario.txt`: the six check vessels (3×6, 2.5×4, 4×8 built to 5 rows, 6×10, 1×2, 0.5×1) and a view. `view.txt`, `probe.txt`: one-line examples.
- `scene-camera.txt`, `scene-columns.txt`, `scene-empty.txt`: the FPS scene (spectator camera at 120.5 −40 −15.5; 20 columns of D 6 m × T/T 40 m on a 10-block grid; their removal).
- `LayoutCounts.java`: prints blocks, shell cells, unique keys, chunk quads and collision boxes of the production `ShapeRasterizer` for the budget cases of the feasibility review, supported drums and four columns (updated 2026-10-16). Run it against the compiled main classes:
  `java -cp build/classes/java/main tools/custom-equipment-rendering-poc/LayoutCounts.java`
- `logs/fps-lanes-2026-10-15.tar.gz`: client logs of the vanilla, Sodium and Iris lanes (with the `FPS_PROBE` lines), and the `hs_err` of the bridge/Sodium crash.
- `gui-check/`: command files of the constructor check (`EQUIPMENT_CONSTRUCTOR_REVIEW.md`). `setup-autopilot-camera.txt` is the autopilot's `camera.txt` (place a constructor, aim at it). The others are run with `bridge.js cmds`: `gui-block*.txt` and `gui-unblock.txt` put stone in and out of the ghost; `gui-aim-vessel.txt` and `gui-aim-constructor.txt` turn the view.
- `logs/gui-check-2026-10-15.tar.gz`: client logs of the constructor check, and the crash report of the bridge's off-thread `use_item` (fixed by `src/mcpCompat` `UseItemMixin`).
- `textures/VesselTextures.java`: writes the tracked sprites `vessel_*`, `equipment_*` and the neutral `painted_*` (mean 236, tinted in game by the vessel's paint; 16 px per metre, fixed seed): `java tools/custom-equipment-rendering-poc/textures/VesselTextures.java src/main/resources/assets/createcheme/textures/block`. `preview()` writes `preview-painted.png` (each painted sprite under the default paint). `textures/Neutral.java <in.png> <out.png> [...]` turns a coloured sprite into a neutral one for the tint (used for the Blender `ac_ring`, `ac_header`, `ac_plenum`).
- `supports-check/`: the supports check (`VESSEL_SUPPORTS_REVIEW.md`). `scene.txt` places a skirted column and drums along x and z by command; `view-*.txt` set the spectator close-ups; `aim-constructor.txt`, `aim-built.txt`, `drop-on-drum.txt` (survival, so the player falls) and `read-pos.txt` drive the GUI and collision checks. `before/` and `coarse/` hold the shots before the texture refinement and before the finer facets. `logs/supports-check-2026-10-16.tar.gz`: client and test logs, and the crash report of the `Design` class-initialisation NPE (fixed before the check).

## Process equipment check (2026-10-16, `PROCESS_EQUIPMENT_REVIEW.md`)

`process-check/` holds the drums, columns, pipes and accessories check, run from the worktree root on a fresh world (`new-world.sh`):
- `scene.txt`: a constructor and a column calculator, a skirted drum, a lying drum, two columns (`/createcheme equipment column`), and a pipe run through a pump and two valves (one vertical) on the drum's liquid nozzle. `cap-void.txt` and `bulk-line.txt` add a void and a pipe on the west nozzle.
- `view-*.txt`: spectator close-ups. `aim-drum.txt`, `aim-pipe.txt` and `debug-drum.txt` aim and `use_item` the drum or a pipe. The pipe's diameter field is set with `click` at the field's framebuffer pixel, backspaces and `paste_text`; `hotkey ctrl+a` does not select.
- `drop-control.txt`, `drop-cage.txt`, `read-health.txt` and `read-pos.txt` make the climbing check: natural regeneration off, a player dropped in a cage slides to the deck with no damage, and one dropped in open air takes fall damage. The bridge's `press_key` does not move a player in the world.
- Probes:
  - `Ports.java` (nozzle cells) runs with `java -cp build/classes/java/main`.
  - `Ladders.java` and `NodeCell.java` (package-private API) are compiled into a temporary folder with `javac -cp build/classes/java/main` and run with that folder on the classpath.
  - `DrumLineProbeTest.java` and `SavedLedgerProbeTest.java` were JUnit probes in `src/test/java/com/wormzjl/createcheme/runtime/fluid/`, never committed; copy one back to run it.
- Screenshot sources are kept here: `v-*.png`, `v2-*.png` (after the deck fix), `before/constructor-strip-overflow.png`. The batch copies are shots 31–42.
- `logs/process-equipment-client-2026-10-16.tar.gz` holds the three client lanes; `logs/process-equipment-gates-2026-10-16.tar.gz` holds the final gates and the gate script `run.sh`.

## Constructor check (bridge lane)

Open the save without menus through the autopilot: `autopilot.properties` with `label=gui`, `warmup=1500`, `measure=1`, `camera.txt` = `gui-check/setup-autopilot-camera.txt`, and `columns.txt` and `empty.txt` holding only a comment. The client then idles in the world for 25 minutes. Drive it with `bridge.js`: `enter_control_mode`, then `use_item` (opens the screen; needs `UseItemMixin`), `click_button_index`, `shot` and `cmds`. The constructor screen's buttons are 0 Form (cycles Vertical, Lengthwise, Crosswise), 1–12 (− and + per row: diameter, T/T, skirt or saddle height, forward, right, up), 13 Build and 14 Done. The supports check opened a fresh world with `new-world.sh`'s clicks instead of the autopilot (old saves hold the previous cell format). Stop the client by its process (command line contains `mcpClientRunProgramArgs`) and remove `run/mcp-client/fps-autopilot/`.

## FPS lanes

The probe was `src/mcpCompat/java/com/wormzjl/createcheme/mcpcompat/FrameTimeProbe.java`. It was measurement-only and is detached to `frame-time-probe/` (commit 03be157b on the batch branch; README and patch there).
- Bridge lane (vanilla pipeline): open the world, run `scene-camera.txt` and `scene-columns.txt`, then the client command `/fpsprobe 60 60 <label>`; repeat with `scene-empty.txt`.
- Unattended lane (Sodium, Iris): create `run/mcp-client/fps-autopilot/` with `autopilot.properties` (`save=New World`, `label=<lane>`, `warmup=60`, `measure=60`) and the scene files copied as `camera.txt`, `columns.txt` and `empty.txt`, then run `JAVA_OPTS=-Xshare:off ./gradlew.bat runMcpClient --offline --console=plain`. The client opens the save, measures columns and then empty, saves `screenshots/fps-<label>-columns.png` and quits. For Iris, also copy the shader pack to `run/mcp-client/shaderpacks/` and write `config/iris.properties` with `enableShaders=true` and `shaderPack=<zip>`.
- Third-party jars come from local CurseForge instances (Sodium 0.8.12 and Iris 1.8.14-beta.1 from "Simple TFC", Complementary Reimagined r5.7.1 from "All The Mods - Gravitas²"). They are copied into `run/mcp-client` only for the run, then removed with their configs and the autopilot folder.
- AGENTS.md: a timing lane runs alone (no other Gradle run, no test suite); 60 s warm-up + 60 s measurement, one pair per claim.

## Device rendering and constructor check (2026-10-16, `DEVICE_RENDERING_REVIEW.md`)

`device-check/` holds the pipeline-device and constructor check, run from the worktree root on a fresh world (`new-world.sh`, bridge lane):
- `scene.txt`: every device unconnected in a row (z = 0), a bolted line generator–pipe–pump–valve–filter–compressor–pipe–tank (z = 4), and a constructor at 316 −60 0. `view-*.txt` set the close-ups; `aim-constructor.txt` then `use_item` opens the constructor. The choice page's buttons are 0 vertical drum, 1 horizontal drum, 2 column, 3 Done; a design page starts with 0 Change.
- Probes, run against the compiled main classes (`javac -d <tmp> -cp build/classes/java/main BackFaces.java Sweep.java VesselSweep.java`, then `java -cp "build/classes/java/main;<tmp>" Sweep`): `Sweep` casts 4,000 sight lines at each of 8,064 device and pipe keys and counts back faces met first; `VesselSweep` does the same for whole drums and columns; `BackFaces <KIND> <facing> <connections> <code>` lists the polygons one key shows from behind.
- Shots: `a-*` and `b-*` (devices), `c*-*` (constructor pages), `before/owner-filter-valve.png` (the owner's report). Batch copies are shots 43–47.
- `logs/device-check-2026-10-16.tar.gz`: the three client lanes, the test runs before and after the fix, the fluid GameTest run and the owner's `runClient` log.

`drum-check/` holds the drum outlet, shell shading and drum datasheet-tab check (review `DRUM_OUTLET_SHADING_GUI_REVIEW.md`), run from the worktree root on a fresh world (`new-world.sh`, bridge lane, `MSYS_NO_PATHCONV=1`):
- `scene.txt`: drums D3 × 4 and D2 × 4 on 2 m skirts (bottom outlet through the skirt), D3 × 4 on a 1 m skirt (shell outlet kept), a D2 column of 12 trays, a constructor at 316 −60 −8. Chat commands sent back to back can be lost: check the log for "Placed" and resend a missing one alone.
- `view-drums.txt`, `view-outlet.txt`, `view-column.txt` set the spectator shots `d-*`; `aim-drum.txt` then `use_item` opens the drum's tank screen (`g-tank`), button 5 is its Datasheet tab (`g-datasheet`); `aim-column.txt` and `aim-constructor.txt` open the column datasheet and the constructor.
- Bridge limits met: `scroll_at` does not reach a 1.21 screen's four-argument `mouseScrolled`, `call_screen_method` takes one argument, and hovering (`mouse_drag`) goes through `glfwSetCursorPos` / `java.awt.Robot`, which the no-desktop-input rule excludes. A minimized game window renders blank shots (1.7 kB).
- Batch copies are shots 48–51. `logs/drum-check-2026-10-16.tar.gz`: the bridge client log and the unit suite run.

`skirt-check/` holds the hollow skirt, outlet through the access opening, silhouette outline, side connections and nozzle sizing check (review `DRUM_SKIRT_CONNECTIONS_REVIEW.md`), run from the worktree root on a fresh world (`new-world.sh`, bridge lane, `MSYS_NO_PATHCONV=1`):
- `scene.txt`: drums D3 × 4 and D2 × 4 on 2 m skirts, a D4 × 12 lying drum, a constructor at 316 −60 −8. `view-*.txt` set the spectator shots `s-view-*`; `outline-front.txt` and `outline-diagonal.txt` (creative, crosshair on the drum) give `s-outline-*` (`*-zoom.png`: enlarged with System.Drawing).
- Constructor: `aim-constructor-low.txt`, then `use_item`; the choice page's button 0 is the vertical drum. Design page (vertical drum): 0 Change, 1–12 the − and + of diameter, T/T, skirt, forward, right, up, 13 Nozzles, 14 Build, 15 Done. Nozzles page: 0/1 connection, 2 Faces, 3/4 height, 5/6 offset, 7 Add, 8 Remove, 9 Back, 10 Build, 11 Done. Shots `c-*`. `bridge.js cmds` opens the chat and so closes any open screen: set the camera before `use_item`.
- `pipes.txt` puts two pipes on the D3 drum's side connection and two on its bottom outlet; `aim-outlet-pipe.txt`, `use_item`, then `click` at the diameter field (250, 187), eight `press_key` backspaces, `paste_text` and `click` on Apply changes (270, 453) set the first outlet pipe's bore; the bore lands with the next engine update. Shots `p-*`.
- Probes (in `build/tmp/probe3/` during the batch, copied here): `VesselSweep` (default sizes) and `VesselSweepSized <rays> <outside m>` (every nozzle on a pipe of that outside diameter) count sight lines that meet a back face first; `Sizes.java` and `Ports.java` print the check drum's nozzles and port cells.

`aux-layout/` holds the column auxiliaries layout study (plan `COLUMN_AUXILIARIES_PLAN.md`, figures in `documentation/2026-10-15-custom-equipment-rendering/figures/column-auxiliaries/`): `AuxProbe.java` prints a column's tray heights, nozzle rows, decks, box and davit for D 1.5, 3 and 6 m, the numbers the figures are drawn from. Run from a worktree root after a build: put it under `build/tmp/aux-probe/com/wormzjl/createcheme/science/equipment/`, `javac -cp build/classes/java/main -d build/tmp/aux-probe/out <file>`, then `java -cp "build/classes/java/main;build/tmp/aux-probe/out" com.wormzjl.createcheme.science.equipment.AuxProbe`. The figures were checked by rendering them with headless Edge (`msedge --headless=new --screenshot=<png> --window-size=W,H file:///<svg>`).

## Column auxiliaries build and check (2026-10-16, `COLUMN_AUXILIARIES_REVIEW.md`)

`aux-layout/probes/` holds the probes of the build, never committed:
- `AuxClashProbeTest` (192 shapes: three tray sets × eight unit sets × eight diameters; writes `build/tmp/aux-probe/clashes.txt`, "none" when clean), `AuxRenderProbeTest` (a software z-buffer render of the rasterizer's cell polygons: front, plan and two isometric PNGs of three columns), `AuxProbeTest` (boxes, bounds, facet counts, build times: `sizes.txt`) and `AuxMemoryProbeTest` (heap per column geometry: `memory.txt`).
  - These are JUnit probes in `com.wormzjl.createcheme.science.equipment`: copy one into `src/test/java/com/wormzjl/createcheme/science/equipment/` and run `./gradlew test --offline --tests "*AuxClashProbeTest"`.
  - The gate `ColumnAuxiliariesTest` keeps the clash check and a walkway check; the probe adds the wider sweep.
- `FloorProbe`, `StandProbe` and `ExtentProbe` print a column's floors and walkway middles in world coordinates, the collision boxes under a point, and the structure's extents.
  - They use package-private API: `javac -cp build/classes/java/main -d <tmp> <file>`, then `java -cp "build/classes/java/main;<tmp>" com.wormzjl.createcheme.science.equipment.FloorProbe`. `StandProbe` takes `<x> <z>`.

`aux-layout/renders/` holds the probe renders and text outputs at the end of the step.

`aux-check/` holds the in-game check, run from the worktree root on fresh worlds (`new-world.sh`, bridge lane, `MSYS_NO_PATHCONV=1`):
- `scene-a.txt` to `scene-d.txt` place D 3 (default units), D 3 (air, ejector, kettle), D 1 (water, ejector) and D 6 (water, kettle) columns. Each starts with a `tp` next to its site: a far site answers "That position is not loaded". `scene-constructor.txt` places a constructor.
- `view-*.txt` set the shots `x-*`.
- Constructor: run `aim-constructor.txt`, then `use_item`.
  - The choice page's button 2 is Column.
  - On a column's design page: 0 Change, 1–14 the − and + of each row, 15 Import, 16 Units, 17 Build, 18 Done.
  - On the Units page: 0 Condenser, 1 Steam ejector, 2 Reboiler, 3 Back, 4 Build, 5 Done.
  - Shots `c-*`; `x-built.png` is the column built from the page.
- Walkways: `drop-walkway.txt` (survival, a drop from 0.7 m over a walkway middle given by `FloorProbe`), then `read-pos.txt`.
  - Shots `w-*` come after the walkway fix. `before-walkway/` holds the drops that found the narrow walkways.
  - A drop onto a fence band's top (1.4 m) or into it stops there.
- Batch copies are shots 62–70. `logs/aux-check-2026-10-16.tar.gz` holds:
  - the two client lanes;
  - the equipment tests, the full unit suite and the walkway gate's proof run with the rule weakened.

## Standalone units check (2026-10-16, `STANDALONE_UNITS_REVIEW.md`)

`units-check/` holds the check of the standalone units and the column's fixed nozzle sides. It replaces the attached auxiliaries; the `aux-layout/` probes and `aux-check/` scenes apply to 2b642fd1 only. Run it from the worktree root on a fresh world (`new-world.sh`, bridge lane, `MSYS_NO_PATHCONV=1`):
- `scene.txt`: a D 3 column of 20 trays, exchangers of 1.25 × 6 m and 2.5 × 12 m, a kettle (bundle 1.5 m), two air-cooler bays side by side and two ejectors (0.5 m and 0.1 m), all by `/createcheme equipment column|unit`. `scene-ejectors.txt` is the retake after the ejector-frame fix.
- `view-*.txt` set the shots `u-*`.
- `scene-constructor.txt` places a constructor and `aim-constructor.txt` aims at it; then `use_item`. The choice page's buttons are 0–6 for the seven kinds and 7 for Done. A unit's design page has 0 Change, 1–12 the − and + of each row, 13 Build and 14 Done. Shots: `c-choice`, `c-exchanger`, `c-built`.
- `aim-kettle.txt` and `aim-column.txt`, then `use_item`, open their datasheets (`g-*`). Batch copies are shots 71–81.
- `logs/units-check-2026-10-16.tar.gz` holds the two client runs, the full unit suite log (1,233 tests) and the equipment test reports.

## Polish check: ghost, pipe ends, bottom line, paint, smaller units, air cooler banks (2026-10-16, `EQUIPMENT_POLISH_REVIEW.md`)

`polish-check/` holds the in-game check of the polish step, run from the worktree root on a fresh world (`new-world.sh`, bridge lane, `MSYS_NO_PATHCONV=1`):
- `scene.txt` places three drums, pipes with open ends, three air coolers (a 2 × 4 bank of 3 parallel × 2 series among them), four ejectors (0.05–0.25 m) and two constructors. Its second line is a `tp` next to the site: a far site answers "That position is not loaded".
- `view-*.txt` set the spectator shots `p-*` (drums, pipes and their close-up, coolers from the side, top, front, back and under one bay, ejectors, ghosts).
- Paint page: `aim-constructor.txt`, `enter_control_mode`, `use_item`; choice button 0 (vertical drum); design page button 14 is Paint. On the Paint page: 0 Body, 1 Structure, 2–17 the swatches (palette order of `Paint.PALETTE`), 18 the custom #RRGGBB field's row, 19 Default, 20 Back, 21 Build, 22 Done. Shots `g-1` to `g-6`; `view-built.txt` sets `g-6`.
- `probes/OutletRenderProbeTest.java` (never committed): a software z-buffer render of the rasterizer's cell polygons, side and isometric, for the bottom line (drums D 3 on the default and a 4 m skirt, D 6, a D 3 column), the air coolers (3 × 6, 1.5 × 4, the 3 × 2 bank of 2 × 4 bays) and a 0.1 m ejector. Copy it into `src/test/java/com/wormzjl/createcheme/science/equipment/` and run `./gradlew test --offline --tests "*OutletRenderProbeTest"`; it writes `build/tmp/outlet-probe/`. `renders/` holds its outputs at the end of the step.
- `logs/polish-check-2026-10-16.tar.gz`: the client lane and the full unit suite log.

`ac-blender/` holds the air cooler parts script (headless Blender 5.2) and its outputs; see its README. The mod copies of the parts are `assets/createcheme/parts/ac_*.obj` and `textures/block/ac_*.png` (ring, header and plenum neutralised by `textures/Neutral.java`).

## Details check: boot, manways, skirt junction, bolts, air cooler hoppers and fans (2026-10-16, `EQUIPMENT_DETAILS_REVIEW.md`)

`details-check/` holds the in-game check, run from the worktree root on a fresh world (`new-world.sh`, bridge lane, `MSYS_NO_PATHCONV=1`):
- `scene.txt` places two standing drums (3 × 4 m and 1 × 3 m on 2 m skirts), two lying drums (3 × 9 m with a boot, 2 × 6 m), two 3 × 6 m air coolers at 4 m and 3 m, and a constructor. `view-*.txt` set the spectator shots `d-*` (drums, boot, backs, heads, bolts, skirt junction), `a-*` (one fan turning, then still) and `e-*` (the cooler from below, its side and corner; `view-under-owner.txt` is the owner's angle).
- `aim-cooler.txt`, `enter_control_mode`, `use_item` opens the cooler's datasheet; its button is the fans switch (`g-datasheet`, `g-datasheet-still`).
- `aim-constructor.txt`, `use_item`: choice button 1 is the lying drum; the boot toggle sits on its Nozzles page (`g-constructor-boot`).
- FPS: `scene-bank.txt` (a 6 × 4 bank of 3 × 6 m bays, 48 fans), `view-bank-fps.txt`, then `fps-turning.txt`; for the still pair, switch the fans off on the datasheet (`aim-bank.txt`, `use_item`) and run `fps-still.txt`. Each 60 s + 60 s pair runs alone.
- The bridge's `shot` can return the same frame for about a second: take rotation shots 2 s or more apart.
- `ac-blender/` regenerates `ac_fan_ring.obj` (flange and bellmouth narrowed to 1.12 D for the owner's overlap report).
- Shots before the ring and hopper fix (owner report, 21:57): `d-coolers`, `d-under-ac`, `a-fan-*`, `g-datasheet*`; after it: `e-*`, `f-bank`, `g-bank-datasheet`, `h-*` (hoppers from below and the side), `g-constructor-boot` (Nozzles page). Batch copies are shots 94–106.
- `logs/details-check-2026-10-16.tar.gz`: the three client lanes (the FPS lines are `FPS_PROBE`), the unit suite log and `fans-turning.jfr`, a 30 s JFR recording of the client with the bank's fans turning (`jfr print --events jdk.ExecutionSample` / `jdk.NativeMethodSample`; none of the render thread's samples fall in `FanAnimation`).
- `QuadShares.java` (2026-10-16, owner question: would fixed-size parts modelled in Blender be cheaper?): per typical vessel, chunk quads by material, quads of the distinct cell keys, the rasterizer geometry's measured heap and the time to cut every cell. From the worktree root after a build: `javac -cp build/classes/java/main -d build/tmp/quad-probe/out tools/custom-equipment-rendering-poc/QuadShares.java`, then `java -Xmx2g -cp "build/classes/java/main;build/resources/main;build/tmp/quad-probe/out" QuadShares` (the resources hold the OBJ parts).

## Performance check: quads, heap, Blender merges (2026-10-16, `EQUIPMENT_PERFORMANCE_REVIEW.md`)

Run from the worktree root after a build (`build/classes/java/main` and the resources on the class path):
- `QuadShares.java` (above) and `ServerHeap.java`: `javac -cp build/classes/java/main -d build/tmp/quad-probe/out tools/custom-equipment-rendering-poc/ServerHeap.java`, then `java -Xmx2g -cp "build/classes/java/main;build/resources/main;build/tmp/quad-probe/out" ServerHeap [server]`. With `server` it calls `ShapeRasterizer.serverOnly()` first and prints what a dedicated server keeps per vessel.
- `perf-check/PartPairs.java` (package `com.wormzjl.createcheme.science.equipment`, it reads `Part.EXACT`): triangles and faces per Blender part, and per shared edge which check refused a merge (off the plane, texture, normals) with the size of the miss. Compile it into its own folder against the main classes and run `com.wormzjl.createcheme.science.equipment.PartPairs`.
- `perf-check/`: `scene.txt` (a 3 m column of 20 trays, a 3 × 6 m air cooler, a 3 × 4 m drum, on a fresh world from `new-world.sh`, bridge lane, `MSYS_NO_PATHCONV=1`), `view-*.txt` for the shots `o-*` (column back, platform rail and rail bend, ladder and rung ends, cooler under and side, fan), `aim-cooler.txt` then `use_item` and button 0 for the fans switch (`o-fan-still-*` against `o-fan-turning-*`). Batch copies are shots 107–114.
- `logs/perf-check-2026-10-16.tar.gz`: the client lane, the full suite runs (the nut-seat failure and the final one) and the server gate's proof run.

## Machines and heaters check: pump, compressor, VC and cabin heaters (2026-10-17, `MACHINES_HEATERS_REVIEW.md`)

`machines-check/` holds the in-game check, run from the worktree root on a fresh world (`new-world.sh`, bridge lane, `MSYS_NO_PATHCONV=1`):
- `scene.txt` places three pumps (0.05, 0.2, 0.4 m), three compressors (0.15 × 2, 0.45 × 4, 0.8 × 6), three VC heaters (2.5 × 4, 4 × 8, 8 × 18), two cabin heaters (6 × 12, 10 × 30) and a constructor. `view-*.txt` set the spectator shots: `m-*` are the first draft (owner verdict "gross shape looks ok, but needs significant improvement"), `n-*` the improvement pass (pumps, pump close and side, compressors, VC, VC under and at the hearth, cabins, cabin front and top).
- GUI: `aim-constructor.txt`, `enter_control_mode`, `use_item`, then the choice page (`g-choice`, `n-g-choice`) and a design page (`g-compressor`, `n-g-compressor`, `n-g-heater`); `aim-pump.txt` / `aim-heater.txt` then `use_item` open a unit's datasheet (`g-pump-datasheet`, `g-heater-datasheet`).
- `MachineCounts.java` (box, shell cells, chunk quads and layout time of each kind at its smallest, middle and largest) and `MaterialCounts.java` (chunk quads by material of four units; the compressor's valve-cap studs were 58 % of its quads). Default package; from the worktree root after a build: `javac -cp build/classes/java/main -d build/tmp/machines-probe/out tools/custom-equipment-rendering-poc/machines-check/MachineCounts.java`, then `java -cp "build/classes/java/main;build/resources/main;build/tmp/machines-probe/out" MachineCounts`.
- `logs/machines-check-2026-10-17.tar.gz`: the step's two client lanes, the full unit suite (1 failure: the determinism source gate) and the gate re-run after the fix.

## Machines and heaters, second pass: ladders, heater nozzles and legs, compressor table and motor, sizes, sheen (2026-10-17, `MACHINES_HEATERS_SECOND_REVIEW.md`)

`machines-check-2/` holds the in-game check, run from the worktree root on fresh worlds (`new-world.sh`, bridge lane, `MSYS_NO_PATHCONV=1`):
- `scene.txt` places three pumps, four compressors (0.15 × 2, 0.45 × 4, 0.8 × 6 and 1.2 × 10 in a second row at z 40), three VC heaters (2.5 × 4, 4 × 9, 12 × 20), a 6 × 12 m cabin, two drums and a constructor. `view-*.txt` set the spectator shots `p-*` (sheen 0) and `s-*` (the same views with `metallicSheen = 0.2` in `run/mcp-client/config/createcheme-common.toml`, a second client session: the quads are cached per cell key, so the value is read when the models bake).
- Ladder drop tests: `survival.txt`, then each `drop-*-ladder.txt` (a point 0.3 m out of the rungs, 0.4 m under the landing, facing the rungs) against its `drop-*-control.txt` (1.5 m further out), then `read-health.txt`; a climber keeps 20 health, the control falls. The points come from `DropPoints.java`.
- `DropPoints.java` (args: kind size length support X Y Z), `Leaks.java` (interior cells beside an outside cell, args: kind size length support) and `MachineCounts.java` (the first pass's table with VC 10 × 20, 12 × 20 and the compressor 1.2 × 10). Package `com.wormzjl.createcheme.science.equipment` for the first two, default package for the third; from the worktree root after a build: `javac -cp build/classes/java/main -d build/tmp/ladder-probe/out <file>`, then `java -cp "build/tmp/ladder-probe/out;build/classes/java/main" <class> <args>`.
- `ShotDiff.java` (mean luminance of a region in two shots and its column profile: the sheen's gain) and `Pair.java` (two crops side by side with labels): `java ShotDiff.java p-x.png s-x.png x0 y0 x1 y1`, `java Pair.java a.png b.png x0 y0 x1 y1 labelA labelB out.png` (single-file launch, no class path). The shots `p-*`, `s-*`, `g-*-2` and the pairs `sheen-*-pair` are in the folder; batch copies are shots 115–125.
- `logs/machines-check-2-2026-10-17.tar.gz`: the equipment test runs (climb, heaters, compressor, sizes), the gate proof with the climb cells disabled, the full unit suite, the gate re-run and the client lanes.

## Machines and heaters, third pass: walkway ends, fireproofing, twin cell, BB1 pump, sheen with shaders (2026-10-17, `MACHINES_HEATERS_THIRD_REVIEW.md`)

`machines-check-3/` holds the in-game check, run from the worktree root on a fresh world (`new-world.sh`, bridge lane, `MSYS_NO_PATHCONV=1`; if `new-world.sh` lands on a wrong screen, press Escape back to the title and run it again):
- `scene.txt` places the pumps 0.2 (OH2), 0.45 and 0.75 (BB1), a VC 4 × 9, cabins 6 × 12, 4 × 25 and 12 × 48 (twins), a drum and a constructor at 580 −60 −40. `view-*.txt` set the shots in `shots/`.
- Fence drops: `survival.txt`, then each `drop-fence-N.txt` (3 m over a closed end's rail band) and `read-health.txt`; the `Pos` line in the client log must read the fence top, not the deck 1.375 m below. Points from `FenceDrops.java`.
- Constructor: `aim-constructor.txt`, `use_item`, button 8 (Compressor), button 4 (+ throws), button 14 (Build). Datasheets: `aim-bb1.txt` or `aim-twin.txt`, then `use_item`.
- `WalkwayEnds.java` (filled voxel centres in each end fence and the floor's last 0.3 m), `EndDebug.java` (the gate's end regions), `BoxDebug.java` (a unit's box and nozzles), `FenceDrops.java`: package `com.wormzjl.createcheme.science.equipment`, args kind size length support [heading | X Y Z]; `MachineCounts.java` (default package) with the BB1 and twin rows. Compile against `build/classes/java/main` as in the second pass.
- `shader-lanes/`: `lane.sh <label> <sheen> <warm-up> <measure> <save> <camera> [pack zip]` copies Sodium, Sodium Extra, EntityCulling, FerriteCore and ModernFix (and Iris with a pack) from the local "Simple TFC" instance, runs the FrameTimeProbe autopilot with `--no-configuration-cache`, keeps the shot and the `FPS_PROBE` lines in `build/tmp/shader-lanes/`, and removes every jar, pack and new config file; `all.sh` runs the five Iris packs from "All The Mods - Gravitas²_" after `busy.ps1` reports no other Gradle test run (stopping it on Windows leaves its child lane running: let the lane finish). `camera.txt` is the `view-shader.txt` view at noon. The save is a copy of the scene world under `run/mcp-client/saves/`.
- The lanes need `src/mcpCompat` `WindowHandleMixin`: without it the bridge 0.4.7-osguard faults in `glfwGetWin32Window` under Sodium (`logs/`: `hs_err-sodium-bridge.log`).
- `logs/machines-check-3-2026-10-17.tar.gz`: the equipment test runs (fence, fireproofing, twin, BB1, the gate proof with 0.1 m fences, final gates), the full unit suite and the crude test alone, the bridge-lane client log and the six autopilot lanes.

## Constructor ghost under shaders, pipe spacing (2026-10-17, `GHOST_PIPE_SPACING_REVIEW.md`)

`machines-check-4/` holds the checks for the owner's two reports ("the structure preview was broken" under Iris; "piping connection should have 1 block spacing between them"):
- `PortSpacing.java` prints, for every unit size (shortest, middle, longest), 40 drums and the test columns, the nozzle pairs whose pipe blocks (the block beyond each flange face) coincide, share a face or touch by an edge or corner; `pairs-before.txt` (110 pairs on the layouts before the fix) and `pairs-after.txt` (0). `PortCells.java` (args: kind size length) lists one unit's nozzles with their pipe blocks and the box; `ColumnRoles.java` each test column's nozzle roles and rows (`columns-before.txt`, `columns-after.txt`: no side nozzle dropped); `Supports.java` the default supports used in `scene.txt`. Package `com.wormzjl.createcheme.science.equipment`; from the worktree root after a build: `javac -cp build/classes/java/main -d build/tmp/port-probe/out <file>`, then `java -cp "build/tmp/port-probe/out;build/classes/java/main;build/resources/main" com.wormzjl.createcheme.science.equipment.<class> [args]`.
- `scene.txt` (fresh world from `new-world.sh`, bridge lane, `MSYS_NO_PATHCONV=1`) places VC heaters 2.5 × 4 and 4 × 9, an exchanger and a kettle 0.5 × 2, an ejector 0.05 and a cabin 4 × 6 and a constructor; `view-*.txt` set the shots `vc`, `vc-front`, `small`, `cabin` and `ghost` (vanilla, constructor ghost) in `shots/`.
- Ghost under shaders: `camera-ghost.txt` is the camera for the third pass's `shader-lanes/lane.sh` at a new constructor (Iris with Complementary Reimagined, Sodium alone); `shots/ghost-iris-before.png` (no ghost) against `ghost-iris-after.png`, and the Sodium pair (visible in both).
- `logs/machines-check-4-2026-10-17.tar.gz`: the gate run, the drum-clash proof with `crowds` out of `clashes()`, the bridge-lane client log, the two ghost lanes (client logs and `FPS_PROBE` summaries) and the probe outputs.

## Heater joints, compressor pistons, machine motion (2026-10-18, `HEATER_JOINTS_MACHINE_MOTION_REVIEW.md`)

`machines-check-5/` holds the checks for the owner's four notes (soot-blower red dots, heater section joints, compressor pistons, pump and compressor animations):
- `MovingParts.java` (package `com.wormzjl.createcheme.science.equipment`, args kind size length support) prints each moving part's pivot and axis in box coordinates, speed, phase, crank, reach and polygon count: use it to aim the camera (box origin from the "Placed ... at" log line, plus the pivot; the eye is 1.62 above a `tp` point). From the worktree root after a build: `javac -cp build/classes/java/main -d build/tmp/motion-probe/out <file>`, then `java -cp "build/tmp/motion-probe/out;build/classes/java/main;build/resources/main" com.wormzjl.createcheme.science.equipment.MovingParts COMPRESSOR 0.45 4 2`.
- `Stack.java` (default package; args out x y w h scale in...) stacks the same crop of several shots, enlarged, to compare a moving part between captures.
- `counts-before.txt`, `counts-after.txt`: `machines-check-3/MachineCounts.java` before the change and on the final code.
- `scene.txt` (fresh world from `new-world.sh`, bridge lane, `MSYS_NO_PATHCONV=1`) places pumps 0.2 and 0.6, a compressor 0.45 × 4, a VC 4 × 9, a cabin 6 × 12 and an air cooler 3 × 6; `scene-2.txt` a VC 6 × 10 (two convection modules). `view-*.txt` set the shots in `shots/`; `*-stack.png` are the crops (rods three times, pump fans and collars twice).
- Motion shots: the background client refreshes its frames slowly, so bridge captures less than about 2 s apart are identical; take them 5 to 6 s apart.
- Iris: `camera-iris.txt` is the camera for `machines-check-3/shader-lanes/lane.sh motion-iris 0.2 90 2 "New World (27)" <camera> <Complementary Reimagined zip>`; the bridge shoots during the 90 s warm-up (`iris-rods-*.png`). Chat commands sent through the bridge do not reach the autopilot client (the chat opens empty; `close_screen` closes it), so that lane keeps one camera.
- `logs/machines-check-5-2026-10-18.tar.gz`: the equipment test runs (before the piston test fix, after it, and on the final code), the bridge-lane client log and the Iris lane log with its `FPS_PROBE` summary.

## Index record (moved from tools/INDEX.md on 2026-10-26)

Purpose: Proof-of-concept checks of the equipment shell (and since 2026-10-16 `device-check/`: pipeline-device sight-line probes and the constructor choice page check; `drum-check/`: drum bottom outlet, shell shading and the drum datasheet tab; `skirt-check/`: hollow skirt with port and slots, silhouette outline, drum side connections and nozzles at the pipe's size; `aux-layout/`: the column auxiliaries layout probe, and the build's clash, render, size, heap and floor probes with their renders; `aux-check/`: the column auxiliaries in-game check, both for 2b642fd1 only; `units-check/`: the standalone units and fixed column nozzle sides check): bridge client and world-creation script, scene command files, the FPS scene (20 columns 6 x 40), `LayoutCounts` (production rasterizer against the estimate), archived lane logs and the bridge/Sodium crash log, the constructor check (`gui-check/` command files, its logs and the off-thread `use_item` crash report); the supports check (`supports-check/` command files and shots, its logs and the `Design` class-init crash report); the process equipment check (`process-check/`: drums, columns, pipes and accessories scene, climbing and deck-fit drops, probes `Ports`, `Ladders`, `NodeCell`, `DrumLineProbeTest`, `SavedLedgerProbeTest`, shots; gate and client logs); `textures/VesselTextures.java`, the generator of the tracked vessel and equipment sprites; the probe `FrameTimeProbe` (with an unattended autopilot, also used to open the save for the constructor check) still lives in `src/mcpCompat` and is detached here before the batch merges; `polish-check/`: the polish step's check (ghosts, blind-flanged pipe ends, bottom line, Paint page, air cooler banks, small ejectors) with the render probe `OutletRenderProbeTest`; `ac-blender/`: the scripted Blender air cooler parts; `textures/Neutral.java`, which neutralises a sprite for the paint tint; `details-check/`: the details step's check (boot, manways, skirt junction, bolts, air cooler hoppers, the fans switch and its FPS pair with a JFR recording); `QuadShares.java`, `ServerHeap.java` and `perf-check/`: quads, client and server heap per vessel, Blender merge refusals, and the look check of the performance step

Batch: 2026-10-15-custom-equipment-rendering

Status: Concluded 2026-10-20 (batch in 0.10.0). Index: [../INDEX.md](../INDEX.md).

### Entry points

README.md, bridge.js, Flatten.java, new-world.sh, LayoutCounts.java, *.txt, gui-check/, textures/, supports-check/, logs/fps-lanes-2026-10-15.tar.gz, logs/gui-check-2026-10-15.tar.gz, logs/supports-check-2026-10-16.tar.gz, process-check/, logs/process-equipment-client-2026-10-16.tar.gz, logs/process-equipment-gates-2026-10-16.tar.gz, device-check/, logs/device-check-2026-10-16.tar.gz, drum-check/, logs/drum-check-2026-10-16.tar.gz, skirt-check/, logs/skirt-check-2026-10-16.tar.gz, aux-layout/, aux-check/, logs/aux-check-2026-10-16.tar.gz, units-check/, logs/units-check-2026-10-16.tar.gz, polish-check/, ac-blender/, logs/polish-check-2026-10-16.tar.gz, details-check/, logs/details-check-2026-10-16.tar.gz, QuadShares.java, ServerHeap.java, perf-check/, logs/perf-check-2026-10-16.tar.gz, machines-check/, logs/machines-check-2026-10-17.tar.gz, machines-check-2/, logs/machines-check-2-2026-10-17.tar.gz, machines-check-3/, logs/machines-check-3-2026-10-17.tar.gz, machines-check-4/, logs/machines-check-4-2026-10-17.tar.gz, machines-check-5/, logs/machines-check-5-2026-10-18.tar.gz

### History

- In progress since 2026-10-15

### Notes

- status corrected 2026-10-26: the batch reached `main`; the folder is kept as its evidence.

Large files (AGENTS.md, batch 2026-11-15-tools-large-files): the 344 images, Gradle results, worlds and other large files of this folder are git-ignored here; their copies are in the main checkout's `research/tools/custom-equipment-rendering-poc/`, at the same relative paths.
