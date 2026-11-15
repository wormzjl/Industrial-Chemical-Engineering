# Process simulator: the sheet, its run, the file, the block and its store

Up: [code guide](README.md) · the units: [simulator-units.md](simulator-units.md) · sizing, Send to block, Import:
[simulator-sizing.md](simulator-sizing.md) · the screen: [simulator-screen.md](simulator-screen.md)

## Read this when

- A run on the simulator's sheet fails, does not reach a steady state, or shows a mass or heat gap.
- A loop does not tear where you expect, or a link or a CHECK misbehaves.
- A sheet runs on the wrong compounds, or the data notice (`RevisionNotice`) shows where it should not.
- The block will not store, replay or hand over a sheet; a flowsheet file or an autosave will not open; a second player
  cannot read or edit.
- You change the flowsheet file, the block's tags or the packets: the file and the tags are facts with corpus images.
- A passage end takes the wrong role (`Wiring`).

## What it does

The simulator is a steady-state, sequential-modular process simulator, as plant engineers use to design before they
build: feeds of known composition, temperature and pressure flow through units, each unit closes its own mass and
energy balance, and recycle loops are torn and iterated until the torn streams stop changing. A `Flowsheet` (the sheet's
facts) is compiled by `FlowsheetCompiler` into `Calculation`s, one or more per unit; `FlowGraph` orders them in strongly
connected groups and picks the fewest tears; `FlowsheetSolver` runs each group once, or loops it (a plain pass, then
bounded Wegstein) to a steady state, and closes the sheet's mass and heat. `FlowsheetEngine` runs the sheet on the
network package's view of the sheet's own compounds. The column is one BLOCK calculation that calls the world's V3
solver ([simulator-units.md](simulator-units.md)). `SheetSizing` then rates the run ([simulator-sizing.md](simulator-sizing.md)).
The whole run happens on the client, on its own thread; the `process_simulator` block only stores the sheet and its
results, replays them to whoever opens it, and lets one player at a time edit (AGENTS.md: the simulator's run is a
client tool outside the engine's schedule).

## Class map

**`science/flowsheet/`** ([source](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/)), Minecraft-free. Lines
at the snapshot. "pp" = package-private.

| Class | Role | Lines |
|---|---|---|
| [`Flowsheet`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/Flowsheet.java) | The sheet's facts: basis, units, streams, settings, sizing facts, unknown fields kept | 183 |
| [`FlowsheetFile`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/FlowsheetFile.java) | The JSON format: `read`, `save`, `FORMAT`; keeps unknown fields, refuses a newer (`file.newer`) and an older format (`file.older`) | 249 |
| `SizingFactsFile` (pp), `JsonFields` (pp) | Sizing's facts in the file (absent on a sheet never sized: every item on Auto); one object's checked fields, `damaged`, `beside` | 239, 167 |
| [`Params`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/Params.java) | A door's settings as the file holds them: SI values under stable keys, the JSON kept whole | 67 |
| [`FlowsheetCompiler`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/FlowsheetCompiler.java) | A sheet into `Calculation`s and warm-start estimates (`compile`, `estimates`, `typed`); reads the sheet's ends as roles (`Wiring.roles`); a unit that cannot run is a failing calculation; routes a utility port's water feed to its unit (`waterAlone`, `livePorts`) | 197 |
| [`Calculation`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/Calculation.java) | One graph node (`Kind` STEP, BLOCK, CHECK): reads, writes, needs and gives (links), a `Law` | 50 |
| `FlowGraph` (pp) | Strongly connected groups in order and the fewest tears (deterministic, `TRIALS`); links are never torn; `plan` | 202 |
| [`FlowsheetSolver`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/FlowsheetSolver.java) | Runs the plan: a group without tears once, a torn group one plain pass then bounded Wegstein (one factor per tear); closes mass and heat (`result`); `Settings` (`DEFAULT`), `SAME_STEP` | 438 |
| [`FlowsheetEngine`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/FlowsheetEngine.java) | The entry: `run` and `alone` (Solve alone) on the network package's view of the sheet's compounds (`SheetCompounds.on`), the run brought back onto the network's axis by id | 87 |
| [`SheetCompounds`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/SheetCompounds.java) | A sheet's compound list: the network package's compounds its feeds hold with a non-zero amount, in package order, water last; Nitrogen as placeholder when none; derived, never stored; `on(sheet, catalog, …)` gives the view (`MaterialCatalog.restrict`) and the maps between axes; a feed compound the network lacks is parked (`basis.parked`) | 162 |
| [`FlowsheetRun`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/FlowsheetRun.java) | What a run leaves (state): streams, outcomes, reports, loops, log, balances, order, passes; `Status` STEADY, NOT_STEADY, FAILED, STOPPED; `cold` | 144 |
| [`Board`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/Board.java) | The run's known stream states and link values | 43 |
| [`Report`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/Report.java), [`Note`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/Note.java) | One calculation's readings, notes, series and boundary terms (enters, duty, leaves), its column warm start `Warm`; a note's severity, subject, key (a suffix of `gui.icheme.flowsheet.`) and SI arguments | 215, 40 |
| [`FlowStream`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/FlowStream.java), [`StreamThermo`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/StreamThermo.java), [`FlowThermo`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/FlowThermo.java) | T, P, amounts on the sheet's axis (water last), enthalpy, mass, phases, solids; what the solver asks of a thermo; the world's flash as a stream thermo, with an enthalpy search, the release-pressure reads and `catalog()`, `water()`, `waterMass`, `waterAlone` | 63, 13, 310 |
| [`UnitModel`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/UnitModel.java), [`UnitTypes`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/UnitTypes.java), [`WiredUnit`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/WiredUnit.java), [`Port`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/Port.java) | A tile's ports, passages and calculations; the tiles by their stable type strings (no aliases: a type it does not hold reads as a placeholder); a unit with its settings, wires, thermo, passages, regulated utility feeds (`Utilities`) and last report (`last`); a port (id, direction, required, utility) | 20, 58, 110, 45 |
| [`Passage`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/Passage.java), [`Wiring`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/Wiring.java), [`Passages`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/Passages.java) | A reversible passage's two ends and the roles its model reads them by (`THROUGH`, `named`); a sheet's ends read as roles (`roles`); a unit's passage drop laws (typed drops without a size, `SizedPassages` with one) | 35, 130, 28 |
| [`FlowsheetFailure`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/FlowsheetFailure.java) | A law's refusal, carrying a note key | 26 |
| [`Members`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/Members.java) | A block's member names, saved names: `member` (`T-101/condenser`), `TRIM`, `COOLER`, `Pumparound` (`pa <id> <part>`); the tiles read them here, so `unit/` names nothing of `sizing/` | 41 |
| [`SheetBody`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/SheetBody.java), [`SheetStore`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/SheetStore.java) | One gzip body (sheet and results) in parts, with its bounds (`FORMAT`, `parts`); the block's body, its unreadable bodies and the holder | 125, 101 |
| [`RevisionNotice`](../../../src/main/java/com/wormzjl/icheme/science/flowsheet/RevisionNotice.java) | Client and server catalog revision tokens; a mismatch is a notice, never a block: `tokens(sheet, catalog)` (the view's token), `data`, `stamped`, `moved` | 77 |

**Block and network**

| Class | Role | Lines |
|---|---|---|
| [`world/level/block/ProcessSimulatorBlock`](../../../src/main/java/com/wormzjl/icheme/world/level/block/ProcessSimulatorBlock.java) | Opens the menu through `ProcessSimulatorNetwork.open` (`useWithoutItem`); solves nothing | 125 |
| [`world/level/block/entity/ProcessSimulatorBlockEntity`](../../../src/main/java/com/wormzjl/icheme/world/level/block/entity/ProcessSimulatorBlockEntity.java) | The `SheetStore`, the sheet id, the saved column design, the sent designs; no update tag | 154 |
| [`world/inventory/ProcessSimulatorMenu`](../../../src/main/java/com/wormzjl/icheme/world/inventory/ProcessSimulatorMenu.java) | A slot-less menu; its `Opening` (position, sheet id, reader or holder, stored and damaged flags, revision tokens), `READ_ONLY` | 144 |
| [`network/ProcessSimulatorNetwork`](../../../src/main/java/com/wormzjl/icheme/network/ProcessSimulatorNetwork.java) | The packets (`SheetPart`, `SaveDesign`, `SendDesigns`, `Answer`) and `open` | 258 |
| [`network/SimulatorResults`](../../../src/main/java/com/wormzjl/icheme/network/SimulatorResults.java) | A run's results as bytes (state): `FORMAT`, `ranOnBody`, `adoptingState` | 366 |

## Main flow

1. The Run key calls `SimulatorHost.run`, which hands a private copy of the sheet to `SimulatorRunner`, one job at a
   time on its own thread ([simulator-screen.md](simulator-screen.md)). Nothing runs on the client tick or in the
   server's workers.
2. `FlowsheetEngine.run` takes the sheet's compound list (`SheetCompounds`), solves on
   `MaterialCatalog.restrict(network package, list)` inside `MaterialRuntime.with` on a `FlowThermo`, and writes the
   streams back on the network package's axis by id. A sheet drawn on another or a removed package runs on the network
   by compound ids.
3. `FlowsheetCompiler.compile` makes the calculations and hands each unit its report from the last steady run
   (`WiredUnit.last`), where a column finds the seed its solve ended on (`Report.Warm`: the V3 seed, the feed tray's and
   steam rows' pressures; `current` while its package keeps that column identity); `estimates` seeds torn streams from
   the same run.
4. `FlowGraph.plan` orders the groups and tears the fewest streams. Two calculations writing one stream or giving one
   link throw.
5. `FlowsheetSolver.run`: a group without tears runs once; a torn group one plain pass, then bounded Wegstein. A tear
   that rises by the same step (`SAME_STEP`) on `SAME_STEPS` passes stops with `loop.no_steady_state`; a Wegstein trial
   that fails a unit falls back to plain passes. `result()` closes mass and heat; a mass gap gives `run.mass_gap` and
   `NOT_STEADY`.
6. If the run is not stopped and is steady (or the sheet has sizing facts), the same thread rates it
   (`SheetSizing.rate`).
7. The screen polls each tick, shows the run and uploads the sheet and results to the block
   (`SimulatorBlockHost.ranOut`).

## Contracts

- **Calculations.** A STEP reads and writes streams. A BLOCK solves a whole unit at once (the column). A CHECK writes
  nothing; the plan puts it after the groups it reads, so it runs once a loop has closed; a CHECK that throws becomes a
  warning note and blocks nothing, its reads do not count as consumers, and it is out of the stage order.
- **Links** carry a value that is not a stream (the condenser's duty to its side, the bottoms temperature, a steam row's
  stage pressure). Links are never torn.
- **Boundary.** `result()` counts every written stream that no non-CHECK calculation reads as leaving the sheet;
  `WiredUnit` gives a free outlet its own stream `unit:port`. A unit that writes a stream nobody reads makes a mass gap.
- **Utility feeds.** A sheet feed of water alone on a port with `utility = true` is not a boundary feed:
  `FlowsheetCompiler` hands it to the unit (`WiredUnit.Utilities`), whose utility calculation writes the feed at the
  flow its duty needs (note `flow.set_by`) ([simulator-units.md](simulator-units.md)).
- **Ports and passages.** A unit's ports are the roles its calculations read (`UnitModel.ports`). A passage that works
  either way (`UnitModel.passages`): the valve, the filter, the cooler and the series heater (`a`, `b`); the apart
  heater's two coils (`convection_a`, `_b`, `radiant_a`, `_b`); the exchanger's tube and shell sides; the kettle's
  tubes; in the column only the heat members' utility sides (`condenser_a`, `_b`, `reboiler_a`, `_b`, `pa/<id>/a`,
  `b`). Fixed: machines, the mixer, the splitter, vessels, the column's process ports, stripping steam and placeholders.
  `Wiring`: an end that is a stream's `to` is the inlet, one that is a `from` the outlet, and the other end takes the
  other role; the first stream on either end decides; an end against that plays none and stays open.
  `Wiring.roles(sheet)` names every end by its role, and the compiler, `SheetSizing` and the doors read that sheet. The
  exchanger stays counterflow whichever ends carry its sides.
- **The data identity.** A run uses the client's bundled catalog, also on a dedicated server; `RevisionNotice` shows a
  notice only when the tokens differ. A token is a view's identity, so an edit to a package record, or to a compound the
  sheet does not feed, moves nothing. Save, autosave, the clipboard export and a new sheet write the view's identity into
  the file's basis `revision` (`stamped`).
- **Results are state.** They carry no column warm start (`SimulatorResults` never writes `warm`), so the first run
  after opening starts its loops from the stored steady run and the column from the learned start. A run from a steady
  one is warm and prints other last digits than a cold one, within the loop's tolerance.
- **The block's store** (`SheetStore`): the first player to open holds the sheet and edits; others read. The holder is
  freed on close, on leaving, on disconnect and when the block goes; a player no longer at this block's screen holds
  nothing (`atThisBlock`); the holder is not saved. Uploads count only from the holder, near the block, with the screen
  open; `SheetBody` bounds them (1 MiB unpacked, parts of 30 000 bytes). The client sends a body on Save, when a run ends
  and on close, never on an edit. A body still on its way counts as read-only; a store lands with its last part; an
  unreadable body opens empty and is kept.
- **Opening** (`ProcessSimulatorNetwork.open`): decides who edits (`block.opens`), opens the menu with an `Opening`,
  replays the stored body in parts, parses it once, solves nothing and creates no process state.
- **Threads.** Network handlers run on the server thread. The run never touches process state.

## Laws and constants

- `FlowsheetSolver.Settings.DEFAULT`: flow tolerance 1e-4, temperature 0.01 K, 30 passes, Wegstein factor −5..0.
- `FlowGraph`: `TRIALS` tear trials, deterministic.
- Cooling water leaves a utility at min(T_in + 10 K, process cold end − 5 K) (`HeatSides.coolingWaterOutlet`); kettle
  steam leaves as saturated condensate; stripping steam flows at its row's rate through an isenthalpic inlet valve to the
  stage pressure.

## Formats

The flowsheet file (`FlowsheetFile.FORMAT`; no compatibility: another format is refused, owner 2026-11-04; corpus
`src/test/resources/compat/flowsheet/format-2*.json`), the block body (`SheetBody.FORMAT`, a fact: the sheet inside it),
the run results (`SimulatorResults.FORMAT`, state: dropped on a mismatch), the sent designs (`SentDesignTags`, a fact;
corpus `compat/simulator/designs-<n>.snbt`) and the packets (on `NetworkProtocol.VERSION`): [FORMATS.md](../FORMATS.md).
The file keeps fields it does not know; a format change re-records the corpus and the block keeps an old body as
unreadable. `SheetLayout` reads a stream's `route`, so a change to it is a format change.

- Packets (`ProcessSimulatorNetwork`, handlers on the server thread):

  | Payload | Direction | Server rule |
  |---|---|---|
  | `simulator_sheet_part` (`SheetPart`) | both | To the client: the replay on open. To the server: an upload from the holder whose menu is this container, near its block; a bad body is refused while the stored sheet stands |
  | `simulator_save_design` (`SaveDesign`) | to server | Holder only: `ColumnDesign.of(input, choices)` (`ColumnWire`), stored as the block's design; a refusal gives its reason |
  | `simulator_send_designs` (`SendDesigns`) | to server | Holder only; the list whole, not empty, at most 64; it replaces the earlier list |
  | `simulator_answer` (`Answer`) | to client | Each request's answer key (`store.saved`, `store.design_saved`, `store.design_refused`, `store.designs_sent`, `store.designs_refused`, or `READ_ONLY`), detail up to 256 characters |

- NBT of the block entity: `SheetId`, `Sheet` (the body), `KeptSheets`, `Design` (`ColumnDesignTags`), `Designs`
  (`SentDesignTags`, unreadable entries kept). `process_simulator` replaced `column_calculator_v3`, whose id is an alias
  for the block, its item and its block entity (`ModBlocks`, `ModItems`, `ModBlockEntities`); it has no recipe.
- Files on the client: `.minecraft/IChemE/flowsheets/<name>.json`; autosaves `<name>.autosave.json` or
  `autosave-<sheet id>.json` ([simulator-screen.md](simulator-screen.md)).

## Tests and gates

| Change | Owner | Tests (`--tests` filter) |
|---|---|---|
| Order, tears, loops | `FlowGraph`, `FlowsheetSolver` | `FlowGraphTest`, `FlowsheetSolverTest` |
| The file | `FlowsheetFile`, `SizingFactsFile` | `FlowsheetFileTest` (the corpus) |
| Which end of a passage is its inlet | `Passage`, `Wiring`, `UnitModel.passages`, `SheetEditor.takes` | `WiringTest`, `PassageEndsTest` |
| The block's store, uploads, packets | `SheetStore`, `SheetBody`, `ProcessSimulatorNetwork`, `ProcessSimulatorBlockEntity` | `SheetStoreTest`, `SimulatorBlockTest`, GameTests `ProcessSimulatorGameTests` (`runColumnGameTestServer`: the old id, a reload with no update tag, a damaged sheet kept, Import, a second player reads, uploads only from the screen near the block, a bad upload refused, the holder freed, a replay that runs no solver) |
| Where a run starts | `FlowsheetCompiler.compile`, `estimates`, `Report.Warm` | `SimulatorBlockTest.aStoredSteadyRunStartsTheNextRunsLoops`, `CrudeUnitSampleTest.SimulatorRunTest.aRunFromASteadyOneStartsTheColumnWhereItEnded` (`holdGates`) |
| The sheet's compounds and the notice | `SheetCompounds`, `RevisionNotice` | `FlowsheetExamplesTest`, `FlowsheetAqueousColumnTest` |
| A new kind | `FlowsheetCatalog`, `SizingCoverage` (both in `src/test`, switches without a default) | `FlowsheetPaletteCoverageTest`, `SizingCoverageTest` |

Shared fixture: `SheetFixture` (`sample()` reads the bundled crude unit example). The guide's criteria:
`ProcessSimulatorNetwork` fires `sheet_run` after the block took a whole uploaded body (`TutorialTriggers.sheetStored`,
nothing solved) and `design_sent` after Send to block ([tutorial.md](tutorial.md)).

## Traps

- **New equipment joins the simulator** (AGENTS.md): every new `EquipmentShape.Kind`, `ConstructorColumn.Equipment` and
  `TopologyCompiler.Kind` constant maps to a tile in `FlowsheetCatalog`, MODELLED or DRAWING_ONLY; a new constant fails
  `compileTestJava` until it is mapped; `FlowsheetPaletteCoverageTest` holds each to a tile, a port rule and a lang
  line; one tile may hold several kinds.
- The run uses the client's catalog even on a dedicated server.
- A run's results are state; the Sizing lamps are state and never saved.
- `SimulatorResults.ranOnBody(bytes)` reads only the results' head: the server never builds a run.
- `SheetCompounds` is derived, never stored: a sheet holds compound ids, and its view is rebuilt per run.

**Snapshot:** `d6f721b94`, 2026-11-15.
