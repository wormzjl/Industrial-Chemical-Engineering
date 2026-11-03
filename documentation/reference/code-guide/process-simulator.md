# Process simulator: the flowsheet, its run, sizing and the block

**Snapshot:** 303270643 (branch `claude/in-game-process-simulator-93dd9a`), 2026-11-04 · **Updated:** 2026-11-04 (new chapter, batch 2026-11-02-process-simulator: W1 to W10b, S1 to S5, C1 to C3, U1 to U2, N1; the sheet's loops of fills one draw call each; reversible passages and sheet FORMAT 2; a stream's flow on the Mole, Mass and Volume key; the bundled crude unit example; Delete in Open; a drag on empty canvas pans; each dialog's confirming key primary; Run lit when out of date; a column frame's empty inside pans; door flows and pressures on the display keys; the client consolidation: `Reader`, the glass in `UnitGlass`, `ColumnGlass` and `SizingGlass`, `SheetUnits.KMOL_PER_HOUR`, `Door.View.press`, one footer builder, templates through the paste rule; a door built once a frame; the autosave off the render thread)

Up: [code guide](README.md)

## Read this when

- A run on the simulator's sheet fails, does not reach a steady state, or shows a mass or heat gap; a unit's door reads wrong.
- You add a unit model, a door row, a port, or a new equipment kind (plan 3.10, below).
- A Sizing row, lamp, Auto size or Size all looks wrong; a pressure path or a line size disagrees with a hand calculation.
- The block will not store, replay or hand over a sheet; an autosave or a flowsheet file will not open; Send to block or the constructor's Import misbehaves.
- You change the flowsheet file, the block's tags or the sent designs: these are facts with corpus images.
- You work on the simulator's screen, its pages and doors, its packets, or the block and its store. [client-gui.md](client-gui.md) keeps only the frame: where the screen is registered, the kit pieces it shares, and `CalculatorColumn` as the column door's builder.

## In one paragraph

Physically, the simulator is a steady-state, sequential-modular process simulator, like the ones plant engineers use to design before they build: feeds of known composition, temperature and pressure flow through units (column, drums, tanks, exchangers, kettles, coolers, fired heaters, pumps, compressors, valves, mixers, splitters, filters, ejectors), each unit closes its own mass and energy balance, and recycle loops are torn and iterated until the torn streams stop changing. In code, a `Flowsheet` (a record of the sheet's facts) is compiled by `FlowsheetCompiler` into `Calculation`s, one or more per unit; `FlowGraph` orders them and picks the fewest tears; `FlowsheetSolver` runs each group once, or loops it (a plain pass, then bounded Wegstein) to a steady state, and closes the sheet's mass and heat. The column is one BLOCK calculation that calls the same V3 solver as the world ([column-v3.md](column-v3.md)), with one calculation per heat side. `SheetSizing` then rates every unit and line against `Limit` rows (red Must, yellow Should, green), proposes real equipment with a 20 % spare (Auto, Size all), and builds the designs Send to block hands to the equipment constructor. The whole run happens on the client, on its own thread; the `process_simulator` block only stores the sheet and its results, replays them to whoever opens it, and lets one player at a time edit (AGENTS.md: the simulator's run is a client tool outside the engine's schedule).

## Class map

Paths are under `src/main/java/com/wormzjl/icheme/`. "pp" = package-private.

| Class | Role | Read it for |
| --- | --- | --- |
| **Science core (`science/flowsheet/`)** | | |
| `Flowsheet` | The sheet's facts: basis, units, streams, settings, sizing facts, unknown fields kept | What a file and the block hold |
| `FlowsheetFile` | The JSON format: `read`, `save`; keeps unknown fields, refuses a newer format (`file.newer`) and an older one (`file.older`) | `FORMAT` |
| `FlowsheetCompiler` | A sheet into `Calculation`s, plus warm-start estimates from the last steady run; reads the sheet's ends as roles (`Wiring.roles`); a unit that cannot run becomes a failing calculation; routes a utility port's water feed to its unit (`waterAlone`, `livePorts`) | `compile`, `estimates`, `typed` |
| `Calculation` (record, `Kind` STEP/BLOCK/CHECK) | One graph node: reads, writes, needs (links), gives (links), a `Law` | How a unit becomes graph nodes |
| `FlowGraph` (pp) | Strongly connected groups in order and the fewest tears (deterministic, `TRIALS` 200 000); links are never torn | Order and tears |
| `FlowsheetSolver` | Runs the plan: a group without tears once; a torn group one plain pass, then bounded Wegstein (one factor per tear); closes mass and heat; `Settings` (flow tolerance 1e-4, temperature 0.01 K, 30 passes, Wegstein factor −5..0) | `run`, `result` |
| `FlowsheetEngine` | The entry: refuses a sheet whose basis package is not the network's (`basis.package`), then works inside `MaterialRuntime.with` on a `FlowThermo`; `run`, `alone` (Solve alone), `rate` | Where a run starts |
| `FlowsheetRun` (record, `Status` STEADY/NOT_STEADY/FAILED/STOPPED) | Streams, outcomes, reports, loops, log, balances, order, passes | What the Results tab shows |
| `Board` | The run's known stream states and link values | Reads and writes of a pass |
| `Report`, `Note` | One calculation's readings, notes and boundary terms (enters, duty, leaves); a note's severity, subject, key (a suffix of `gui.icheme.flowsheet.`) and SI arguments | The log and door status |
| `FlowStream` (record), `StreamThermo`, `FlowThermo` | T, P, amounts on the package axis (water last), enthalpy, mass, phases; the world's flash as a stream thermo, with an enthalpy search | Every stream state |
| `UnitModel`, `UnitTypes` (`ALIASES`: `air_cooler` → `cooler`), `WiredUnit` (record, with `Utilities`), `Port` (record: id, direction, required, utility), `Params` | A tile's ports (the roles its calculations read), its reversible passages (`passages`) and calculations; a unit with its settings, wires, thermo, passages and regulated utility feeds | Adding a unit |
| `Passage` (record: `a`, `b`, `in`, `out`; `THROUGH`, `named`), `Wiring` | A reversible passage's two ends and the roles its model reads them by; a sheet's ends read as roles: the first stream on either end decides which is the inlet ([ports and passages](#ports-and-passages)) | Port sides |
| `Passages` | A unit's passage drop laws: typed drops while it has no size, sized laws (`SizedPassages`) once it has one | Drops in the run |
| `EjectorLaw` | The vacuum set's steam law S1 (K = 1.6) | Ejector steam |
| `FlowsheetFailure` | A law's refusal, carrying a note key | Failing units |
| `SheetBody`, `SheetStore` | One gzip body (sheet and results) in parts, with its bounds; the block's body, kept unreadable bodies and the holder | The block's storage |
| `RevisionNotice` | Client and server catalog revision tokens; a mismatch is a notice, never a block | Dedicated servers |
| **Units (`science/flowsheet/unit/`)** | | |
| `ColumnUnit`, `ColumnDoor` (record) | The column as one BLOCK calculation (`ColumnUnit.solve`: warm from the last seed, else the learned start) plus one calculation per heat side; the condenser duty reaches its side as a link; regulated utility feeds (cooling water, kettle steam, stripping steam through its inlet valve); the steam CHECK | The column on the sheet |
| `ExchangerUnit`, `ExchangerRating` | Exchanger or kettle, target Outlet, Duty or Area; counterflow surface and duty on the two heat curves | Heat units |
| `CoolerUnit` | Target Outlet or Duty, coolant in at 25 °C; Air or Water is a sizing type only | Coolers |
| `FiredHeaterUnit`, `MoverUnit`, `ValveUnit` (isenthalpic), `VesselUnit` (drum or tank flash), `MixerUnit` (lowest inlet pressure), `SplitterUnit`, `FilterUnit` (solids leave as cake), `EjectorUnit` | The other units | Each unit's balance |
| **Sizing (`science/flowsheet/sizing/`)** | | |
| `Limit` (enum, `Kind` MUST/SHOULD/RULE, `Rank` CLOSEST/QUIET/PAID), `Bound`, `Lamp` (NONE < GREEN < YELLOW < RED) | Every rated row with its bound, unit and source; the name is the stable id | A row's lamp |
| `Rating`, `Reading` (records) | A unit's or line's readings and values | Sizing output |
| `UnitSize` (sealed) and the sizers `PumpSizing`, `CompressorSizing`, `ExchangerSizing`, `AirCoolerSizing` (fan speed and power), `CoolerSizing`, `HeaterSizing`, `DrumSizing`, `TankSizing`, `ValveSizing`, `ColumnSizing` (half-metre diameters by real solves), `EjectorSizing` | One `Size` record, `rate` and `auto` per kind | A unit's sizes |
| `LineSizing`, `Service` (enum) | A line's bore, speed and drop (pipe resistance on the network's viscosity); the service picks its rows | Line sizes |
| `PressurePath` (record) | From a set pressure through every step to its taker: available, lost, spare (bar) | Path rows |
| `Selection` | Auto: the smallest size that rates well, those holding the need with the spare first | Auto |
| `SheetSizing` | The whole sheet: `rate` (cheap, no trial columns), `sizeAll` (proposes, applies, re-runs once warm where a size moves the run), `designs` (the list Send to block hands over) | Size all, Send to block |
| `DesignBasis` (record, `DEFAULT` 1.10 design flow, 50 m line length, 60 K pumparound fall), `SizingFacts`, `UnitType` | The sheet's sizing basis and each item's chosen size and type | Sizing facts |
| **Client (`client/gui/flowsheet/`, Minecraft-free)** | | |
| `SimulatorRunner`, `SimulatorHost` (`NONE`) | Runs one job at a time on the thread "IChemE simulator run" (daemon, lowest priority): Run, Solve alone, Size all, the quiet rate; Stop sets a flag the next checkpoint throws on | Threads and Stop |
| `SimulatorFrame`, `SimulatorPage`, `DoorFrame`, `Door` (record), `SheetContext` (record), `SheetUnits` (record) | The screen's frame, pages and doors on the GUI kit | Layout |
| `UnitDoors`, `ColumnDoors`, `StreamDoors`, `BasisPage`, `ResultsPage`, `SizingPage`, `SelectionColumn`, `FileColumns` | The doors and pages: units, the column, streams, basis, Results, Sizing, the Auto list, Open/Save as and file names | A door's rows |
| `UnitGlass`, `ColumnGlass`, `SizingGlass`, `Reader` (record) | The glass beside a unit's and the column's door and the Sizing table (split from their doors and page); one reading of a unit's results in the display units | A door's rows |
| `FlowsheetExamples` | The bundled example flowsheets Open lists first: `assets/icheme/flowsheet_examples/index.json` and its files, read through a `Function<String, InputStream>` (the screen passes `ColumnTemplates.resources`, the resource manager); a file that does not read is listed with its reason, never thrown | The examples |
| `SheetEditor`, `SheetGestures`, `SheetClipboard` | Edits with undo and redo, the mouse, one clipboard | Editing |
| `SheetAutosave` (`CLIENT`) | The autosave's one writer, off the render thread; the newest sheet wins | Tick |
| `SheetLayout`, `SheetPainter`, `Symbol` (enum, the symbol atlas), `Tiles`, `SheetView` | Boxes, ports, routes and hit tests (reads a stream's saved `route`); drawing; the palette; pan and zoom | Drawing |
| `ColumnBlock`, `AssemblyBlock` | The column block's drawing, and the assembly screen drawn as one | The column on the glass |
| `SheetState`, `SheetResults` (record), `StreamProps` (record), `NoteText` | Looks, greying and status; results; a note in the player's units | What a state shows |
| **Block and network** | | |
| `world/level/block/ProcessSimulatorBlock` | Opens the menu through `ProcessSimulatorNetwork.open`; solves nothing | The block |
| `world/level/block/entity/ProcessSimulatorBlockEntity` | The `SheetStore`, the sheet id, the saved column design, the sent designs; no update tag | NBT |
| `world/inventory/ProcessSimulatorMenu` (`Opening` record, `READ_ONLY`) | A slot-less menu; the opening's position, sheet id, reader or holder, stored and damaged flags, revision tokens | Who may edit |
| `network/ProcessSimulatorNetwork` | The packets: sheet parts both ways, Save design, Send designs, answers | Protocol |
| `network/SimulatorResults`, `network/ColumnWire` | A run's results as bytes (state); a column's input, choices and display result on the wire | Results and column wire |
| `client/gui/screens/inventory/ProcessSimulatorScreen`, `SimulatorBlockHost` | The one Minecraft screen (design height 480): forwards events, draws, polls the runner, uploads, autosaves | The game side of the screen |
| `science/equipment/SentDesign` (record), `world/level/block/entity/SentDesignTags` | One sent unit design (tag, member, count, shape, column) and its NBT | Send to block and Import |

## Main flow

```mermaid
flowchart TD
    A[Screen: Run] --> B[SimulatorRunner thread]
    B --> C[FlowsheetEngine.run: package check, MaterialRuntime.with]
    C --> D[FlowsheetCompiler.compile + estimates]
    D --> E[FlowGraph.plan: groups, tears]
    E --> F[FlowsheetSolver: passes, Wegstein, result]
    F -->|steady, or sizing facts| G[SheetSizing.rate]
    G --> H[screen tick: show; upload sheet + results to the block]
```

1. The Run key calls `SimulatorHost.run`, which hands a private copy of the sheet to `SimulatorRunner`. The runner starts its own daemon thread; one job runs at a time. Nothing runs on the client tick or in the server's workers.
2. `FlowsheetEngine.run` refuses a sheet whose basis package differs from `FluidMaterialCatalog.networkPackage`, then works inside `MaterialRuntime.with` on a `FlowThermo`.
3. `FlowsheetCompiler.compile` makes the calculations; `estimates` seeds torn streams from the last steady run.
4. `FlowGraph.plan` orders strongly connected groups and tears the fewest streams. Two calculations writing one stream or giving one link throw.
5. `FlowsheetSolver.run`: a group without tears runs once. A torn group runs one plain pass, then bounded Wegstein. A tear that rises by the same step (`SAME_STEP` 0.01) on 3 passes stops with `loop.no_steady_state`; a Wegstein trial that fails a unit falls back to plain passes. `result()` closes mass and heat; a mass gap gives `run.mass_gap` and `NOT_STEADY`.
6. If the run is not stopped and is steady (or the sheet has sizing facts), the same thread rates it (`FlowsheetEngine.rate` → `SheetSizing.rate`).
7. The screen polls each tick, shows the run, and uploads the sheet and results to the block (`ranOut`). The Sizing tab re-rates quietly 10 ticks after the last edit.

<details><summary>Calculations, links and utility feeds</summary>

- A STEP reads streams and writes streams. A BLOCK is a calculation that solves a whole unit at once (the column). A CHECK writes nothing; the plan puts it after the groups it reads, so it runs once a loop has closed. A CHECK that throws becomes a warning note and blocks nothing (its own notes keep their severity, an error included), its reads do not count as consumers, and it is out of the stage order.
- Links carry a value between calculations that is not a stream (the condenser's duty to its side, the bottoms temperature, a steam row's stage pressure). Links are never torn.
- `FlowsheetSolver.result()` counts every written stream that no non-CHECK calculation reads as leaving the sheet. `WiredUnit` gives a free outlet its own stream `unit:port` for this reason. A unit that writes a stream nobody reads makes a mass gap.
- A sheet feed of water alone on a port with `utility = true` is not a boundary feed. `FlowsheetCompiler` hands it to the unit (`WiredUnit.Utilities`), whose utility calculation writes the feed at the flow its duty needs (`Note.info` `flow.set_by`). Cooling water leaves at min(T_in + 10 K, process cold end − 5 K) (`HeatSides.coolingWaterOutlet`); kettle steam leaves as saturated condensate; stripping steam flows at its row's rate through an isenthalpic inlet valve to the stage pressure, and a CHECK reads the supply against the stage once the sheet stands (`column.steam_pressure`, an error note).

</details>

<details><summary>Sizing, lamps and Auto</summary>

- Each `Limit` row has a kind: MUST (red when broken), SHOULD (yellow), RULE (picks a type or a service, lights no lamp). A unit's lamp is its worst row; a sheet's is its worst unit.
- The 20 % spare is the owner's "always have enough excess": `Limit.AUTO_SPARE` (RULE, at least 20 %) is what Auto adds to exchanger, kettle and air-cooler surface, heater duty, type and cabin count, and compressor displacement. The lamps read the bare need, so a sized unit is green with room. `PATH_EXCESS` (SHOULD, 20 %) and `EJECTOR_MOTIVE_SPARE` (SHOULD, 20 %) are spares on a path and on motive steam.
- Auto (`Selection`) calls the same `rate` a Fixed size calls, so an Auto size is never rated by another rule.
- `SheetSizing.sizeAll` sizes units that read no line first (a column with its members), pump NPSH and valve bores last. Where a size moves the run (a column's diameter, sized drops), it re-runs once, warm; a run that is not steady sizes nothing. The result is one undoable edit.
- A `PressurePath` runs from a set pressure through every step to its taker; `spare()` feeds the heater's and the valve's sizing. `PATH_SPARE` (MUST ≥ 0 bar) is red where a supply cannot reach its taker, a steam row's tray included.
- `ColumnSizing` tries half-metre diameters with real solves; a trial that does not solve is red on `COLUMN_SOLVES`.
- `AirCoolerSizing.fans` finds the fan speed (0..1) that holds the load on the chosen face; power goes with the cube of the speed. `Sized.fanPower()` is the sheet's total.

</details>

<details><summary>Send to block and Import</summary>

- Save design (`simulator_save_design`) stores the column door's `ColumnDesign.of(input, choices)` on the block; a refusal names its reason.
- Send to block (`simulator_send_designs`) carries `SheetSizing.designs`: one `SentDesign` per sized unit (valves left out, at most 64). The server takes it only from the holder, whole; it replaces the earlier list.
- The constructor's Import (`EquipmentNetwork`, protocol `equipment-17`) asks the nearest simulator within `EquipmentNetwork.IMPORT_REACH` (16 blocks, `EquipmentConstructorBlockEntity.simulator`) for its list: the saved column design first, then the sent designs. The player picks one; the constructor lands it. See [equipment.md](equipment.md).

</details>

<details><summary>Formats and versions</summary>

| Format | Version | Where | Kind |
| --- | --- | --- | --- |
| Flowsheet file and block body's sheet | `FlowsheetFile.FORMAT` = 2 (passage ends, option B) | `science/flowsheet/FlowsheetFile` | No compatibility (owner 2026-11-04): another format is refused; corpus `compat/flowsheet/format-2*.json` (`FlowsheetFileTest`) |
| Bundled examples | Each a sheet file of the current `FORMAT`, on the current network package and revision | `assets/icheme/flowsheet_examples/` | A format or material revision change fails `FlowsheetExamplesTest`: write the example again on the new one (the sample gates run it) |
| Block body | `SheetBody.FORMAT` = 1 | `science/flowsheet/SheetBody` | Fact (the sheet inside it) |
| Run results | `SimulatorResults.FORMAT` = 1 | `network/SimulatorResults` | State: dropped on a mismatch, rebuilt by a run |
| Sent designs | `SentDesignTags` (fields 2) | `world/level/block/entity/SentDesignTags` | Fact: corpus `compat/simulator/designs-<n>.snbt` (`SentDesignTagsTest`) |
| Column wire | `ColumnWire.WIRE_SCHEMA_VERSION` = 18 | `network/ColumnWire` | Wire |
| Simulator packets | `ProcessSimulatorNetwork.PROTOCOL` = "2" | `network/ProcessSimulatorNetwork` | Wire |
| Constructor packets | `EquipmentNetwork.PROTOCOL` = "equipment-17" | `network/EquipmentNetwork` | Wire |

The file keeps fields it does not know and refuses a newer format and an older one, which it would misread: a sheet file needs no compatibility (owner 2026-11-04), so a format change re-records the corpus and the block keeps an old body as unreadable (`KeptSheets`). `SheetLayout` reads a stream's `route`, so a change to it is a format change.

</details>

<details><summary>Ports and passages</summary>

<a id="ports-and-passages"></a>A unit's ports are the roles its calculations read (`UnitModel.ports`). A passage that works either way in a plant is a `Passage` (`UnitModel.passages`, PORT_SIDES_REVIEW.md option B): the sheet shows its two ends, and the streams on them say which is the inlet.

- **Passages:** the valve, the filter, the cooler and the series heater (`a`, `b`); the apart heater's two coils (`convection_a`/`_b`, `radiant_a`/`_b`); the exchanger's tube and shell sides (`tube_a`/`_b`, `shell_a`/`_b`); the kettle's tubes; and in the column only the heat members' utility sides (owner, "Column components like heat exchanger utility side only"): `condenser_a`/`_b` (the condenser's water, or its trim's), `reboiler_a`/`_b`, `pa/<id>/a`/`b`.
- **Fixed:** machines (pump, compressor, ejector), the mixer, the splitter, vessels (drum and tank), the column's process ports, stripping steam and placeholders.
- **`Wiring`:** an end that is a stream's `to` is the inlet, one that is a `from` the outlet, and the other end takes the other role. The first stream on either end decides. An end against that, or an end that names a role, plays none and stays open. `Wiring.roles(sheet)` names every end by its role, and the compiler, `SheetSizing` (its path walk included) and the doors and pages (`SheetContext.into`, `outOf`, `streams`, through `SheetEditor.roles`) read that sheet; the layout and the editor read the ends. The exchanger stays counterflow whichever ends carry its sides: its model reads roles.
- **Editing:** `SheetEditor.takes` lets a free passage end take a stream's start or its end; once one end holds a stream, the other takes only the other role. Two ends of one passage wired the same way give `PASSAGE` (`flowsheet.refused.passage`); two fixed ports of one direction still give `PORT_SIDE`. A drag from a free end offers rings of both kinds (`SheetGestures.drawRings`). Where nothing decides (a free end to a pill, or to another free end), the stream leaves the end it is drawn out of, so a pill is a product (owner 2026-11-04, "product"); a passage is fed by landing a Feed on the end wanted. A free inlet of a fixed unit still starts a feed.
- **Drawing:** an end stands where it stands whichever way its stream runs: `a` where the inlet stood, so the cooler now draws `a` on its left like every passage. A column side entered by its second end turns its pipes' arrows (`ColumnBlock.of(unit, door, reversed)`). A free end's hover reads both roles (`flowsheet.port.either`).

</details>

<details><summary>Traps</summary>

- A run uses the client's bundled catalog, also on a dedicated server; `RevisionNotice` only shows a notice when the tokens differ.
- Results carry no column warm start, so the first run after opening starts the column cold.
- The Sizing tab's lamps are state and never saved; the screen rebuilds them with a quiet rate job.
- A body still on its way counts as read-only. Network handlers run on the server thread; a store lands with its last part.
- `FlowsheetCatalog` and `SizingCoverage` live in `src/test`: their switches have no default, so a new kind fails `compileTestJava` until it is mapped.

</details>

## The screen

`client/gui/screens/inventory/ProcessSimulatorScreen` is the only Minecraft class of the screen. `IChemEClient.registerScreens` registers it on `ModMenus.PROCESS_SIMULATOR`. It is a plain `Screen` on a `ProcessSimulatorMenu` (`MenuAccess`), not an `AbstractContainerScreen`, so it has no slots and a typed E is text. A close, a disconnect and the block going all end it. It forwards events and draws. Everything it shows is built Minecraft-free in `client/gui/flowsheet/`, so `tools/heat-gui-renders/SimulatorRender.java` draws the same pages from shipped code.

- **Window:** `WindowScale.apply` at design height 480. The least window is 464 × 254 (`SimulatorFrame.LEAST_W`, `LEAST_H`). A door takes 790 × 524 (`DOOR_W`, `DOOR_H`). `removed()` restores the scale.
- **Frame and tabs:** `SimulatorFrame` gives the rectangles: title, tabs and file keys, the one status display, the canvas's well, the palette and the footer. The screen hit-tests them, and the render tool draws them. The tabs (`SimulatorFrame.Tab`) are FLOWSHEET, RESULTS, SIZING and BASIS. The Sizing tab opens only once the sheet can be sized (`SizingPage.open`). `SimulatorPage` says which keys stand where on each page.
- **The sheet:**
  - `SheetLayout` places boxes, ports and routes and answers hit tests.
  - `SheetPainter` draws them with the `Symbol` atlas (`textures/gui/flowsheet/symbols.png`). The grid, each line, circle and valve, the hatch and the tear are each one batch (`Canvas.batch`, [client-gui](client-gui.md)): the sample's Flowsheet tab went from 3,486 draw calls a frame to 718 (the render tool's `-Dcount`), and `DrawCallTest` holds the sheet's drawing to 360.
  - `SheetView` pans and zooms.
  - `SheetGestures` turns mouse events into edits. A press where `SheetLayout.at` finds nothing pans (`Mode.PAN`): the game sends `mouseMoved` before each `mouseDragged` at the same point, so the cursor (`lastX`) follows every move and a pan steps from where the last drag left it (`heldX`). Unit, pill and segment moves measure from the press. `SheetGesturesTest` drags as the game sends it. On a column only what is drawn grabs it (`SheetLayout.grabs`): the frame's outline, the tower, the members and the pipes within `REACH`, and the tag and ports found before; the empty plate pans (the owner, 2026-11-04, in place of D23's grab anywhere in the frame).
  - `SheetEditor` applies them, with undo and redo.
  - `Tiles` is the palette.
  - `ColumnBlock` draws a column with its members inside a boundary.
  - `SheetState` greys what is stale and builds the status.
- **Doors:** a click on a unit or a stream opens its door, a `Door` placed by `DoorFrame`. The screen builds the shown door once a frame and hands it to `signature`; a frame that rebuilds the widgets draws the door the build placed them on. The column door parses its params once (`ColumnDoor.of`) and hands the record on.
  - `UnitDoors` builds every unit's door; `UnitGlass` draws its glass (heat, streams, the sketch, through `Door.Glass`). A unit's readings go through `Reader` (its run's value of a key, its temperature and pressure in the display units), as `SelectionColumn`'s do.
  - `ColumnDoors` builds the column's, through `client/gui/common/CalculatorColumn` (its tabs, the Templates dialog, Save design); `ColumnGlass` draws its glass (the drawing, the connections, the results and the total drop).
  - `StreamDoors` builds a stream's: feed, flow, T and P, components, the clipboard's Copy and Paste. A stream template loads through `SheetClipboard.paste` with the template's flow: parts not on the sheet drop and the rest take the whole flow, a part of none is no part, and a template with none on the sheet changes nothing.
  - A door's `tab/` and `phase/` presses go through `Door.View.press`. kmol/h settings use `SheetUnits.KMOL_PER_HOUR`; a part's molar mass, water's where the package lacks it, is `StreamProps.Fluids.weight`.
  - A stream's flow follows the display key (`SheetUnits.Basis`: Mole, Mass, Volume, the actual m³ at the stream's own T and P): the feed's Flow entry in the panel and the door, the feed door's status line and the panel's Flow meter for a stream a unit makes. `SheetUnits.flow` is the one format, `molesOf` the one way back (through `SheetContext.feedMoles` and `StreamDoors.writeFeed`). `SheetContext.density` gives a feed greyed only by a flow edit the run's density while its T, P and mole fractions are the run's (density is intensive); otherwise Volume leaves the entry blank, takes no typing, and its hover says why (`door.volume_unknown`). The composition tables, `ResultsPage`, the unit doors' stream tables, the column door, splitter flows and `FileColumns` keep their own columns.
  - Every flow and pressure entry in a door shows, labels and reads in the display keys' units (D1, D2; `DoorUnitsTest`): pressures through `SheetUnits.p` and `pressure().si`; a unit's own flow setting (a column draw or steam feed, a splitter outlet) stays molar, for its makeup is known only after a run, and only its time follows the key (`SheetUnits.moles`, `molesUnit`, `molesPerSecond`). The column door passes its pressure and flow units to `CalculatorColumn.ColumnInput`. The column door's Results glass keeps kmol/h and kPa.
  - `SelectionColumn` lists Auto's sizes.
  - Run (footer slot 1) is primary, with a ▶, and lit amber while the results are out of date (`SimulatorPage.runDue`: it can act and `SheetState.outOfDate`, no run yet on a sheet with something on it or anything greyed since the run, the state behind the status line's "Changed since the last run"); its hover then says `hint.run_due`. Off while a run is going, Stop on. The screen's signature carries `runDue`, so an edit relights it.
  - `FileColumns` holds the Open and Save as dialogs and the file names. Each dialog's confirming key is primary in game as in the renders (`ProcessSimulatorScreen.dialogWidgets`): Save in the name dialogs and the unsaved prompt, Open in the changes prompt, Delete in the delete prompt, and a listing's `primary()` (Open's Open, the templates' and Replace's Load); in the frame, Run and Size all. Open lists the bundled examples first (`entries(folder, examples, t)`: the translated name `gui.icheme.flowsheet.example.<stem>`, the unit count, Saved "Example", the state as for a file). `FileColumns.opened` gives an example's sheet with no file name, so it opens unsaved, Save asks for a name, and the example itself never changes. Open's keys are Cancel, Folder, From clipboard, Delete, Open (`OPEN_FOLDER` to `OPEN_OPEN`, Open the primary). `deletable` takes any of the player's own files (an autosave, a damaged file), never an example; Delete is off with a hover when no row is chosen or on an example. The screen asks first (`Dialog.DELETE`, `deleting`: "Delete <name>?", Cancel back to Open, Delete primary); `delete(folder, entry)` removes only that file, a regular file directly in the folder and never a link, else refuses and changes nothing (the status line says `file.delete_failed`); `nameAfterDelete` drops the open sheet's file name when its own file went, so Save asks for one. The list reads again after a delete.
- **Pages:**
  - `ResultsPage`: streams, units, the run log through `NoteText` in the player's units, and the balances.
  - `SizingPage`: rows and lamps, Fixed and Auto, Size all, Send to block. It re-rates quietly 10 ticks after the last edit (`rateWhenDue`). `SizingGlass` draws, clicks, picks and scrolls its table. The page's footer is `SimulatorPage.footer` with its own second and third keys (`SimulatorFrame.footer(third)`).
  - `BasisPage`: the package, the design basis, the solver settings.
- **Tick:** each tick the screen does four things.
  - It lets the block host upload a finished run (`SimulatorBlockHost.ranOut`).
  - It autosaves every 1200 ticks, through `SheetAutosave.CLIENT`: one writer thread for the client (no daemon, started when there is work), the newest sheet wins. A failed write hands its sheet back, and the next tick says `store.autosave_failed` on the status line; the next autosave tries again.
  - It clears a spent status line.
  - It re-rates the Sizing tab when that is due.
- **Close:** `onClose` lets the host send the sheet once more (`settle`). `removed()` autosaves, closes the host (the runner's job stops) and restores the window scale.
- **Host:** `SimulatorHost` is the screen's only link to running and storing. `SimulatorBlockHost` is the game's host: the runner plus the block's store. `SimulatorHost.NONE` serves the render tool and tests.

## Packets and protocol

`network/ProcessSimulatorNetwork`, protocol `"2"`, handlers on the server thread (`HandlerThread.MAIN`). Wire formats get no compatibility work (AGENTS.md).

| Payload | Direction | Carries | Server rule |
| --- | --- | --- | --- |
| `simulator_sheet_part` (`SheetPart`) | both | container id, part index and count, bytes | To the client: the replay on open, in `SheetBody.parts`. To the server: an upload, which counts only from the holder whose menu is this container, near its block. A store lands with its last part, and a bad body is refused while the stored sheet stands. |
| `simulator_save_design` (`SaveDesign`) | to server | container id, the column door's `V3ColumnInput` and `ColumnComponents.Choices` (`ColumnWire`) | Holder only. `ColumnDesign.of`, stored as the block's saved design. A refusal gives its reason. |
| `simulator_send_designs` (`SendDesigns`) | to server | container id, a tag of `SentDesign`s (`SentDesignTags`) | Holder only. The list must be whole, not empty and at most 64. It replaces the earlier list. |
| `simulator_answer` (`Answer`) | to client | container id, key (`store.saved`, `store.design_saved`, `store.design_refused`, `store.designs_sent`, `store.designs_refused`, or the menu's `READ_ONLY`), detail up to 256 characters | Each request's answer, shown on the status line |

Opening needs no packet of its own. `ProcessSimulatorBlock.useWithoutItem` calls `ProcessSimulatorNetwork.open`, and `open` does four things:
- it decides who edits (`block.opens`);
- it opens the menu with an `Opening`: position, sheet id, reader flag, the holder's name for a reader, stored and damaged flags, and the server's catalog revision tokens for `RevisionNotice`;
- it replays the stored body in parts;
- it parses the body once, solves nothing and creates no process state.

The constructor's Import uses `EquipmentNetwork` (`equipment-17`); see Send to block and Import above.

## The block and its store

- `process_simulator` replaced `column_calculator_v3`. The old id is an alias for the block, its item and its block entity (`ModBlocks`, `ModItems`, `ModBlockEntities`). It has no recipe.
- `ProcessSimulatorBlockEntity` holds a `SheetStore`, the sheet id (a UUID made at the first open), the saved column design, and the sent designs with any unreadable entries. It has no update tag, so the sheet never rides in a chunk packet.
- `SheetStore` has one holder.
  - The first player to open holds the sheet and edits; others read.
  - The holder is freed on close, on leaving, on disconnect and when the block goes.
  - A player who is no longer at this block's screen holds nothing (`atThisBlock`).
  - The holder is not saved.
- Uploads count only from the holder, near the block, with the screen open.
  - `SheetBody` bounds them: 1 MiB unpacked, parts of 30 000 bytes.
  - The client sends a body on Save, when a run ends and on close, never on an edit.
  - A body still on its way counts as read-only.
  - An unreadable body opens empty and is kept (`KeptSheets`).
- NBT:
  - `SheetId`;
  - `Sheet` (the body);
  - `KeptSheets`;
  - `Design` (the column's saved design, `ColumnDesignTags`);
  - `Designs` (sent designs, `SentDesignTags`, unreadable entries kept).
- Files on the client:
  - A sheet is saved as `.minecraft/IChemE/flowsheets/<name>.json`.
  - An autosave runs every 1200 ticks and on close. It writes `<name>.autosave.json` beside a named file, or `autosave-<sheet id>.json` for a sheet with no name. A player cannot use these names.
  - A reader never autosaves.

## New equipment joins the simulator (plan 3.10)

Every new `EquipmentShape.Kind`, `ConstructorColumn.Equipment` and `TopologyCompiler.Kind` constant joins the simulator (AGENTS.md, New equipment). `FlowsheetCatalog` (`src/test/.../client/gui/flowsheet/`) maps each to its tile, MODELLED or DRAWING_ONLY, through switches without a default, so a new constant fails `compileTestJava`. `FlowsheetPaletteCoverageTest` holds each to a tile, a port rule and a lang line; one tile may hold several kinds. `SizingCoverage` (`src/test/.../science/flowsheet/sizing/`) does the same for sizing: each kind names its size class or why it has none (`SizingCoverageTest`).

## Change the layer that owns it

| Symptom / change | Owning class | Tests that pin it |
| --- | --- | --- |
| A unit's balance or door | `science/flowsheet/unit/*`, `UnitTypes` | `FlowsheetUnitsTest` (39: every unit closes mass and energy), `FlowsheetColumnDoorTest` |
| Order, tears, loops | `FlowGraph`, `FlowsheetSolver` | `FlowGraphTest`, `FlowsheetSolverTest` |
| The column on the sheet against the world's solver | `ColumnUnit`, `ColumnDoor` | `FlowsheetColumnTest` (slow), `ColumnEjectorTest` (slow), `UtilityRegulationTest` (slow) |
| A utility feed's flow | `FlowsheetCompiler`, `WiredUnit.Utilities`, `ColumnUnit` | `UtilityRegulationTest` |
| A Sizing row or lamp | `Limit`, the unit's sizer | `LimitTest`, `UnitSizingTest`, `CoolerSizingTest`, `SheetSizingTest`; the sample: `UnitSizingSampleTest`, `ColumnSizingSampleTest` (slow) |
| Lines and paths | `LineSizing`, `Service`, `PressurePath`, `SheetSizing` | `LineSizingTest`, `ServiceTest`, `LineSizingSampleTest`, `SizeAllSampleTest` (slow) |
| Which end of a passage is its inlet | `Passage`, `Wiring`, `UnitModel.passages`, `SheetEditor.takes` | `WiringTest`, `PassageEndsTest` |
| The file | `FlowsheetFile` | `FlowsheetFileTest` (27, the corpus) |
| The bundled examples and Open's rows | `FlowsheetExamples`, `FileColumns.entries`, `FileColumns.opened`, `assets/icheme/flowsheet_examples/` | `FlowsheetExamplesTest`; the sample gates run the crude unit |
| Open's Delete: which rows go, the folder guard, the open sheet's name | `FileColumns.deletable`, `delete`, `nameAfterDelete`, `ProcessSimulatorScreen` (`Dialog.DELETE`) | `OpenDeleteTest`, `SimulatorBlockTest` (the keys) |
| The block's store, uploads and packets ([packets](#packets-and-protocol), [block](#the-block-and-its-store)) | `SheetStore`, `SheetBody`, `ProcessSimulatorNetwork`, `ProcessSimulatorBlockEntity` | `SheetStoreTest`, `SimulatorBlockTest`, `ProcessSimulatorGameTests` |
| Sent designs and Import | `SheetSizing.designs`, `SentDesign`, `SentDesignTags`, `EquipmentNetwork` | `SentDesignTagsTest` (the corpus), `ProcessSimulatorGameTests.aConstructorImportsTheNearestSimulatorsDesign` |
| The screen ([the screen](#the-screen)) | `ProcessSimulatorScreen`, `client/gui/flowsheet/*` | `SimulatorFrameTest`, `SimulatorColumnsTest`, `StreamFlowBasisTest` (the flow basis), `RunKeyTest` (Run lit), `SheetLayoutTest`, `SheetEditorTest`, `SheetGesturesTest`, `SizingPageTest`, `ColumnBlockTest`, `AssemblyBlockTest`, `DrawCallTest` (draw calls); renders: `tools/heat-gui-renders/SimulatorRender.java` (`-Donly=run-key`: Run's states, sheet 28, and what grabs a column, sheet 29; `-Dcount`: draw calls per page; `-Dbasis`: MOLE, MASS or VOLUME) |
| The runner and Stop | `SimulatorRunner` | `SimulatorRunnerTest`, `SimulatorRunTest` (slow: Stop inside a column) |
| A new kind | `FlowsheetCatalog`, `SizingCoverage` (both in `src/test`) | `FlowsheetPaletteCoverageTest`, `SizingCoverageTest` |

## Tests that pin it

- `test`: `science/flowsheet/` (`FlowGraphTest`, `FlowsheetSolverTest`, `FlowsheetUnitsTest`, `FlowsheetColumnDoorTest`, `FlowsheetFileTest`, `SheetStoreTest`, `EjectorTest`, `WiringTest`), `science/flowsheet/sizing/` (`LimitTest`, `ServiceTest`, `LineSizingTest`, `UnitSizingTest`, `SheetSizingTest`, `CoolerSizingTest`), `client/gui/flowsheet/` (the screen tests above, `PassageEndsTest`, `FlowsheetExamplesTest`, `OpenDeleteTest`, `DrawCallTest`, `SimulatorBlockTest`, `SimulatorRunnerTest`, the two coverage tests), `network/ColumnWireTest`, `world/level/block/entity/SentDesignTagsTest`.
- `holdGates` (slow, `slowGateClasses`): `FlowsheetColumnTest`, `ColumnEjectorTest`, `SimulatorRunTest`, `LineSizingSampleTest`, `UnitSizingSampleTest`, `ColumnSizingSampleTest`, `SizeAllSampleTest`, `UtilityRegulationTest`.
- `runColumnGameTestServer` (namespace `icheme_column_test`): `ProcessSimulatorGameTests` (11): the old id, a reload with no update tag, a damaged sheet kept, the constructor's Import, a second player reads, uploads only from the screen near the block, a bad upload refused, the holder freed on disconnect, away and when the block goes, a replay that runs no solver.
- Shared fixture: `client/gui/flowsheet/SheetFixture` (the sample sheet: `sample()` reads the bundled crude unit example, so the sample gates run the sheet players open).
- Probes and renders outside the gates: `tools/process-simulator/`, `tools/device-names/`, `tools/heat-gui-renders/` (`SimulatorPrototypes`, `SimulatorRender`, `SimulatorSizing`).

## Terms

| Term | Meaning here |
| --- | --- |
| Sheet | The flowsheet: units, streams, feeds, basis, settings, sizing facts |
| Door | A unit's or stream's settings page on the glass |
| Tear | A stream the solver guesses to break a loop, then iterates |
| Pass | One run through a torn group |
| Link | A value passed between calculations that is not a stream; never torn |
| Holder | The one player who edits a block's sheet; others read |
| Utility feed | A sheet feed of water alone on a utility port; its unit sets its flow |
| Passage, end, role | A passage works either way; the sheet shows its two ends (`a`, `b`); a role (`in`, `out`, `tube_in`) is what the model reads, and the streams say which end plays which |
| Auto, Fixed | A size Auto proposes (with the 20 % spare) or one the player keeps |
