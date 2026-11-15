# Inventory of tests, build, tools and documents (P4)

Batch `2026-11-13-codebase-review`, subagent P4, 2026-11-13. Tables only. Base: `HEAD` of branch `claude/codebase-review-refactor-8a85ef` (tree clean). All sizes are tracked sizes (`git ls-files`, `git ls-tree -l`); untracked and ignored files are not counted. Lines count `\n` of the tracked file. Judgements used: "unused", "duplicate", "over the size rule" only. Not read: the content of any document, tool or test beyond annotations, imports and first lines.

Where this differs from `REVIEW_PLAN.md` section 1: `tools/` holds 174 folders (the plan says 176: it counted the two root files `INDEX.md` and `development.gradle`); 15 012 tracked files and 515.9 MB tracked (plan: about 500 MB) are confirmed. `documentation/` holds 142 batch folders (plan: 141; the 142nd is `2026-11-13-codebase-review` itself) plus `reference/` and `repository/`.

## 1. Tests

### 1.1 Totals

| Item | Files | Lines | Note |
|---|---|---|---|
| `src/test/java` | 622 | 118 164 | 3 529 test annotations (`@Test`, `@ParameterizedTest`, `@TestFactory`, `@RepeatedTest`); per area in 1.2 |
| `src/fluidGameTest/java` | 45 | 12 630 | 113 test annotations by the same regex (151 by `git grep -c @GameTest`, which also counts imports and comments); 38 of the 45 files hold tests, 7 are helpers |
| `src/test/resources` | 260 | 3.85 MB | `compat/` 133 files (corpus images), `materials/` 73, `science/` 32, `fluid/` 10, `fluid_conversion/` 6, `column/` 5 |
| `src/fluidGameTest/resources` | 5 | small | structure `empty.nbt` x4 and `neoforge.mods.toml` |
| `src/mcpCompat` | 9 | small | the MCP bridge client mod (no tests) |
| Test classes over 1 500 lines | 1 | 1 528 | `src/fluidGameTest/.../ColumnFailureModesReviewWorld.java` (1 test). No file in `src/test/java` exceeds 1 500; 10 files exceed 1 000 (listed in 1.4) |

### 1.2 Test classes by production area (package under `src/test/java/com/wormzjl/icheme/`, same path as `src/main/java`)

Column "Helpers" is files with no `@Test`, `@ParameterizedTest`, `@TestFactory`, `@RepeatedTest` or `@GameTest`. "Task" is the Gradle task or gate group that runs the area (see 1.3).

| Test package | Files | Lines | Test annotations | Helpers | Largest file (lines) | Task |
|---|---|---|---|---|---|---|
| `runtime/fluid` | 105 | 21 916 | 620 | 5 | `ColumnRunTest` 1 395 | `test`, `fluidRuntimeTest`; 4 classes in `holdGates` |
| `science/column/v3` (with `v3/thermo`, 15 files) | 93 | 18 183 | 478 | 10 | `V3BlockJacobianAssemblerTest` 681 | `columnGates` only (`test` excludes it) |
| `science/fluid/network` | 71 | 10 813 | 389 | 1 | `BatchStillTest` 1 065 | `test`, `fluidScienceTest` |
| `science/equipment` | 29 | 8 868 | 264 | 1 | `ShapeRasterizerTest` 1 490 | `equipmentGates` only |
| `client/gui/flowsheet` | 29 | 7 589 | 252 | 3 | `CrudeUnitSampleTest` 1 236 | `test`; 4 classes in `holdGates` |
| `client/gui/common` (with `kit/` 4) | 27 | 6 105 | 159 | 1 | `CalculatorColumnTest` 861 | `test` |
| `science/flowsheet` (with `sizing/` 10) | 24 | 5 680 | 298 | 2 | `UnitSizingTest` 665 | `test`; 2 classes in `holdGates` |
| `science/thermo/phase` | 18 | 5 298 | 88 | 3 | `FluidTpEquilibriumP3Test` 849 | `test` |
| `science/material` | 30 | 4 552 | 127 | 10 | `DerivedPairsTest` 472 | `test` |
| `science/fluid/thermo` | 21 | 3 405 | 96 | 2 | `NetworkPhaseEngineTest` 694 | `test`, `fluidScienceTest` |
| `science/thermo/qualification` | 17 | 3 378 | 32 | 2 | `G4F1CarbonDioxideFrostPointHoldoutTest` 355 | `test` |
| `science/tutorial` | 23 | 3 230 | 93 | 5 | `GuideCoverageTest` 387 | `test` |
| `science/thermo` (top level) | 8 | 2 932 | 43 | 2 | `PengRobinsonKernelHuronVidalTest` 1 176 | `test` |
| `science/machine` | 18 | 1 669 | 101 | 0 | `ShaftTrainTest` 217 | `test` |
| `client/fx` (with `emitter/` 4) | 19 | 1 563 | 79 | 1 | `FxCompatTest` 192 | `test` |
| `client/gui/tutorial` (with `cards/` 7) | 12 | 1 213 | 39 | 0 | `CardViewTest` 264 | `test` |
| `world` | 7 | 1 150 | 34 | 0 | `LineContractTest` 390 | `test` |
| `science/assembly` | 5 | 1 120 | 34 | 1 | `AssemblyMatcherTest` 399 | `test` |
| `science/column/operation` | 4 | 1 065 | 31 | 0 | `ColumnSolveTest` 487 | `test` |
| `network` | 6 | 1 055 | 43 | 1 | `ColumnWireTest` 390 | `test` |
| `science/thermo/reference` | 9 | 1 036 | 2 | 8 | `HelmholtzFluid` 283 (helper) | `test` |
| `runtime/control` | 3 | 884 | 29 | 0 | `ControlSystemTest` 557 | `test` |
| `runtime` (top level) | 6 | 848 | 25 | 0 | `BoundedCpuSolveServiceTest` 412 | `test`; 3 `Fluid*Test` also `fluidRuntimeTest` |
| `fluid` (`benchmark/` 2, `support/` 2) | 4 | 668 | 1 | 3 | `SolverRegressionHarness` 314 (helper) | `benchmark/` runs only in `fluidSolverRegression`; `support/` is shared |
| `runtime/assembly` | 5 | 655 | 18 | 0 | `AssemblyWalkerTest` 205 | `test` |
| `runtime/instrument` | 6 | 651 | 33 | 1 | `GaugeAccessoriesTest` 197 | `test` |
| `client/gui/screens` | 4 | 607 | 35 | 0 | `V3ClientPresetsTest` 196 | `test` |
| `science/fluid` (top level) | 5 | 536 | 19 | 0 | `DeterministicMathTest` 207 | `test`, `fluidScienceTest` |
| `science/fluid/solver` | 4 | 484 | 21 | 0 | `TraceTruncationLayoutTest` 239 | `test`, `fluidScienceTest` |
| `client` (top level) | 4 | 470 | 20 | 0 | `ValveTurnsTest` 180 | `test` |
| `runtime/power` | 2 | 218 | 6 | 0 | `PowerGridTest` 176 | `test` |
| `science/fluid/conversion` | 1 | 167 | 6 | 0 | `ConversionRuleTest` 167 | `test`, `fluidScienceTest` |
| `science/fluid/transport` | 2 | 116 | 9 | 0 | `SlurryTransportTest` 63 | `test`, `fluidScienceTest` |
| `science/fluid/topology` | 1 | 43 | 5 | 0 | `TopologyCompilerTest` 43 | `test`, `fluidScienceTest` |
| `src/fluidGameTest` (`fluid/gametest`) | 45 | 12 630 | 113 `@GameTest` methods by annotation count in test files | 7 | `ColumnFailureModesReviewWorld` 1 528 | GameTest servers (1.3) |
| **Sum `src/test`** | 622 | 118 164 | | | | |

Same-name test classes exist for the monoliths `PassiveStepSolver` (`PassiveStepSolverTest`, `science/fluid/network`) and `IslandCoordinator` (`IslandCoordinatorTest`, `runtime/fluid`), and `ProcessUnitTest` (1 341 lines) is in `science/equipment`. There is no test file with `StillColumn` or `ShaftTrains` in its name (name search only, not an import-graph check).

### 1.3 Tests by Gradle task or gate group (from `build.gradle`; the filters are quoted)

| Task | Selects | Test files (incl. helpers) | Lines | Notes |
|---|---|---|---|---|
| `test` | all of `src/test`, except `**/fluid/benchmark/**`, `science/column/v3/**`, `science/equipment/**` and the 10 slow classes | 488 | 86 356 | `dependsOn compileFluidGameTestJava` and `jar`; sets `icheme.fluid.scheduler.verify=true`; `maxParallelForks` from `-PtestForks` (1 to 8) |
| `holdGates` | 10 slow classes (`slowGateClasses`, plus `CrudeUnitSampleTest$*`); `failOnNoMatchingTests` | 10 | 4 339 | `HoldClassWorldsTest` 1 141, `CrudeUnitSampleTest` 1 236, `ExtremeTopologyIslandTest` 420, `FlowsheetColumnTest` 359, `UtilityRegulationTest` 326, `JunctionSeedingGateTest` 269, `IslandSchedulerTest` 233, `ColumnEjectorTest` 154, `SmallCrudeUnitTest` 135, `SmallUnitRecordingTest` 66 |
| `columnGates` | `com.wormzjl.icheme.science.column.v3.*` on a copied class subset (`columnGateInputs`) | 93 | 18 183 | `maxHeapSize 1g`; lists 17 main class globs, 12 helper globs in `build.gradle` lines 562-573 |
| `equipmentGates` | `com.wormzjl.icheme.science.equipment.*` on a copied class subset (`equipmentGateInputs`) | 29 | 8 868 | `maxHeapSize 1g`; lists 40 main globs, 7 helpers, 3 test resource globs, 15 main resource globs in lines 574-598 |
| `slowGates` | no task of its own: depends on `holdGates`, `columnGates`, `equipmentGates` | | | `check` depends on it |
| `check` | `test` + `slowGates` | | | |
| `fluidScienceTest` | `com.wormzjl.icheme.science.fluid.*`; `failOnNoMatchingTests` | 105 | 15 564 | subset of `test` (duplicate selection); `icheme.fluid.scheduler.verify=true`; depends on `jar` |
| `fluidRuntimeTest` | `com.wormzjl.icheme.runtime.fluid.*` and `runtime.Fluid*Test` | 108 | 22 275 | subset of `test` (duplicate selection); includes the 4 slow classes of `runtime/fluid` |
| `fluidSolverRegression` | `fluid.benchmark.FluidSolverRegressionTest` (107 lines) with `SolverRegressionHarness` (315) | 2 | 421 | `outputs.upToDateWhen { false }`; properties `fluidRegressionReferences`, `-Capture`, `-Mode`, `-TraceCutoff` |
| `v3HollandBenchmark` | `science.column.v3.HollandExample32BenchmarkTest` (403 lines) | 1 | 403 | also inside `columnGates` (duplicate selection); `upToDateWhen false` |
| `nativeSmoke` | `science.column.v3.NativeColumnSolverTest` (173 lines) | 1 | 173 | also inside `columnGates` (duplicate selection); depends on `nativeBuild` |
| GameTest servers (`runs` block, `build.gradle` lines 83-170) | `src/fluidGameTest`: `fluidGameTestServer` (namespace `icheme_fluid_test`), `columnGameTestServer` (`icheme_column_test`), `reviewWorldServer` (`icheme_review`), `fluidBenchmarkServer` | 45 | 12 630 | none is in `check`; `test` only compiles them. 4 files name `icheme_review`, 3 name `icheme_column_test` |

### 1.4 Shared rigs (test files with no test annotation, ordered by number of other test files naming the class)

| Rig | Lines | Files naming it | Role |
|---|---|---|---|
| `fluid/support/FluidTestSupport` | 202 | 132 | fluid test scaffolding |
| `science/fluid/thermo/TestPhases` | 114 | 86 | phase builders |
| `science/material/MaterialTestBasis` | 10 | 67 | material basis |
| `science/material/PilotCryogenicTestCatalog` | 102 | 59 | test catalog |
| `science/column/v3/V3TestInputs` | 138 | 51 | column inputs |
| `runtime/fluid/EventPathWorld` | 213 | 45 | event path world |
| `science/material/TiaJuanaTestCatalog` | 103 | 41 | test catalog |
| `science/material/Cdu17FixtureExtension` | 7 | 32 | fixture extension |
| `client/gui/flowsheet/SheetFixture` | 162 | 23 | sheet fixture |
| `science/flowsheet/FlowsheetRuns` | 37 | 21 | flowsheet runs |
| `science/fluid/network/PortFixture` | 71 | 20 | port fixture |
| `fluid/gametest/TankCharge` | 50 | 15 | GameTest helper |
| `science/material/PilotAqueousTestCatalog` | 54 | 13 | test catalog |
| `science/material/Cdu17TestCatalog` | 92 | 12 | test catalog |
| `science/thermo/reference/HelmholtzFluid` | 283 | 12 | reference EOS (`reference/` holds 9 files, 1 036 lines, 2 test annotations) |
| `science/thermo/phase/PhaseTestSupport` | 230 | 9 | phase tests |
| `science/thermo/qualification/G3Support` | 331 | 8 | holdout support |
| `science/thermo/qualification/G5Support` | 283 | 5 | holdout support |

All other helpers (46 further files) are named by 1 to 6 other test files. No helper has zero users (an unused helper: none found by name). Duplicate catalogs: four test catalogs build material sets (`Cdu17TestCatalog`, `PilotCryogenicTestCatalog`, `PilotAqueousTestCatalog`, `TiaJuanaTestCatalog`) next to `src/test/resources/materials/` (73 files, three catalog trees `cdu17-reference`, `tia-juana`, `pilot-cryogenic`); both exist for the same data, one in code and one in resource files.

Files over 1 000 lines in `src/test`: `ShapeRasterizerTest` 1 490, `ColumnRunTest` 1 395, `FluidFactsCompatTest` 1 376, `ProcessUnitTest` 1 341, `CrudeUnitSampleTest` 1 236, `PengRobinsonKernelHuronVidalTest` 1 176, `HoldClassWorldsTest` 1 141, `BatchStillTest` 1 065 (8 files in `src/test`); `src/fluidGameTest` adds `ColumnFailureModesReviewWorld` 1 528 and `RegistryFirstGameTests` 1 057 (10 files over 1 000 in all). Over 1 500: none in `src/test`; one in `src/fluidGameTest` (1 528).

## 2. `tools/`

Tracked: 174 folders, 2 root files (`INDEX.md` 39 KB, `development.gradle` 17 KB), 15 012 files, 515.9 MB. `tools/INDEX.md` has 174 folder rows plus a row for `development.gradle`; every folder has a row. "Named by" counts tracked files that contain the text `tools/<folder>` (exact name, followed by a non-name character) in: `build.gradle`, `tools/development.gradle`, `src/test` and `src/fluidGameTest`, `.claude/skills/**`, `AGENTS.md`, `src/main`. A test or `src/main` hit is a text citation (comment or path), not proof that the folder is read. `.agents/skills` is a symlink to `.claude/skills` and not counted twice. "unused" = named by none of these and its `tools/INDEX.md` status is not "In use"/"In progress". Folders are ordered by tracked size.

| Folder | Files | MB | Status in `tools/INDEX.md` | Named by | Tracked files over 1 MB | Vendored dependencies | Judgement |
|---|---|---|---|---|---|---|---|
| `junction-holdup-prototype` | 3318 | 109.32 | Concluded 2026-09-26 | test x2 | 20: run101-runtime.fluid.JunctionEventProbe.xml 7.1, run102-runtime.fluid.JunctionEventProbe.xml 1.1, run103-runtime.fluid.JunctionEventProbe.xml 7.9, ... | - | - |
| `new-units` | 846 | 84.32 | In use since 2026-10-18 | none | - | - | - |
| `custom-equipment-rendering-poc` | 670 | 54.46 | Concluded 2026-10-20 (batch in 0.10.0) | src/main x2 | 1: shader-iris-photon.png 1.0 | - | - |
| `pump-compressor-controls` | 1013 | 43.02 | Archived 2026-10-23 | skill x1 | 3: libsndfile_x64.dll 2.3, libscipy_openblas64_-ed4f167a5330424524f45258e7ca2c8d.dll 19.6, _multiarray_umath.cp312-win_amd64.pyd 3.7 | python-deps/ (1 011 files, 43.0 MB: numpy, soundfile, 20 .pyd, 3 .dll) | - |
| `wti-pumping-rate-hold` | 1173 | 32.29 | Concluded 2026-10-26 (batch in 0.12.0); world dump to research/ 2026-11-05 (O5) | none | 7: TEST-com.wormzjl.icheme.science.fluid.network.PhasePortPriorityTest.xml 1.2, output.bin 1.8, all-printed-references.txt 1.7, ... | - | unused |
| `neural` | 175 | 26.85 | Implemented 2026-09-14 | development.gradle, test x1 | 6: gen3-case-map.jsonl.gz 7.8, gen3-model-card.json 6.0, generalized-case-map.jsonl.gz 3.2, ... | - | - |
| `gauge-accessories` | 644 | 21.34 | Concluded 2026-10-26 (batch in 0.12.0) | none | 8: TEST-com.wormzjl.icheme.science.fluid.network.PhasePortPriorityTest.xml 1.2, output.bin 1.8, TEST-com.wormzjl.icheme.science.fluid.network.PhasePortPriorityTest.xml 1.2, ... | - | unused |
| `accessory-refinements` | 48 | 17.31 | Concluded 2026-10-26 (batch in 0.12.0) | none | 4: r.-1.-1.mca 3.8, r.-1.0.mca 3.6, r.0.-1.mca 3.9, ... | - | unused |
| `phase-ports-probes` | 496 | 13.70 | Implemented 2026-10-14 | test x14 | 5: scenarios.txt 1.1, scenarios.txt 1.1, scenarios.txt 1.1, ... | - | - |
| `benchmark-population` | 27 | 10.97 | Implemented 2026-09-14 | none | 3: historical-test-inputs.jsonl 1.3, train-inputs.jsonl 6.1, validation-inputs.jsonl 1.3 | - | unused |
| `sight-glass-flow-direction` | 66 | 10.31 | Concluded 2026-10-26 (batch in 0.12.0) | none | 1: flow-direction.gif 4.5 | - | unused |
| `floating-roof-tanks` | 114 | 8.57 | Concluded 2026-10-26 (batch in 0.12.0) | none | - | - | unused |
| `session-transcripts` | 15 | 7.77 | Concluded 2026-09-27 (reference material) | none | 1: agent-a37c89b1d55df9780.jsonl.gz 1.2 | - | unused |
| `fluid-in-game-rig` | 81 | 5.57 | Detached 2026-09-24 (`c1b8464`) | none | 3: r.-1.0.mca 1.2, r.0.-1.mca 1.1, r.0.0.mca 1.7 | - | unused |
| `unified-thermo-port-test-world` | 126 | 5.53 | Prepared 2026-10-16 | none | 3: r.-1.0.mca 1.2, r.0.-1.mca 1.1, r.0.0.mca 1.7 | - | unused |
| `storage-vessel-renders` | 27 | 5.48 | Concluded 2026-10-26 (batch in 0.12.0) | none | - | - | unused |
| `tank-indicator-ports` | 767 | 5.26 | Archived 2026-10-24 | none | 2: TEST-com.wormzjl.icheme.science.fluid.network.PhasePortPriorityTest.xml 1.2, TEST-com.wormzjl.icheme.science.fluid.network.PhasePortPriorityTest.xml 1.2 | - | unused |
| `inline-mover-accessories` | 589 | 4.53 | Concluded 2026-10-26 (batch in 0.12.0) | none | 2: TEST-com.wormzjl.icheme.science.fluid.network.PhasePortPriorityTest.xml 1.2, TEST-com.wormzjl.icheme.science.fluid.network.PhasePortPriorityTest.xml 1.2 | - | unused |
| `pipeline-check-valve` | 303 | 4.35 | Archived 2026-10-24 | none | 2: TEST-com.wormzjl.icheme.science.fluid.network.PhasePortPriorityTest.xml 1.2, output.bin 1.8 | - | unused |
| `pipe-laying-renders` | 71 | 3.78 | In use since 2026-10-26 | none | - | - | - |
| `pressure-valve-accessory` | 305 | 3.52 | Archived 2026-10-24 | none | 1: TEST-com.wormzjl.icheme.science.fluid.network.PhasePortPriorityTest.xml 1.2 | - | unused |
| `tank-gauge-enclosure` | 318 | 2.91 | Concluded 2026-10-26 (batch in 0.12.0) | none | 1: TEST-com.wormzjl.icheme.science.fluid.network.PhasePortPriorityTest.xml 1.2 | - | unused |
| `jdk-determinism` | 719 | 2.56 | In use since 2026-09-27 | test x1, src/main x2 | - | - | - |
| `fluid-gui-consistency` | 298 | 2.50 | Archived 2026-10-24 | none | 1: TEST-com.wormzjl.icheme.science.fluid.network.PhasePortPriorityTest.xml 1.2 | - | unused |
| `sight-glass-ratio-seams-sound` | 302 | 2.27 | Concluded 2026-10-26 (batch in 0.12.0) | none | 1: TEST-com.wormzjl.icheme.science.fluid.network.PhasePortPriorityTest.xml 1.2 | - | unused |
| `multi-machine-framework` | 87 | 1.79 | Concluded 2026-10-27 | none | - | - | unused |
| `minecraft-mcp-bridge` | 5 | 1.78 | In use since 2026-10-16 (official v0.4.18 on main since 0.12.4, 2026-10-26) | build.gradle, AGENTS.md | - | 2 jars (v0.4.18 pinned; superseded-0.4.7-osguard) | - |
| `pipe-laying-ingame` | 34 | 1.75 | Concluded 2026-10-26 | none | - | - | unused |
| `transformer-promotion` | 16 | 1.72 | Implemented 2026-09-14 | none | 1: validation-inputs.jsonl 1.6 | - | unused |
| `simulator-sizing` | 325 | 1.44 | Retained since 2026-11-08 | none | - | - | unused |
| `unified-thermo-port-m2` | 63 | 1.07 | Done 2026-10-16 | none | - | - | unused |
| `lnn-gap` | 44 | 0.99 | Implemented 2026-09-14 | none | - | - | unused |
| `neural-budget` | 39 | 0.97 | Implemented 2026-09-14 (defaults promoted) | none | - | - | unused |
| `fluid-world-inspector` | 21 | 0.95 | Archived 2026-10-23; world dump to research/ 2026-11-05 (O5) | none | - | - | unused |
| `crystal-junction-holds` | 7 | 0.78 | Implemented 2026-10-20 | none | - | - | unused |
| `process-control` | 35 | 0.74 | Implemented 2026-10-27 | none | - | - | unused |
| `decoder-floor-followup` | 32 | 0.68 | Concluded 2026-09-13 (negative) | none | - | - | unused |
| `unified-thermo-port-m5` | 172 | 0.57 | Done 2026-10-18 | test x1 | - | - | - |
| `open-thermo-data` | 51 | 0.49 | Implemented 2026-11-09 | test x11, src/main x3 | - | - | - |
| `minecraft-time-delta` | 30 | 0.49 | In progress since 2026-11-11 | test x2 | - | - | - |
| `test-archive-2026-10-21` | 35 | 0.48 | Archived 2026-10-21 (`d13e72d1`) | test x1 | - | - | - |
| `heat-gui-renders` | 10 | 0.48 | In use since 2026-10-28 | test x1, skill x3, AGENTS.md, src/main x1 | - | - | - |
| `junction-seeding` | 25 | 0.46 | Concluded 2026-10-19 (batch in 0.9.0) | test x1 | - | - | - |
| `v3-cold-core-benchmark` | 11 | 0.42 | Implemented 2026-09-05 | none | - | - | unused |
| `capacity-followup` | 29 | 0.40 | Concluded 2026-09-13 | none | - | - | unused |
| `mover-equipment-arrows` | 10 | 0.36 | Archived 2026-10-24 | none | - | - | unused |
| `test-archive-2026-11-02` | 12 | 0.34 | Archived 2026-11-02 (`c1b35ce2d`) | none | - | - | unused |
| `trace-followup` | 32 | 0.33 | Concluded 2026-09-12 | none | - | - | unused |
| `unified-thermo-consolidation` | 21 | 0.30 | Done 2026-10-18 | none | - | - | unused |
| `fluid-probes` | 54 | 0.30 | Concluded 2026-09-24 | none | - | - | unused |
| `hybrid-learning` | 38 | 0.29 | Concluded 2026-09-11 | none | - | - | unused |
| `dry-ice-cost` | 60 | 0.27 | Implemented 2026-11-03 | none | - | - | unused |
| `heat-exchanger-design` | 13 | 0.27 | Implemented 2026-10-28 (0.17.0) | none | - | - | unused |
| `equipment-drawings` | 29 | 0.24 | In use since 2026-11-08 | skill x1, AGENTS.md, src/main x1 | - | - | - |
| `phase-equilibrium-scans` | 27 | 0.24 | Concluded 2026-09-24 (P2 phase side) | none | - | - | unused |
| `cooling-towers` | 13 | 0.23 | Implemented 2026-11-02 | none | - | - | unused |
| `path-dependent-infeasible` | 9 | 0.23 | Implemented 2026-09-14 | none | - | - | unused |
| `pipe-junction-probe` | 48 | 0.21 | Concluded 2026-09-25 | none | - | - | unused |
| `unified-thermo-port-forms` | 44 | 0.19 | Done 2026-10-16 | none | - | - | unused |
| `dwsim` | 30 | 0.18 | Concluded 2026-09-10 | none | - | - | unused |
| `aqueous-solvents` | 34 | 0.17 | In use since 2026-11-11 | test x2, src/main x7 | - | - | - |
| `compound-import` | 22 | 0.17 | Implemented 2026-11-09 | test x1, src/main x5 | - | - | - |
| `unified-thermo-port-m1` | 19 | 0.16 | Done 2026-10-15 | none | - | - | unused |
| `process-simulator` | 12 | 0.15 | In use since 2026-11-02 | test x1 | - | - | - |
| `rotating-machinery` | 21 | 0.15 | In use since 2026-11-04 | none | - | - | - |
| `fluid-solver-simplification` | 39 | 0.14 | Implemented 2026-10-19 | none | - | - | unused |
| `unified-thermo-holds-review` | 15 | 0.14 | Concluded 2026-10-18 (analysis only) | none | - | - | unused |
| `hybrid-diagnosis` | 18 | 0.13 | Concluded 2026-09-11 | none | - | - | unused |
| `cloud-science-harness` | 65 | 0.13 | In use since 2026-09-26 as a fallback | none | - | - | - |
| `vacuum-column-test` | 21 | 0.12 | Implemented 2026-11-02 | none | - | - | unused |
| `tutorial-column` | 10 | 0.12 | In use since 2026-11-12 | test x1 | - | - | - |
| `index-consolidation` | 8 | 0.11 | Concluded 2026-10-26 (one-shot) | none | - | - | unused |
| `neural-retrain` | 15 | 0.11 | Implemented 2026-11-09 | test x2, src/main x2 | - | - | - |
| `unified-thermo-port-ice` | 25 | 0.11 | Done 2026-10-16 (report `ICE_IH_WP4.md`) | test x2, src/main x2 | - | - | - |
| `column-sump` | 18 | 0.11 | Concluded 2026-10-27 (batch in 0.15.1) | none | - | - | unused |
| `tutorial-renders` | 11 | 0.10 | In use since 2026-11-12 | test x2 | - | - | - |
| `unified-thermo-port-m3` | 37 | 0.09 | Done 2026-10-16 | none | - | - | unused |
| `wp7d-engine-defects` | 19 | 0.09 | Concluded 2026-09-25 (P3 WP7d) | none | - | - | unused |
| `test-archive-2026-11-08` | 9 | 0.09 | Archived 2026-11-08 (`da982f268`) | none | - | - | unused |
| `unified-thermo-port-quick-fixes` | 21 | 0.09 | Done 2026-10-17 | none | - | - | unused |
| `p6-pilot-acceptance` | 26 | 0.09 | Concluded 2026-09-26 (P6) | none | - | - | unused |
| `p5-solid-liquid-scans` | 22 | 0.09 | Concluded 2026-09-25 (P5) | test x1 | - | - | - |
| `pump-sprites` | 12 | 0.08 | In use since 2026-11-04 | skill x1, src/main x2 | - | - | - |
| `training-curation` | 8 | 0.07 | Concluded 2026-09-11 | none | - | - | unused |
| `process-sounds` | 8 | 0.07 | In use since 2026-11-10 | skill x2, src/main x3 | - | - | - |
| `crude-grades` | 16 | 0.06 | In use since 2026-11-04 | test x1, src/main x16 | - | - | - |
| `simulator-bench` | 4 | 0.06 | In use since 2026-11-04 | none | - | - | - |
| `unified-thermo-port-m3-arrival` | 6 | 0.06 | Done 2026-10-16 | none | - | - | unused |
| `unified-thermo-port-review` | 7 | 0.05 | Done 2026-10-16 | none | - | - | unused |
| `eppr78-transcription-check` | 5 | 0.05 | Concluded 2026-09-25 | src/main x1 | - | - | - |
| `p6b-runtime-failures` | 11 | 0.05 | Concluded 2026-09-26 (P6b) | none | - | - | unused |
| `pilot-transport-tables` | 4 | 0.05 | Concluded 2026-09-24 (P3 WP3) | test x4 | - | - | - |
| `ph-false-root` | 13 | 0.05 | Implemented 2026-11-02 | none | - | - | unused |
| `column-operation` | 5 | 0.05 | Implemented 2026-11-02 | none | - | - | unused |
| `liquid-supply-screen` | 4 | 0.05 | Implemented 2026-09-14 | none | - | - | unused |
| `wp4-runtime-diagnosis` | 11 | 0.05 | Concluded 2026-09-25 | none | - | - | unused |
| `fx-bench` | 18 | 0.05 | In use since 2026-11-10 | src/main x1 | - | - | - |
| `wp7-network-integration` | 14 | 0.05 | Concluded 2026-09-25 (P3 WP7) | none | - | - | unused |
| `batch-still-column` | 3 | 0.05 | Kept for reference since 2026-11-10 | none | - | - | unused |
| `merge-lane` | 8 | 0.04 | Implemented 2026-11-09 | skill x1, AGENTS.md | - | - | - |
| `pipe-laying-system` | 5 | 0.04 | Concluded 2026-10-27 | none | - | - | unused |
| `spine-join-measurement` | 5 | 0.04 | Concluded 2026-09-24 (P2 data side) | test x6 | - | - | - |
| `fluid-paced-analysis` | 9 | 0.04 | In use | none | - | - | - |
| `post-release-followups` | 15 | 0.04 | Implemented 2026-10-19 | none | - | - | unused |
| `pipe-thermal-loss` | 6 | 0.04 | Concluded 2026-09-24 | none | - | - | unused |
| `skirt-shadow` | 5 | 0.04 | Implemented 2026-10-27 | none | - | - | unused |
| `gerg-bubble-points` | 2 | 0.04 | Concluded 2026-09-24 (P3 WP8) | test x1 | - | - | - |
| `valve-hold-probes` | 17 | 0.04 | Implemented 2026-11-02 | none | - | - | unused |
| `square-flanges` | 4 | 0.03 | In use since 2026-10-23 | none | - | - | - |
| `column-gui-mcp` | 7 | 0.03 | Detached 2026-09-24 (`43bf7c5`) | none | - | - | unused |
| `methane-ideal-gas` | 2 | 0.03 | Concluded 2026-09-24 (P3 WP10) | none | - | - | unused |
| `column-failure-modes` | 8 | 0.03 | In use since 2026-11-10 | none | - | - | - |
| `native-column-default` | 8 | 0.03 | Implemented 2026-11-02 | none | - | - | unused |
| `lod-rendering` | 9 | 0.03 | Implemented 2026-11-09 | none | - | - | unused |
| `package-rework` | 9 | 0.03 | Kept for reference since 2026-11-04 | none | - | - | unused |
| `wp7c-hot-gas-label` | 6 | 0.03 | Concluded 2026-09-25 (P3 WP7c) | none | - | - | unused |
| `unified-thermo-port-m3a` | 7 | 0.02 | Done 2026-10-16 | none | - | - | unused |
| `liquid-level-gauge` | 6 | 0.02 | In progress since 2026-10-26; feature delivered | none | - | - | - |
| `solid-co2-qualification` | 4 | 0.02 | Concluded 2026-09-25 (P4 stage 1) | none | - | - | unused |
| `integration` | 4 | 0.02 | Implemented 2026-09-14 | none | - | - | unused |
| `wp5-legacy-digest` | 3 | 0.02 | Concluded 2026-09-25 (P3 WP5); data copy removed 2026-11-05 (O5) | none | - | - | unused |
| `retired` | 5 | 0.02 | Retired 2026-09-14 | none | - | - | unused |
| `unified-thermo-port-extrapolation` | 8 | 0.02 | Done 2026-10-16 | none | - | - | unused |
| `cutoff-handle-flicker` | 7 | 0.02 | Archived 2026-10-24 | none | - | - | unused |
| `device-names` | 3 | 0.02 | In use since 2026-11-03 | none | - | - | - |
| `eppr78-table-s4-extraction` | 6 | 0.02 | Concluded 2026-09-25 (P3 WP2b) | src/main x1 | - | - | - |
| `gas-solid-equilibrium-scans` | 5 | 0.02 | Concluded 2026-09-25 (P4 stage 2a) | none | - | - | unused |
| `wp4-chain-deviation-probe` | 3 | 0.02 | Detached 2026-09-25 by `8a10bfd` (P3 WP11) | none | - | - | unused |
| `mover-port-markings` | 3 | 0.02 | Archived 2026-10-23 | none | - | - | unused |
| `still-sprites` | 3 | 0.02 | In use since 2026-11-09 | src/main x1 | - | - | - |
| `tutorial-recordings` | 6 | 0.02 | In use since 2026-11-12 | test x4, src/main x5 | - | - | - |
| `wp11-closeout` | 8 | 0.02 | Concluded 2026-09-25 (P3 WP11) | none | - | - | unused |
| `phase-cost-probe` | 3 | 0.02 | Detached 2026-09-25 by `8a10bfd` (P3 WP11) | none | - | - | unused |
| `stability-cost-probe` | 3 | 0.02 | Detached 2026-09-25 by `8a10bfd` (P3 WP11) | none | - | - | unused |
| `tangent-plane-stability-scans` | 7 | 0.02 | Concluded 2026-09-24 (P1 item 3) | none | - | - | unused |
| `unified-thermo-port-m4` | 13 | 0.02 | Done 2026-10-16 | none | - | - | unused |
| `localization` | 9 | 0.02 | Implemented 2026-11-03 | skill x1 | - | - | - |
| `equipment-geometry-estimate` | 2 | 0.02 | Concluded 2026-10-15 | none | - | - | unused |
| `wp7b-engine-fixed-point` | 6 | 0.01 | Concluded 2026-09-25 (P3 WP7b) | none | - | - | unused |
| `sight-glass-bubbles` | 6 | 0.01 | Concluded 2026-10-26 (batch in 0.12.0) | none | - | - | unused |
| `pipeline-sight-glass` | 2 | 0.01 | Archived 2026-10-23 | none | - | - | unused |
| `equipment-service-probes` | 11 | 0.01 | Implemented 2026-11-09 | none | - | - | unused |
| `unified-thermo-port-extrapolation-plan` | 2 | 0.01 | Done 2026-10-16 | none | - | - | unused |
| `pilot-volume-anchors` | 4 | 0.01 | Concluded 2026-09-24 (P3 WP3) | test x4 | - | - | - |
| `fluid-display-followups` | 4 | 0.01 | Archived 2026-10-23 | none | - | - | unused |
| `coolprop-parity` | 2 | 0.01 | Concluded 2026-10-19 (batch in 0.9.0) | test x8 | - | - | - |
| `drum-operation-holds` | 4 | 0.01 | In use since 2026-11-11 | none | - | - | - |
| `machine-sounds` | 2 | 0.01 | In use since 2026-11-04 | skill x2, src/main x1 | - | - | - |
| `mcp-gui-helpers` | 8 | 0.01 | In use | none | - | - | - |
| `pbr-textures` | 2 | 0.01 | In use since 2026-11-10 | test x1 | - | - | - |
| `machine-nozzle-probe` | 5 | 0.01 | In use since 2026-11-03 | none | - | - | - |
| `conversion-rules` | 2 | 0.01 | In use since 2026-11-12 | none | - | - | - |
| `pipeline-shutoff-valve` | 2 | 0.01 | Archived 2026-10-23 | none | - | - | unused |
| `g3-qualification` | 4 | 0.01 | Concluded 2026-09-25 (P3 WP9b) | none | - | - | unused |
| `glfw-focus-probe` | 5 | 0.01 | Concluded 2026-10-16 | none | - | - | unused |
| `rack-slot-clearance` | 3 | 0.01 | Superseded 2026-10-26 (layered rack) | none | - | - | unused |
| `fx-textures` | 2 | 0.01 | In use since 2026-11-10 | none | - | - | - |
| `p4-network-rigs` | 3 | 0.01 | Concluded 2026-09-25 (P4 stage 2b) | test x1 | - | - | - |
| `agent-guard` | 3 | 0.01 | In use since 2026-11-10 | AGENTS.md | - | - | - |
| `stale-worktrees-archive` | 2 | 0.01 | Archived 2026-10-21 | none | - | - | unused |
| `old-mover-removal` | 2 | 0.01 | Implemented 2026-10-28 | test x1 | - | - | - |
| `heating-values` | 2 | 0.01 | In use since 2026-11-09 | src/main x48 | - | - | - |
| `machine-parts` | 2 | 0.00 | In use since 2026-11-04 | none | - | - | - |
| `psv-flare` | 4 | 0.00 | In use since 2026-11-11 | none | - | - | - |
| `converter-gametest` | 4 | 0.00 | In use since 2026-11-12 | none | - | - | - |
| `drum-outlet-sweep` | 2 | 0.00 | Implemented 2026-10-28 | none | - | - | unused |
| `rack-frame-probe` | 2 | 0.00 | Implemented 2026-11-09 | none | - | - | unused |
| `fluid-tank-column-gui-fixes` | 1 | 0.00 | Concluded 2026-10-15 | none | - | - | unused |
| `pipeline-tool` | 1 | 0.00 | Archived 2026-10-23 | none | - | - | unused |
| `backward-compat` | 1 | 0.00 | Concluded 2026-10-26 (batch in 0.13.0) | none | - | - | unused |
| `chunk-loading-review` | 1 | 0.00 | Concluded 2026-10-26 (batch in 0.13.2) | none | - | - | unused |
| `functional-pumps-compressors` | 1 | 0.00 | Archived 2026-10-23 | none | - | - | unused |
| `fluid-format-reset` | 1 | 0.00 | Archived 2026-10-23 | none | - | - | unused |
| `fluid-device-controls-display` | 1 | 0.00 | Archived 2026-10-23 | none | - | - | unused |

Totals: 113 folders are "unused" by this definition, 170.6 MB tracked.

### 2.1 Tools: sizes, large files, vendored dependencies, duplicates

| Item | Value |
|---|---|
| Largest folders (tracked MB) | `junction-holdup-prototype` 109.3 (3 318 files), `new-units` 84.3 (846), `custom-equipment-rendering-poc` 54.5 (670), `pump-compressor-controls` 43.0 (1 013), `wti-pumping-rate-hold` 32.3 (1 173), `neural` 26.9 (175), `gauge-accessories` 21.3 (644), `accessory-refinements` 17.3 (48), `phase-ports-probes` 13.7 (496), `benchmark-population` 11.0 (27) |
| Tracked files over 1 MB | 76 files, 217.4 MB in `tools/`, by folder in the table. Kinds: 31 `.xml` (JUnit result files), about 14 `.mca` region files (`mcp-fixture`, `bench-template`, `world-template`), 6 `.bin` (JUnit `output.bin`), 6 `.txt`, 4 `.jsonl`, 4 `.json`, 3 `.jfr`, 3 `.gz`, 2 `.dll`, 1 `.pyd`, 1 `.png`, 1 `.gif`. Over the size rule (AGENTS.md: images, world region files, `*-logs/` folders and files over 1 MB belong in `research/`): all 76 |
| JUnit result trees tracked | about 2 600 tracked files under `results/` and `test-results/` paths; the same `TEST-...PhasePortPriorityTest.xml` (1.2 MB) and `binary/output.bin` (1.8 MB) recur in 13 folders |
| Vendored dependencies | `pump-compressor-controls/python-deps/`: 1 011 files, 43.0 MB (numpy with `libscipy_openblas64` 19.6 MB, `_multiarray_umath` `.pyd` 3.7 MB, `libsndfile_x64.dll` 2.3 MB, 20 `.pyd`, 3 `.dll`). `minecraft-mcp-bridge/`: `minecraft-mcp-1.21.1-neoforge-v0.4.18.jar` (pinned; `build.gradle` and `AGENTS.md` name it) and one superseded jar under `superseded-0.4.7-osguard/`. No `node_modules` and no other `site-packages` |
| Duplicate content | 31 groups of identical files over 100 KB, 40 extra copies, 19.0 MB: `fluid-in-game-rig` and `unified-thermo-port-test-world` hold the same region files (about 5.7 MB extra); `gauge-accessories` holds 2 copies of its `output.bin` and of a result XML; `phase-ports-probes` 5 copies of a 1.15 MB file |
| `tools/development.gradle` (304 lines) | applied by `build.gradle` when present. Source sets `benchmark` (srcDir `benchmarks/`: 0 tracked files) and `neuralMvp` (srcDir `tools/neural`: 25 tracked `.java`). 24 `JavaExec` tasks (section 4.3) |

## 3. `documentation/`

Tracked: 1 224 files, 16.3 MB (1 007 `.md` files, 14.2 MB; 49 `.mcfunction` 1.7 MB; 43 `.js`; 6 `.jfc`; 15 `.svg`). 142 batch folders (`YYYY-MM-DD-topic/`), `reference/` (24 files, 1.44 MB; `code-guide/` 21 files) and `repository/` (6 files). Root files: `INDEX.md` (165 lines) and `README.md`. Every batch folder has a `README.md` and a row in `documentation/INDEX.md`; every row has a folder.

### 3.1 Batch folders by status word in `documentation/INDEX.md`

| Status word | Folders | Which |
|---|---|---|
| Implemented (date and version) | 126 | all others |
| Concluded | 5 | research only |
| In progress since | 4 | `2026-09-24-minecraft-mcp-skill` (not on `main`), `2026-09-25-non-full-block-occlusion` (not committed), `2026-10-26-liquid-level-gauge-accessory` (delivered `b1477c09`, main merge pending), `2026-11-13-codebase-review` (this batch) |
| Planned | 2 | `2026-08-31-v3-vdu`, `2026-09-01-reaction-thermochemistry-tool` |
| Superseded | 1 | `2026-09-01-v3-full-cdu-draw-wall` (2026-09-06, by `2026-09-06-v3-literature-cdu-pumparounds`) |
| Abandoned | 1 | `2026-09-08-v3-newton-alternatives` (2026-09-09, DF-SANE found no root) |
| Free text "Merged into the custom-shader-system batch" | 3 | `2026-11-10-chimney`, `2026-11-10-prime-movers`, `2026-11-10-vacuum-pump` (not one of the status words defined at the top of `INDEX.md`) |
| Total | 142 | |

### 3.2 Folders with no `README.md`, files over 1 MB, sizes

| Check | Result |
|---|---|
| Batch folders with no `README.md` | none (142 of 142 have one); `reference/` and `repository/` have one |
| Tracked files over 1 MB | none. Largest: `2026-09-26-phase-ports-and-compressor/PHASE_PORTS_REVIEW.md` 905 KB (not over the size rule); next `2026-09-24-mixed-gas-junction/HANDOFF_REVIEW.md` 320 KB, `2026-09-26-phase-ports-and-compressor/DECISION_LOG.md` 308 KB, three copies of `rest1000.mcfunction` 282 KB |
| Folders by tracked size | `2026-09-24-coolprop-low-temperature` 1.95 MB (69 files), `2026-09-23-fluid-followups` 1.74 (142), `2026-09-26-phase-ports-and-compressor` 1.56 (13), `reference` 1.44 (24), `2026-09-23-fluid-scheduling-rest` 1.16 (84), `2026-10-15-custom-equipment-rendering` 0.56 (73), `2026-09-15-fluid-network` 0.48 (17), `2026-09-18-solid-phase-fluid-system` 0.45 (17), `2026-11-02-process-simulator` 0.40 (13), `2026-11-11-aqueous-solvents` 0.36 (21) |
| Non-document files tracked | 49 `.mcfunction`, 43 `.js`, 15 `.svg`, 6 `.jfc`, 17 `.java`, 5 `.patch`, 15 `.sh` (rigs under `2026-09-23-fluid-followups/f*/f*-rig` and `2026-09-23-fluid-scheduling-rest/wp5/wp5-rig`) |

### 3.3 Duplicates in `documentation/`

| Item | Count |
|---|---|
| Groups of identical tracked files | 50 groups, 108 extra copies, 1.36 MB |
| Largest | `rest1000.mcfunction` 282 KB in `2026-09-23-fluid-followups/f1`, `f2` and `2026-09-23-fluid-scheduling-rest/wp5` (3 identical copies); `fill100.mcfunction` 45 KB, `mixed100.mcfunction` 39 KB, `through100.mcfunction` 39 KB, `wp5-window.jfc` 36 KB, `minecraft-flightrecorder-config.jfc` 36 KB, each in 3 copies |

### 3.4 Code guide chapters (`documentation/reference/code-guide/`)

"Snapshot paragraphs end at line" is the line before the `## Read this when` heading. Where a chapter has no such heading: before its first non-dated structural `##` heading (README before `## Level 1`, `cooling-towers` before `## Geometry and placement`, `pipe-laying` before `## Contract and ownership`, `wired-control-power` before `## Class map`). `process-control` has no structural heading and every `##` section is dated (first at line 5), so its snapshot end is line 4. `fluid-science` and `verification` have dated sections inside the span before "Read this when"; the line given is still the one before that heading. KB are tracked sizes.

| Chapter | Lines | KB | Snapshot paragraphs end at line | Lines after the snapshot | Snapshot share of lines |
|---|---|---|---|---|---|
| `README.md` | 397 | 99.1 | 47 (`## Level 1` at 48) | 350 | 12 % |
| `client-gui.md` | 490 | 134.4 | 94 | 396 | 19 % |
| `column-operation.md` | 343 | 67.0 | 36 | 307 | 10 % |
| `column-v3.md` | 355 | 62.1 | 6 | 349 | 2 % |
| `cooling-towers.md` | 73 | 19.5 | 30 (no "Read this when") | 43 | 41 % |
| `effects.md` | 61 | 11.9 | 6 | 55 | 10 % |
| `equilibrium-engine.md` | 460 | 47.2 | 5 | 455 | 1 % |
| `equipment.md` | 404 | 125.9 | 102 | 302 | 25 % |
| `fluid-runtime.md` | 507 | 172.6 | 105 | 402 | 21 % |
| `fluid-science.md` | 544 | 172.8 | 92 | 452 | 17 % |
| `heat-exchangers.md` | 276 | 59.2 | 40 | 236 | 14 % |
| `instrument-accessories.md` | 142 | 30.9 | 61 | 81 | 43 % |
| `machine-assemblies.md` | 306 | 54.5 | 34 | 272 | 11 % |
| `pipe-laying.md` | 114 | 25.5 | 6 (no "Read this when") | 108 | 5 % |
| `process-control.md` | 69 | 17.9 | 4 (no "Read this when") | 65 | 6 % |
| `process-simulator.md` | 359 | 67.3 | 65 | 294 | 18 % |
| `rotating-machinery.md` | 189 | 39.8 | 54 | 135 | 29 % |
| `thermo-materials.md` | 647 | 85.6 | 111 | 536 | 17 % |
| `tutorial.md` | 135 | 20.7 | 4 | 131 | 3 % |
| `verification.md` | 355 | 128.5 | 94 | 261 | 26 % |
| `wired-control-power.md` | 92 | 18.1 | 49 (5 dated sections before `## Class map`) | 43 | 53 % |

Total of the 21 chapters: 6 318 lines (sum of the rows), 191 467 words. Chapters over 100 KB (a whole-file read returns at most about 25 000 tokens, roughly 100 KB at this text density): `client-gui` 134 KB, `equipment` 126 KB, `fluid-runtime` 173 KB, `fluid-science` 173 KB, `verification` 128 KB; `README.md` 99 KB is at the limit. `documentation/reference/` also holds the legacy `HYBRID_SOLVER_CODE_GUIDE.md` and `archive/` (older guide copies).

## 4. `build.gradle` (685 lines) and `tools/development.gradle` (304 lines)

### 4.1 Structure

| Block | Lines | What |
|---|---|---|
| `sourceSets` | 20-34 | `mcpCompat` (9 files), `fluidGameTest` (45 java + 5 resources), `test` (adds the main compile classpath) |
| repositories, dependencies, NeoForge `runs` and `mods` | 41-315 | maven repos (createmod, ithundxr, blamejared, latvian, jitpack, Modrinth), optional mods (Create, KubeJS, Iris, Sodium, Distant Horizons, JEI), run configurations in 4.2 |
| Test configuration | 327-334 | `useJUnitPlatform`, `maxParallelForks` from `-PtestForks` (1 to 8) |
| Native C++ library | 338-426 | `src/main/cpp` 5 files, 742 lines; CMake from `-PnativeCmake`, `PATH` or Visual Studio |
| Gate-group builder | 534-598 | `gateGroup(name, tests, main, helpers, testResources, mainResources)` makes `<name>GateInputs` (Sync) and `<name>Gates` (Test) |
| Material index check | 675-685 | `verifyMaterialIndex` runs before `processResources` |
| Optional tools | `apply from` near line 653 | `tools/development.gradle` if the file exists |

### 4.2 Tasks in `build.gradle`

| Task | Type | Group | What it runs |
|---|---|---|---|
| `generateModMetadata` | ProcessResources | | writes `neoforge.mods.toml` into the main resources |
| `nativeConfigure` | Exec | native | CMake configure of `src/main/cpp` into `build/native` |
| `nativeBuild` | Exec | native | CMake `--build` Release; builds the shared library, no executable |
| `nativeResources` | Sync | native | puts the library under `natives/<os>-<arch>/` in the jar resources (only if CMake is found) |
| `nativeSmoke` | Test | verification | `NativeColumnSolverTest` (173 lines) with `-Dicheme.native.path`; fails if an `.exe` was built |
| `test` (configured) | Test | | quick suite (1.3); depends on `compileFluidGameTestJava` and `jar` |
| `fluidScienceTest` | Test | verification | `science.fluid.*` with the scheduler verifier on |
| `fluidRuntimeTest` | Test | verification | `runtime.fluid.*` and `runtime.Fluid*Test`, scheduler verifier on |
| `prepareFluidBenchmark` | task | | writes `run/fluid-benchmark/<id>/config` and `manifest.json` with SHA-256 of the jar, config and fixture classes (the build's only content hashes, used as benchmark provenance) |
| `fluidServerBenchmark` | task | verification | runs `runFluidBenchmarkServer`, scans `latest.log` for 7 error patterns, writes `runtime-audit.json` |
| `fluidSolverRegression` | Test | verification | chain-100 replay (1.3) |
| `columnGateInputs`, `equipmentGateInputs` | Sync | | copy the class subsets the gates load into `build/gate-inputs/<name>` |
| `columnGates`, `equipmentGates` | Test | verification | gate groups (1.3), 1 GB heap |
| `holdGates` | Test | verification | the 10 slow classes |
| `slowGates` | task | verification | `holdGates` + `columnGates` + `equipmentGates` |
| `check` (configured) | | | depends on `slowGates` |
| `v3HollandBenchmark` | Test | verification | `HollandExample32BenchmarkTest`, writes a JSON report |
| `verifyMaterialIndex` | task | | `materials-index.json` lists each bundled material JSON exactly once |
| `prepareMcpControlMod` | Copy | | copies the pinned bridge jar from `run/mcp-client-mods/` into `run/mcp-client/mods/` |
| `wrapper` (configured) | Wrapper | | BIN distribution |
| Runs from the `neoForge.runs` block | | | `client`, `server`, `fluidGameTestServer`, `columnGameTestServer`, `reviewWorldServer`, `fluidBenchmarkServer`, `mcpClient` (each becomes a `run<Name>` task) |

### 4.3 Tasks in `tools/development.gradle` (all `JavaExec`)

| Tasks | Main class | Source set | Tracked source for the main class |
|---|---|---|---|
| `generateNeuralMvpData`, `evaluateNeuralMvp` | `V3NeuralMvpProbe` | `neuralMvp` (`tools/neural`) | yes |
| `generalNeuralExperiment` | `V3GeneralTrainingProbe` | `neuralMvp` | yes |
| `generalNeuralParallelCheck` | `V3GeneralParallelCheck` | `neuralMvp` | yes |
| `generalNeuralModelParity` | `V3GeneralModelParityCheck` | `neuralMvp` | yes |
| `factorizedNeuralModelParity` | `V3FactorizedModelParityCheck` | `neuralMvp` | yes |
| `concurrentColumnEvaluation` | `V3ConcurrentColumnEvaluationProbe` | `neuralMvp` | yes |
| `concurrentColumnEvaluationCheck` | `V3BoundedEvaluationCheck` | `neuralMvp` | yes |
| `transformerNeuralModelParity` | `V3TransformerParityCheck` | `neuralMvp` | yes |
| `nearestProfileModelParity` | `V3NearestModelParityCheck` | `neuralMvp` | yes |
| `qualifyNeuralWetStates` | `V3WetQualificationProbe` | `neuralMvp` | yes |
| `generateMethaneNeuralData`, `evaluateMethaneNeural`, `generateMethaneWetData` | `V3MethaneTrainingProbe` | `neuralMvp` | yes |
| `v3TimeoutBenchmark`, `v3TimeoutBenchmarkHarnessTest` | `V3TimeoutBenchmark` | `benchmark` (`benchmarks/`, 0 tracked files) | none: unused |
| `v3FlashFailureCapture` | `V3FlashFailureCapture` | `benchmark` | none: unused |
| `v3FlashReplayProbe` | `V3FlashReplayProbe` | `benchmark` | none: unused |
| `v3FlashTruncationProbe` | `V3FlashTruncationProbe` | `benchmark` | none: unused |
| `v3ContinuationProbe` | `V3ContinuationProbe` | `benchmark` | none: unused |
| `v3LowPressureProbe`, `v3LowPressureLinearProbe` | `V3LowPressureProbe`, `V3LowPressureLinearProbe` | `benchmark` | none: unused |
| `v3MainOptimizationProbe`, `v3FlashOptimizationProbe` | `V3MainOptimizationProbe`, `V3FlashOptimizationProbe` | `test` runtime classpath | none: unused |

10 of the 24 tasks name a main class with no tracked `.java` file anywhere in the repository (the `benchmark` source set directory `benchmarks/` is empty in git): unused. The `neuralMvp` tasks compile `tools/neural` (25 `.java`); its 13 task classes exist. `tools/neural` is 26.9 MB tracked; `gen3-case-map.jsonl.gz` 7.8 MB, `gen3-model-card.json` 6.0 MB and `generalized-case-map.jsonl.gz` 3.2 MB are over 1 MB.

