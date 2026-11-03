# Consolidation review: the process simulator (batch 2026-11-02)

Read only, 2026-11-04, at `a958cecd2`. Scope: `git diff main...HEAD` (251 files, +29 183 / −4 923). Method: `perl tools/merge-lane/main-reach.pl .` (527 classes, 0 reached only by tests), a lang-key scan (`tmp/consolidation/keys.js`), two read-only Opus scans (client; science and network), each claim grepped in `src/main`, `src/test`, `src/fluidGameTest` and `tools/`. No code changed. "None" in Changes: no run result, save format or GUI pixel moves.

## A defect found on the way

- D1 `UnitDoors.java:336`: a splitter's typed flow is read as kmol/h (`value / 3.6`), but the door shows it in the time key's unit (`x.units().moles`, :557; default per second). Typing back what is shown on /s or /min stores a flow 3600 or 60 times off. Proposal: read it as `u.time().perSecond(v) * 1000`, as `SheetUnits.molesOf` does. Changes a result (stored splitter flows), not a save format.

## Findings, best lines deleted against risk first

| # | Where | What to do | −Lines | Risk | Changes |
| --- | --- | --- | --- | --- | --- |
| 1 | `UnitTypes.java:26-56`, `FlowsheetFile.java:85-113, 296-318` | Drop `Aliases`, `ALIASES` (air_cooler→cooler) and the held-key plumbing: FORMAT 2 refuses older files and C2's rename came before it; sheets have no compatibility (owner) | ~35, and `FlowsheetFileTest:365-385` | low | none |
| 2 | `ColumnWire.java:26-41, 357-363, 384-387` | Drop ColumnV3Network leftovers with no caller: `WIRE_SCHEMA_VERSION`, `MAX_DIAGNOSTICS(_LENGTH)`, `writeWireSchema`, `requireWireSchema`, `nonNegative` | ~22 | low | none |
| 3 | `SheetEditor:360 set(…JsonElement…)`, `:636 basis`, `FileColumns:380 keyAt`, `StreamProps:100 firstPhase`, `SheetUnits:71 kelvins`, `SheetContext:181 title` | Drop: no caller anywhere | ~27 | low | none |
| 4 | `UnitDoors.java:398` | Drop the private `Glass` and its adapter; use `Door.Glass` lambdas as `StreamDoors:316` does | ~20 | low | none |
| 5 | `FlowsheetSolver:112,121`, `FlowsheetEngine:21-32`, `SheetSizing:225`, `WiredUnit:22-28,36`, `AirCoolerSizing:52,90`, `ExchangerSizing:91`, `CoolerSizing:44`, `Calculation:51`, `SheetContext:21`, `SheetEditor:86`, `SheetState:42` | Drop overloads and constructors that only tests or tools reach (some reach nothing); about 80 test and tool call sites take the long form | ~60 | low-med | none |
| 6 | `SheetSizing:560-581`; `HeaterSizing:87`, `CompressorSizing:125`, `DrumSizing:146`, `TankSizing:106`; the 20 % spare pick in 5 sizers; `rate()`'s opening in 8 | One `auto(load, @Nullable type)`, one `Selection.spared(…)` and `Choice.NONE`, one `UnitSize.readings(size)` | ~34 | low | none |
| 7 | `SimulatorFrame:112/129`; `SimulatorPage:58-67` vs `SizingPage:1150-1167` | One `footer(int third)` and one builder for the Close, Stop, Undo and Redo slots | ~20 | low | none |
| 8 | Member names `"pa " + id + " cooler…"`: built at `SheetSizing:293, 723-736, 992`, `SizedPassages:75,95`; parsed at `EquipmentNetwork:400-416`, `ConstructorColumn:262-267`, `SizingPage:~180`. Column port names re-declared at `SheetSizing:52,55` | One builder and one parser in `SheetSizing`; `ColumnUnit`'s port constants made public | ~25 | low | none |
| 9 | `SelectionColumn:91 Reader` vs `UnitDoors:112, 251` | One meter reader, the `Reader`, at package level | ~15 | low | none |
| 10 | `SheetEditor:369/378, 172, 654` | `set(String)` and `set(double)` share one body; `apply` and `sizing` call `replace` and `withUnit` | ~14 | low | none |
| 11 | `ProcessSimulatorScreen:486-517, 106` | One key table per dialog; type `host` as `SimulatorBlockHost` and drop its 4 `instanceof` checks | ~18 | low | none |
| 12 | `SizingPage:1102-1132` | One lamp-count helper for units and lines | ~12 | low | none |
| 13 | `ProcessSimulatorNetwork:185-216`, `EquipmentNetwork:164,352` | One menu-and-holder check, one `MAX_DETAIL` clip, `SentDesignTags.wrap`/`unwrap` | ~10 | low | none (same bytes) |
| 14 | `FiredHeaterUnit:67,105`, `FlowsheetCompiler:66,150`, `Limit:234 TYPE_TURBINE`, `SheetSizing:80 UnitEntry.height` | Drop an unread parameter set, a repeated body, two re-inlined `typed()`, an unused constant and a field always null from game code | ~14 | low | none |
| 15 | kmol/h factor ×5 (`ColumnDoors:43,227`, `StreamDoors:24`, `SheetClipboard:28`, `UnitDoors:336`); molar mass fallback ×4 (`SheetContext:109`, `StreamDoors:276`, `StreamProps:74`, `BasisPage:73`) | One constant in `SheetUnits`, one `Fluids.weight(i)`; with D1 | ~8 | low | none |
| 16 | `StreamDoors:115,364`, `UnitDoors:217`, `ColumnDoors:354` | Tab and phase keys parsed in one `Door.View.press(key)` | ~6 | low | none |
| 17 | `AssemblyBlock:30,120-124,132-137` vs `SheetPainter:23-25,83`; `SheetLayout:584-585`; `Skin.java:131-134,149-152` | One grid and one set of plate colours; the tower is a `ColumnBlock.Part` (`ColumnBlock:268`), so its own checks go; `Skin.sign`'s THEME and `icon`'s RUN branches are never reached (the last two from my `1c16e742f`) | ~20 | low | none |
| 18 | `SheetLayout:70` | Drop `Box.slices` (never read) and `Box.column` (always `block != null`) | ~3 | low | none |
| 19 | `gui.icheme.flowsheet.mark.reboiler_units`, `.door.inlet` | Remove from the 5 lang files: no user in `src` or `tools` | 10 | low | none |
| 20 | `ColumnDoors:577 fmt`, `SizingPage:226 metres`, `Tiles:113 typeName` | Call `SheetUnits.number` and `Door.upper` | ~6 | low | NaN text: "NaN" becomes "—" |
| 21 | Water helpers (`EjectorUnit:106`, `ColumnUnit:501`, `CoolerSizing:57`; water index as `length - 1` and as `indexOf(WATER)`) | Move them onto `FlowThermo` | ~12 | med: both index forms must agree (water last) | none |
| 22 | `ExchangerSizing:172,184`, `AirCoolerSizing:158` copy `SizedPassages:135-149`; `ColumnSizing:127-136` vs `ColumnUnit:631-645`; `StreamDoors:225 template` vs `SheetClipboard:70 paste` | Keep `SizedPassages`, `ColumnUnit.product` and `paste` | ~28 | med: flow scaling, an unknown id (throws vs drops), NaN T or P | result only at those edge cases |

About 420 lines deleted in all. Splits that move code and delete none, all low risk with no change: `SheetSizing` (1033) into Proposal (240-336), BlockSizing (338-370, 602-781) and PathWalk (882-1031); `ColumnUnit` (647) into ColumnSides (198-313) and a top-level Solve; `FlowsheetFile` (599) into SizingFactsFile (117-277) and JsonFields (451-598); `SizingPage` (1170) into SizingGlass (732-1092); `ProcessSimulatorScreen` (1070) into SimulatorDialogs (about 250 lines); the glass sections of `UnitDoors` and `ColumnDoors`. Merges: `EjectorLaw` into `EjectorUnit`; `ExchangerRating` into `sizing/`.

Checked and kept: `ProcessSimulatorBlockEntity.savedDesign()` (the fluid GameTests call it), `AssemblyBlock.scale()` (`GuiPrototypes`), `SheetUnits.Basis` beside `ClientConfig.Basis` (`SheetUnits` stays Minecraft-free), `StreamThermo` (the solver tests' seam), every `SheetGestures.Mode` and every `Limit` constant except `TYPE_TURBINE`.

Sequencing: thread A takes science and network (1, 2, 5's science half, 6, 8, 13, 14, 21, 22's first two, the science splits); thread B takes client, kit and lang (3, 4, 5's client half, 7, 9-12, 15-20, 22's last, the client splits, D1). Item 8's `SizingPage` and `ConstructorColumn` edits go to A only after B is done with those files.

## Plan: the one in-game frame-time session (launch nothing until the orchestrator says so)

- Probe: re-attach `tools/custom-equipment-rendering-poc/frame-time-probe/FrameTimeProbe.java` into `src/mcpCompat`, with its package renamed to `icheme`; uncommitted, removed after. It gains GPU time: GL 3.3 `GL_TIME_ELAPSED` queries around the frame (`RenderFrameEvent.Pre/Post`) and around the screen (`ScreenEvent.Render.Pre/Post`), a ring of 4 read 3 frames late so nothing stalls; the screen's CPU time by `nanoTime` between the same events. One `FPS_PROBE` line per case: mean FPS, frame p50/p95/p99 ms, GPU frame and screen p50/p95 ms.
- No desktop input and no bridge clicks: its autopilot (`run/mcp-client/fps-autopilot/autopilot.properties`) opens the save, stands the camera, opens the simulator block by `gameMode.useItemOn`, opens the bundled crude unit, and drives the screen's own state: idle; panning (`view.pan` 2 px a frame, back and forth); T-101's door (a double press on its tag through `gestures`); the Results tab (`act("tab/1")`). Baseline: the same camera, no screen.
- Lane: `runMcpClient`, background mode, `-PmcpClientWidth=1920 -PmcpClientHeight=1080`; `options.txt` with `maxFps:260` and `enableVsync:false`; the window not minimised; `JAVA_OPTS=-Xshare:off`.
- Length: 60 s warm-up and 60 s measured per case, 5 cases, about 12 minutes in one session, alone on the machine (no Gradle, no bench). Report each case as frame ms and GPU ms over the baseline.
