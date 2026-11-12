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

## 11. Owner decision (2026-11-12)

"reuse w5": the build goes to the live sub-thread `w5-column` (Opus high, the owner's choice of thread; section
10 asked for a new one on Opus medium). Its brief is sections 1 to 9.

- Choice 1: no answer given. The main thread takes a (the example sheet `ethanol_rectifier`), its
  recommendation. It is the build's last step, so the owner can still drop it.
- Correction to D1: `selection-tier1.json` carries no thermochemistry form. The heat data take the route of the
  33 defined compounds: a `thermochemistry_only` entry with its `limits`, as in
  `selection-thermochemistry-existing.json`, inserted with `insert-subrecord.mjs`; then `heating-values.mjs`.
- Addition to D4: the importer writes a Tr = 0.7 liquid reference point for every imported compound. It is not
  copied to the main data, as the pilot's was not.
- Addition to D2: the overlay changes in the same commit as the records (W6a), since one catalog cannot hold
  two `Ethanol` components.

## 12. W6a committed; W6b's stop on positional fixtures (2026-11-12)

W6a `e1cc5ceca`: the main record, its heat data, the overlay on the main compound. G1: 0 differences from the
pilot record. Heating value 1277.512 kJ/mol; NIOSH read: 3.3 % and 19 %. W6-0: an eighth preset fits (a
scrolled list); before numbers 1725 kernel evaluations (dry scenario), 249 fits and 453 ms cold (whole list).

W6b's run of the affected classes: 1729 tests, 131 failed. No production code and no physics moved.
- 118 from one test helper, `TiaJuanaTestCatalog.thermo`: it builds its column view as the package's prefix
  before the crystals' compounds and pads feeds by position.
- `CoolingTowerRuntimeTest`: its fixture pumps `oil[length-3]`, ethyl mercaptan before, CO2 now.
- `FluidFactsCompatTest`, "batch stills": the recorded string lists still accounts by position; by id the
  amounts are equal.
- `RefineryImportTest`: its catalog has no ethanol and refuses the new preset.
- The rest are count and identity pins, expected.

Decision (main thread, inside the approved task): keep D3 and address compounds by id in the fixtures. Putting
ethanol before CO2 would move CO2's index and fix only the helper. Conditions sent to the sub-thread:
- The helper's view is the list it was before W6: the package's compounds that are not a crystal's and do not
  name a homomorph. A view ending in ethanol at zero amount would take the activity branch on about 44 pairs
  (3.6 to 4.6 times a kernel call) on a list no world runs. The 118 must pass with no number re-pinned.
- A fixture that runs an island on the whole list moves to the island's view, as production does.
- The corpus expectations are projected by id onto each image's saved basis, not regenerated; images untouched.
- `RefineryImportTest` keeps its guard (importer output equals the main records).
- Named before the commit: what in production reads the package record's revision (`AqueousSolventDataTest`
  guarded it in W3b; `PilotCryogenicCatalogTest` pins an identity string with it). A saved identity that compares
  it must show no restatement for an image saved at r3 (G7).

## 13. W6b committed; W6c's stop on the load time (2026-11-12)

W6b `691552f3f`: ethanol is the last compound of `icheme:refinery` (r4), with its preset and name. The six
conditions of section 12 are met: the 118 pass with no number re-pinned; the 20 failing classes pass (212 tests,
34.2 s against 36.7 s on main); every corpus image loads, its still accounts read by id. The package record's
revision is read only by a GUI label and a placeholder: no saved identity, no learned start. 96 test files still
build networks on the whole list (production narrows each island); left, since their numbers did not move.
Re-pins: counts and identity strings, with the cause "W6: ethanol joins icheme:refinery"; one stale water pin
(r3, r4 since e1b9a3dd0).

W6c's gates (uncommitted at the stop), measured:

| Gate | Plan | Measured |
|---|---|---|
| G2 pure ethanol | 351.4 K within 0.5 K; 795.5 kg/m3 | 351.698 K; 795.459 kg/m3 |
| G3 wash | equal to the pilot within 1e-9 | 92.574318 C, 0.256679: equal |
| G4 heating value | 1277.5 kJ/mol within 0.5 % | 1277.512 kJ/mol; C2H6O 46.068 g/mol |
| G5 burner | heat = rate x heating value | 6275 kW at 27.731 MJ/kg |
| G6 preset | one compound at 1.0 | yes |
| G8 no-ethanol cost | counters equal | identical (1725 kernel evaluations) |
| G9 load | report; stop above +0.5 s | fits 249 to 291; 0.35 to 0.45 s before, 1.52 to 1.75 s after: +1.2 s |
| G10 still, sheet | 92.57 C; 0.8075 | 365.9265 K, the pilot's exactly; example STEADY at 0.80747 |

G9 is over its bound. What the code does: ethanol's 32 activity fits take 1.40 s (44 ms each; 58 to 80 ms with
the aromatics, butadiene and water). Water's 31 take 0.34 s (11 ms each). The cause, from
`DerivedPairs.Context.shape`: where the group model has one liquid, each trial of the fit also scans 99 points
for a false liquid split (`falls`, `SCAN_POINTS`), on top of the 15 grid points: 114 evaluations a trial against
15. Water with a hydrocarbon has two liquids in the group model and skips the scan. The cost is paid once per
catalog: at world start, on `/reload`, and at the client's first simulator open (the render thread). A list
without ethanol pays nothing.

Decision (main thread): W6c is committed as it stands and W6d follows; W6 is data, and the fit and the load
path stay. The plan's estimate (section 4, "about 45 more group fits", a fraction of a second) was wrong by the
scan's cost. Each later solvent compound would add about as much, so the load needs its own change. Proposal
for the owner, in this order:
- a. Fit the activity pairs of a list in parallel before the assembly (`DerivedPairs.derive`: the fits are pure
  and kept by pair in `Cache`). No number moves, no revision. Expected on this machine: 1.7 s to about 0.3 s.
- b. Derive the whole list's pairs only when something flashes on it (production flashes on views). A larger
  change to the load path; only if more solvents make a still too slow.
- c. Cut the scan (fit without the penalty first, add it only where the optimum splits). It moves the fitted
  numbers in the last digits: a revision of the derivation and re-pins. Not recommended now.

## 14. W6 built (2026-11-12)

Sub-thread `w5-column` (Opus high). Commits: W6a `e1cc5ceca`, W6b `691552f3f`, W6c `ca8b9c92e`, W6d `031d96c25`.
Report: `tmp/w6-ethanol/reports/W6_FINAL.md`. The main thread checked the commits: main changes are data only
(the record, the component, the package, the preset, the network's list, the index, the example sheet, two
`en_us.json` keys); no main Java changed.

- Gates G1 to G8 and G10 pass, with the numbers of section 13. G1: 0 differences from the pilot record. G7:
  `FluidFactsCompatTest` passes, images and recorded strings untouched.
- G9 is over its bound and accepted for W6 (section 13): 0.35 to 0.45 s before, 1.52 to 1.75 s after.
- The record: heat of formation -234.95 kJ/mol, C2H6O, limits 0.033 and 0.19 (NIOSH npgd0262, read
  2026-11-12), heating value 1277.512 kJ/mol.
- New tests: `EthanolMainDataTest`; cases in `FiredHeaterFuelTest`, `StillRuntimeTest`,
  `FlowsheetAqueousColumnTest`. `PilotSolventTestCatalog` is retired: its tests run on `icheme:refinery`.
- The example `ethanol_rectifier`: a wash at 25 C and 2 bar, a steam preheater to 359.84 K, the E1 rectifier;
  STEADY, spirit 0.80747. One name key; the picker shows names only. `WriteRectifier.java` in
  `tools/aqueous-solvents/probes` writes it again when the data move.
- Guide: `thermo-materials.md` (the records, the counts, the load cost), `process-simulator.md` (the example),
  MATERIALS.md (the counts).

Where the measurement contradicted this plan:
- Section 1's boiling point 351.4 K: the cubic gives 351.70 K (inside the bound).
- Section 4's load estimate (section 13).

Open from W6:
- The whole list's load time; the parallel fit of section 13 waits on the owner.
- 96 test files run networks on the whole list, where production narrows each island.
- Two `en_us.json` keys for the translation commit: `material.icheme.ethanol`,
  `gui.icheme.flowsheet.example.ethanol_rectifier`.
- Not run: `LanguageFilesTest`, `UnitTextTest` (the translation commit), the final lane, `crude_unit`'s re-record.

## 15. Owner decision: the activity fits in parallel (2026-11-12)

"Approve": proposal a of section 13, built by the live sub-thread `w5-column` (Opus high).

- What changes: `DerivedPairs.derive` gains a first pass over the list's pairs. It finds the activity pairs
  whose fit the catalog's `Cache` does not hold and runs those fits on several threads. The loop that assembles
  the pairs is unchanged and finds every fit kept. One fit alone runs in the loop as before.
- Why no number moves: a fit is a function of its two compounds' records and the group table alone. Each fit
  builds its own `Binary` (kernel, workspace) and its own group mixture. The cache is keyed by pair.
- Expected on this machine (16 processors): the whole list 1.52 to 1.75 s to about 0.3 s; without ethanol it was
  0.35 to 0.45 s. Fits run stay 291. No revision of the derivation, no re-pin.
- Pitfalls named in the brief: a fit that throws must throw from the same place as now (the first pass leaves
  it to the loop); the first pass adds no notice; a shared mutable field on the fit's path; a class
  initialiser that reaches `derive` while a fit thread needs that class.
- Gates: a dump of every pair's parameters in hexadecimal, before and after, with no difference; a test that
  the whole list's pairs equal each pair derived alone; `LoadFits` at or under 0.5 s, run alone.
