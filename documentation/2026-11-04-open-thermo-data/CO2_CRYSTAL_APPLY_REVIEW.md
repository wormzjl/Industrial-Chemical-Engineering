# O11 applied: CO2 and CO2-I in icheme:refinery (E27, E28)

## Expected behaviour
- CO2 is a world compound with its dry-ice crystal. At 1 atm it sublimes near 194.7 K with about 25.2 kJ/mol (Giauque-Egan).
- The crystal is anchored at the triple point to its own family's liquid: on a fit package (no reference spine) PR78 with the `shifted_polynomial_5` ideal gas, on a spine package the spine. The network datum is the family's, so the fit path has a formation offset of 0.
- A view without CO2 has no CO2 crystal: same identity, same numbers. The V3 column still refuses a view that holds a crystal.
- N2/H2S is DECHEMA's 0.1652, not N2/benzene's 0.1641 (E28).

## What changed
- `CoefficientIdealGas` moved out of `NetworkPhaseEngine` into its own public class (`of`, `fitRange`). `CrystalPhaseEvaluator.forPackage` uses it on a fit package (the spine otherwise); `CubicPhaseEvaluator.forPackage` on a fit package returns `HydrocarbonModel.coefficientEvaluator()`.
- The spine-only refusals are deleted in 5 places: the `MaterialCatalog` loader, `FluidThermodynamics` admission, `NetworkCrystals` (offset 0 without a spine), `NetworkPhaseEngine`'s evaluator choice, and the `CrystalPhaseEvaluator` ideal gas. The column refusal (`CrystalPhasesUnavailable`) stays.
- The fit path's revision names `spineRevision` (the crystal records) only when the view holds a crystal.
- Data:
  - `refinery.json` r3: CarbonDioxide (`icheme:chemsep_carbon_dioxide`) is last; crystal `icheme:crystal_carbon_dioxide_i`; evidence line.
  - `crude_pr78.json` r3 lists `icheme:dwsim_refinery_pr78_co2` last.
  - New records: the dataset, the 8-pair CO2 library, the Tr 0.7 volume point.
  - Preset `fluid_6` `carbon_dioxide` is last in `fluid_presets`. It uses a literal label like every fluid preset; `material.icheme.carbon_dioxide` already exists, so no lang line.
  - `materials-index.json` gains 4 rows.
- E28: `tools/compound-import/pairs.mjs` reads the N2 row of `kij_pr.dat` one column on after the diagonal. Every N2-column cell after the diagonal is checked: a copy of the row's shifted cell, or the corrected value; anything else throws.
  - The Tier 1 re-import changes only N2/H2S 0.1641 to 0.1652 (with its source text) and `dwsim_refinery_pr78` d3-r1 to d3-r2 (checked by diff). N2/CO2 is -0.0193, N2/benzene 0.1641, no N2/H2O pair.
- Test fixtures: The pilot overlay's CO2 preset is removed (it duplicated the bundled id). `TiaJuanaTestCatalog.thermo` and `FluidServerBenchmark` no longer build the column on the whole refinery.

## Numbers against the O11 probe (`RefineryCarbonDioxideCrystalTest`)
| | probe | applied |
|---|---|---|
| model triple point | 216.5915 K, 515508 Pa (-0.47 %) | 216.5915 K, 515507.9 Pa |
| sublimation at 101325 Pa | 194.6359 K (S-W 194.6855) | 194.6359 K |
| worst p_sub error, 150 K to T_t | 3.836 % at 150 K | 3.836 % at 150 K |
| dH_sub at 195 K | 25173.9 J/mol | 25173.9 J/mol |
| pilot spine family | 216.5916 K, 516048 Pa, 194.6278 K, 2.838 %, 25185.0 | unchanged |
- On the network, a (Methane, CO2) view sublimes at 194.6359 K with 25184.17 J/mol. That is the family's value within 0.5 J/mol, with offset 0.

## Identity
- Without CO2 (the 19-compound crude view, and CH4/C2H6/H2S/benzene) nothing moves, compared with the bundled catalog minus CO2: the scientific, fluid and network revisions, and TP flashes bit for bit (`RefineryCarbonDioxideCatalogTest`).
- State only, for the owner:
  - The bump to `dwsim_refinery_pr78` d3-r2 moves the scientific identity of every view where that library gives a pair, not only N2+H2S views. Numbers move only with N2+H2S.
  - The pilot's fluid identity now names the volume-point record, because the Tr 0.7 CO2 point shares the `liquid_volume_reference_points@chemsep-8.3-tr07-r1` token. The pilot's numbers do not change.
- The O11 probe in `tools/open-thermo-data/o11/` is the record at c254b785d and no longer runs on this tree. The test pins its numbers.

## Tests run and why (lane 2, `--tests` only, at most 4 workers)
- The changed classes and their loaders (191 classes, 1123 tests). This run found 112 failures from whole-refinery column builds and plain-string component edits; fixed above. Rerun: 17 classes, 192 tests, 2 failures (RefineryImportTest); then 3 of 3 pass.
- `columnGates`, because CrystalPackageConsumersTest and TiaJuanaTestCatalog changed: 4 classes, 21 tests, all pass.
- chain-100 exact: 0 deviation.
- Quick check (200 seeds, 4 workers) against `tmp/phuv-roundtrip/land/quick/`: every seed line identical except ms. Cold random 197 clean (E1 85, E6 101, F 118), bundled random 200/200, bundled defaults 200/200. No new hold class, no worse world.

## Open items
- At merge, the code guide: `equilibrium-engine.md` (CoefficientIdealGas, the crystal ideal-gas source, offset 0 on the fit path), `fluid-science.md`, `thermo-materials.md` (crystals on fit packages, refinery r3), and the `README.md` code map.
- Final lane: the fluid GameTests (`FluidServerBenchmark` edited), `equipmentGates`, `holdGates`, and `test slowGates`.
