# Removing the cdu17 test package: review

Plan: [CDU17_REMOVAL_PLAN.md](CDU17_REMOVAL_PLAN.md). Base `ee13d1034` (0.34.1). Branch `claude/priceless-carson-4f1d4c`.

## Summary

- The test-only package `icheme:cdu17_tjl_acs2018` is gone: its 40 resource files, `Cdu17TestCatalog`,
  `Cdu17FixtureExtension`, `V3Cdu17TiaJuanaPackage` and its test, the two `build.gradle` gate-list entries, and its
  16 records in each of the two DWSIM viscosity reference files. No file under `src/main` changed.
- 35 test classes moved to the medium crude preset (`icheme:tjl20_methane`, `icheme:medium_crude`): 8 thermo, 24
  column, `ColumnWireTest`, `MaterialCatalogTest`, `ViscosityTest`. None is disabled. One test class is deleted
  (`V3Cdu17TiaJuanaPackageTest`, 7 tests of the fixture's own data; the data are gone, so no cover is needed). One
  test line is deleted (`ViscosityTest`, the cdu17 "no liquid viscosity" line; the Water vapour line covers the
  same path).
- Step 0: the medium crude is one liquid at all 24 cold states (tm 0). The same probe on the removed package finds
  a second liquid at all 24 (tm -5.7 to -0.09). The "cold Tia Juana second liquid" open item closes.
- New fixture: `V3ZeroPairCrudePackage`, the medium crude's constants with zero binary pairs, so the kernel takes
  the rank-one path. It replaces `V3Cdu17TiaJuanaPackage` in the kernel and derivative tests.
- New helpers in `V3TestInputs`: `MEDIUM_CRUDE_PACKAGE`, `MEDIUM_CRUDE_ASSAY`, `mediumCrudeInput`, `mediumCrudeDry`
  (the preset's 29-stage dry column with its three draws), `mediumCrudeThermo`, `index`, `crudeBinary`,
  `crudeBoilingBinary`. Components are found by id, never by position.
- One design change beyond the plan: the binary. The plan's `crude_pc03`/`crude_pc10` with zero duty fails on every
  pair tried (450 to 570 K). The medium crude's `crude_pc10` boils at 868 K (the fixture's PC10 at 709 K), and with
  no reboiler duty the tray below the feed has no vapour. `crudeBinary` is `crude_pc02` (383.5 K) / `crude_pc06`
  (577.9 K) with a 0.2 MW reboiler: 18 of 18 one-factor cold-start cases converge. The two tests that need a
  collapsed numerical root keep a zero-duty `crude_pc03`/`crude_pc10` binary of their own.
- The medium crude carries methane and ethane; the removed feed did not. Three assertions follow from that: the
  two low-pressure total-condenser cold starts become partial-condenser tests, the cold steam pilot's condenser is
  TWO_PHASE, and the condenser phase transition at 110 kPa happens only without the trace cutoff.
- Frozen numbers re-recorded: 4 thermo states, the closure pins of cases A, B, D, E, the operating-domain package
  floor (50 kPa to 1 kPa), `invalid-binary-roots.json` (captured again by the new
  `tools/vacuum-column-test/capture-roots.sh`), and the wet pumparound bound. Each old and new value is in the
  table below.
- Runs (`--max-workers 4 --offline`, one invocation at a time): each class alone while it moved; then once on the
  final `V3TestInputs`: `columnGates` 44 classes 302 tests, `test` 5 classes 54 tests, `nativeSmoke`
  `NativeColumnSolverTest` 4 tests (library built, none skipped). 360 tests, 0 failures, 0 skipped.
- `affected.mjs select --since ee13d1034` selected `test` 304/506, `holdGates` all, `columnGates` 77/89,
  `equipmentGates` 1, `fluidSolverRegression`. Almost all of it comes from one heuristic: the deleted
  `materials/cdu17-reference/data/icheme/...` paths match a `MaterialCatalog` path constant, so every class that
  loads `MaterialCatalog` is selected. `MaterialCatalog.bundled()` never reads those files. The run was narrowed to
  the classes whose selection reason is a changed class or resource they load (listed under "Runs").
- `grep -rIl -iE cdu17 src build.gradle` leaves one hit: `src/main/.../V3FeatureRamp.java` lines 62 and 557,
  comments naming the measured CDU17 cases. `src/main` is out of scope; the owner decides (open item 1).
- Code guide: `column-v3-solver.md` and `materials.md` name the new fixture and the preset helpers.

## Step 0: the medium crude is one liquid when cold

Probe: `tools/supercritical-probes/p3/.../P3MediumCrudeProbe.java`, `bash tools/supercritical-probes/p3/run.sh medium
tmp/<name>`. Bundled catalog, package `icheme:tjl20_methane`, assay `icheme:medium_crude`, dry. Per state: the production
flash (`V3FeedFlash.resolve`), then `TangentPlaneStability` on its liquid (the flash's liquid, or the feed when it is one
liquid) on the column's own kernel, exhaustive, at four times the default trial budget, with every vapour-root trial
excluded. A negative distance there is a second liquid.

Expected: one liquid everywhere (worst cut pair 4 to 6 RT, diluted by the intermediate cuts).

Result: **one liquid at all 24 states**. No liquid trial lowers the Gibbs energy (distance 0 at every state). The two
crudes split off vapour only at 0.1 MPa (the dissolved light ends).

| Mixture | T (K) | P (MPa) | Flash | Vapour fraction | Liquid test | tm, liquid trials |
|---|---|---|---|---|---|---|
| medium crude | 280 | 0.10 / 0.25 / 1.00 | two phase / liquid / liquid | 0.000107 / 0 / 0 | stable | 0 |
| medium crude | 300 | 0.10 / 0.25 / 1.00 | two phase / liquid / liquid | 0.003095 / 0 / 0 | stable | 0 |
| medium crude | 320 | 0.10 / 0.25 / 1.00 | two phase / liquid / liquid | 0.009359 / 0 / 0 | stable | 0 |
| medium crude | 350 | 0.10 / 0.25 / 1.00 | two phase / liquid / liquid | 0.034955 / 0 / 0 | stable | 0 |
| 565 C+ / naphtha 50/50 mass | 280, 300, 320, 350 | 0.10 / 0.25 / 1.00 | liquid | 0 | stable | 0 |

The 565 C+ end is `crude_pc10..12` in the assay's proportions, the naphtha `crude_pc01..03`; the blend holds 11.3 mol %
of the heavy end.

Control (scratch, not committed): the same probe on the cdu17 fixture before its removal (`cdu17_pc` ids, assay
`icheme:tia_juana_light_kl1976`) finds a second liquid at all 24 states: tm -5.7 (280 K) to -0.39 (350 K) on the whole
crude, -3.6 to -0.09 on the blend, the trial liquid 45 to 95 mol % `pc10..12`. The probe detects the defect the plan
names, and the medium crude does not have it.

The "cold Tia Juana second liquid" open item of batch 2026-11-16-supercritical-followups closes with this batch: it was
a property of the fixture, and no data change is needed.

## Classes

"Ext" is the class's catalog extension before -> after. All tests pass after the move; the count is after.

| Class | Tests | Ext | Frozen numbers, old -> new | Design adjustment (rule 7) |
|---|---|---|---|---|
| V3PengRobinsonThermoTest | 5 | Cdu17 -> none | hot state 901 K -> 921 K (the medium crude is 99.75 % vapour at 901 K, 250 kPa); captured-feed vapour fraction 0.7855276025697608 -> 0.6382013326899407 | basis 16 -> 19 components; captured feed carried by id (C4 lump onto N-butane) |
| V3PengRobinsonKernelTest | 4 | none | none | `V3ZeroPairCrudePackage` replaces `V3Cdu17TiaJuanaPackage` |
| V3PengRobinsonDerivativesTest | 12 | Cdu17 -> TiaJuana | none | package parameters {tjl19, cdu17} -> {tjl19, tjl20_methane}; refusal composition by id |
| V3FeedFlashEquivalenceTest | 3 | Cdu17 -> TiaJuana | endpoint 110 kPa -> 90 kPa (at 110 kPa Wilson calls it liquid with 0 iterations; at 90 kPa the PR endpoint converges in 40) | 30 states on tjl20_methane |
| V3FlashCheckpointTest | 4 | none | none | Ethane/crude_pc12 by id |
| V3FlashTruncationTest | 6 | none | hot state 638.15 K -> 740 K at 137.25 kPa (crude_pc12 vapour 2.0e-9 -> 4.5e-6, NO_CANDIDATES) | indexes by id |
| V3TruncatedFlashNumericsTest | 12 | none | none | domain refusal on the medium crude |
| RegroupedCrudeTest | 5 | none | none | the assertion that the bundled catalog lacks the cdu17 package is removed |
| V3AggressiveStrategyTest | 2 | Cdu17 -> TiaJuana | none | reads the literature preset only |
| V3ColumnDisplayResultTest | 1 | none | none | `crudeBinary` |
| V3PotColumnTest | 1 | none | none | binary cuts by id; duty 0 -> 0.2 MW |
| V3FlashTruncationColumnTest | 1 | none | none | medium crude feed, same 30-stage dry design |
| V3OperatingDomainValidatorTest | 5 | none | package floor 50 kPa -> 1 kPa (the package declares 1 kPa to 2 MPa): boundary 50,000 -> 1,000 Pa (max node 71,750 -> 22,750 Pa), rejection 1,000 -> 999 Pa, preflight 49,999 -> 999 Pa | none |
| V3ColdStartTest | 5 | none | matrix duty 0 -> 0.2 MW, duty edits 0.02/0.05 -> 0.1/0.3 MW; 22/22 accepted (assertion >= 5) | collapsed-root case: own zero-duty crude_pc03/crude_pc10 binary at 551 K (ACCEPTANCE_AUDIT_FAILURE on INTERIOR_PHASE_IDENTITY, residual 2e-14); two total-condenser tests become partial-condenser tests, renamed `...WithTheLightEndsAsOutletGas` |
| V3ExactWarmStartSweepTest | 2 | none | `invalid-binary-roots.json`: 10 rows (5 TWO_PHASE, 5 unused LIQUID_ONLY) -> 5 TWO_PHASE rows, residual 2e-14 to 6e-14, rejected on INTERIOR_PHASE_IDENTITY and CONDENSER_PHASE | own crude_pc03/crude_pc10 binary by id (`binaryInput`, now package-private); positive case 600 K / 500 kW accepted |
| V3SimultaneousColumnSolverTest | 8 | none | none | medium crude 4-stage column; captured-root case uses `binaryInput` |
| V3StageTraceCalculatorTest | 5 | none | none | medium crude 30-stage dry column; `crudeBinary` |
| V3ColumnInitializerTest | 9 | none | none | zero-aware seed case zeroes the medium crude's methane (the old feed had none) |
| V3ConvergenceClosureTest | 16 | Cdu17 -> TiaJuana | pins: A 0 -> 1 iteration, B 2 -> 3, D 7 -> 0, E 3 -> 3; paths lose `/liquid-only-condenser` (A, B, E) and `/condenser-phase-correction` (D); C (TJL19) unchanged; loose bounds A 1, B 3, C 3, D 0, E 3 | none |
| V3BlockJacobianAssemblerTest | 22 | none | none | mechanical move |
| V3CarryOverEntrainmentTest | 3 | none | none | mechanical move |
| V3EnergyShiftPredictorTest | 6 | none | none | mechanical move |
| V3PathDependentInfeasibilityTest | 3 | none | none | mechanical move |
| V3SideDrawCalculatorTest | 7 | none | none | near-feed draw binary -> binary cuts by id |
| V3TraceFloorSupportTest | 2 | Cdu17 + TjlReference -> TjlReference | none | pilot draws -> `mediumCrudeDry` draws (same rates) |
| V3StageContinuationTest | 11 | Cdu17 -> TiaJuana | none | mechanical move |
| V3CondenserPhaseTransitionTest | 8 | none | none | 110 kPa client point: with the 1e-6 trace cutoff the first rung starts two-phase (no transition event); the event is asserted for cutoff 0 only |
| V3ColumnCalculatorTest | 13 | none | none | `pilotInput` -> `mediumCrudeDry`; cold 323 K steam pilot's condenser LIQUID_ONLY -> TWO_PHASE (free water still published) |
| V3PumparoundCalculatorTest | 13 | Cdu17 -> TiaJuana | none; 40 MW checkpoint ceiling 525,000 holds | `v1ScaleInput` on the medium crude; `pilotInput` -> `mediumCrudeDry` |
| V3PumparoundSteamCalculatorTest | 7 | Cdu17 -> TiaJuana | bound "0 < removed < 5 MW" -> "0.6 x 5 MW < removed < 1.05 x 5 MW"; measured 5.039 MW (ledger closes at -1.8e-16 relative; +39 kW leave in the partial condenser's gas) | none |
| V3Sotelo2019SideDrawCaseTest | 2 | none | none | feed = medium crude; published geometry, pressures and flow fractions kept |
| NativeColumnSolverTest | 4 | none | none | four-tray column on the medium crude; Java and Rust bit-identical |
| ColumnWireTest | 17 | none | none | `pilotInput` -> `mediumCrudeDry` (same trays and rates) |
| MaterialCatalogTest | 15 | Cdu17 -> none | none | separate-datasets case compares the medium crude's Ethane (`icheme:tjl19_ethane`) with the cryogenic pilot's (`PilotCryogenicTestCatalog.withPilot()`) by record id and lower temperature bound |
| ViscosityTest | 8 | Cdu17 -> none | none | the cdu17_pc01 line deleted (Water vapour line covers it); the ambient skip of cdu17 ids removed with the records |
| V3Cdu17TiaJuanaPackageTest | deleted (7) | - | - | tests of the fixture's own data |

## Printed lines

Every printing class in the run now prints from the medium crude where it used the removed package, so these are
new lines, not moved ones; no old line is comparable. The classes: `V3ColdStartTest` (cold-start matrix, all
SUCCESS), `V3PumparoundCalculatorTest` (40 MW case 99,486 checkpoints under the 525,000 ceiling),
`V3PumparoundSteamCalculatorTest` (ledger closure -1.863e-08 W; removed 5.039 MW of 5 MW), `V3SideDrawCalculatorTest`,
`V3StageContinuationTest`, `NativeColumnSolverTest` (20 band solves on the library). `V3TraceFloorSupportTest` and
`RegroupedCrudeTest` print from TJL19 and the bundled presets, whose inputs did not change.

## Runs

- Per class while moving: each class above alone, in its own task (`columnGates`, `test`, `nativeSmoke`).
- Final selected run, once, on the final `V3TestInputs`: `columnGates` with the 44 column-gate classes that load a
  changed test class or resource (the 45 `affected.mjs` names for a reason other than the path heuristic, minus
  `NativeColumnSolverTest`, which `columnGates` excludes); `test` with `ColumnWireTest`, `V3InspectionCodecTest`,
  `V3ColumnInputDraftTest`, `MaterialCatalogTest`, `ViscosityTest`; `nativeSmoke` with `NativeColumnSolverTest`.
  All three exit 0: 302 + 54 + 4 tests, 0 failures, 0 skipped.
- Not run: the final lane, `holdGates`, `equipmentGates`, `fluidSolverRegression`, the GameTests, the campaign. The
  fluid network and equipment do not load the removed package; they are selected by the path heuristic only.
- `compileTestJava` passes after the deletions; `git ls-files -i -c --exclude-standard` is empty.

## Open items

1. Closed at the merge: the two `V3FeatureRamp.java` comments (lines 62 and 557) name a crude case, not the fixture
   (`44efaaaad`, comments only, the measured reasons kept).
2. `affected.mjs` selects every `MaterialCatalog` user when a file under `materials/` is deleted, because a path
   constant matches the deleted test resources. A fix in the tool would narrow the next lane.
3. The final lane's reference lines for the moved column tests are new; the next final lane records them.
