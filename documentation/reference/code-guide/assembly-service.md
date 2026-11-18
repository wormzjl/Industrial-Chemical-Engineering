# Assembly service: when a check runs, its store and reverts, the screens, the tool's watch and Start

Up: [code guide](README.md). The check itself (blueprints, walk, matcher, slot states): [machine-assemblies.md](machine-assemblies.md).
The running column after Start: [column-run.md](column-run.md); what a complete exchanger side's lines do:
[heat-units.md](heat-units.md); the GUI kit the screen is built on: [client-equipment-screens.md](client-equipment-screens.md).

## Read this when

- A report did not go back to "not validated" after a change, or went back without one (`assembly status=REVERTED`).
- A column's assembly screen shows a stale or missing answer, a second Check parts is refused, or a click on a column or
  a unit beside it opens the wrong screen.
- An exchanger side's status says `LINES NOT CHECKED`, `LINES NOT COMPLETE` or `LINES COMPLETE` wrongly, or keeps working
  a valve its report no longer holds.
- Start refuses a complete column, or a change stops a running one; Apply refuses set points.
- The laying tool's port marks are missing, coloured wrongly or do not change with a check.
- You touch the assembly packets, the `icheme_assemblies` store, or the review world (`runReviewWorldServer`).

## What it does

A check runs only when the player presses Check parts (or Check lines on an exchanger side's screen): nothing polls.
`AssemblyService` queues it, runs it at the next server tick's round, and keeps each master's report with the structural
code of every block it relied on in `AssemblyStore`. A fluid edit, a structure placed or removed, a flowmeter installed
or removed, or a chunk load that changes a coded block reverts the report to not validated. The column's screen is an
engine presentation session (`AssemblyPresentation`): opening it shows the stored view at once, a check or a command
answers on the engine's bucket (AGENTS.md, "Engine schedule and player-facing updates"). An exchanger's report reaches
its sides' controls as `FluidWorldAuthority.Lines` (`ExchangerLines`). A column with a complete report can start from
its Assembly screen or a DCS linked to its data socket. `ColumnStarts` uses the saved report, built shapes and registry
to name its boundary valves, nozzles and members. The laying tool watches the reports of nearby columns (`AssemblyWatch`).

## Class map

Paths are relative to `src/main/java/com/wormzjl/icheme/`.

| Class | Role | Lines |
|---|---|---|
| [`runtime/assembly/AssemblyService`](../../../src/main/java/com/wormzjl/icheme/runtime/assembly/AssemblyService.java) | Static, one queue per server: `designed(shape)`, `view`, `watched`, `entry` (reads), `holder`, `cellOf`, `request`, `validate`, `tick`; the reverts `edited` (with `redesigned`), `placed`, `removed`, `chunkLoaded`, `controllerLoaded`, `metered`; `published`; the `walks(server)` counter; for a column's run `start`, `stop`, `setpoints`, `operation`, `dismantled` | 515 |
| [`runtime/assembly/AssemblyStore`](../../../src/main/java/com/wormzjl/icheme/runtime/assembly/AssemblyStore.java) | Per-level `SavedData` `DATA_NAME` (`icheme_assemblies`), `FORMAT`, state: `Entry(status, report, codes, changedAt)`, `Status` NOT_VALIDATED, CHECKED, COMPLETE; indexes by position and by chunk (`mastersAt`, `mastersInChunk`); `put`, `revert`, `forget`, `taken(except)`, `rebuilding()`; bounds `MAX_MASTERS`, `MAX_SLOTS`, `MAX_PORTS`, `MAX_FOOTPRINT` | 292 |
| [`runtime/assembly/AssemblyPresentation`](../../../src/main/java/com/wormzjl/icheme/runtime/assembly/AssemblyPresentation.java) | The column screens' sessions on a `PresentationMailbox`: `open`, `validate`, `act` (Start, Stop or Apply, queued as Validate is), `close` (a command queued before still runs), `tick`; `Host` | 101 |
| [`runtime/assembly/AssemblyWatch`](../../../src/main/java/com/wormzjl/icheme/runtime/assembly/AssemblyWatch.java) | The laying tool's watch within `RANGE`: `start`, `stop`, `tick`; `Host` | 81 |
| `runtime/assembly/ColumnStarts` (pp) | `build(level, world, origin)` gives a `Built(request, refusal)`: the boundary valves of a complete report as `FluidWorldAuthority.PortSpot`s, the design's set points, the nozzles, the condenser's water sides, the air condenser's bare tube, the members (`ColumnUnit.Members`) and the shape's material of construction | 151 |
| `runtime/assembly/ExchangerLines` (pp) | `design(level, world, vessel)` reads the lines each side's registered control asks for (`HeatUnit.lines()`, through `EquipmentStructure.sideIdentities`); `publish(level, world, vessel, entry)` sets each such side's `FluidWorldAuthority.Lines` | 94 |
| [`network/AssemblyNetwork`](../../../src/main/java/com/wormzjl/icheme/network/AssemblyNetwork.java) | The packets on `NetworkProtocol.VERSION` (main thread); `open(player, vessel, cell)`, `target`, `closed`, `datasheet`, `presentationTick`, `forget`; the `VIEW` codec, `encodeOperation`/`decodeOperation` (also read by the DCS's column page); `Action` START, STOP, APPLY, CLEAN (a stopped column's trays, answered by a chat line); `REFUSALS` (each server's last refused Start per master, until the next press, not saved) | 612 |
| [`world/inventory/AssemblyMenu`](../../../src/main/java/com/wormzjl/icheme/world/inventory/AssemblyMenu.java) | Menu `assembly`: origin, cell (a shell cell of the column), anchor, reach (`REACH` plus the clicked structure's largest size), selected member; client `view()`/`accept`; `removed` ends the session | 102 |
| [`client/gui/screens/inventory/AssemblyScreen`](../../../src/main/java/com/wormzjl/icheme/client/gui/screens/inventory/AssemblyScreen.java) | The column's screen on the kit's door and glass: the drawing beside the parts list, Check parts, Open unit, Datasheet, the column's name in the title strip (saved by `EquipmentNetwork.rename`); tabs Structure and Operation (`OperationColumn`, when the report is complete and the view has an operation); its help tours (`LINES_STEP`) | 685 |
| [`client/gui/flowsheet/AssemblyBlock`](../../../src/main/java/com/wormzjl/icheme/client/gui/flowsheet/AssemblyBlock.java) | The drawing: the simulator's column block (`ColumnBlock` of a `ColumnDoor` built from the trays by `params`), each part a slot of `ColumnBlueprint`; `fit(box)`, `draw(canvas, box, report, selected, name, texts)`, `part(x, y)`. No Minecraft classes | 146 |
| [`client/gui/common/AssemblyDiagram`](../../../src/main/java/com/wormzjl/icheme/client/gui/common/AssemblyDiagram.java) | Minecraft-free parts and ports of a column's assembly: `Part(slot, name, where, wanted)` per slot, `Port(slot, role, ordinal, partner)` in a fixed order (ordinal -1: any of that role); `of(trays)`, `part(slot)`; texts under `KEY` | 182 |
| [`client/gui/common/AssemblyColumn`](../../../src/main/java/com/wormzjl/icheme/client/gui/common/AssemblyColumn.java) | The door and the parts list of the column's screen | 291 |
| [`client/gui/common/AssemblyTour`](../../../src/main/java/com/wormzjl/icheme/client/gui/common/AssemblyTour.java) | The screen's tour anchors | 49 |
| [`client/AssemblyToolClient`](../../../src/main/java/com/wormzjl/icheme/client/AssemblyToolClient.java) | The laying tool's watch on the client: `watch`, `received`, `colour`, `frame`, `line` | 157 |
| [`client/PortColours`](../../../src/main/java/com/wormzjl/icheme/client/PortColours.java) | `assembly(diagram, slot, role, ordinal)`: a nozzle's colour by its port's index in `AssemblyDiagram.ports`, -1 where the design has no port; class colours `VAPOUR`, `BULK`, `LIQUID`, `OVERFLOW`; `CONNECTED`, `MISPLACED` | 55 |

## Main flow

1. **A click.** `EquipmentShellBlock.use` on the server → `AssemblyNetwork.open`: a column opens its own screen (its
   controller cell); a unit opens the screen of the column whose report holds it (`AssemblyService.holder`, the least
   master if two do), with the unit selected; otherwise the structure opens its own (`EquipmentShellBlock.openOwn`). A
   fake player opens nothing.
2. **Open.** The menu opens; the session subscribes and gets the stored view at once (`AssemblyService.view`:
   NO_DESIGN for a column without components, NOT_VALIDATED with no entry, else the entry). Nothing is walked.
3. **Check.** `icheme:assembly_validate` → `AssemblyPresentation.validate` queues one command (a second before the
   bucket is refused). On the bucket, `AssemblyService.validate` runs the check ([machine-assemblies.md](machine-assemblies.md)),
   each master at most once, and `AssemblyStore.put` keeps the entry; every open screen of that master gets the view,
   other sessions only when it changed. An exchanger side's Check lines: `icheme:fluid_check_lines` →
   `FluidNetwork.checkLines` → `AssemblyService.request` at the side's cell, run on the next server tick; after it
   `ExchangerLines.publish`.
4. **Revert.** A change a report relied on → its entry NOT_VALIDATED with `changedAt` and the place of the change; the
   screens show it at the next bucket (table below).
5. **Start, Stop, Apply.** `icheme:assembly_operation` → `AssemblyPresentation.act`, queued as Validate is →
   `AssemblyService.start` (→ `ColumnStarts.build` → `FluidWorldAuthority.startColumn`), `stop`, `setpoints`; every open
   screen of the column gets the answer. The DCS routes its queued commands through the same service at the column's
   saved master position. [Wired control and FE power](wired-control-power.md#a-column-on-the-dcs).

## Contracts

**Reverting without polling** (`AssemblyService`):

| Path | Trigger | Reverts | Log `reason=` |
|---|---|---|---|
| `edited` | each `FluidWorldAuthority.DeviceChange` of a queued edit (`FluidWorldAuthority.listen`, registered in `IChemE`) | a report with that position coded, when the code changed; a COMPLETE report whose coded block a new device stands beside; an exchanger whose side's control now asks for other lines forgets its entry and its sides' lines (`redesigned`, log `assembly status=REDESIGNED`) | `changed`, `branch` |
| `metered` | `InstrumentService` installs or removes a flowmeter on a pipe | a report with that position coded | `flowmeter` |
| `placed` | `EquipmentStructure.place` and the end of a refresh | a COMPLETE report coding a shell cell of the structure or the block in front of a nozzle; a new designed column gets a NOT_VALIDATED entry | `structure` |
| `removed` | a dismantle and the start of a refresh | the column's own entry is forgotten; a report holding the structure, or coding its cells or nozzle blocks | `removed` |
| `chunkLoaded` | a chunk with coded blocks loads (after `FluidWorldAuthority.loadedChunk`) | the next tick compares each coded block with what stands there (reads only) | `chunk_load` |

- A CHECKED report reverts only at a block or part it found: an addition beside it does not revert it, because the
  player is building. A COMPLETE one reverts on any structural change it relied on, a new branch included.
- Log line: `assembly status=REVERTED master=… was=… at=… reason=…`.
- `tick` (from `IChemE`'s server tick, after `FluidWorldAuthority.tick`) runs the queued chunk checks, then the queued
  requests, one validation per master per round. Nothing runs when nothing is queued.
- A dropped store is marked `rebuild`: each designed column's controller cell queues one validation when it loads
  (`controllerLoaded`), only for a column with no entry yet.

**The exchanger's lines** (`ExchangerLines.publish`). After every validation, revert and chunk check, each asking side's
`FluidWorldAuthority.Lines`: NOT_VALIDATED, CHECKED (parts not in place counted), UNMETERED (complete, but the operated
valve carries no flowmeter, so it stands open) or COMPLETE (the valve carries its flowmeter), with the operated valve's
identity and the slot states the side's screen colours its parts by. A side asking for no lines gets none. The fluid
world keeps lines only while the server runs; a chunk check publishes them again. A COMPLETE side's control sets the real
valve's flow target and its internal bypass stays shut ([heat-units.md](heat-units.md)).

**Start** (`ColumnStarts.build`, refusals in order, texts from `Refusal`): `NO_DESIGN`, `NO_SAVED_POINT`, `VACUUM` (a
design with a vacuum top, `ColumnComponents.ejector`, whether an ejector or a vacuum pump draws it), `INCOMPLETE`, `VALVE_UNREGISTERED`; the tab shows the text as a NOT_READY
engine line. The ledger side has its own ([column-run.md](column-run.md)).

- **Ports from slots.** `port(slot)` maps `feed_valve`, `steam_valve`, `offgas_valve`, `water_valve`,
  `distillate_valve`, `draw_valve` and `bottoms_valve`. The valve comes from the slot's `member` (a fitting's device),
  not from `at`. An inlet's column side is against the valve's facing, an outlet's along it, and the port's cell is one
  step from the valve on that side. Conditions come from `PortConditions.estimate`.
- **Nozzles** are `ShapeRasterizer.ports` of the column's shape; `startColumn` walks from their pipe blocks to the
  boundary valves in the fluid registry.
- **Condenser.** The water sides come from every unit (`SlotReport.units`) of the slot `ColumnBlueprint.waterCooled`
  names (the condenser's shells for an exchanger, the trim's for air then water, none for an air cooler;
  `EquipmentStructure.heatSides`). Air and air-then-water condensers contribute the bare-tube area of every air cooler
  bank in the condenser slot (`HeatSides.of(...).area()`).
- **Members.** The saved assembly identifies each unit in `REBOILER` and `pumparoundCooler(i)`, each air condenser bank,
  and each pump at `REFLUX_PUMP` and `pumparoundPump(i)`. `SavedEquipment.at` resolves constructed members' shapes by
  origin. `EquipmentStructure.registrationsAtNodes` resolves their heat sides and movers in layout order, and an in-line
  pump uses the device registered at the slot's position. The Start request carries the reboiler and pumparound sides
  for utility assignment, and the fan sides and movers as `ColumnUnit.Members`.
- **A start reverts nothing.** A held device keeps its structural code, and a port has ports, so `LevelGround.piece` is
  null and it has no code.
- **Stops.** `revert` and `removed` call `stopColumn` first. A dismantle also calls `dismantled`
  (`FluidWorldAuthority.columnRemoved`): the unit leaves with its accounts booked as destroyed. A refresh calls `removed`
  alone, so its unit stays, stopped.
- **Set points.** `setpoints(level, origin, values)` takes feed, condenser, reflux, reboiler, steam…, draws…,
  pumparounds…, holds the feed to the design rate and refuses a wrong count. A column that has not run yet takes them on
  its saved design's set points (a stopped unit with no stations), unless it has no operating point or is a vacuum column.
- **`operation`** reads the unit's published state or uses the saved design for a stopped column with no unit. A vacuum
  column or a locally refused Start shows NOT_READY. Without a unit or an operating point it returns null.

**The laying tool's watch** (`AssemblyWatch`, `AssemblyToolClient`).

- Holding the laying tool is the request. The client judges it (`AssemblyToolClient.watch`, each tick from
  `PipeLayingClient`) and sends `assembly_watch` on and off: the server learns of a number key a tick after the request,
  so a server check of the hand would refuse the request it answers.
- Taking the tool up replays the stored view of every column with a block, a nozzle or a member within `RANGE`
  (`AssemblyService.watched`, reads). On each bucket the watcher gets the views that changed, the columns that came into
  range, and an end (no view) for each that left or is gone. The server drops only a watcher who left the world
  (`Host.present`), at the next bucket; putting the tool away ends the watch by the client's word (`stop`).
- On the client a watched port gets a frame round the tool's port square: yellow on a misplaced part's ports, grey once
  CONNECTED, the shared GUI colour while NEEDED or UTILITY (class colour for utilities absent from the diagram), none
  otherwise (`frame`). A column's own connected nozzle has the grey frame even without a report. Aiming at a watched
  nozzle adds its line to the label (`line`, key `gui.icheme.assembly.tool.line`).
- `PortColours.assembly` gives each nozzle a colour by its port's place in `AssemblyDiagram.ports` (the receiver's SIDE
  port, ordinal -1, matches any side nozzle); the colour does not change with the report. The tool reads a column's
  nozzles directly and other equipment through its watched report. Diagrams are cached by design and cleared with the
  watch or its next view.

**Other readers.** Before each control cadence applies edits, `ControlHosts.refreshColumnMembers` resolves the column's
C1 instrumentation from the stored report, saved member shapes and registry identities. [Wired control and FE power](wired-control-power.md).
`AssemblyNetwork`'s scheduled send to an open screen also fires `TutorialTriggers.assemblyShown`. [Tutorial](tutorial.md).

## Laws and constants

- `AssemblyWatch.RANGE`: the watch's reach round the player; `AssemblyMenu.REACH`: the screen's reach beyond the clicked
  structure's largest size.
- `AssemblyPresentation` runs on a `PresentationMailbox` bucket (the engine's schedule).
- Wire bounds in `AssemblyNetwork`: `MAX_SLOTS`, `MAX_PORTS`, `MAX_MEMBERS`, `MAX_TEXT`, `MAX_STREAMS`, `MAX_VALUES`;
  store bounds in `AssemblyStore` (class map).

## Formats

- **Store** `icheme_assemblies` in each level's data storage (`AssemblyStore.of`), state: `format` (`AssemblyStore.FORMAT`,
  [FORMATS.md](../FORMATS.md#saves)), `rebuild`, `entries` (each: `master`, `status`, the coded positions and codes,
  `changed`, the slots with their `parallel` origins, fewer than `ColumnComponents.MAX_UNITS`, the ports, footprint and
  members). A store of another format, or one that does not read, is dropped whole (`assembly_store status=STATE_DROPPED`)
  and marked for rebuilding.
- **Wire**, on `NetworkProtocol.VERSION` ([FORMATS.md](../FORMATS.md#wire-protocols)), all on the main thread; the
  `VIEW` codec leaves out the footprint, carries each slot's `parallel` origins after its detail (at most
  `MAX_UNITS` - 1) and the operation after the report:

| Payload | Direction | Fields |
|---|---|---|
| `icheme:assembly_state` | S→C | container id, view |
| `icheme:assembly_validate` | C→S | container id |
| `icheme:assembly_operation` | C→S | container id, action, values (Apply's set points) |
| `icheme:assembly_open_unit` | C→S | container id, member (varlong origin) |
| `icheme:assembly_watch` | C→S | bool on |
| `icheme:assembly_watched` | S→C | master (varlong), optional view |
| `icheme:assembly_datasheet` | S→C | BlockPos |

- **Menu opening buffer**: origin, column cell, anchor, reach (double), selected member (long).
- The column design itself, its member counts included, is a fact on the shell and on the process simulator
  ([equipment-designs.md](equipment-designs.md)).

## Tests and gates

| Rule | Owner | Test |
|---|---|---|
| An exchanger's report as its sides' lines; a redesign; a flowmeter's revert | `ExchangerLines`, `AssemblyService` (`validate`, `redesigned`, `metered`, `published`) | GameTest `AssemblyGameTests.anExchangersBypassValidatesAndARemovedPipeReverts` |
| When a report reverts | `AssemblyService` (`edited`, `placed`, `removed`, `chunkLoaded`) | GameTests `AssemblyGameTests` (`aRemovedPipeRevertsACompleteReport`, `aPipeAddedAtAnOpenEndKeepsAPartialReport`) |
| What a save keeps; a dropped store | `AssemblyStore`, `AssemblyService.controllerLoaded` | `AssemblyStoreTest`; `AssemblyGameTests` (`aReloadKeepsACompleteReportWithoutAWalk`, `aDroppedStoreRebuildsWithOneWalkPerColumn`) |
| GUI timing: replay on open, one validation or command per bucket | `AssemblyPresentation`, `AssemblyNetwork` | `AssemblyPresentationTest` |
| What Start needs; what stops a column | `ColumnStarts`, `AssemblyService` (`start`, `stop`, `dismantled`) | `ColumnOperationGameTests.aColumnRunsInItsPlantStopsAndLeaves` |
| The laying tool's watch | `AssemblyWatch`, `AssemblyToolClient`, `PipeLayingClient` | `AssemblyToolWatchTest` |
| Packets and bounds | `AssemblyNetwork` | `network/AssemblyNetworkCodecTest` |
| What a click opens | `AssemblyNetwork.open`/`target`, `EquipmentShellBlock.use`/`openOwn` | `AssemblyGameTests.aClickOnAMemberOpensItsColumnsGuiWithTheMemberSelected` |
| The drawing, the parts list, the words, the tool's port colours | `AssemblyBlock`, `AssemblyDiagram`, `AssemblyColumn`, `AssemblyScreen`, `PortColours.assembly`, `lang/en_us.json` (`gui.icheme.assembly.*`) | `AssemblyBlockTest`, `AssemblyDiagramTest`, `AssemblyColumnTest` |
| The screen's tours | `AssemblyScreen`, `AssemblyTour` | `AssemblyTourTest`; renders `tools/tutorial-renders/StageBRender.java` |

The unit tests run under `test`; the GameTests (namespace `icheme_fluid_test`, batches `assembly-*`) under
`runFluidGameTestServer`.

**Review world** (not a gate): `ColumnReviewWorld` (namespace `icheme_review`, structure
`data/icheme_review/structure/empty.nbt`), task `runReviewWorldServer` (game directory `run/review-world`, loads
`icheme` and `icheme_fluid_test`). It builds a 20-tray column with every part a design can ask for, validates it
complete, then puts it back to not validated, and leaves the world in `run/review-world/world` to copy into a client's
saves. The same run builds `HeatExchangerReviewWorld` (batch `review-heat`, [heat-units.md](heat-units.md)) and
`ColumnOperationReviewWorld` (batch `review-operation`, [column-run.md](column-run.md)).

## Traps

- **A report is state.** Losing the store loses only reports; the columns validate again (after a drop, once per column
  as its controller loads). Never put a fact in it.
- **Only a check walks.** A screen open, a watch, a chunk load or an edit never walks: a chunk load compares codes, an
  edit looks up one index.
- **Open unit and Datasheet replace the column's screen.** `openOwn` opens the member's fluid menu or sends its
  datasheet, closing the column's menu and its session first.
- Should two reports hold one unit (one validated while the other was reverted), a click opens the least master's
  screen (`holder`). `cellOf` looks for a loaded shell cell among the report's coded nozzles: a member whose cells are
  not loaded cannot be opened from the list.
- **An exchanger's lines are state twice over.** Its entry is in the store; the sides' `Lines` are in the fluid world and
  last only while the server runs. Every path that changes an exchanger's entry calls `published` or
  `ExchangerLines.publish`; a new path must too, or a side keeps working a valve its report no longer holds.
- **A vacuum column does not start** in the world (`Refusal.VACUUM`), though its assembly checks.

**Snapshot:** `c004a4c57`, 2026-11-16.
