# In-game process simulator: design plan

Batch [2026-11-02-process-simulator](README.md). Status: design for the owner's approval. Nothing here is built.
The drawings are in [renders/](renders/). Every number comes from the shipped code at `79b853e0`, run by the tools in
`tools/process-simulator/`.

## 1. Expected behaviour

The simulator is a paper plant. It reads nothing from the world and writes nothing to it. The player draws a flowsheet, sets
the feeds and the units, and presses Run. Each unit closes its mass and energy balance at steady state. A loop in the sheet
has no first unit, so the solver guesses one stream, the tear, and repeats the loop until that stream stops moving.

The sheets show a crude unit, solved by hand on the mod's own thermo and column code (`SampleFlowsheetProbe`):

| Unit | What it does | Heat or work |
|---|---|---|
| P-101 | Crude 2652.3 kmol/h, 572.09 t/h, 8.00 to 14.00 bar | 110.7 kW |
| E-101 | Crude 25.1 to 132.5 °C against residue 300.2 to 160.0 °C | 34.43 MW |
| H-101 | Crude to 365.0 °C, 71.1 mole % vapour | 111.13 MW |
| V-101 | 5.00 to 2.72 bar, 365.0 to 359.5 °C, 77.9 mole % vapour | isenthalpic |
| T-101 | 42 trays, 3 draws, 3 pumparounds, 1200 kmol/h steam | condenser -39.23 MW, pumparounds -41.93 MW |
| P-102 | Diesel 2.58 to 6.00 bar | 11.7 kW |
| E-102 | Residue 160.0 to 70.0 °C | -18.56 MW |

- Mass closes: 593.708 t/h in and out. The heat gap is 0.001 MW of 211 MW of duty.
- Without the recycle the heater needs 145.56 MW. The residue's preheat saves 34.43 MW, 24 %.
- It converged in 2 passes, because the heater holds its outlet: the column's feed does not move when the preheat moves. A
  heater set by duty passes the change on, and needs more passes. A material recycle needs more again.
- P-102 is the sheets' warning, and it is real: a side draw leaves at its bubble point, so a pump needs static head above it.

## 2. What exists today

- The column calculator: one block, four pages, solved on the server through `ProcessSolveServices`. Its presets
  (`V3ClientPresets`, `columnpreset` schema 2) hold the feed and the column together; six crude packages exist only to pair a
  crude with a column.
- The stream thermo (`FluidThermodynamics`: `flashTP`, `equilibriumPH`, `phaseStream`), the column (`V3ColumnCalculator`) and
  the unit closures (`FlowControl.Pump`, `FlowControl.Compressor`, `HeatLaw`, `HeatCurve`). All are Minecraft-free.
- The GUI kit, the Fluid Generator's page, the calculator's pages and the shipped heat drawings.
- There is no steady-state flowsheet solver. The world's engine solves a dynamic network, so it is not reusable here. Its
  closures are.

Can a client run this? Yes. Measured on a plain JVM with no Minecraft classes, one thread, as a client on a server would:

| Step | Time |
|---|---|
| Catalog from the mod's resources | 220 ms |
| Neural start model bound | 187 ms |
| Column, cold, learned start | 1.7 to 2.5 s |
| Column, warm, feed +1 %, +2 K | 0.25 to 0.31 s |
| TP flash, PH flash | 0.06 to 0.4 ms, 0.3 to 0.5 ms |
| The whole sample from a cold JVM | 3.3 to 5.1 s |
| Heap held after the run | 10 MB, in a 1 GB heap |

The two thermo stacks agree at the column's boundary. On the default preset the feed is 100.742 against 100.707 MW, and the
balance re-read in the stream thermo leaves 0.022 MW, 0.027 % of the duties. So one stream thermo with the column as a block
works. The flowsheet shows what is left as the heat gap.

## 3. Proposal

### 3.1 Solver

A new Minecraft-free package, `science/flowsheet`. Sequential modular at steady state:

1. Split the unit graph into strongly connected parts, and solve them in order.
2. In each loop, tear the fewest streams. Mark them T on the sheet.
3. Repeat with direct substitution, then bounded Wegstein. Tolerance 0.01 %, at most 30 passes, both in the flowsheet file.
4. Every pass after the first starts each column from its last state.

A stream carries T, P, amounts on the flowsheet's one axis, and solids. Pressure runs forward only: each unit sets its outlet,
and no pressure network is solved. A failed unit stops what lies after it. What was solved stays, and the status names the
first thing to fix. Leaving a data range never stops a run; it is a notice, as on the world's islands.

### 3.2 Units on day one

19 lit tiles (sheet 04). The other 14 are drawn unlit, with a hover: adsorber, cyclone, ejector, two turbines, five reactors,
four solid-fuel units. They have no process model in the mod. Reactors need a reaction model first.

| Tile | Model |
|---|---|
| Feed stream | T, P, flow and composition on the Fluid Generator's page, plus a name |
| Product stream | A reader; Copy puts it on the clipboard |
| Control valve | Isenthalpic to a set outlet or drop |
| Mixer | Adds amounts and enthalpy; takes the lowest inlet pressure, with a warning if they differ |
| Splitter | Same state; shares or flows; one outlet takes the rest |
| In-line filter | Takes the solids |
| Drums, tank, sphere | Flash; one outlet per phase; heat none, temperature or duty |
| Column | Section 3.3 |
| Heat exchanger, kettle | Outlet, duty or UA, with `HeatCurve`; the kettle has a gas and a liquid outlet |
| Air cooler, fired heaters | Outlet or duty, with a duty limit on a heater |
| Pump | Ideal work booked as heat, as in the world; refuses over 2 % vapour by volume |
| Compressors | Isothermal power booked as heat, as in the world; refuses over 1 % condensed mass |

### 3.3 The column as a unit

- Ports: feed, steam ×2, overhead, distillate, free water, bottoms, draw ×4. The condenser and pumparounds stay inside.
- The door is the calculator: its Column and Results pages, its drawing, its Equipment page with Save design (sheet 12). Flow
  and temperature become meters read from the stream. The feed tray stays an entry.
- V3 has no feed pressure, so the unit flashes the stream at its own enthalpy to the feed tray's pressure. That gives V3's
  feed temperature.
- V3's feed is hydrocarbons only. Water in the feed joins as a steam feed if it is vapour. Liquid water is refused with a
  message.
- The learned start declines a package with nitrogen. The unit takes the package without nitrogen when the feed holds none.
- `Solve alone` runs this unit on the stream it has now. Run is the only other way to solve it.

### 3.4 Where a run happens: on the client

Results are information only. No world state changes, so the server has nothing to trust and pays nothing.

- A run is one low-priority background thread. Stop goes through `V3SolveControl`, which reaches inside a column.
- `AGENTS.md` gets one clause: the simulator is a client tool outside the engine schedule, reading and writing no world state.
- A client on a dedicated server uses the bundled catalog, not the server's datapacks. When the block opens, the server sends
  its revision tokens. A mismatch is a notice on the status line. Nothing is blocked.
- Save design still goes to the server, which validates it with `ColumnDesign.of`. Server solves stay for column operation.

### 3.5 Screen

The whole window, `WindowScale` design height 480: 2 at 1920 by 1080, 3 at 2560 by 1440. Tabs Flowsheet, Results, Basis. The
palette lies below the canvas, with category keys over tiles. Doors use the 790 by 524 device frame (sheets 01 to 18).

- New kit parts: a wide text row, a Minecraft-free `FlowsheetCanvas` with hit tests, a builder class per door, each with gates.
- Double click a symbol or a pill to open its door, a name to rename it. Tags come from the kind; a used name is refused.
- Door entries take effect as typed, so doors have no Apply. A change greys what lies downstream. Undo and Redo sit in the
  footer.

### 3.6 Block

`process_simulator`, on the calculator's model at first. It only opens the screen. Its block entity keeps saved column designs,
so the constructor's Import (`IMPORT_REACH` 16) works as now. The column block needs no save compatibility, so an alias takes
`column_calculator_v3` to it.

### 3.7 Streams and columns standalone

- A stream template is a mixture, with T, P and flow if it has them. A column template holds no feed and no package. Its
  optional `design_feed_kmol_h` scales draws, steam and duties to the feed it meets (sheet 13).
- `columnpreset` goes to schema 3. A schema 2 file still loads: its feed flow becomes the design feed.
- The bundled column presets split the same way. The crude-specific packages go, and a crude becomes a stream template. All six
  share `icheme:crude_19` physics; they differ only in id and evidence text.

### 3.8 Copy and paste

One clipboard in client memory, shared by every screen. A Copy key on the reservoir's own page takes what the page shows, from
the last published snapshot: no packet, no solve (sheet 09). A feed's Composition tab has Paste, Paste with T and P, and Save as
(sheet 08). Paste maps by component id, and names what it could not place.

### 3.9 Files and backward compatibility

`.minecraft/IChemE/flowsheets/<name>.json`, written whole and renamed into place, with an autosave beside it. The same rule as
world saves: facts survive, state rebuilds.

- Facts: basis (package id, revision, component ids), units (id, stable type string, name, position, params), streams (id, name,
  from and to as stable port strings, spec, route). Unknown fields are kept and written back.
- State: results. A changed package revision drops them, and the player runs again.
- Fields are append-only, with a default equal to the old behaviour. Enums go by name. A renamed type or port gets an alias.
- A removed unit type becomes a hatched placeholder that keeps its ports, streams and settings. Everything upstream solves, the
  rest stays grey, and Replace offers units with the same ports (sheet 18).
- A removed component is parked: its amounts stay in the file, out of the solve, and the Basis tab and Results name them. An
  added component starts at zero everywhere. A removed package asks for another, and maps by component id.
- A newer format or a damaged file is refused and left untouched (sheet 17). Opening never rewrites a file; Save does.
- Corpus images under `src/test/resources/compat/`, read by a gate, at every format change.

### 3.10 New equipment joins the simulator

`FlowsheetCatalog` maps every `EquipmentShape.Kind`, `ConstructorColumn.Equipment` and `TopologyCompiler.Kind` to MODELLED or
DRAWING_ONLY through an exhaustive switch. A new constant fails the compile. `FlowsheetPaletteCoverageTest` checks each has a
tile, a port list and a lang line. `AGENTS.md` and the code guide get the rule.

## 4. Work packages

Each ends with the quick suite and a commit. W1 to W4 have no GUI and no Minecraft classes.

| WP | Content | Gate |
|---|---|---|
| W1 | `science/flowsheet`: model, graph, tears, Wegstein, stub units | Loops with known answers |
| W2 | Unit models of section 3.2 | Each closes mass and energy; pump and compressor match the world's closures |
| W3 | Column unit | Matches the calculator on the default preset to the last digit; the sample in 2 passes, gap under 0.05 % |
| W4 | Files, aliases, placeholders, parked components | Corpus, round trip, damaged file refused |
| W5 | Standalone templates: schema 3, split presets, packages removed | Old presets load; column and stream gates hold |
| W6 | Screen: canvas, palette, doors, Results, Basis | Column-builder gates; sheets rendered from shipped code |
| W7 | Client run: thread, Stop, progress, revision notice | Stop mid-column; nothing on the render thread |
| W8 | Block swap, Save design, clipboard, reservoir Copy | Constructor import; no packet on Copy |
| W9 | Coverage gate, `AGENTS.md` rule, code guide chapter, zh_cn, cleanup, final lane | `test slowGates` |

## 5. Decisions for the owner

| # | Decision | Recommended | Why |
|---|---|---|---|
| D1 | Layout A (door column) or B (card, sheet 02) | A | The run and selection stay in view; B gains 22 % of the width |
| D2 | Runs on the client only, or also on the server | Client only | Section 3.4 |
| D3 | Replace the calculator block, or keep it beside the new block | Replace | One way to design a column |
| D4 | Column template flows scale with the feed, or stay fixed | Scale | A template then fits any crude rate |
| D5 | Remove the crude-specific packages | Remove | They are the combined packages you asked to remove |
| D6 | Doors apply as typed, or keep an Apply key | As typed | Nothing crosses a network; Undo covers a mistake |
| D7 | Energy streams in the first version | No | Pumparounds have no stream, so preheat against one needs them; a later step |
| D8 | A plain heater/cooler tile with no equipment behind it | No | The tiles stay the mod's own equipment |
| D9 | Recipe for the block | Your call | The calculator has none today |
| D10 | Start on main now, or after column operation merges | After it merges | `claude/column-operation-5c3005` changes `ProcessSolveServices`, `V3ColumnCalculator`, `FlowsheetPainter`; W1 to W4 can start now |

## 6. What you did not mention

Taken as defaults unless you say otherwise. Items marked D are in section 5.

- **Basis**: a flowsheet picks one package, so a Basis tab is needed (sheet 16). Default: the world network's.
- **Mixers and splitters**: not in your list, and a flowsheet needs them. Added under Piping.
- **Recycles**: the solver picks tears and marks them. A forced tear is a later step.
- **Spec modes**: each unit needs its choice of target: outlet, duty, UA, rise (sheet 11).
- **Pressure**: forward only, set by units. No pressure-driven flows.
- **Water and solids**: water is on every stream's axis. Solids pass through units; the filter takes them; the column refuses them.
- **Failure and partial results**: a failed unit is red with a message, and the solved part stays.
- **Run progress and Stop** (sheet 06). Editing during a run is refused.
- **Editing basics**: undo, multi-select, copying units, autosave, an unsaved-changes prompt.
- **Names**: unique within a sheet.
- **Results extras**: Units and Log views (sheet 15), CSV export. Plots are a later step.
- **Multiplayer**: files are per player. A flowsheet can go through the clipboard as text (Open, From clipboard).
- **Data mismatch** with a server's datapacks: a notice only.
- **Link to the world**: Save design covers columns only. Other units have no build link in this batch.
- **Cold start**: the first run is cold, later runs warm. A Reset key is needed so a result can be reproduced.
- **Limits**: 200 units, 1 MiB a file, so a bad file cannot stall a client.
- **Languages**: en_us and zh_cn, as the mod ships.
- **Column limits that stay**: one feed, no side strippers, no condenser ports.
- **Later**: Adjust and Set blocks, case studies, sensitivity runs.

## 7. Risks

- The column's convergence on feeds far from the training crudes is untested in a loop. A run that fails keeps what it solved.
- A native-library column (`9c5eb01f`, not merged) needs its library on every client. The Java solver stays the client's path.
- A large sheet costs client CPU. It is one background thread, so the game stays smooth, and Stop always works.
