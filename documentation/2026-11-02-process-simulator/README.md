# 2026-11-02-process-simulator

Purpose: an in-game process simulator: a dedicated block, a drag-and-drop flowsheet with a palette below, feed streams from the Fluid Generator's page, every unit including the column, Run on the client, a Results tab, flowsheet files under `.minecraft/IChemE`, and a compatibility system (owner request 2026-11-02: "make some design drawings for me to approve before moving to implementation").

Status: In progress since 2026-11-02. The owner took decisions D1 to D21 on 2026-11-02. W1 to W4 are built on the branch; the rest follows column operation's merge (D10). Not merged (branch `claude/in-game-process-simulator-93dd9a`, base `79b853e0`). Index: [../INDEX.md](../INDEX.md).

## Documents

- [PROCESS_SIMULATOR_PLAN.md](PROCESS_SIMULATOR_PLAN.md): expected behaviour with the sample's and the loops' numbers, what exists, the proposal (solver, units, column unit, client run, screen, block, standalone templates, clipboard, files and compatibility, new equipment rule, blocks and connection points), work packages, the owner's decisions, what the request did not mention, risks.
- [SIZING_PLAN.md](SIZING_PLAN.md): the sizing stage: the criteria class by class with sources, Must and Should, the selection rule, the hand-off to the constructor, each tile's type, exchanger drops in the run, the ejector's law, the sample's result, work packages S1 to S5, decisions D13 to D21.
- [SIZING_SOURCES.md](../../research/2026-11-02-process-simulator/sizing/SIZING_SOURCES.md): the sourced values, 13 classes with provenance, in the main checkout's `research/`.
- [renders/](../../research/2026-11-02-process-simulator/renders/): the 30 design sheets in the main checkout's `research/`, drawn with the shipped kit in the game font, numbers from the real solve.

| Sheet | Shows |
|---|---|
| 01, 02 | Main screen, both doors; the selection panel on a unit, a feed and a stream |
| 03 | Dragging, connecting, renaming; right click to reconnect |
| 04 | The palette: one tile for each function, lit and unlit |
| 05 | Small windows: 1280 by 720, and the design minimum |
| 06 | A run in progress; a second player reading |
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
| 23 | Symbols: options A silhouette, B framed (chosen, D12), C line |
| 24 | Sizing, Units: E-101 and T-101 selected, a kept size that fails |
| 25 | Sizing, Lines |
| 26 | Sizing, Pressure: two paths |
| 27 | Sizing, Build, and the constructor's Import |
| 28 | Types: a heater by duty, a tank by pressure; an exchanger's door after sizing |

## Code areas

Built: `science/flowsheet` (`Flowsheet`, `FlowsheetFile`, `FlowsheetCompiler`, `FlowGraph`, `FlowsheetSolver`, `FlowThermo`, `FlowsheetEngine`, `UnitTypes`, `unit/`), `InletPhase.bulk`, the comment on `HeatSides.shellPassage` (D18). Gates: `FlowGraphTest`, `FlowsheetSolverTest`, `FlowsheetUnitsTest`, `FlowsheetColumnDoorTest`, `FlowsheetFileTest`, and `FlowsheetColumnTest` under `slowGates`; the corpus is `src/test/resources/compat/flowsheet/`. Still to come, as the plan names them: `client/gui/common` (new canvas and door builders), the calculator's block, menu, screen and `network/ColumnV3Network`, `V3ClientPresets` and the bundled presets and packages.

Tool folders: [tools/process-simulator/](../../tools/process-simulator/README.md) (the feasibility, sizing and ejector probes), `tools/heat-gui-renders/` (`SimulatorPrototypes.java`, `SimulatorIcons.java`, `SimulatorSizing.java`: the sheets).

## History

- 2026-11-02: sheets and plan written. `ClientRunProbe` showed a plain JVM runs the column and flashes with no Minecraft classes. `SampleFlowsheetProbe` solved the sample with its recycle by hand: 2 passes, mass closed, heat gap 0.001 MW of 211 MW. Waiting for the owner's choices D1 to D10.
- 2026-11-02: the owner's note: a multi-machine is one block, its configuration adds connection points, and loops must reach steady state. The sample became the owner's example: furnace 78.37 MW against 145.56 MW with no preheat, 2 passes, heat gap 0.001 MW of 187 MW. `LoopProbe` closed a furnace on fixed duty (7 passes, plain 12) and 15 and 30 % material recycles (4 and 5, plain 5 and 8), and showed a recycle with no way out. Plan section 3.11, sheets 19 to 22, decision D11. The owner's note answers D7.
- 2026-11-02: the owner's answers. D1: layout A, with the right panel only while something is selected. D2: the run on the client, its state persisted on the server. D7: a pair takes any stream. D9: no recipe. Others as recommended. Left click selects, a stream showing settings or properties by the situation; right click lifts a stream's end to reconnect it. Sheets 01 to 03 and 06 redrawn, plan sections 3.4 to 3.6 and 5.
- 2026-11-02: symbols redrawn as vector shapes in three options (`SimulatorIcons`, sheets 23), open as D12.
- 2026-11-02: the owner asked for an equipment and line sizing stage after the main flowsheet, with research and criteria now. One research agent collected 12 classes into `SIZING_SOURCES.md`. It read no standard in full, so its quotes of them are leads, and it routed three public PDFs through a third-party text reader, marked in the note; the permission system stopped a fourth. `SizingProbe` sized the sample on the shipped laws: 16 units, 31 lines, every Must met, one Should missed. `SIZING_PLAN.md`, sheets 24 to 27.
- 2026-11-02: the owner's note: same-function equipment is one tile with its type proposed at sizing; exchanger design goes by duty and area with a flow-based drop; an ejector model comes late in this work. Five pairs became one tile each, 15 lit. Exchangers and air coolers lost their speed rows, so every limit of the sample is met. The shipped shell law is 10 times under Kern at the 0.4 D its comment names and equals Kern at about 0.93 D, so it prices a wide-baffle shell (D18). A jet law for the ejector failed against El-Dessouky's correlation, which W10 takes with a molar-mass rule. `EjectorProbe` added, sheet 28, `SIZING_PLAN.md` sections 3.10 to 3.12, decisions D18 to D21.
- 2026-11-02: the owner approved D12 to D21 as recommended: framed symbols, the sizing defaults, no speed rows, the shipped shell law, sized drops in the run, five merged pairs, the ejector last. `HeatSides.shellPassage`'s comment corrected (D18). W1 started, as D10 allows.
- 2026-11-02: W1 (`8e6a0401`): `science/flowsheet`, a graph of calculations, parts in order, fewest tears, bounded Wegstein with a fallback to a plain pass, a slope-1 stop, stub units. Gates `FlowGraphTest` (10), `FlowsheetSolverTest` (15).
- 2026-11-02: W2 (`1637d8a3`): the unit models of section 3.2 on the world's flash, `FlowsheetUnitsTest` (38), quick suite 1866, 0 failures. A stream searches along the TP flash, because the PH flash has a false root on wet oil with volatile components and misses its enthalpy under the melting line. Three engine findings in `tools/process-simulator/README.md`; the engine is unchanged.
- 2026-11-02: W3 (`fc3b4ad2`): the column as one block. Flashed to the authored 2.5 bar the sample's feed left a gap of 0.198 %, because the real tray stands at about 2.7 bar; on the solved tray pressure the gap is under the gate's 0.05 %, and a stream at that pressure matches the calculator to the last digit. The sample closes in 2 passes, the 15 % recycle closes, and the furnace on a fixed duty closes from a first estimate. A gate caught the kettle's side taking the column's sign, not its negative, before the commit. `FlowsheetColumnTest` takes about 90 s, so it runs under `slowGates`. Quick suite 1871, 0 failures.
- 2026-11-02: W4 (`859f4882`): `FlowsheetFile`, format 1, with `format-1.json` in the corpus: a later version's field on every object, a removed type, a parked component and solid. `FlowsheetFileTest` (19): every image writes back all it holds, a damaged field is refused by name, a cut file and bad bytes are refused, a refused file stays as it is, a save lands whole, a removed row leaves open pills and moves no other. The image found a bug on its first run: the compiler's live ports compared whole ends, so an end holding a later version's field fell off its port. A stream gains `route`, so format 1 is whole for the screen. Quick suite 1890, 0 failures; `FlowsheetColumnTest` 9 of 9.

## Open items

- The simplifications table of `SIZING_PLAN.md` section 6: confirm with the owner before S1.
- The three engine findings in `tools/process-simulator/README.md`: for the owner. The false PH root has a candidate fix that moves world references.
- A fresh sheet with a furnace on a fixed duty needs a first estimate on a line, or a first run with the furnace on its outlet. The alternative is a door start temperature, with a retry in the block.
- W6: English lines for the keys `science/flowsheet` names under `gui.icheme.flowsheet.`, none yet (search `Note.` and `FlowsheetFailure(`); the door's Results pages need the block's `V3ColumnResult`, which a `Report` does not keep; a save name's guard (separators, reserved names).
- W7, W8: results are state with no format yet: a file holds none. The autosave beside a file is the client's; `FlowsheetFile.save` serves it.
- W9: code guide chapter, the `AGENTS.md` clause. W10: the Motive steam port; the block has none until then.
- Order against `claude/column-operation-5c3005`, `claude/valve-hold` and the native column default (`9c5eb01f`), none merged.
- Not measured: option B of D11, a reboiler stream, a column with nitrogen on the plain start, a run's peak heap; the sizing rules on any sheet but the sample; the ejector law on a real vacuum column; a compressor's or a drum's type proposal.
