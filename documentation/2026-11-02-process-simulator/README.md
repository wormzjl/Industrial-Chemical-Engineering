# 2026-11-02-process-simulator

Purpose: an in-game process simulator: a dedicated block, a drag-and-drop flowsheet with a palette below, feed streams from the Fluid Generator's page, every unit including the column, Run on the client, a Results tab, flowsheet files under `.minecraft/IChemE`, and a compatibility system (owner request 2026-11-02: "make some design drawings for me to approve before moving to implementation").

Status: Planned. Design sheets and plan await the owner's approval; no game code changed (branch `claude/in-game-process-simulator-93dd9a`, base `79b853e0`). Index: [../INDEX.md](../INDEX.md).

## Documents

- [PROCESS_SIMULATOR_PLAN.md](PROCESS_SIMULATOR_PLAN.md): expected behaviour with the sample's and the loops' numbers, what exists, the proposal (solver, units, column unit, client run, screen, block, standalone templates, clipboard, files and compatibility, new equipment rule, blocks and connection points), work packages, the owner's decisions, what the request did not mention, risks.
- [renders/](renders/): the 22 design sheets, drawn with the shipped kit in the game font, numbers from the real solve.

| Sheet | Shows |
|---|---|
| 01, 02 | Main screen, layouts A and B, both doors |
| 03 | Dragging, connecting, renaming |
| 04 | The palette, every category, lit and unlit |
| 05 | Small windows: 1280 by 720, and the design minimum |
| 06 | A run in progress |
| 07, 08, 09, 10 | Feed stream, its Composition tab, reservoir Copy, product stream |
| 11, 12 | Unit doors; the column's door, with its Equipment page and connection points |
| 13 | Stream templates and column templates, standalone |
| 14, 15 | Results: Streams, Units, Log |
| 16 | Basis: package and components |
| 17, 18 | Open, repair after an update, a removed unit on the sheet |
| 19 | One block: connection points by the door's settings |
| 20 | Pumparound exchangers, options A and B |
| 21 | The same rule for other blocks |
| 22 | Loops: a material recycle, a furnace on duty, no steady state |

## Code areas

None changed yet. The plan names `science/flowsheet` (new), `client/gui/common` (new canvas and door builders), the calculator's block, menu, screen and `network/ColumnV3Network`, `V3ClientPresets` and the bundled presets and packages.

Tool folders: [tools/process-simulator/](../../tools/process-simulator/README.md) (the feasibility probes), `tools/heat-gui-renders/SimulatorPrototypes.java` (the sheets).

## History

- 2026-11-02: sheets and plan written. `ClientRunProbe` showed a plain JVM runs the column and flashes with no Minecraft classes. `SampleFlowsheetProbe` solved the sample with its recycle by hand: 2 passes, mass closed, heat gap 0.001 MW of 211 MW. Waiting for the owner's choices D1 to D10.
- 2026-11-02: the owner's note: a multi-machine is one block, its configuration adds connection points, and loops must reach steady state. The sample became the owner's example: furnace 78.37 MW against 145.56 MW with no preheat, 2 passes, heat gap 0.001 MW of 187 MW. `LoopProbe` closed a furnace on fixed duty (7 passes, plain 12) and 15 and 30 % material recycles (4 and 5, plain 5 and 8), and showed a recycle with no way out. Plan section 3.11, sheets 19 to 22, decision D11. The owner's note answers D7.

## Open items

- The owner's decisions D1 to D6 and D8 to D11 (plan section 5) and the defaults of plan section 6.
- Order against `claude/column-operation-5c3005`, `claude/valve-hold` and the native column default (`9c5eb01f`), none merged.
- Not measured: option B of D11, a reboiler stream, a column with nitrogen on the plain start, a run's peak heap.
