# Tutorial column

Batch `2026-11-12-tutorial-system` (P4, decision D4). It solves and sizes the candidates for the "Small crude tower
(Medium crude)" on the bundled crude unit scaled to 1/64. Then it draws one datasheet per candidate for the owner. It
registers no preset and writes nothing under `src/`.

## What it does

- `SmallColumn` builds each candidate's column template, a `columnpreset` draft of schema 3:
  - A is `medium_crude.json` on 1/64 of its design feed (39.96 kmol/h) at 1.0 m.
  - B and C come from `candidates.json`.
- It copies `flowsheet_examples/crude_unit.json` and scales by 1/64 every feed (the crude, the cooling water) and every
  typed duty (H-101 duty and limit, E-101 duty and area, E-102 duty).
- It replaces T-101 through `ColumnDoor.template`, the Templates dialog's path. It wires the crude through the
  pumparound coolers in order and drops the product lines a candidate has no draw for. The steam feed is the candidate's
  row.
- It runs the sheet once on the simulator's own path: `FlowsheetEngine.run`, then `SheetSizing.rate` (the candidate as
  given) and `Proposal.sizeAll` (Size all with the 20 % spare and its one re-run). These are the paths that
  `SimulatorRunTest` and `SizeAllSampleTest` take.
- Its checkpoint counts the column's control polls by route, read from the stack: the learned correction, the classical
  continuation, the warm start, and the sizing stage's trial solves. Its progress marks each column calculation. A
  counter, never a clock.
- `Boiling` holds the rule of the crude barrel recording (`BarrelData`): a pure compound boils at its normal boiling
  point, and a cut boils evenly between its edges. From it come each product's 5, 50 and 95 % points by mass and the gaps
  between neighbours (the heavier one's 5 % point less the lighter one's 95 % point).
- `ShapeCheck` builds a candidate's shell at other diameters with no solve and prints why the constructor refuses one: a
  nozzle with no free row by its tray. With `-Dsearch=true` it lists every pumparound placement the shell builds.
- `Datasheet` draws, from the result files: `column-<id>.png` (the elevation to scale, the numbers and the acceptance
  rules) and `columns-compare.png` (the elevations on one scale, each over a 16 by 16 block plot of its footprints, shelf
  packed with one block between units). It also writes `tmp/tutorial-p4/summary.txt`.
- An optional `tmp/tutorial-p4/findings-<id>.txt` adds the analysis lines to a datasheet.

## Run

From the worktree root. Each run is one Gradle invocation, so check the machine's lane count first.

```
./gradlew --offline --init-script tools/tutorial-column/column.init.gradle smallColumnSolve -Dcandidate=A,REF8
./gradlew --offline --init-script tools/tutorial-column/column.init.gradle smallColumnSolve -Dcandidate=B,C -Ddraft=true
./gradlew --offline --init-script tools/tutorial-column/column.init.gradle smallColumnShape -Dcandidate=B,C -Ddiameters=1.0,1.5
./gradlew --offline --init-script tools/tutorial-column/column.init.gradle smallColumnSolve -Dcandidate=B,C
./gradlew --offline --init-script tools/tutorial-column/column.init.gradle smallColumnDatasheets
```

- `REF8` runs the bundled 8 m sheet once, unchanged, for A's tray comparison.
- `-Ddraft=true` writes the template and the sheet only, for `ShapeCheck`.
- `-Dnosize=true` runs the sheet without sizing (the tuning solves).
- `-Dv101=3.2` sets V-101's outlet, bar (default 3.2, the bundled sheet's 2.8 leaves the transfer path red at 1/64).
- `-Dspecs=tools/tutorial-column/tuning.json` reads the candidates from another file. `tuning.json` holds every solve
  that aimed B and C at the barrel's shares (`B-t1` to `B-t14`, `C-t1` to `C-t14`).
- A candidate's `heater_outlet_kelvin` and `heater_limit_mw` set H-101 (sheet choices); its `reflux_ratio` the column's.
- The datasheet splits the lamps: real fails after Size all, and "larger than needed" (the catalogue's smallest pump or
  shell, with the feed factor at which it turns green). It shows each product's mass share beside the barrel's, where the
  crude's naphtha leaves, and the fuel balance (off-gas, then bottoms through a burner).

## The bundled preset and example (candidate C)

The owner chose candidate C, variation 1 (D4). Two bundled files come from its final run:

- `src/main/resources/assets/icheme/columnpreset/small_crude.json` is `tmp/tutorial-p4/C.json` with the preset's name
  and `translation_key` (`gui.icheme.column_template.small_crude`).
- `src/main/resources/assets/icheme/flowsheet_examples/small_crude_unit.json` is `tmp/tutorial-p4/C-sheet.json`
  (unsized, no results, as `crude_unit.json`), with gson's `=` escapes written as `=`.

- `src/main/resources/assets/icheme/mixturepreset/small_crude.json` is the medium crude stream at C's design feed, at the
  feed T-101 meets on the example's run (S-7, 657.15 K). The grades' streams stand at the heater outlet; at the small
  unit's (663.15 K) the calculator leaves 44.9 % in the bottoms. `PairCheck` solves a stream and tower pair the way
  `V3TestInputs.crude` builds it and prints each product's share by mass:
  `./gradlew --offline --init-script tools/tutorial-column/column.init.gradle smallColumnPair -Dpair=small_crude`
  (`-DfeedK=663.15,657.15` tries other feed temperatures).

To regenerate them after a change to the crude unit or to C, run `smallColumnSolve -Dcandidate=C`, check the result, and
write the three files with `node tools/tutorial-column/bundle.js` (it also keeps the index lines). Then run
`SmallCrudeUnitTest` (holdGates), which names every lamp the sheet may show.

## Outputs

- In `tmp/tutorial-p4/`, for each candidate id:
  - `<id>.json`, the template;
  - `<id>-sheet.json` and `<id>-sized-sheet.json`, which open in the simulator from the clipboard;
  - `<id>-result.json`.
- In `tmp/tutorial-renders/p4/`: the PNGs.
