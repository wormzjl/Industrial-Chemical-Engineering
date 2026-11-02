# W6: the flowsheet screen, how to build it

Batch [2026-11-02-process-simulator](README.md). This is the working plan for work package W6 of
[PROCESS_SIMULATOR_PLAN.md](PROCESS_SIMULATOR_PLAN.md), section 4. The owner approved what the screen does and looks like in decisions D1 to
D22, on the drawings; this file only says how to build it. Where the two differ, the approved plan wins. Nothing here is built yet.

W6 ends when the screen's code is on the branch, its gates pass, and the owner has the sheets rendered from shipped code. Run stays
unwired (W7). There is no block to open the screen from (W8), so no one can open it in the game yet, and the owner judges it on the sheets.

## 1. What to read first

- `AGENTS.md`, above all "No repeated tests", and the `gui-creation` skill (`.claude/skills/gui-creation`): the owner's rules for every screen.
- `PROCESS_SIMULATOR_PLAN.md` sections 3.2, 3.3, 3.5, 3.8, 3.9, 3.11. Section 3.5 holds the gesture table.
- `tools/heat-gui-renders/SimulatorPrototypes.java`: every approved screen, with its exact numbers: rects, sizes, colours, tile lists, door rows.
  The shipped screen matches it. Its sample numbers are real, so the render tool should get them from a real run, not by copying.
- `tools/heat-gui-renders/SimulatorIcons.java`: the approved symbols (option B, FRAMED, D12). The renders are in the main checkout,
  `research/2026-11-02-process-simulator/renders/`.
- `science/flowsheet`: `Flowsheet`, `Params`, `Port`, `UnitTypes`, `UnitModel`, `FlowsheetRun`, `Report`, `Note`, `FlowsheetFile`, `FlowsheetCompiler`, `unit/`.
- The pattern to follow: `AssemblyFlowsheet` (a drawing with hit tests, no Minecraft classes), `AssemblyScreen` (a kit screen), `CalculatorColumn`
  and `ColumnCalculatorV3Screen` (the column's door reuses them), `DeviceColumn` and `FluidDeviceScreen.FluidGuiLayout` (the door frame).

## 2. Code shape

Everything but one screen class is Minecraft-free, in `client/gui/flowsheet/`, so the gates and the render tool run the same code the screen does:

| Class | Holds |
|---|---|
| `Symbol` | The symbols by design box, and where each lies in the atlas |
| `Tiles` | Categories, tiles, a type's symbol and tag prefix |
| `SheetLayout` | From a sheet: each unit's box and port places, each stream's line and pills, hit tests |
| `SheetEditor` | Every edit of the sheet, undo and redo, tags, refusals |
| `SheetView` | Pan, zoom, fit, canvas to design and back |
| `SheetGestures` | The mouse and key state machine of section 3.5; what is selected, what a gesture overlays |
| `SheetPainter` | Draws the grid, lines, symbols, ports, names, pills, tears, rings, overlays |
| `SheetState` | From a run and the edits since: each unit's look, the tears, the status text and tone |
| `SelectionColumn`, `UnitDoors`, `StreamDoors` | The panel's and the doors' rows, as builders with gates |
| `ResultsPage`, `BasisPage`, `FileColumns` | The other tabs and dialogs |

`client/gui/screens/inventory/ProcessSimulatorScreen.java` only forwards events and draws. A plain `Screen` for now. Keep what W7 and W8 need behind a
small interface (start and stop a run; load and store the sheet), so they fill it in and do not rewrite the screen.

Kit additions, each with a line in `KitLayoutTest` where it has a rule:

- `Sprite(texture, u, v, w, h, atlasWidth, atlasHeight)`, and `Canvas.sprite(sprite, x0, y0, x1, y1, argb)`, a default no-op. `GuiCanvas` draws it with
  `blit` under `setColor`, with blending on and the texture's filter set to linear (`getTexture(location).setFilter(true, false)`). The render tool's
  `Java2dCanvas` draws the same PNG tinted.
- `Column.Wide(key, hint)`: an entry across the whole row, for a name. `Column.place`, `draw` and `hover` take it; `KitDoor.keep` makes its widget through
  the field factory. The prototypes leave a `Gap` for this row.

## 3. Symbols

- `Symbol` holds the prototypes' `Glyph` boxes, less `MISSING` (a placeholder is drawn with `FlowsheetPainter`: a red box, hatched) and with the column in
  three slices: `COLUMN_TOP(44, 15)`, `COLUMN_TRAY(44, 16)`, `COLUMN_FOOT(44, 15)`. A column is `30 + 16 N` high, so its tray slice repeats whole.
  Painting `SimulatorIcons`' column at height `30 + 16 N` puts the trays at `11 + 8 i`, so the cuts at 15 and `15 + 16 N` fall between trays.
- Each symbol bakes to masks, white with the mask in alpha: body, marks, and for HEATER, CABIN, CFB, GRATE and FIXED_GAS a hot layer in the flame colour,
  drawn untinted. Body takes the look's fill, marks its stroke.
- 3 atlas pixels per design unit, 1 design unit of margin round each mask, because an outline stands half outside its box.
- The packing is a plain function of the enum order inside `Symbol`, so the game and the tool cannot disagree and no layout file is needed.
- `tools/heat-gui-renders/SymbolAtlas.java` bakes `src/main/resources/assets/icheme/textures/gui/flowsheet/symbols.png` from `SimulatorIcons`. `SimulatorIcons`'
  framed path needs its three layers split out for that. The PNG is committed; a gate reads its size against `Symbol`.

One linear-filtered page was chosen over mip levels: at scale 2 and zoom 1 an atlas pixel is near a screen pixel, and vanilla has no mip chain for a plain texture.
At zoom 0.5 fine strokes turn faint. If the sheets show that as a real loss, say so rather than building mip levels unasked.

## 4. Layout

Port places, in the symbol's own box, from `model.ports(params)`:

| Type | Places |
|---|---|
| valve, filter | `in` left, `out` right, both at mid height |
| pump, compressor | `in` and `out` at y 13 and 19 of their boxes |
| mixer | `in/n` spaced down the left, `out` right |
| splitter | `in` left, `out/<id>` spaced down the right |
| drum, tank | `in` left, `gas` top, `oil` right, `water` bottom |
| exchanger | `tube_in` left, `tube_out` right, at y 14; `shell_in` (48, 28), `shell_out` (16, 28), its two shell nozzles |
| kettle | `tube_in` and `tube_out` left, `feed` (46, 34), `gas` (44, 0), `liquid` (70, 24) |
| air cooler | `in`, `out` at y 8 |
| fired heater | `in` (0, 66), `out` (58, 42); apart: `convection_in` (7, 20), `convection_out` (51, 20), `radiant_in` (0, 66), `radiant_out` (58, 42) |
| column | Section 3.11's rule: left in tray order `condenser_in`, `condenser_out`, each `pa/<id>/in` and `out`, `feed`, `steam/<id>`, `reboiler_in`, `reboiler_out`; right `distillate`, `water`, `draw/<id>`; `overhead` top, `bottoms` bottom. Evenly spaced to fill the side; the height's N follows the longer side, at least 14 px a port. A pair's name is on the block, at the pair's middle |
| removed type, or settings that give no ports | The placeholder keeps what its streams name: inlets left, outlets right |

- A port's square is the laying tool's colour by class (`PortColours`: gas and overhead VAPOUR; oil, water, liquid, distillate, bottoms LIQUID; the rest BULK), `CONNECTED` grey once a stream holds it.
- **Pills.** `Stream.route` holds the corners the player gave. An end with no `from` or `to` has no port, so its pill's place is the route's first or last point. The rest are corners.
  A stream with an end that names a port no longer live (a removed row) keeps that end, drawn as an open pill near the unit, so the file keeps it and Undo restores it.
  Write this rule in the class comment: it reads the file's `route` field, so it counts as format, and it needs a line in the corpus image if a corner case changes.
- A pill's line joins the edge nearer the line's other end (the sample's RESIDUE pill lies left of its unit). A feed's outline is `Palette.ACCENT`. A pill is `max(38, text + 12)` wide, half height 9.
- **Lines** are orthogonal. With no corners: leave each port outward by 10, then one or two bends. Avoid no obstacles. With corners: through them, adding an elbow where two points share neither x nor y.
- Moving a segment writes the line's inner corners into `route`, then moves that segment's two corners; a segment on a port gets new corners, so the port stays.
- A name: a unit's tag sits over its box (a column's beside its foot). A stream's name sits over its longest across segment, only where neither end is a pill: a pill says it already.
- Hit tests in this order: port, pill, name, unit, line, within a few pixels that shrink with zoom.

## 5. Editor

- A sheet is immutable records, so undo holds whole sheets. **`Params` wraps a mutable `JsonObject`: never change one in place. Deep-copy, then set.** Entries typed under one key
  coalesce into one undo step, or every keystroke costs one.
- A name in use is refused, with a status line. Tags: the type's prefix and the first free number from 101. Streams: `S-n`, the first free.
- Removing a unit leaves its streams, each end an open pill at the place it held.
- `Flowsheet` records hold `unknown` fields: rebuild them with their `unknown`, and never compare or key on a whole record.

A dropped unit needs settings that run, or the first thing the player meets is a refusal:

| Type | Keys and defaults (SI; drops in Pa) |
|---|---|
| valve | `target` outlet or drop, `pressure`, `drop` |
| mixer | `inlets` 2 |
| splitter | `mode` shares or flows; `outlets`: rows of `id`, `share` or `flow`; a row with neither takes the rest |
| drum | `pressure_mode` inlet or set, `pressure`, `drop`; `heat` none, temperature or duty; `temperature`, `duty` |
| storage_tank | `ambient`; `heat` as a drum's |
| exchanger, kettle | `target` outlet, duty or area; `side` tube or shell; `temperature`, `duty`, `area`, `tube_drop`, `shell_drop` |
| air_cooler | `target` outlet or duty, `temperature`, `duty`, `drop` |
| fired_heater | `coils` series or apart; `target`, `temperature`, `duty`, `limit`, `radiant_drop`, `convection_drop`; apart adds `convection_target`, `convection_temperature`, `convection_duty` |
| pump, compressor | `target` outlet or rise, `pressure`, `rise` |
| column | `ColumnDoor.from(input, condenser)` on the default input |

## 6. What a run gives the screen

- `FlowsheetRun.outcomes` and `reports` are keyed by calculation id, not by unit. A unit with several calculations is `unit/part`, a source is `feed <stream id>`. A unit's look takes the worst of its parts.
- `Note.subject` names a unit, a stream or `sheet`. A warning makes its unit yellow.
- `Loop.tears` are stream ids. A free outlet writes a stream `unit:port`, so `streams` holds some ids the sheet does not.
- A stream's table reads `FlowStream`; density, viscosities and so on come from `FlowStream.state()` and `FluidThermodynamics.phaseStream`.
- The status follows `SimulatorPrototypes.screen`. The science names keys under `gui.icheme.flowsheet.`, and none has an English line yet: `run.mass_gap, unit.failed, unit.setting, unit.removed, unit.free_inlet, unit.pressure, stream.open, port.free_outlet, thermo.no_state, loop.fallback, loop.no_steady_state, loop.not_closed, loop.first_estimate, valve.outlet, mixer.pressures, splitter.value, splitter.rest, splitter.over, exchanger.area_pair, exchanger.set_side, exchanger.heat_cross, cooler.heats, heater.limit, heater.cools, mover.rise, pump.phase, compressor.phase, pump.bubble_point, column.row_id, column.solids, column.liquid_water, column.steam_water, column.steam_wet, column.inlet_pressure, column.component, column.failed, column.pa_hot, column.condenser_hot, column.reboiler_cold, file.damaged, file.newer, basis.parked, basis.package`. A gate should find them from the science's sources, so a new key fails it. Arguments are raw numbers as strings, in SI: decide where they are formatted.
- A column door's Results pages need the block's `V3ColumnResult`, which a `Report` does not keep. Open since W3. Keep it in the run's state, not in `Report`'s readings, or say why not.

## 7. Boundaries

- Doors apply as typed, so no Apply (D6). A change greys what lies downstream, until the next run.
- Sizing's tab is off, with a hover, until the sheet stands steady. Its pages are S1 to S5.
- The ejector, reactors and solid-fuel tiles are unlit, with a hover. W10 lights the ejector.
- Save stores on the block (W8). Open and Save as work on files now: `.minecraft/IChemE/flowsheets/<name>.json`, through `FlowsheetFile`. A save name needs a guard: separators and reserved names.
- The clipboard and the reservoir's Copy are W8. A feed's Templates key is W6. Sheet 08's Paste with T and P and Save as write a stream template's conditions.
- `FlowsheetPaletteCoverageTest` and `FlowsheetCatalog` are W9. A tile list that a later coverage gate can read is all W6 needs.

## 8. Gates and renders

- Column-builder gates as the skill lists them, for every door and panel, at every width from 300 to 790 in steps of 7.
- `SheetEditor`: every operation, its undo and redo, a refused name, an unknown field surviving each edit.
- `SheetLayout`: every unit type's ports fall on its box and stay on it; the column's ports hold 14 px apart at every row count the door allows; a removed row's stream ends in an open pill and returns on Undo; hit tests find what the painter drew.
- `SheetGestures`: each row of section 3.5's table, reconnect and its cancel, drawing a stream to a port and to empty canvas, a refused edit in a read-only sheet. Assert on the sheet that results.
- A language gate over every text the builders name and every key the science names.
- Render from shipped code: a new `tools/heat-gui-renders/SimulatorRender.java`, on the sample sheet (the owner's crude unit, `tools/process-simulator/SampleFlowsheetProbe.java`), with a real `FlowsheetEngine` run, in both doors, for each sheet the plan names: 01, 02, 03, 05, 06, 07, 08, 10, 11, 12, 14, 15, 16, 17, 18, 19, 21. Look at every sheet. Send them to the owner with `SendUserFile`, and keep them in the main checkout's `research/` folder, not in `documentation/`.
