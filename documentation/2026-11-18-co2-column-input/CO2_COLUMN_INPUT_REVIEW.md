# CO2 in the intake's air and as a column input: review

Owner request, 2026-11-18: "Add CO2 in air intake composition and make it a valid column input (if it is not, IIRC
currently there is some weird hard lock)". Branch `claude/co2-column-input-c69ef8`, cut from
`claude/column-testing-sizing-26b5b9` at 809a88cd2.

## 1. Expected behaviour

- **Air.** Ambient dry air holds carbon dioxide. The U.S. Standard Atmosphere 1976 (Table 3, sea-level composition; a
  U.S. government work), which `Air` already cites with ISO 2533, gives 0.000314 by volume (314 ppm). Nitrogen stays the
  balance. At the intake's state (25 °C, 50 % RH, 1 atm, y_w = 0.0156) CO2 is 314e-6 × 0.9844 = 3.09e-4 of the wet air:
  a partial pressure of 3.13e-4 bar.
- **Column.** CO2 is an ordinary vapour-liquid component of the cubic wherever no dry ice forms. Dry ice (CO2-I) forms
  only where CO2's fugacity in the fluid reaches the solid's. Pure CO2 has its triple point at 216.59 K and 0.518 MPa,
  and its melting line rises to about 218.6 K at 10 MPa. Dissolved CO2 freezes colder than pure CO2: 0.5 mol % in liquid
  methane at 185 K, or 2 mol % in a C2 to C4 liquid at 250 K or more, holds no solid. So a deethaniser or stabiliser
  carrying CO2 solves and sends CO2 overhead (it is more volatile than ethane at the top). Only a cold, CO2-rich liquid
  (for example a CO2 and propane overhead at 200 K) can hold dry ice, and that is a capability limit: the column has no
  solid on its trays.

## 2. What the code did instead (step 0)

There is no separate column calculator any more: `column_calculator_v3` is an alias of the process simulator
(`ModBlocks`). There are two column paths, and both stopped at the same line.

- **Every path.** A column's package is the network package `icheme:refinery` restricted to the feed's compounds
  (`MaterialCatalog.restrict`, through `FluidThermodynamics.on` in the world and `SheetCompounds` in the simulator). A
  view keeps the crystals of its compounds, so a view holding CO2 admits `icheme:crystal_carbon_dioxide_i`.
  `V3CatalogPropertyPackage`'s constructor threw `CrystalPhasesUnavailable` for any admitted non-ice crystal. The refusal
  was by compound list, before any solve and at any temperature.
- **Placed column.** `ColumnSolve.run` (the worker's solve, `ProcessSolveJobs.ColumnUnitCommand`) catches the exception
  as an `IllegalArgumentException` and returns `Failed("Package icheme:refinery admits crystal phases [...]")`.
  `ColumnOperation` installs no table: the column's status reads SOLVE_REFUSED ("first solve refused: ..."), the cycle
  takes nothing, and a state inside the refused state's bands is never asked again. A column whose feed holds any CO2
  (an intake's air would bring it) stood still for good. This was the "hard lock".
- **Simulator column block** (and its sizing, `ColumnSizing`): the same exception from `ColumnSolve.calculate` failed
  the block.
- **What already existed.** Both paths already test each solved product for a crystal (owner D6 of batch
  2026-11-16-fouling-corrosion, `ColumnSolve.frozen`). They run the network's crystal equilibrium at the product's
  enthalpy and pressure, gated by `FluidThermodynamics.crystalsCompete` (T at or below the crystal's record ceiling, CO2
  present). A crystal gives `Frozen` (the FROZEN trip in the world) or `column.frozen` (the simulator). The package
  refusal fired first, so this test never saw CO2.
- **Air.** `Air.DRY` left CO2 out (batch 2026-11-15-fluid-interfaces, D7), so nitrogen took its 314 ppm.

**A wrong premise in the brief.** The brief asked for a temperature rule: a coldest stage at or below "the crystal's
ceiling" would be refused. The ceiling the network uses (`FluidTpEquilibrium.crystalCeiling`,
`FluidThermodynamics.crystalCeiling`) is the crystal record's `temperature_max_kelvin`, 300 K for CO2-I. That is the
upper end of the Jaeger and Span 2012 validity range, where the melting line reaches about 500 MPa; it is not the
216.59 K triple point. A rule on that ceiling would refuse every CO2 column with a condenser below 27 °C.

The options sent to the main thread:

- **A.** Delete the package refusal. The existing product equilibrium is the rule.
- **B.** A, plus the same equilibrium on every tray at or below the gate.
- **C.** The brief's line at the triple-point anchor, 216.59 K.

The main thread chose **A**, plus one test of the coldest tray where it is colder than every product tested. Why: the
equilibrium decides exactly, at no new cost for a normal column. C puts a temperature bound where the package has the
equilibrium: it refuses cold columns the equilibrium accepts, and it never tests a tray just above 216.6 K. B costs one
equilibrium per cold tray per solve. A's gap (a tray colder than the condenser, behind a cold side feed or a pumparound
return) is closed by the one-tray test.

## 3. What was changed

- `science/fluid/network/Air`: `DRY` gains `CarbonDioxide` at 314e-6 (the refinery package's component id). The javadoc
  names the source table.
- `science/column/v3/thermo/V3CatalogPropertyPackage`: the crystal refusal is deleted, with its water-ice filter, which
  only existed to exempt ice from it. Water's handling is unchanged: no solve read that filter.
- `science/plant/column/ColumnSolve`:
  - `frozen` returns a `Solid` (the compound, the crystal record id).
  - `Frozen` is `(node, solid, temperature)`. Its text names the compound, the node (the product port, or "tray n"), the
    temperature and the crystal, for example "CarbonDioxide solidifies in this column at its tray 3 at 195.0 K (crystal
    icheme:crystal_carbon_dioxide_i)".
  - `coldestTray` (new) runs the same equilibrium on the coldest inspection node's liquid where that node is colder than
    every product tested: at most one equilibrium per accepted solve. `solve` calls it after the products.
- `science/flowsheet/unit/ColumnBlockSolve.frozen`: the same coldest-tray test on the sheet's thermo. `column.frozen`
  carries four arguments (compound, node, temperature, crystal); `en_us.json` reads them.
- `science/material/CrystalPhasesUnavailable`: the javadoc now names only the neural inference projection
  (`MaterialCatalog.inferenceView`), which still refuses. The bundled neural registry stays classical for a CO2 view:
  a model whose lump map holds CO2 reads the view without it.
- Unchanged and checked: the column's component basis is the view's list, so CO2 is a column component. The world's
  mapping is by id (`ColumnOperation.designTaken`, `probes`, `SplitTable.of` on the world list), and CO2 is on the
  world list. `SheetCompounds` keeps CO2 as any compound.
- **Saved columns.** A column's refusal and its frozen finding are memory only (`ColumnOperation.Run`: `refused`,
  `frozen`). A world saved with a column refused for CO2 loads with no refusal: the column asks a START solve on its
  schedule, and that solve now runs. Nothing saved is rewritten.
- **Saved intakes.** A placed intake's air is a fact in its spec and keeps its bytes (`FluidFactsCompatTest`: the
  schema-30 intake re-encodes byte for byte). A new intake draws the new air. No format, record or package changed: no
  revision bump, no `FORMATS.md` row.

## 4. Moved references

Baseline: the printed lines of the last lane that ran `GasIntakeTest` (`fouling-corrosion-wp6-materials-tab`,
`tmp/merge-lane/head/printed.txt`). Its nitrogen-only charge reprints bit for bit, so the two runs compare.

| Reference | Old | New | Bound (the test's) |
|---|---|---|---|
| Intake air in the charged drum: N2 | 0.768952196 | 0.768643109 | air as `Air.wet`, 1e-9 |
| CO2 | 0 | 3.0909e-4 (= 314e-6 × 0.98435) | the same |
| O2, Ar, Ne, He, Kr, Xe, water | as before to 1e-16 | | the same |
| Drum behind the compressor, ratio 3 | 303 974.996751 Pa | 303 974.996745 Pa | 2.7e5 to 3.05e5 Pa |
| Its solve counters | newton 518, lu 48 | newton 520, lu 48 | none |
| Drum behind the centrifugal machine | 137 708.57 Pa | 137 715.67 Pa (+5.2e-5: the air's molar mass 28.964 → 28.969 g/mol) | above 1.1e5 Pa |

No other printed value moved. A column without CO2 runs the same code as before: the deleted lines only threw, and the
coldest-tray test returns before any equilibrium where no node is colder than the products, or the crystals do not
compete. Every selected column reference passed unchanged.

Tests (`affected.mjs select --since 809a88cd2..HEAD`, 889 picks; `LanguageFilesTest`, `UnitTextTest` and
`fluidSolverRegression` left out): `test` 2642, `columnGates` 465, `holdGates` and `equipmentGates` all passed but two.
Both were test setup, and both were fixed and rerun alone: the pilot package is now refused for its spine rather than
its crystal; the first deethaniser design was outside the package's 2 MPa range. Then `ColumnCarbonDioxideTest` 3/3,
`CrystalPackageConsumersTest` 4/4, and `GasIntakeTest` 4/4 (whole: its drum test is in `left-out.txt` and was edited).

New and changed tests:

- `ColumnCarbonDioxideTest` (new):
  - A deethaniser, 1 MPa top, 230 K condenser, 2 mol % CO2. It solves; CO2 leaves 99.998 % overhead; every compound is
    conserved exactly and the energy closes at the reference. The coldest node is the condenser.
  - The same profile with tray 3 made a 90 % CO2 liquid at 195 K: `Frozen` at "tray 3".
  - A CO2 and propane column at 0.6 MPa with a 200 K condenser: `Frozen` at the distillate, 200 K, CO2-I.
  - 0.5 mol % CO2 in liquid methane at 185 K and 3 MPa: the crystals compete and the equilibrium finds none.
- Changed: `CrystalPackageConsumersTest` (the old refusal: the pilot refused for its spine, a refinery CO2 view builds),
  `ColumnSolveTest` (`frozen` gives a `Solid`), `ColumnBlockSolidsTest` (`column.frozen`'s four arguments),
  `ColumnRunTest` (the new `Frozen`), `GasIntakeTest` (the air), `FluidFactsCompatTest` (the saved intake keeps its
  air).

Crystal equilibria per accepted solve (counted in the test: the products the gate lets through, plus the coldest tray):
deethaniser 1 before and 1 after (its distillate at 230 K; the bottoms at 319 K is above the gate).

A finding outside this batch: of eight deethaniser designs probed (`tmp/co2-column/probe-results.xml`), seven did not
converge in V3 (stage continuation stalled at 4 stages). The CO2-free twin of one of them failed the same way, so CO2 is
not the cause. This is V3 on light ends, and it is recorded here only.

## 5. Owner items

- **O1. Released air books CO2 as toxic.** CO2's toxic reference is its IDLH, 40 000 ppm. A vent, outfall or burst that
  releases intake air now adds 3.09e-4 / 0.04 = 0.0077 of a reference per mole of air. A cube whose whole air (about
  167 400 mol) were replaced by released air would read toxic index 0.0077: 13 times below the first exposure level
  (0.1), above the field's cutoff (1e-3). A meter in a cube full of vented air would show it. Options:
  (a) leave it, since it is real CO2 and harmless at that level; (b) the pollution system books only CO2 above the
  ambient share of the air it releases (pollution code, another thread's area; not touched here).
- **O2. The refinery package's advisory text.** `refinery.json`'s `CO2_DRY_ICE` evidence line ends "the V3 column
  refuses CO2 views", which is no longer true. Changing it changes the package record. Its `revision` (and the column
  identity that names it) would bump, and every column seed and digest on the package would move for a text change.
  Options: (a) leave the text until the next package change; (b) edit it now with a revision bump. Not done here.
- **O3. Corrosion (no change).** M4 (wet CO2) on wet intake air at 25 °C: 0.3 · 10^(5.8 − 1710/298.15 + 0.67 log10
  3.13e-4) = 1.6e-3 mm/y. M7 (aerated water) on the same air: 0.25 · (0.209/0.21) · 2^(0.15/30) = 0.25 mm/y. The inside
  rate is the largest mechanism, so an air-blanketed carbon steel vessel's rate does not move. At 80 °C the two are
  0.012 and 0.89 mm/y; at 3 bar 3.4e-3 and 0.50: M7 still governs. No owner decision is needed; recorded for the record.

## 6. Not verified

- No dev client and no in-game check: a placed column fed through an intake in a world was not run. The placed path's
  solve is `ColumnSolve.run` on the feed island's model, which the tests run directly. The world rig with a real V3
  solve does not exist (`ColumnRunTest` uses a stand-in solver).
- The final lane, the GameTests and chain-100 were not run (the final lane's alone).
- `LanguageFilesTest` and `UnitTextTest` were not run (the translation commit's). The other language files still read
  `column.frozen` with two arguments until the translation commit.
