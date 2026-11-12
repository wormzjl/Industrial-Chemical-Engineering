# W6: ethanol in the main data

Batch 2026-11-11-aqueous-solvents, work package W6 of [AQUEOUS_SOLVENTS_PLAN.md](AQUEOUS_SOLVENTS_PLAN.md). It
follows W3 (engine and network), W4 (still) and W5 (V3 column), which run on the test overlay `pilot-aqueous`.
Owner, 2026-11-12: "Write W6 design". A design; not built.

## 1. Expected behaviour

Ethanol is one more compound of the world's package. A player takes it from a creative source, mixes it with
water, boils it in a still, rectifies it in a column and burns it. Numbers the main data must give:

| Quantity | Expected | Source |
|---|---|---|
| Normal boiling point | 351.4 K, within 0.5 K | PR78 on Tc 513.92 K, Pc 61.48 bar, omega 0.649 |
| Liquid density, 15.6 C | 795.5 kg/m3 (the volume anchor) | the record's standard density |
| The 10 % wash of W4, 1 atm | boils at 92.57 C; first vapour 0.2567 ethanol | W4, pilot record |
| Azeotrope, 1 atm | 0.8965 ethanol (measured 0.894) | W4 |
| Rectifier E1 (W5) | distillate 0.8075 | W5, pilot record |
| Lower heating value, gas basis | 1277.5 kJ/mol (27.73 MJ/kg), within 0.5 % | Hess: 2 CO2 + 3 H2O(g), ethanol -234.95 kJ/mol |
| Liquid ethanol burnt | about 1235 kJ/mol (26.8 MJ/kg) | the liquid's enthalpy is 42 kJ/mol under the gas |
| Flammability in air | 3.3 % to 19 % by volume | NIOSH Pocket Guide, ethyl alcohol |

The main record holds the pilot record's phase data unchanged, so every number of W3 to W5 stands.

## 2. What the code does now

- Ethanol is in the test overlay only: `components/ethanol.json`, `properties/pilot_ethanol.json`, the package
  `pilot_aqueous`. No main package names it. `en_us.json` has no `material.icheme.ethanol`.
- The pilot record has no `thermochemistry`, `flammability` or `combustion`: ethanol cannot burn.
- `icheme:refinery` (`refinery-tier1-r3`, the network's package) holds 45 compounds; the world list is these
  and water, which `MaterialCatalog.withWater` appends on `icheme:cubic_water`.
- `networks/default.json` lists seven fluid presets; a preset is one compound or one assay.
- An island runs on the view of its own compounds (`FluidThermodynamics.on`), a sheet on its feeds' compounds
  (`SheetCompounds`), a column on one of the two. The whole 45 list is never a column's (its CO2 crystal).
- A package's identity lists the records of its compounds. The package record is no part of it, so a compound
  added to the package moves no identity of a view without it (`MaterialCatalog.Package`, option B of 2026-11-04).
- The campaign's worlds draw water, methane, nitrogen and crude on fixed ids (`HoldFuzzTest`), not the presets.

## 3. Design

- D1, the records. `properties/chemsep_ethanol.json` (`icheme:chemsep_ethanol`, revision
  `chemsep-8.3-dwsim-10.2.5-r1`): the pilot record's fields with the same values and sub-record revisions, and
  three more sub-records: `thermochemistry`, `flammability`, `combustion`. `components/ethanol.json` moves from
  the overlay and names it. Written by `tools/compound-import/import.mjs` from a new
  `selection-aqueous-ethanol.json` (the pilot selection's ethanol entry, prefix `icheme:chemsep_`,
  `no_pair_library`, the thermochemistry form of `selection-tier1.json`) on `tmp/dwsim-groups`, then
  `tools/heating-values/heating-values.mjs`. Flammability from the NIOSH page, read at the build.
- D2, the overlay. Its two ethanol files are deleted; `pilot_aqueous` and `PilotSolventTestCatalog` name the
  main compound. One `Ethanol` in the catalog. The pilot package and method stay: W3 to W5's gates run on them.
- D3, the package. `refinery.json` gains `"Ethanol"` as its last entry, after `CarbonDioxide`; revision
  `refinery-tier1-r4`. No index of an existing compound moves. No pair library, no method edit: pairs are derived.
- D4, no liquid reference point and no dissolved-viscosity curve. The volume anchor is the standard density
  (`CubicPhaseEvaluator`: no point, so molar mass over density), as in the pilot.
- D5, the preset. `presets/fluid_7.json`: `{"id": "ethanol", "kind": "fluid", "label": "Ethanol", "component":
  "Ethanol"}`; `networks/default.json` lists it after `carbon_dioxide`. A wash is mixed from two sources.
- D6, names and index. `material.icheme.ethanol`: "Ethanol" in `en_us.json` only. `materials-index.json` gains
  three files (127 to 130).
- D7, fuel. No code: fired heaters, the still's heat, engines, gas turbines and the flare read `combustion`
  and `flammability` from the record.
- D8, `MaterialPresets`' rule stays: a network package names no Water; water joins by `withWater`.
- D9, saves. No facts field, no schema change, no corpus image. The world list grows by one id; facts are read
  by compound id.
- D10, guide. The compound counts in `documentation/reference/code-guide/thermo-materials.md` and MATERIALS.md.

## 4. What moves, and what it costs

- Islands and sheets without ethanol: nothing. Same view, same identity, same kernel.
- The whole-list model (`FluidThermodynamics.whole`): one more compound and its derived pairs. Its derivation
  runs about 45 more group fits at load. Measure it once (section 6, G9).
- A list with ethanol and N other compounds carries N activity pairs. Ethanol and water: one pair (network
  polar/dry cost 1.34, W5). A list of 10 with one such compound measured 3.6 to 4.6 times a kernel call (W5a:
  methyl mercaptan before the limit). A crude
  sheet with ethanol pays this; it is the price of the mixture, not of the data.
- Memory: every amount array on the world list is 47 long instead of 46 (2 %).
- Tests that pin counts: `MaterialThermochemistryTest` (45, 46, 40), `RefineryImportTest`, `MaterialCatalogTest`,
  `RestrictedPackagePhysicsTest`; tests that pin an identity string naming `icheme:pilot_ethanol`.

## 5. Known deviations kept (game level)

- Ethanol and water's liquid viscosity has a measured maximum of 2.9 mPa s near 25 mol % at 20 C; the mixing
  rule gives 1.0 to 1.2. A laminar line reads up to 2.4 times low in pressure drop, a turbulent one about 25 %.
- Ethanol and water shrink up to 3 % on mixing; the model's volume is the cubic's.
- Solid ethanol (159 K) is data only: no crystal record.
- No learned start for a column feed with ethanol; the classical start runs (W5).
- No process makes ethanol: the creative source is its only origin.

## 6. Gates

| Gate | Passes when |
|---|---|
| G1 record equality | every field of `chemsep_ethanol` that the pilot record has is equal to it (a script, by JSON value) |
| G2 pure ethanol on `icheme:refinery` | boils at 351.4 K within 0.5 K at 1 atm; density 795.5 kg/m3 within 0.1 % at 288.71 K |
| G3 wash on `icheme:refinery` | view of ethanol and water: bubble point and first vapour equal W4's within 1e-9 relative |
| G4 heating value | `combustion` 1277.5 kJ/mol within 0.5 %; elements C2H6O give the molar mass within 0.02 g/mol |
| G5 burner | an existing fuel test burns ethanol: heat = rate times the heating value times the burner's efficiency |
| G6 preset | `FluidPresetCatalog.resolve` gives `ethanol` as one compound at 1.0 |
| G7 saves | `FluidFactsCompatTest`: every corpus image loads; amounts by id unchanged; no restatement booked |
| G8 no-ethanol cost | one existing dry scenario: kernel call counters equal before and after |
| G9 load | whole-list derivation: fits run and time, before and after, run alone; report both |
| G10 sheet and still | the W5 sheet wash and the W4 still wash, on `icheme:refinery`: 0.8075 and 92.57 C |

## 7. Owner choice

1. An example sheet for the simulator (`flowsheet_examples` holds only `crude_unit`):
   - a. Add `ethanol_rectifier`: a wash feed, a preheater, the E1 column, two products. One recorded reference,
     two `en_us.json` keys. Recommended: without it nothing in the game shows the feature.
   - b. None: tests only.

## 8. Failure modes to expect

- Two `Ethanol` components at overlay load, if the overlay's files stay (D2).
- `RefineryImportTest` or `regression.mjs` compares main records with importer output: the new selection joins it.
- A test that builds the whole `icheme:refinery` kernel and asserts no activity pair or a constant matrix.
- The creative source's preset list drawn for seven entries. If the eighth does not fit: stop and report (GUI
  work needs the `gui-creation` skill and its own approval).
- The NIOSH page unreadable: stop and report; do not write a value without its source.
- A corpus image restates (G7): the identity moved where section 2 says it does not. Stop and report.

## 9. Build order and commands

1. W6-0, read only: the overlay's loader on a duplicate id; the source screen's preset list; who builds the
   whole-list model at load. G8's and G9's "before" numbers.
2. W6a, records: selection, import, heating value, G1. Commit.
3. W6b, package, overlay, preset, index, name. Run by filter the classes `tools/merge-lane/affected.mjs --since
   HEAD` lists for the changed files (`test`, `holdGates`, `columnGates`, `equipmentGates` groups), at most 4
   workers on a second lane. Re-pin counts with their cause. Commit.
4. W6c, the new gates G2 to G10 (one test class `EthanolMainDataTest`, cases added to `StillRuntimeTest` and
   `FlowsheetAqueousColumnTest`), and the example sheet if chosen. Commit.
5. W6d, code guide and MATERIALS.md counts. Commit.

No whole `test`, `slowGates` or `check`; no campaign (its worlds hold no ethanol). The batch's final lane is later.

## 10. Delegation request

**Sub-thread** (`start_session`, 1-h cache), new, fresh brief: builds W6-0 to W6d as above; done is the four
commits, gates G1 to G10 reported beside this table, the tree clean. Not main-thread work: an importer run and
about eight filtered Gradle runs to wait on, about 2 hours. Model and effort: Opus medium (standard: data
records and tests on a clear brief; no solver change). Not a reuse: `w5-column` is on Opus high and its cache
will have expired. It reports every 50 minutes, at once on a stop named in section 8, and at the end.
