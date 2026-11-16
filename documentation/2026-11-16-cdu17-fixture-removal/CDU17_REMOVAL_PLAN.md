# Removing the cdu17 test package: plan

Owner, 2026-11-16: "remove that test package entirely and use medium crude preset instead".

Base: `main` at `ee13d1034` (0.34.1). Test code, test resources, `build.gradle`'s gate list, tools and documents only.
No file under `src/main` changes.

## 1. Why

- `icheme:cdu17_tjl_acs2018` is a test-only package (`src/test/resources/materials/cdu17-reference/`, 40 files). Its
  heaviest cut, PC12, is extrapolated (advisory `ESTIMATED_HEAVY_RESIDUE`): Tc 1,123 K, Pc 0.372 MPa, omega 1.731,
  b 1,955 cm3/mol. Its PR78 cohesion `sqrt(a)/b` at 298 K is 11.8e3 Pa^0.5 against 20.6e3 (PC01), 17.7e3 (PC06) and
  14.8e3 (PC11). With `k_ij = 0` between cuts (`DerivedPairs`, NONPOLAR) the mismatch is about 60 RT, and PC12
  separates as a second liquid at ambient temperature. That second liquid is the "cold Tia Juana second liquid" of
  the supercritical follow-ups' open items. It is a property of this fixture, not of the game.
- The game's crude is the medium crude preset: `presets/column_medium_crude.json`, package `icheme:tjl20_methane`
  (`packages/tjl20.json`: Methane, Ethane, Propane, Isobutane, N-butane, Isopentane, N-pentane, `crude_pc01..12`,
  with water), assay `icheme:medium_crude`. The cohesion of `crude_pc01..12` runs 19.4, 18.7, 18.0, 17.4, 16.8, 16.3,
  15.9, 15.5, 15.2, 15.1, 15.5, 16.8 (e3 Pa^0.5): the worst pair is about 4 to 6 RT.
- Tests on the fixture measure a crude no player has. Tests on the preset measure the game's.

## 2. What is removed

| Item | Path |
|---|---|
| The package's resources | `src/test/resources/materials/cdu17-reference/` (all 40 files) |
| The fixture | `src/test/java/com/wormzjl/icheme/science/material/Cdu17TestCatalog.java`, `Cdu17FixtureExtension.java` |
| The compiled package and its test | `science/column/v3/thermo/V3Cdu17TiaJuanaPackage.java`, `V3Cdu17TiaJuanaPackageTest.java` (7 tests of the fixture's own data: axis of 16, zero pairs, PC12 constants, C4 split, ACS density) |
| The gate list entries | `build.gradle` lines 628-629 (`science/material/Cdu17FixtureExtension*`, `Cdu17TestCatalog*`) |
| The binary helpers | `V3TestInputs.cdu17Binary`, `cdu17BoilingBinary` (replaced, section 3) |
| Single mentions | `MaterialCatalogTest` line 24, `ViscosityTest` line 28, `RegroupedCrudeTest` line 16 |
| Resource mentions | `src/test/resources/column/invalid-binary-roots.json`, `materials/dwsim-viscosity-api.json`, `materials/dwsim-ambient-viscosity-api.json`: read each; a cdu17 record goes or moves to `crude_pc` ids |

After the change `grep -rIl -iE "cdu17" src build.gradle` returns nothing.

The Tia Juana TJL19 fixture (`TiaJuanaTestCatalog`, `TiaJuanaExtension`, `TjlReferenceExtension`,
`icheme:tjl19_dwsim`, `src/test/resources/materials/tia-juana/`) is not named by the owner and stays. It was measured
stable as one liquid.

## 3. What replaces it

One source: the bundled catalog (`MaterialCatalog.bundled()`), package `icheme:tjl20_methane`, assay
`icheme:medium_crude`, preset `medium_crude`.

New helpers in `V3TestInputs` (the class already has `literatureCduInput`, `methaneCduInput`, `crude`):

- `MEDIUM_CRUDE_PACKAGE = "icheme:tjl20_methane"`, `MEDIUM_CRUDE_ASSAY = "icheme:medium_crude"`.
- `mediumCrudeInput()`: `V3ColumnPresets.input(catalog.presets().column("medium_crude"), catalog)` on the current
  catalog. The whole preset: 40 stages, feed stage 37, 250 kPa, three draws (trays 10, 18, 28), three pumparounds,
  steam at stage 41, condenser 332.15 K, reflux ratio 4.17.
- `mediumCrudeDry()`: the replacement of `Cdu17TestCatalog.pilotInput()`. The pilot's own design (29 stages, feed
  stage 24, 150 kPa, 750 Pa per tray, condenser 400 K, reflux ratio 2, reboiler 8 MW, draws on trays 13, 17, 22)
  fed with the preset's crude: `thermo.crudeFeed(MEDIUM_CRUDE_ASSAY)` scaled to 2,610.7 kmol/h, 638.15 K. Draw rates
  keep the pilot's fractions of the feed (92.3, 131.85, 32.96 over 2,610.7).
- `crudeBinary(assayId)`, `crudeBoilingBinary(assayId)`: `crude_pc03` and `crude_pc10`, 50 mol/s each, the same
  design as today. The two indexes come from `componentBasis().componentIds().indexOf(...)`, never from a number.

Rules for moving a test class:

1. A test of a mechanism (pumparound perturbation, side draw, trace floor, Jacobian, flash truncation, checkpoint,
   derivatives, cold start, initializer, closure, continuation, wire round trip) keeps its own column design and
   takes the preset's package, assay and crude. A test that wants "a crude column" takes `mediumCrudeInput()`.
2. Every component index becomes a lookup by id. The basis grows from 16 hydrocarbons (C1, C2, C3, C4, PC01..12) to
   19 (seven light ends, `crude_pc01..12`). `component(15)` meant PC12: it becomes `crude_pc12`.
3. The fixture annotation goes. A class that also reads `icheme:tjl19_dwsim` or a Tia Juana preset (10 classes:
   `V3FeedFlashEquivalenceTest`, `V3PengRobinsonDerivativesTest`, `V3ColumnInitializerTest`,
   `V3ConvergenceClosureTest`, `V3PumparoundCalculatorTest`, `V3PumparoundSteamCalculatorTest`,
   `V3SimultaneousColumnSolverTest`, `V3StageContinuationTest`, `V3TraceFloorSupportTest`, and the deleted package
   test) takes `TiaJuanaExtension` or `TjlReferenceExtension`, whichever holds what it reads. A class that reads
   only the bundled package needs no extension.
4. A parameterised test over `{tjl19_dwsim, cdu17}` becomes `{tjl19_dwsim, tjl20_methane}`.
5. An assertion of a behaviour (converges, conserves, is monotone, round-trips, Java equals native, a derivative
   matches its finite difference) stays as written. A frozen number recorded on cdu17 is recorded again once on the
   medium crude, with the old and the new value in the review.
6. A moved test that then repeats an existing medium crude test (`ColumnRunTest`, `V3ClientPresetsTest`, the
   `column-preset-inputs.json` cases) is deleted instead, and the review names the test that covers it.
7. A mechanism test that does not converge on the medium crude with its own design is not forced. Allowed
   adjustments, in this order: draw rates as the same fraction of the cut's yield, then the preset's pumparound
   above a large draw (owner rule: large side draws need pumparound cooling above them). A test still failing after
   those two is listed in the review with its trace and left `@Disabled` with the reason: the main thread decides.
8. `V3Sotelo2019SideDrawCase(+Test)` keeps the published geometry, pressures and flow fractions (they are the
   paper's) and takes the medium crude as its feed. Rule 7 applies to its convergence cases.
9. No production code changes. If a test can pass only with a production change, stop and report.

The users, by reference count: `V3PumparoundCalculatorTest` 22, `V3PengRobinsonDerivativesTest` 12,
`V3BlockJacobianAssemblerTest` 7, `V3ColumnCalculatorTest` 6, `V3PengRobinsonThermoTest` 5,
`V3PengRobinsonKernelTest` 5, `V3TestInputs` 5, `V3PumparoundSteamCalculatorTest` 5, `V3FeedFlashEquivalenceTest` 4,
`V3StageContinuationTest` 4, `V3SimultaneousColumnSolverTest` 4, `V3ConvergenceClosureTest` 4,
`V3StageTraceCalculatorTest` 3, `V3SideDrawCalculatorTest` 3, `V3ColumnInitializerTest` 3; 2 each in
`V3TruncatedFlashNumericsTest`, `V3FlashTruncationTest`, `V3FlashCheckpointTest`, `V3TraceFloorSupportTest`,
`V3Sotelo2019SideDrawCaseTest`, `V3PotColumnTest`, `V3PathDependentInfeasibilityTest`,
`V3OperatingDomainValidatorTest`, `V3FlashTruncationColumnTest`, `V3ExactWarmStartSweepTest`,
`V3EnergyShiftPredictorTest`, `V3CondenserPhaseTransitionTest`, `V3ColumnDisplayResultTest`, `V3ColdStartTest`,
`V3CarryOverEntrainmentTest`, `NativeColumnSolverTest`, `network/ColumnWireTest`; 1 each in `RegroupedCrudeTest`,
`V3AggressiveStrategyTest`. `grep -rlE "cdu17|Cdu17" src/test/java` is the list of record.

## 4. Step 0: is the medium crude one liquid when cold?

Before any test moves, one probe (`tools/supercritical-probes/p3`, beside `P3WetProbe`; run by that folder's
`run.sh`): `TangentPlaneStability` on the `icheme:medium_crude` feed, dry, at 280, 300, 320 and 350 K and 0.1, 0.25
and 1.0 MPa; and on its 565 C+ end (`crude_pc10..12` in the assay's proportions) with its naphtha
(`crude_pc01..03`), 50/50 by mass, at the same states.

- Expected: one liquid everywhere (worst pair 4 to 6 RT, diluted by ten intermediate cuts).
- If a state is unstable towards a second liquid: record the state, the trial phase and the tangent-plane distance,
  do not change any data, and report it at once with the removal still going on. It decides whether the open item
  closes with this batch.

## 5. Tools

- `tools/supercritical-probes/p3/.../P3WetProbe.java`: its cdu17 states move to the medium crude (this batch's
  step 0 lives beside it).
- `tools/open-thermo-data/o10/O10FixResumeProbe.java`, `tools/vacuum-column-test/BinaryPhaseProbe.java`,
  `tools/package-rework/migrate-cdu17.mjs`: not built by Gradle. Leave the sources; add one line to each tool's
  `README.md`: "reads the cdu17 test fixture, removed by batch 2026-11-16-cdu17-fixture-removal; last runnable at
  `ee13d1034`".
- Manifests, captures and archives (`tools/neural/*manifest*.json` and plans, `tools/hybrid-learning`,
  `tools/jdk-determinism/captures`, `tools/test-archive-2026-10-21`, `tools/unified-thermo-port-m5`,
  `tools/trace-followup`, `tools/capacity-followup`): records of past runs. Not edited.

## 6. Documents

- `documentation/reference/code-guide/` and `documentation/reference/`: `grep -rIl -iE "cdu17"`; a line that names
  the fixture as current is brought to the medium crude. History documents of older batches are not edited.
- This batch: `CDU17_REMOVAL_REVIEW.md` (the table of section 7), `README.md`, the row in `documentation/INDEX.md`.

## 7. Runs

All column classes are in the `columnGates` group; `MaterialCatalogTest`, `ViscosityTest` and `ColumnWireTest` are
in `test`. Commands from the worktree root, never the final lane:

- While moving a class: `./gradlew columnGates --tests "<fully qualified class>" --max-workers 4 --offline`
  (or `test --tests ...` for the three). One class per run while it is being worked on.
- After `V3TestInputs` is final: once, the classes `node tools/merge-lane/affected.mjs` selects for the diff against
  `ee13d1034`, as `--tests` filters on `columnGates` and on `test`. Each run proves the moved classes pass on the
  medium crude and that no class still loads the removed files.
- `./gradlew compileTestJava --offline` after the deletions proves nothing else referenced the fixture.
- At most two Gradle invocations on the machine; this thread uses one. No times are reported; cost is the solver's
  counters where a test prints them. A test that passed and whose loaded files did not change is not run again.
- `DeterministicMathSourceTest` and `LanguageFilesTest` are not reached (no `src/main` change, no lang change).

The review's table, one row per class: tests before and after, moved / deleted (covered by) / disabled (reason),
extension before and after, frozen numbers old and new, the design adjustments of rule 7.

## 8. Results that move

- No production result moves: `src/main` is unchanged, so the campaign, chain-100, the junction lines and
  `MIXED_GAS_COST` stand from the 0.34.1 lane.
- The printed lines of the moved column-gate classes are new lines on a new crude, not moved references. The next
  final lane records them as its baseline. The review lists every class whose printed lines changed.

## 9. Pitfalls

- `Cdu17FixtureExtension` also put the TJL19 fixture into scope (`withProduction`). A class that silently relied on
  that fails with an unknown package after the annotation goes: rule 3.
- The preset's column solves with steam and pumparounds; stripping them from the preset gives a column with large
  draws and no cooling, which meets the draw wall. Use `mediumCrudeDry()` for dry cases, not a stripped preset.
- The medium crude carries Methane; the cdu17 feed's lightest was C1 as well, but its C4 was one lump. A test that
  asserts a count of light components changes with the basis.
- Files are CRLF in places; `sed -i` and `perl -0` break them. Edit with the editor tool and check the diff size.
- `affected.mjs` selects by recorded load sets; a deleted class's users are found by `compileTestJava`, not by it.
- The lane baselines under `tmp/` of a reused worktree folder belong to an older batch: leave them.

## 10. Done

1. Step 0's table is in the review.
2. `grep -rIl -iE "cdu17" src build.gradle` is empty; `compileTestJava` passes.
3. Every class of section 3 is moved, deleted with its cover named, or disabled with a reason, and the selected
   classes pass in `columnGates` and `test`.
4. The review, the README, the INDEX row and the tool README lines are committed on the branch.
5. Nothing is merged: merge preparation, the lane and the version wait for the owner's word.
