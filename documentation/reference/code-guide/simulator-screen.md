# Process simulator: the screen, its pages and doors, the runner and the files

Up: [code guide](README.md) · the sheet and the block: [process-simulator.md](process-simulator.md) · sizing:
[simulator-sizing.md](simulator-sizing.md) · the GUI kit and its rules: [client-kit.md](client-kit.md),
`.claude/skills/gui-creation/SKILL.md`

## Read this when

- The simulator's screen draws, lays out, scrolls or hit-tests wrong; a door's row, a page or a dialog misbehaves.
- An edit, undo, redo, drag or paste goes wrong; a passage end refuses a stream.
- Run, Stop, Solve alone or Size all hangs or runs on the wrong thread; Run stays lit or dark wrongly.
- Open, Save as, Delete, the examples or the autosave misbehave.
- A help tour stop is missing on the simulator.

## What it does

`ProcessSimulatorScreen` is the one Minecraft class of the screen: it forwards events and draws. Everything it shows is
built Minecraft-free in `client/gui/flowsheet/`, so `tools/heat-gui-renders/SimulatorRender.java` draws the same pages
from shipped code. The frame has four tabs (FLOWSHEET, RESULTS, SIZING, BASIS); the sheet is drawn on a canvas with pan
and zoom; a click on a unit or a stream opens its door; edits go through one editor with undo and redo. Runs go to
`SimulatorRunner`, one at a time on its own low-priority thread; the screen polls it each tick, uploads finished runs to
the block (`SimulatorBlockHost`) and autosaves through one writer thread.

## Class map

**`client/gui/flowsheet/`** ([source](../../../src/main/java/com/wormzjl/icheme/client/gui/flowsheet/)), Minecraft-free.
Lines at the snapshot.

| Class | Role | Lines |
|---|---|---|
| [`SimulatorRunner`](../../../src/main/java/com/wormzjl/icheme/client/gui/flowsheet/SimulatorRunner.java) | The run half of the host: one daemon thread "IChemE simulator run" at the lowest priority, one job at a time on a private copy of the sheet (Run, Solve alone, Size all, the quiet rate); Stop sets a flag the next checkpoint throws on and drops the columns' warm starts (`FlowsheetRun.cold`); `steady` (the last steady run starts the next Run), `restore` (from a block's stored steady run) | 346 |
| [`SimulatorHost`](../../../src/main/java/com/wormzjl/icheme/client/gui/flowsheet/SimulatorHost.java) | What the screen asks of the outside: `run`, `rate`, `settle`, storing; `NONE` for the render tool and tests | 162 |
| [`SimulatorFrame`](../../../src/main/java/com/wormzjl/icheme/client/gui/flowsheet/SimulatorFrame.java) | The frame as rectangles: title, tabs (`Tab`), file keys, the status display, the canvas's well, the palette, the footer; `keys`, `keysOnTabRow`, `wideKeys`, `recesses`, `conditionsKey`, `conditionsRecess` | 296 |
| [`SimulatorPage`](../../../src/main/java/com/wormzjl/icheme/client/gui/flowsheet/SimulatorPage.java) | Which keys stand where on each page and what draws (`back`, `keys`, `runDue`) | 365 |
| [`Door`](../../../src/main/java/com/wormzjl/icheme/client/gui/flowsheet/Door.java), [`DoorFrame`](../../../src/main/java/com/wormzjl/icheme/client/gui/flowsheet/DoorFrame.java) | A door as a builder says it (texts, rows, entries, glass, footer keys; `View`, `Glass`); where its parts stand (`tabs`) | 184, 100 |
| [`SheetContext`](../../../src/main/java/com/wormzjl/icheme/client/gui/flowsheet/SheetContext.java), [`SheetUnits`](../../../src/main/java/com/wormzjl/icheme/client/gui/flowsheet/SheetUnits.java) | What every door and page reads (the sheet through its editor, the run, the display units, `into`, `outOf`, `streams`, `feedMoles`, `density`, `conditions`); the display keys' units (`Basis`, `flow`, `molesOf`, `reading`, `p`, `drop`, `KMOL_PER_HOUR`, `conditions`) | 182, 154 |
| [`UnitDoors`](../../../src/main/java/com/wormzjl/icheme/client/gui/flowsheet/UnitDoors.java), [`UnitGlass`](../../../src/main/java/com/wormzjl/icheme/client/gui/flowsheet/UnitGlass.java), [`Reader`](../../../src/main/java/com/wormzjl/icheme/client/gui/flowsheet/Reader.java) | Every unit's door as rows; its glass (the shipped drawing, the streams, the sketch); one reading of a unit's results in the display units | 568, 230, 37 |
| [`ColumnDoors`](../../../src/main/java/com/wormzjl/icheme/client/gui/flowsheet/ColumnDoors.java), [`ColumnGlass`](../../../src/main/java/com/wormzjl/icheme/client/gui/flowsheet/ColumnGlass.java) | The column's door through `CalculatorColumn` (its tabs, Templates, Save design; `duties`); its glass (the drawing, the connections, the Results pages, `streamPairs`, `productHeader`, `nodePairs`, `dropText`) | 511, 341 |
| [`StreamDoors`](../../../src/main/java/com/wormzjl/icheme/client/gui/flowsheet/StreamDoors.java), [`StreamProps`](../../../src/main/java/com/wormzjl/icheme/client/gui/flowsheet/StreamProps.java) | A stream's door (feed, flow, T and P, components, Copy and Paste; `largest`, `writeFeed`); what a stream's meters read (`Fluids.weight`) | 397, 102 |
| [`ResultsPage`](../../../src/main/java/com/wormzjl/icheme/client/gui/flowsheet/ResultsPage.java), [`NoteText`](../../../src/main/java/com/wormzjl/icheme/client/gui/flowsheet/NoteText.java), [`SheetResults`](../../../src/main/java/com/wormzjl/icheme/client/gui/flowsheet/SheetResults.java) | The Results tab (streams, units, the log, the balances); a note in the player's units; a run as the screen reads it | 470, 118, 93 |
| [`SizingPage`](../../../src/main/java/com/wormzjl/icheme/client/gui/flowsheet/SizingPage.java), [`SizingGlass`](../../../src/main/java/com/wormzjl/icheme/client/gui/flowsheet/SizingGlass.java) | The Sizing tab's four views (sizing method apart from equipment type, count and size; `open`, `number`); its table, picks and scroll (`Table.branch`, `parented`) | 979, 503 |
| [`BasisPage`](../../../src/main/java/com/wormzjl/icheme/client/gui/flowsheet/BasisPage.java), [`SelectionColumn`](../../../src/main/java/com/wormzjl/icheme/client/gui/flowsheet/SelectionColumn.java) | The Basis tab (package, design basis, solver settings); the selection's panel (readings, a feed's entries, Auto's sizes) | 84, 137 |
| [`FileColumns`](../../../src/main/java/com/wormzjl/icheme/client/gui/flowsheet/FileColumns.java), [`FlowsheetExamples`](../../../src/main/java/com/wormzjl/icheme/client/gui/flowsheet/FlowsheetExamples.java) | Open and Save as dialogs and file names (`entries`, `opened`, `deletable`, `delete`, `nameAfterDelete`, `changes`); the bundled examples (`assets/icheme/flowsheet_examples/index.json`, read through a resource function) | 381, 81 |
| [`SheetEditor`](../../../src/main/java/com/wormzjl/icheme/client/gui/flowsheet/SheetEditor.java), [`SheetGestures`](../../../src/main/java/com/wormzjl/icheme/client/gui/flowsheet/SheetGestures.java), [`SheetClipboard`](../../../src/main/java/com/wormzjl/icheme/client/gui/flowsheet/SheetClipboard.java) | Every edit with undo and redo, tags and refusals (`takes`, `roles`); the mouse and key state machine (`Mode.PAN`, `drawRings`); the one clipboard (`paste`) | 668, 436, 101 |
| [`SheetAutosave`](../../../src/main/java/com/wormzjl/icheme/client/gui/flowsheet/SheetAutosave.java) | The autosave's one writer (`CLIENT`), off the render thread; each file's newest sheet wins | 69 |
| [`SheetLayout`](../../../src/main/java/com/wormzjl/icheme/client/gui/flowsheet/SheetLayout.java), [`SheetPainter`](../../../src/main/java/com/wormzjl/icheme/client/gui/flowsheet/SheetPainter.java), [`SheetView`](../../../src/main/java/com/wormzjl/icheme/client/gui/flowsheet/SheetView.java) | Boxes, ports, routes and hit tests (`at`, `grabs`, `REACH`; reads a stream's `route`); drawing (`draw`); pan and zoom | 674, 369, 76 |
| [`Symbol`](../../../src/main/java/com/wormzjl/icheme/client/gui/flowsheet/Symbol.java), [`Tiles`](../../../src/main/java/com/wormzjl/icheme/client/gui/flowsheet/Tiles.java) | The symbol atlas (`textures/gui/flowsheet/symbols.png`); the palette's categories and tiles | 114, 154 |
| [`ColumnBlock`](../../../src/main/java/com/wormzjl/icheme/client/gui/flowsheet/ColumnBlock.java), [`AssemblyBlock`](../../../src/main/java/com/wormzjl/icheme/client/gui/flowsheet/AssemblyBlock.java) | A column as the sheet draws it (`of(unit, door, reversed)`); a built column's assembly drawn the same way | 449, 146 |
| [`SheetState`](../../../src/main/java/com/wormzjl/icheme/client/gui/flowsheet/SheetState.java) | Each unit's look, the tears, the status, `outOfDate` | 303 |
| [`StillCurve`](../../../src/main/java/com/wormzjl/icheme/client/gui/flowsheet/StillCurve.java) | A batch still's curve on its door's Batch tab, from the run's `Report.Series` | 210 |
| [`SimulatorTour`](../../../src/main/java/com/wormzjl/icheme/client/gui/flowsheet/SimulatorTour.java) | Where the simulator's help tours stand: each tab's tour, the anchors a stop may name ([tutorial.md](tutorial.md)) | 79 |

**The game side:** [`client/gui/screens/inventory/ProcessSimulatorScreen`](../../../src/main/java/com/wormzjl/icheme/client/gui/screens/inventory/ProcessSimulatorScreen.java)
(1149 lines; a plain `Screen` on a `ProcessSimulatorMenu`, registered by `IChemEClient.registerScreens` on
`ModMenus.PROCESS_SIMULATOR`; no slots, so a typed E is text) and
[`SimulatorBlockHost`](../../../src/main/java/com/wormzjl/icheme/client/gui/screens/inventory/SimulatorBlockHost.java)
(152 lines; the game's host: the runner plus the block's store, `ranOut`, `settle`).

## Main flow

1. **Open.** The menu arrives with its `Opening` and the replayed body; the screen builds the editor on the sheet, and
   `SimulatorRunner.restore` sets the last steady run from the stored results.
2. **Render.** The screen draws its whole frame, page or door or dialog and the widgets in one `Canvas.batch`: fills go
   out in runs while texts and sprites wait (`HeldDraws`, [client-kit.md](client-kit.md)). `SheetPainter.draw` batches
   the grid, each line, circle and valve, the hatch and the tear; each line ends in one head (`FlowsheetPainter.head`).
3. **Input.** `SheetGestures` turns mouse events into edits, `SheetEditor` applies them with undo and redo; a door's
   `tab/` and `phase/` presses go through `Door.View.press`; an entry's edit ends on Enter or a click outside
   (`SkinField.clickOutside`, `EditFocus.ends`), and the screen's commit hook ends the editor's step.
4. **Run.** The Run key calls `SimulatorHost.run`; the runner's thread runs the job; Stop sets its flag.
5. **Tick.** The screen lets the host upload a finished run (`SimulatorBlockHost.ranOut`), autosaves every
   `AUTOSAVE_TICKS` through `SheetAutosave.CLIENT`, clears a spent status line, and re-rates the Sizing tab 10 ticks after
   the last edit (`rateWhenDue`).
6. **Close.** `onClose` lets the host send the sheet once more (`settle`); `removed()` autosaves and closes the host (the
   runner's job stops).

## Contracts

- **Window.** The screen keeps the player's GUI scale; layout and scrolling use the actual viewport.
- **Frame keys.** `keys(third)` puts Close in the footer and the tab's two keys, then Undo and Redo, in two pairs 12 px
  apart: on the tab row where they fit (`keysOnTabRow`), else on the title row; the tabs and file keys keep their wide
  widths only where the pairs fit between them (`wideKeys`). Each pair, the tabs, the file keys and the theme key stand
  in a recess (`Skin.recess`); the footer keeps Close bare and the display keys' tray; the P, T key stands in its own
  recess and puts each stream's T and P under its name (`SheetContext.conditions`). The Sizing tab opens only once the
  sheet can be sized (`SizingPage.open`); on it, Size all and Send to block stand in Run's and Stop's place.
- **Run lit.** Run is primary and lit amber while the results are out of date (`SimulatorPage.runDue`:
  `SheetState.outOfDate`); its hover then says `hint.run_due`; off while a run is going, Stop on. The screen's signature
  carries `runDue`.
- **Doors.** The screen builds the shown door once a frame and hands it to its signature; the column door parses its
  params once (`ColumnDoor.of`). A stream a unit makes has no tabs: its phase keys pick the glass; it opens on the phase
  that holds the most until a press sets `Door.View.phasePicked`. A stream template pastes with the template's flow;
  parts not on the sheet drop.
- **Units on the doors.** Every flow and pressure entry shows, labels and reads in the display keys' units
  (`SheetUnits.p`, `pressure().si`); a unit's own flow setting stays molar and only its time follows the key
  (`SheetUnits.moles`, `molesUnit`, `molesPerSecond`). A stream's flow follows the basis key (Mole, Mass, Volume at the
  stream's own T and P): `SheetUnits.flow` is the one format, `molesOf` the one way back; Volume leaves an entry blank
  where the density is unknown (`door.volume_unknown`). A reading that spans magnitudes goes through
  `SheetUnits.reading` (three significant figures under 1, E form under 0.01 or from a million).
- **Gestures.** A press where `SheetLayout.at` finds nothing pans; the game sends `mouseMoved` before each
  `mouseDragged`, so a pan steps from where the last drag left it. On a column only what is drawn grabs it
  (`SheetLayout.grabs`): the frame's outline, the tower, the members and the pipes within `REACH`; the empty plate pans.
- **Passage ends.** `SheetEditor.takes` lets a free passage end take a stream's start or its end; once one end holds a
  stream, the other takes only the other role. Two ends of one passage wired the same way give `PASSAGE`; two fixed
  ports of one direction `PORT_SIDE`. Where nothing decides, the stream leaves the end it is drawn out of, so a pill is a
  product. A column side entered by its second end turns its pipes' arrows (`ColumnBlock.of(unit, door, reversed)`).
- **Files.** A sheet is saved as `.minecraft/IChemE/flowsheets/<name>.json`; an autosave writes `<name>.autosave.json`
  beside a named file, or `autosave-<sheet id>.json`; a player cannot use these names; a reader never autosaves. The
  autosave has one writer thread for the client (no daemon, started when there is work); a failed write hands its sheet
  back and the next tick says `store.autosave_failed`. Open lists the bundled examples first (`entries`); an example
  opens unsaved (`FileColumns.opened`) and never changes. `deletable` takes any of the player's own files, never an
  example; `delete` removes only a regular file directly in the folder, never a link, else refuses
  (`file.delete_failed`); `nameAfterDelete` drops the open sheet's name when its own file went.
- **Threads.** The runner's thread is the only one that solves; the render thread never waits on it. The screen's
  classes under `client/gui/flowsheet/` name no Minecraft type.

## Laws and constants

The screen holds no physics. Its timing constants: `ProcessSimulatorScreen.AUTOSAVE_TICKS`, the quiet re-rate 10 ticks
after the last edit; its draw-call budget is held by `DrawCallTest`.

## Formats

The screen saves the flowsheet file and the autosaves ([process-simulator.md](process-simulator.md)); the stream T and
P key is `ClientConfig.streamConditions`. Numbers: [FORMATS.md](../FORMATS.md).

## Tests and gates

| Change | Owner | Tests (`--tests` filter) |
|---|---|---|
| Frame, pages, layout | `SimulatorFrame`, `SimulatorPage`, `SheetLayout` | `SimulatorFrameTest`, `SimulatorColumnsTest`, `SheetLayoutTest` |
| Editing and gestures | `SheetEditor`, `SheetGestures` | `SheetEditorTest`, `SheetGesturesTest` (drags as the game sends them), `PassageEndsTest`, `EditFocusTest` |
| Doors and units | `UnitDoors`, `StreamDoors`, `SheetUnits` | `DoorUnitsTest`, `StreamFlowBasisTest`, `RunKeyTest` |
| Sizing page | `SizingPage`, `SizingGlass` | `SizingPageTest` |
| Column drawings | `ColumnBlock`, `AssemblyBlock` | `ColumnBlockTest`, `AssemblyBlockTest` |
| Draw calls | `SheetPainter`, the frame batch | `DrawCallTest` |
| Runner and Stop | `SimulatorRunner` | `SimulatorRunnerTest` (`stopLeavesTheNextRunsColumnsToTheLearnedStart`), `CrudeUnitSampleTest.SimulatorRunTest` (`holdGates`: Stop inside a column) |
| Files, examples, Delete, autosave | `FileColumns`, `FlowsheetExamples`, `SheetAutosave` | `OpenDeleteTest`, `FlowsheetExamplesTest`, `SheetAutosaveTest`, `SimulatorBlockTest` |
| Tours | `SimulatorTour` | `SimulatorTourTest` |

Renders: `tools/heat-gui-renders/SimulatorRender.java` (with `SimulatorPrototypes`, `SimulatorSizing`); its system
properties pick a sheet, count draw calls per page or set the flow basis. Probes outside the gates:
`tools/process-simulator/`, `tools/device-names/`.

## Traps

- A body still on its way counts as read-only on the screen.
- The Sizing tab's lamps are state: the screen rebuilds them with a quiet rate job.
- `FlowsheetCatalog` and `SizingCoverage` are in `src/test`: a new kind fails `compileTestJava` until mapped.
- Results carry no column warm start: the first Run after opening starts the column from the learned start.
- A stream's T and P under its name read the run's values, or a feed's set values before a run.

**Snapshot:** `d6f721b94`, 2026-11-15.
