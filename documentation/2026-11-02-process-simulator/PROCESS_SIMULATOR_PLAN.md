# In-game process simulator: design plan

Batch [2026-11-02-process-simulator](README.md). Status: the owner took decisions D1 to D22 on 2026-11-02, and D23 on 2026-11-03 (section 5). W1 to W5 are built on the branch, not merged; column operation is on main, so the rest may follow (D10).
The drawings are in [research/2026-11-02-process-simulator/renders/](../../research/2026-11-02-process-simulator/renders/), in the main checkout. Every number comes from the shipped code at `79b853e0`, run by the tools in
`tools/process-simulator/`.

## 1. Expected behaviour

The simulator is a paper plant. Its calculations read nothing from the world and write nothing to it. The player draws a
flowsheet, sets the feeds and the units, and presses Run. Each unit closes its mass and energy balance at steady state. A loop in the sheet
has no first unit, so the solver guesses one stream, the tear, and repeats the loop until that stream stops moving.

A multi-machine is one block. A column with its condenser, pumparound coolers and reboiler is one tile, and its settings put
connection points on the main flowsheet: cooling water through the condenser, the crude through a pumparound cooler, steam
through the reboiler (owner's note, 2026-11-02).

The sheets show the owner's example, solved by hand on the mod's own thermo and column code (`SampleFlowsheetProbe`): the
pumparounds heat the crude, the residue next, and a furnace sets the final temperature.

| Unit | What it does | Heat or work |
|---|---|---|
| P-101 | Crude 2652.3 kmol/h, 572.09 t/h, 8.00 to 14.00 bar | 110.7 kW |
| T-101, PA 1 to 3 | Crude through the coolers' tube sides, 25.1 to 67.9, 122.0, 153.3 °C | 12.84, 17.89, 11.20 MW |
| E-101 | Crude 153.3 to 218.4 °C against residue 300.2 to 200.0 °C | 25.26 MW |
| H-101 | Crude to 365.0 °C, 71.1 mole % vapour | 78.37 MW |
| V-101 | 5.00 to 2.72 bar, 365.0 to 359.5 °C, 77.9 mole % vapour | isenthalpic |
| T-101 | 42 trays, 3 draws, 1200 kmol/h steam; cooling water 190000 kmol/h, 30.0 to 39.9 °C | condenser 39.23 MW into the water |
| P-102 | Diesel 2.58 to 6.00 bar | 11.7 kW |
| E-102 | Residue 200.0 to 70.0 °C | -27.73 MW |

- Mass closes: 4016.612 t/h in and out, 593.708 t/h of it process. The heat gap is 0.001 MW of 187 MW of duty.
- The furnace needs 145.56 MW with no preheat, 111.13 MW with the residue's preheat alone, and 78.37 MW here. 67.19 MW is
  recovered, 46 %.
- Every crude outlet stays under its hot side: 72.1, 91.4 and 120.5 K under the draw trays.
- P-102 is the sheets' warning, and it is real: a side draw leaves at its bubble point, so a pump needs static head above it.
  The sheet knows no heights, so the run warns; the sizing stage clears it with the draw nozzle's height.

Loops reach steady state on the real column and flash code (`LoopProbe`, one warm column a pass):

| Loop | What the physics does | Plain passes | With Wegstein |
|---|---|---|---|
| Furnace holds 365.0 °C (the sample) | The feed cannot move, so the column does not answer. The preheat only moves the furnace's duty | 2, 3.6 s from cold | Not needed |
| Furnace on a fixed duty, 8 MW short | Feed 359.5 to 336.4 °C, bottoms 300.2 to 281.0 °C, E-101 25.26 to 21.25 MW. Less bottoms heat means less preheat: loop gain about 0.5 | 12, 8.1 s | 7, 4.7 s torn at the bottoms; 5, 3.5 s on the feed temperature |
| 15 % of the residue back to the crude | Column feed 2789 kmol/h, furnace 84.50 MW | 5, 2.8 s | 4, 2.3 s |
| 30 % of the residue back to the crude | Column feed 2979 kmol/h, furnace 92.63 MW | 8, 4.2 s | 5, 2.7 s |
| All of the residue back | No steady state exists: the heaviest cut gains 175.2 kmol/h every pass, with nowhere to go | 30, not closed, 26.3 s | The first trial step fails the column |

The hold on the furnace is why the sample closes at once. A furnace that holds its outlet decouples the column from everything
upstream, as it does in a real unit.

## 2. What exists today

- The column calculator: one block, four pages, solved on the server through `ProcessSolveServices`. Its presets
  (`V3ClientPresets`, `columnpreset` schema 2) hold the feed and the column together; six crude packages exist only to pair a
  crude with a column.
- The stream thermo (`FluidThermodynamics`: `flashTP`, `equilibriumPH`, `phaseStream`), the column (`V3ColumnCalculator`) and
  the unit closures (`FlowControl.Pump`, `FlowControl.Compressor`, `HeatLaw`, `HeatCurve`). All are Minecraft-free.
- The GUI kit, the Fluid Generator's page, the calculator's pages and the shipped heat drawings.
- There is no steady-state flowsheet solver. The world's engine solves a dynamic network, so it is not reusable here. Its
  closures are.
- The multi-machine framework (`science/assembly`) already names what a block needs on its outside. `ColumnBlueprint` holds the
  exchanger condenser's and each PA cooler's tube sides, the kettle's tube side, the steam valves and the ejector's motive side.
  `ColumnDesign.of` derives them from the column's settings, and the constructor builds from them.
- A pumparound in the column is a heat sink on its trays with no circulating stream (`V3PumparoundSpec`: return tray, draw tray,
  duty, split). The owner chose that model earlier. The tray's temperature and liquid are readable (`V3ColumnInspection.Node`).

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

1. Build the graph from calculations, not tiles. A block gives one node for its own solve and one for each heat side whose
   duty is known, so the crude's pass through a pumparound cooler reads the door's duty and is no loop. An exchanger set by one
   side's outlet splits the same way.
2. Split the graph into strongly connected parts, and solve them in order.
3. In each loop, tear the fewest streams. Mark them T on the sheet.
4. Repeat with plain passes, then bounded Wegstein: one factor per tear, from the variable that moved most in its tolerances, in [-5, 0].
   Tolerance 0.01 % on flows and pressures, 0.01 K on temperature, at most 30 passes, all in the flowsheet file.
5. Every pass after the first starts each column from its last state.
6. A trial step that fails a column falls back to a plain pass. Measured: on a loop of slope 1, the bounded step failed the column
   on pass 3, and plain passes went on to pass 30.
7. A tear amount that rises by the same step on three passes has slope 1 and cannot close. The run stops there and says so.

The status says Steady state only when every tear has stopped moving and mass closes. Otherwise it says Not steady, the last
pass stays on show, and the Log names the tear and what grew (sheet 22).

A tear starts from no flow, or from an estimate: the last steady run's stream, else a line's own spec. A column under a furnace on
a fixed duty needs one: with no bottoms yet there is no preheat, the feed comes in far colder than at steady state, and the column
does not solve. A fresh sheet with neither fails at the block, and the Log says the loop needs a first estimate.

A stream carries T, P, amounts on the flowsheet's one axis, and solids. Pressure runs forward only: each unit sets its outlet,
and no pressure network is solved. A stream's state is what the world's TP flash says at the
temperature that holds its enthalpy; the engine's PH answers only on a true step of H(T), or where the TP flash refuses a trial
(flash findings, `tools/process-simulator/README.md`). A failed unit stops what lies after it. What was solved stays, and the status names the
first thing to fix. Leaving a data range never stops a run; it is a notice, as on the world's islands.

### 3.2 Units on day one

15 lit tiles (sheet 04), one for each function. The other 13 are drawn unlit, with a hover: adsorber, cyclone, ejector, steam
turbine, five reactors, four solid-fuel units. They have no process model in the mod. Reactors need a reaction model first. The
ejector lights with W10. A tile's ports follow its settings (section 3.11).

Equipment with the same function is one tile: Drum, Storage tank, Fired heater, Compressor, Steam turbine. A tile runs with no
type, because the world's law is the same for its types. The sizing stage proposes the type ([SIZING_PLAN.md](SIZING_PLAN.md),
section 3.10), and the symbol on the sheet follows it.

| Tile | Model |
|---|---|
| Feed stream | T, P, flow and composition on the Fluid Generator's page, plus a name |
| Product stream | A reader; Copy puts it on the clipboard |
| Control valve | Isenthalpic to a set outlet or drop |
| Mixer | Adds amounts and enthalpy; takes the lowest inlet pressure, with a warning if they differ |
| Splitter | Same state; shares or flows; one outlet takes the rest |
| In-line filter | Takes the solids |
| Drum, storage tank | Flash; one outlet per phase; heat none, temperature or duty. A storage tile holds its liquid at the higher of ambient and its bubble pressure |
| Column | Section 3.3 |
| Heat exchanger, kettle | Outlet, duty or area, with `HeatCurve`; the kettle has a gas and a liquid outlet |
| Air cooler, fired heater | Outlet or duty, with a duty limit on a heater |
| Pump | Ideal work booked as heat, as in the world; refuses over 2 % vapour by volume |
| Compressor | Isothermal power booked as heat, as in the world; refuses over 1 % condensed mass |

A heat unit's drops are typed until it has a size. From then on the run takes them from its flow, by the world's law
([SIZING_PLAN.md](SIZING_PLAN.md), section 3.11).

### 3.3 The column as a unit

- One block. The condenser, the pumparound coolers, the reboiler and the ejector stay inside it, and their outer sides are ports
  (section 3.11). Column limits stay: 4 draws, 2 steam feeds, 4 pumparounds.
- The door is the calculator: its Column and Results pages, its drawing, its Equipment page with Save design (sheet 12). Flow
  and temperature become meters read from the stream. The feed tray stays an entry. The Equipment page's glass lists the
  connection points and what each carries.
- V3 has no feed pressure, so the unit flashes the stream at its own enthalpy to the feed tray's pressure. That gives V3's
  feed temperature. Tray hydraulics march that pressure from the column's traffic, so only a solve can say it. The unit starts at
  the stream's own pressure and goes round, at most 3 times, while the answer moves the feed by over 0.01 K. A stream at its tray's
  pressure solves once and matches the calculator to the last digit.
- V3's feed is hydrocarbons only. Water in the feed joins as a steam feed if it is vapour. Liquid water is refused with a
  message.
- The learned start declines a package with nitrogen. The unit takes the door's package, the one without nitrogen, while it exists and holds the feed, else the sheet's.
- `Solve alone` runs this unit on the stream it has now. Run is the only other way to solve it.

### 3.4 Where a run happens and where the sheet lives

A run happens on the client. The flowsheet and its last results stay on the block, on the server (D2), the way the column
calculator's input and result do today. So a sheet survives a restart, and every player who opens the block sees the same one.
The server stores what it is sent. It never solves it, and nothing in it reaches the engine.

- A run is one low-priority background thread. Stop goes through `V3SolveControl`, which reaches inside a column.
- Opening the block replays the stored sheet and results at once: a read, with no solve. Closing ends it. The sheet is sent to
  no one who did not ask.
- The client sends the sheet when the player presses Save, when a run ends and when the screen closes, not on each edit. It
  goes as one gzip body, at most 1 MiB unpacked, in parts if a packet needs them.
- The first player to open the block edits it. Another reads the last saved sheet, with tiles and keys off, and may Save as to a
  file (sheet 06). The block frees the sheet when the editor closes the screen or leaves.
- The server checks the upload's size, its format number and that it parses. A bad upload is refused with a message, and the
  stored sheet stands.
- Results are state. A changed package revision drops them, and the next run rebuilds them. A column's warm start is client
  memory only, so the first run after opening a sheet starts cold.
- The sheet sits in the block entity as a byte array, in no update tag. A damaged stored sheet never refuses a world: the block
  opens empty, says so, and keeps the bytes for a later version.
- `AGENTS.md` gets one clause: the simulator's run is a client tool outside the engine schedule, and reads and writes no process
  state.
- A client on a dedicated server uses the bundled catalog, not the server's datapacks. When the block opens, the server sends
  its revision tokens. A mismatch is a notice on the status line. Nothing is blocked.
- Save design still goes to the server, which validates it with `ColumnDesign.of`. Server solves stay for column operation.

### 3.5 Screen

The whole window, `WindowScale` design height 480: 2 at 1920 by 1080, 3 at 2560 by 1440. Tabs Flowsheet, Results, Sizing, Basis.
Sizing is off, with a hover saying why, until the sheet reaches a steady state. The palette lies below the canvas, with category
keys over tiles. Doors use the 790 by 524 device frame.

With nothing selected the canvas takes the whole width, and the run's result reads on the status display (sheet 01). A selection
opens a panel over the right end of the glass (D1, sheet 02). The sheet under it does not move or re-zoom; the kit's rule is that
a widget never moves. A click on empty canvas closes it.

| Gesture | On a unit | On a stream |
|---|---|---|
| Left click | Selects; the panel shows its readings | Selects; a feed's panel shows its settings as entries, any other stream's its properties as meters |
| Double click | Opens its door | Opens its door |
| Double click on a name | Renames in place | Renames in place |
| Drag | Moves it | Moves a route's segment |
| Right click | Nothing | Lifts the end nearer the click, to reconnect it |

Reconnecting (sheet 03): the lifted end follows the cursor with no key held. The stream keeps its name and every setting. Its
old port shows free, and the ports that can take that end are ringed. A left click on one lands it. A left click on empty canvas
ends it in a new pill. Right click or Esc puts it back. A landed end greys what lies downstream, and Undo restores it.

- New kit parts: a wide text row, a Minecraft-free `FlowsheetCanvas` with hit tests, a builder class per door, each with gates.
- Tags come from the kind; a used name is refused.
- Door entries take effect as typed, so doors have no Apply (D6). A change greys what lies downstream. Undo and Redo sit in the
  footer.
- Symbols are vector shapes in one Minecraft-free class, true to the mod's equipment (sheets 23). A tool bakes each to a mask pair,
  body and marks, in a texture atlas, tinted by the unit's state, so edges stay smooth at every window scale and zoom. A unit
  shows its tile's symbol until the sizing stage gives it a type.

### 3.6 Block

`process_simulator` replaces the column calculator block (D3), with no recipe (D9), as the calculator has none. Its block entity
holds the sheet and its results (section 3.4) and the saved designs, so the constructor's Import (`IMPORT_REACH` 16) works as now.
The column block needs no save compatibility, so an alias takes `column_calculator_v3` to it.

### 3.7 Streams and columns standalone

- A stream template is a mixture, with T, P and flow if it has them. A column template holds no feed and no package. Its
  optional `design_feed_kmol_h` scales draws, steam and duties to the feed it meets (sheet 13).
- `columnpreset` goes to schema 3. A schema 2 file still loads: its feed flow becomes the design feed.
- The bundled column presets split the same way. The crude-specific packages go, and a crude becomes a stream template. All six
  share `icheme:crude_19` physics; they differ only in id and evidence text.
- As built (W5): `V3ColumnTemplate` holds the column and the one scaling rule, and `V3ClientPresets` reads schema 2 and 3 and writes 3. A
  stream template names no package: `V3EditorCatalog.sourceFor` seats it on the fewest components that hold it. The five crude assays moved onto
  `icheme:tjl20_methane`, each with its crude's evidence lines in its `provenance`, so the column's audit list now shows the shared package's
  lines alone. The five crude column presets in the material data went too: no screen read them, only gates, and those now run on the two templates.

### 3.8 Copy and paste

One clipboard in client memory, shared by every screen. A Copy key on the reservoir's own page takes what the page shows, from
the last published snapshot: no packet, no solve (sheet 09). A feed's Composition tab has Paste, Paste with T and P, and Save as
(sheet 08). Paste maps by component id, and names what it could not place.

### 3.9 Files and backward compatibility

The block holds the working sheet. Files are the player's own library: Save as and Open use
`.minecraft/IChemE/flowsheets/<name>.json`, written whole and renamed into place, with an autosave beside it. One format serves
both, under the same rule as world saves: facts survive, state rebuilds.

- Facts: basis (package id, revision, component ids), units (id, stable type string, name, position, params), streams (id, name,
  from and to as stable port strings, spec, route). Unknown fields are kept and written back, at every level. `FlowsheetFile`
  is the format, number 1.
- State: results. A changed package revision drops them, and the player runs again.
- Fields are append-only, with a default equal to the old behaviour. Enums go by name. A renamed type or port gets an alias.
- A port string names a block's row by the row's own id, never its position. So removing PA 1 does not move PA 2's wires.
- A removed unit type becomes a hatched placeholder that keeps its ports, streams and settings. Everything upstream solves, the
  rest stays grey, and Replace offers units with the same ports (sheet 18).
- A removed component is parked: its amounts stay in the file, out of the solve, and the Basis tab and Results name them. An
  added component starts at zero everywhere. A removed solid is parked the same way, and a package's alias of a component is
  followed. A removed package asks for another, and maps by component id: a stream flashes on the world's own package, so a sheet
  on any other asks the same. A door's removed package falls to the sheet's.
- A newer format or a damaged file is refused and left untouched (sheet 17). Opening never rewrites a file; Save as does.
- Corpus images under `src/test/resources/compat/`, read by a gate, at every format change.

### 3.10 New equipment joins the simulator

`FlowsheetCatalog` maps every `EquipmentShape.Kind`, `ConstructorColumn.Equipment` and `TopologyCompiler.Kind` to MODELLED or
DRAWING_ONLY through an exhaustive switch. A new constant fails the compile. `FlowsheetPaletteCoverageTest` checks each has a
tile, a port rule and a lang line; one tile may hold several kinds. `AGENTS.md` and the code guide get the rule.

### 3.11 Blocks and connection points

A multi-machine is one tile, one door, one block (sheets 19, 21). Its ports are a pure function of its door's settings:

| Door setting | Connection points | What goes through them |
|---|---|---|
| Always | Feed, Overhead, Distillate, Free water, Bottoms | The column's own streams |
| Condenser: Exchanger | Condenser in, out | Cooling water. Air cooler adds none |
| Reboiler duty above zero | Reboiler in, out | Steam through the kettle's tube side |
| A pumparound row | PA in, out | The cold side of its cooler, under option A of D11. Any stream may use it |
| A steam row | Steam | Stripping steam |
| A draw row | Draw | A side product |
| Top below ambient pressure | Motive steam | The ejector's motive side |
| Heater coils in series, or apart | Process in, out; or Convection and Radiant in, out | One pair, or two |
| Exchanger | Tube in, out; Shell in, out | Its bypass or ratio lines stay inside |

- The column's list comes from `ColumnDesign` and `ColumnBlueprint`, so a port on the sheet is the nozzle the constructor builds.
- There is no energy stream type. A heat link is an ordinary stream through a port pair, and that stream gains or gives the
  block's duty at the door's drop. The pair does not care what the stream is (D7): crude, boiler feed water, another column's
  bottoms: `h_out = h_in + Q / F`, then a PH flash. The sample's condenser puts 39.23 MW into 3423 t/h of
  water, 30.0 to 39.9 °C. Water is on every stream's axis, so nothing else is needed.
- A wired steam port replaces the door's steam entries, which turn to meters. It must be water, because `V3SteamFeedSpec` holds
  water alone. A column inlet under its tray's pressure is a warning: no pressure network pushes it in.
- Each pair is checked after the column solves. A pumparound's cold stream stays under the draw tray, the condenser's under the
  condenser outlet, the reboiler's steam condenses above the bottoms.
- The column's block shows what it holds (D23): one process model, drawn as a solid frame, 202 by 336 at its 14 slices, with the
  name on a plate on its top edge. The frame is the box: `unit.x` and `unit.y` stay its corner, so a file changes nothing, and
  ports, hit tests and the selection ring follow it. There is one part per slot of `ColumnBlueprint`, so a drawn part is a part the
  constructor builds, and a hover names it with the assembly screen's words. A click on a part selects the block.
- Ports sit on the frame in tray order: pairs and inlets left, products right, overhead on top, bottoms below. Condenser water
  keeps its left pair and passes over the tower head. On the right `water` stands above `distillate`, because of the boot. At
  least 14 px stand between ports, and port ids do not change. A port's hover gives its full name and what happens if it stays
  free.
- Inside the frame: tower, then the overhead train at the top right as the assembly drawing has it: head, ejector where the top
  is below ambient, condenser (coil or air cooler), receiver and boot, one reflux pump, the tee, a valve on each leg. Off-gas and
  water leave through a valve each. Each pumparound runs draw, pump, cooler, return, straight on its true trays, and its cold
  stream passes through the cooler back to the frame. A free pair still shows its cooler, because a utility takes the heat then.
  The kettle stands bottom left with its pair left. Every line that crosses the frame has a valve, except the pairs.
- Near trays move a line off its true tray: a pumparound stays whole, in tray order, and elbows in a channel beside the tower take
  its two lines to their trays. Two loops on one wall that interleave cannot be drawn without a crossing, so the order is what
  the gates hold, over the door's settings.
- Hot lines are amber and cold lines teal on a solved block; product lines stay neutral. Any other tone takes them all, so a
  greyed or failed block reads as one. Below label scale the frame, tower, coolers, drum, pumps and pipes stay; names, notes,
  arrows, valves, motors, coil marks and the tee go.
- Adding a row adds its ports, free. Removing a row removes them: its streams stay, each ending in an open pill, and the status
  says so. Undo restores it.


## 4. Work packages

Each ends with the tests it can affect and a commit (`AGENTS.md`, "No repeated tests"). The whole suites run once, in the final
lane after W10, when the owner asks for the merge. W1 to W4 have no GUI and no Minecraft classes. W1 to W4 can start now; the rest
follow the merge of column operation (D10).

| WP | Content | Gate |
|---|---|---|
| W1 | `science/flowsheet`: model, graph of heat-side nodes, tears, Wegstein, fallback, slope-1 stop, stub units | Loops with known answers; one with no steady state says Not steady |
| W2 | Unit models of section 3.2 | Each closes mass and energy; pump and compressor match the world's closures |
| W3 | Column block: ports from `ColumnDesign`, heat sides, steam port | Matches the calculator on the default preset to the last digit; the sample in 2 passes, gap under 0.05 %; the furnace on duty and the 15 % recycle close |
| W4 | Files, aliases, placeholders, parked components, port ids by row | Corpus, round trip, damaged file refused, a removed row leaves open pills |
| W5 | Standalone templates: schema 3, split presets, packages removed | Old presets load; column and stream gates hold |
| W6 | Screen: canvas with block ports and hovers, selection panel, reconnect, baked symbols, palette, doors, Results, Basis | Column-builder gates; sheets rendered from shipped code |
| W7 | Client run: thread, Stop, progress, revision notice | Stop mid-column; nothing on the render thread |
| W8 | Block swap, the sheet on the block (upload, replay, read only for a second player), Save design, clipboard, reservoir Copy | Constructor import; no packet on Copy; a sheet survives a reload; a bad upload is refused; no solve on the server |
| W9 | Coverage gate, `AGENTS.md` rule, code guide chapter, zh_cn, cleanup | The coverage gate |
| S1 to S5 | The sizing stage, after the main flowsheet: [SIZING_PLAN.md](SIZING_PLAN.md) | There |
| W10 | Ejector, last: the law in `science/flowsheet`, the tile lit, the block's Motive steam port, the steam's heat on the condenser, its sizing ([SIZING_PLAN.md](SIZING_PLAN.md), section 3.12) | Reproduces the correlation's points; closes mass and energy; a wired motive short of the need is red |

## 5. Decisions

The owner's answers of 2026-11-02.

| # | Decision | Answer |
|---|---|---|
| D1 | Screen layout | A, with the right panel only while something is selected (section 3.5) |
| D2 | Where a run happens | On the client, with its state persisted on the server (section 3.4) |
| D3 | The calculator block | Replaced |
| D4 | Column template flows | Scale with the feed |
| D5 | Crude-specific packages | Removed |
| D6 | Doors | Apply as typed |
| D7 | What goes through a pair port | Any stream the player likes, not only the feed. No energy stream type (section 3.11) |
| D8 | A plain heater or cooler tile | None |
| D9 | Recipe for the block | None |
| D10 | When it starts | After column operation merges; W1 to W4 can start now |
| D11 | Pumparound exchangers | Option A: the cooler stays in the block, a stream through its tube side |
| D12 | Symbols | B, framed (sheets 23): it matches the shipped door drawings, and its dark fill leaves the eye on the streams |
| D13 to D21 | The sizing stage, the shell's drop, drops in the run, the other tiles, the ejector's scope | As recommended: [SIZING_PLAN.md](SIZING_PLAN.md), section 6 |
| D22 | A fresh sheet with a furnace on a fixed duty | A first estimate on a line, or a first run with the furnace on its outlet (section 3.1). The column's door gets no start temperature |
| D23 | The column's block on the sheet (2026-11-03) | Option C rearranged, section 3.11. The owner: "The "block" should expose its internal components to the flowsheet, but with boundary well presented"; "C, but the lines arrangement can be better. Remember that Reflux and naphtha output are split from the same line with a pump boosting, and valve regulating". The rearranged sheet went to the owner and has no answer yet; the orchestrating session decided to build it, because it applies that correction and a drawing is reversible. D11 stands: one block, one process model |

## 6. What you did not mention

Taken as defaults unless you say otherwise. Items marked D are in section 5.

- **Basis**: a flowsheet picks one package, so a Basis tab is needed (sheet 16). Default: the world network's.
- **Mixers and splitters**: not in your list, and a flowsheet needs them. Added under Piping.
- **Recycles**: the solver picks tears and marks them. A forced tear is a later step.
- **A free pair**: a utility takes the heat, as the calculator does today, with no warning.
- **A heat cross**: a warning that names it, as free water on trays is. The run still ends.
- **A pumparound's cold end**: not checked, because the model has no circulation rate.
- **A target on the crude's outlet**: later. The pumparound's duty would become a result, and the block would read its port.
- **Motive steam**: joins the overhead as a mixer does. The steam the ejector needs is not calculated until W10.
- **A flooded column**: a warning above 100 % flood. The solve has no flood limit: T-101 took 24000 kmol/h in the no-way-out run.
- **Spec modes**: each unit needs its choice of target: outlet, duty, area, rise (sheet 11).
- **Pressure**: forward only, set by units. No pressure-driven flows. A sized unit's drops follow its flow (D19).
- **Water and solids**: water is on every stream's axis. Solids pass through units; the filter takes them; the column refuses them.
- **Failure and partial results**: a failed unit is red with a message, and the solved part stays.
- **Run progress and Stop** (sheet 06). Editing during a run is refused.
- **Editing basics**: undo, multi-select, copying units, autosave, an unsaved-changes prompt.
- **Names**: unique within a sheet.
- **Results extras**: Units and Log views (sheet 15), CSV export. Plots are a later step.
- **Multiplayer**: one sheet per block, one editor at a time; a second player reads (section 3.4). Files are per player. A
  flowsheet can go through the clipboard as text (Open, From clipboard).
- **Breaking the block**: its sheet goes with it. The editor's autosave file stays on their computer.
- **Data mismatch** with a server's datapacks: a notice only.
- **Link to the world**: the sizing stage hands every unit to the constructor ([SIZING_PLAN.md](SIZING_PLAN.md)).
- **Cold start**: the first run is cold, later runs warm. A Reset key is needed so a result can be reproduced.
- **Limits**: 200 units, 1 MiB a file, so a bad file cannot stall a client.
- **Languages**: en_us and zh_cn, as the mod ships.
- **Column limits that stay**: one feed, no side strippers.
- **Later**: Adjust and Set blocks, case studies, sensitivity runs.

## 7. Risks

- The column's convergence on feeds far from the training crudes is only partly tested in a loop: warm columns took every feed the
  plain passes gave them, up to 9 times the design rate. A bounded step can still jump too far (section 3.1). A run that fails
  keeps what it solved.
- One Wegstein factor per tear took 7 passes torn at the bottoms, and 5 on the feed temperature alone. W1 picks the tear variable.
- Option B of D11 is not measured.
- A native-library column (`9c5eb01f`, not merged) needs its library on every client. The Java solver stays the client's path.
- A large sheet costs client CPU. It is one background thread, so the game stays smooth, and Stop always works.
