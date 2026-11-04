# Port sides review: free left and right connections (2026-11-03)

Owner, 2026-11-03: "These common equipment shouldn't have a fixed in/out connections, user should be free to connect it from left or right, review the logic". Case: heater H-101 (a `fired_heater`) draws its inlet left and its outlet right, and a feed could not land on the right. Review only, code at `e4709610a`; no code changed, no Gradle run.

Owner, 2026-11-04, on keeping old sheets readable: "no need". Simulator sheet files are treated like AGENTS.md's other formats: a breaking change is tested on a fresh sheet, with no migration. Port ids and stream-end fields may change meaning. Each option below names the corpus images it re-records or retires.

## 1. Expected behaviour

What the equipment fixes:
- **Machines:** a pump's suction and discharge are set by its impeller and casing; a centrifugal pump run backwards does not pump the other way. The same holds for a compressor, a turbine and the ejector (motive nozzle, throat, diffuser; the suction branch on the chest).
- **Phase outlets, fixed by gravity:** vapour leaves at the top and liquid at the bottom: a drum's gas and water, the kettle's gas, a column's overhead and bottoms.

What is only a drawing convention:
- **Free nozzles on a vessel:** a drum's feed and oil outlet can be at either end. Column feeds, draws and returns are on whichever side of the shell the piping needs.
- **Passages:** a heater coil, an air-cooler bank, each exchanger side, a valve and a filter each have two nozzles, and the piping decides which is the inlet. Some valves and filter elements carry a flow arrow, but the PFD symbol is the same either way. An exchanger's two sides together set counter- or co-current, and the world builds counterflow (`ExchangerRating`; the `EXCHANGER_*_END` Must rows, "World: counterflow"). So the arrangement stays a fact of the model, whatever the drawing.
- **Many-port units:** a mixer's and a splitter's ports can be arranged in any way.

Simulators: Aspen Plus, HYSYS and DWSIM all let the player flip (mirror) and rotate a unit's icon; in DWSIM the flip is a property of the graphic object. Aspen Plus also lets a port take streams at more than one place, for example a column feed on any tray. In none of them is a port's direction free: a stream has a source and a destination, and each port is an inlet or an outlet. This is from their manuals as I know them; I did not check them online for this review.

So the direction is a fact: of the equipment for machines and phase outlets, and of the stream for passages. The side a port is drawn on is only a drawing fact, and today nothing lets the player choose it.

## 2. What the code does

| Type | Ports (IN required R, optional o, utility U; OUT) | Drawn (`SheetLayout.spot`, `ColumnBlock.spot`) | Direction fixed in reality by |
|---|---|---|---|
| `valve`, `filter` | `in` R, `out` | `in` left middle, `out` right middle (default case) | the drawing only |
| `pump` / `compressor` | `in` R, `out` | `in` left, `out` right (y 13 / 19) | the machine |
| `ejector` | `suction` R, `motive` o, `discharge` | suction top (x 10), motive left, discharge right | the machine |
| `mixer` | `in/1..n` o, `out` | inlets stacked left, `out` right | the drawing only |
| `splitter` | `in` R, one `out` per row | `in` left, outlets stacked right | the drawing only |
| `drum`, `storage_tank` | `in` R, `gas`, `oil`, `water` | `in` left, `gas` top, `oil` right, `water` bottom | gas and water by gravity; `in` and `oil` sides by drawing |
| `exchanger` | `tube_in` o, `tube_out`, `shell_in` o, `shell_out` | tube left to right (y 14); shell in and out on the bottom (x 48, 16) | the drawing; the model is counterflow |
| `kettle` | `tube_in` o, `tube_out`, `feed` R, `gas`, `liquid` | tube in and out left; feed bottom; gas top; liquid right | gas by gravity; the rest by drawing |
| `cooler` | `in` R, `out` | **`in` right, `out` left** (y 8; "the owner's sheet") | the drawing only |
| `fired_heater` series / apart | `in` R, `out` / `convection_in` o, `convection_out`, `radiant_in` R, `radiant_out` | inlets left, outlets right (CABIN and VERTICAL spots) | the drawing only |
| `column` | `feed` R, `steam/*` U, `draw/*`, `overhead`, `distillate`, `water`, `bottoms`, `condenser_*` U, `reboiler_*` U, `pa/*/in` U and `out`, `motive` o | feed, steam, pairs and motive left; draws, distillate and water right; overhead top; bottoms bottom | overhead and bottoms by gravity; the rest by drawing |
| placeholder | whatever its streams name | inlets left, outlets right | — |

Every model reads its inlet and writes its outlet by port id (`u.wire(id)`, `u.out(id)`, `u.through(in, out, …)`). A side is never a model input.

Where direction is enforced:
- `Port.direction` (IN or OUT) is fixed for each port id by `UnitModel.ports(params)`. `ExchangerUnit` and `FiredHeaterUnit` refuse an outlet wired with no inlet (`unit.free_inlet`).
- `FlowsheetCompiler.claims` (lines 35 to 36, 105 to 107): a stream's `from` claims only an OUT port and its `to` only an IN port; any other end stays open and is not refused. `livePorts` gives placeholders whatever their streams name.
- `SheetLayout`: `spot` (260 to 308) is a pure function of the type, the port id and the direction, so no fact of the unit moves a port. `placeholder` (316 to 327) and `live`/`held` (187 to 197, 355 to 366) anchor a line only on a port of the matching direction.
- `SheetEditor`: `takes` (452 to 455) lets an outlet take a stream's start and an inlet its end. `addStream` (462 to 481) refuses two ports of the same direction with `PORT_SIDE`, "A stream runs from an outlet to an inlet."; `reconnect` (512) applies the same rule, and the ring hints in `SheetGestures` (392, 409) follow `takes`.
- Readers that name `in` and `out` by id: `SheetSizing` (the exchanger loads 493 to 498; the cooler, heater, compressor, pump and valve 521 to 877; the pressure-path walk 910 to 962); `UnitDoors` (`into`, `outOf` and the nozzle rows 432 to 439); `ResultsPage` 317; `SelectionColumn` 59 to 60; `SheetContext.into`/`outOf`; `SimulatorPage` 273 to 282 (the open-port notice).
- File (`FlowsheetFile` FORMAT 1): a stream end is `{unit, port}`, saved as `from` or `to`; which of the two it is gives its direction, and nothing saves a side. A unit saves `id, type, name, x, y, params`. `FlowsheetFileTest` reads the five images in `compat/flowsheet/` and writes back all they hold. Their passage ends: `format-1.json` has `valve` in and out and `drum` in; `-coolers` has `cooler` in and out; `-counts` and `-sizing` have the `exchanger`'s `tube_in` and `tube_out`; `-ejector` has machine ports only. `compat/simulator/designs-*.snbt` (`SentDesignTagsTest`) holds the block's sent designs, shapes and counts with no port or side, and no option touches it.

Finding: the direction rule matches every simulator and the physics. The defect is that the side is hard-coded for each type, so the player cannot draw a unit facing the other way. The cooler's inverted spot was a one-off answer to the same need.

## 3. Options

**A. A mirror fact on each unit.** Ports swap left and right and keep their ids and directions.
- **Changes:** `Flowsheet.Unit` gains `mirrored`. `spot`, the `ColumnBlock` spots and the tag position (244 to 246) use `x → w − x`, with left and right swapped; `SheetPainter.sprite` draws the sprite flipped. A "Mirror" key or door button is one undoable edit, with one lang key in all 5 files.
- **Deletes:** nothing below the editor. The cooler's special spot becomes the plain default (Q4).
- **Save:** `"mirror": true` on the unit, false when absent. No image is re-recorded or retired. A cooler whose default moves to the left draws mirrored in `-coolers`, which is a drawing change only.
- **Tests moved:** new cases in `SheetLayoutTest`, `SheetEditorTest`, `SheetGesturesTest` and `FlowsheetFileTest` (the field round-trips). No `science/flowsheet` test moves.
- **Risks:** mirrored symbol marks (the ejector's cone, the heater's stack, the air cooler's fan) must still read right; check them in `SimulatorRender`. The column, with its members drawn inside its frame, is the largest piece of work.

**A′. Mirror on landing**, an addition to A. When a stream's end lands on a port of the wrong direction, the editor mirrors the unit if the port it needs is free, then lands the end on it, as one undo step. The heater case then works in one gesture, and `takes` and the rings answer "yes, by mirroring". The risk is a unit that turns when the player did not expect it to, so the status line says that it mirrored.

**B. Reversible passages.** A two-nozzle passage has no fixed in or out, and the stream wired to it decides; machines and phase outlets stay fixed.
- **Changes:** the passage ports get neutral ids, so no id names a direction (`a`/`b`, `tube_a`/`tube_b`, `shell_a`/`shell_b`, the coil ends). `Port` gains a pair, and `claims` lets the stream's `from` or `to` decide which end is the inlet. Every reader that names `in`/`out` (about 40 places, listed in section 2) must find the inlet through the wiring: the models, sizing, the path walk, doors, results, the open-port notice.
- **Deletes:** the direction in passage port ids; the cooler's special spot.
- **Save:** the ends change meaning. Re-record `format-1.json` (valve), `-coolers`, `-counts` and `-sizing` (exchanger tube ends); `-ejector` stays. The unit, sizing, results, door and editor tests move.
- **Risks:** a passage with one end wired is ambiguous until the other end lands; a reversed exchanger side must not read as co-current; and it gives nothing for machines, mixers, splitters or vessels, so A is still needed.

**C. Every port drawn on both sides.** Changes layout, hit tests and rings. `End` gains a required `side`, so every image with a stream end is re-recorded (all five). Every free port shows twice (a 6-inlet mixer shows 12 dots), and the layout, editor and gestures tests move. Risks: clutter and mis-hits on small symbols.

**Recommendation: A with A′**, for every tile; the column in the same step or a second one (Q2). It gives the owner's free left and right connection and keeps direction a fact, as every simulator and the physics do. It changes nothing in the compiler, the models or sizing, adds one unit field and re-records no image. The owner's decision makes B cheaper (neutral ids, no save cost), but it still means about 40 readers and still needs A, so reject it. Reject C: clutter, and all five images re-recorded.

Questions for the owner:
1. A alone (an explicit Mirror), or A with A′ (mirror on landing)?
2. Is the column mirrored in the same step, or later?
3. Only a left-right mirror? Gravity ports rule out a vertical flip for vessels and columns; a rotation (inlet on top) for passages could come later.
4. The cooler's default: keep the inlet on the right, as the owner's sheet drew it, or make it left like the others, with Mirror for the right?
