# Verification: the test map, the GameTests and the compatibility corpus

Up: [code guide](README.md). The tasks and the final lane: [verification.md](verification.md). The regression, junction lines, campaigns and determinism: [verification-campaigns.md](verification-campaigns.md). Each subsystem's chapter names its own gates in its "Tests and gates" section; this chapter is the map across them.

## Read this when

- You need the test class that pins a rule, or the `--tests` filter and task that reach it.
- You change a facts schema, a shape field, a saved NBT tag, an enum constant or a layout, and must add a corpus image or update the layout table (AGENTS.md, "Saves and data formats").
- A GameTest fails, finds a leftover from another test, or a GameTest world must be fresh.
- A structural gate fails: `LayeringTest`, `FormatsTableTest`, `EngineDigestTest`, `DeterministicMathSourceTest`, `LanguageFilesTest`.

## What it does

The tests live under [`src/test/java/com/wormzjl/icheme/`](../../../src/test/java/com/wormzjl/icheme/), one package per production package they pin, and the GameTests under [`src/fluidGameTest/java/com/wormzjl/icheme/fluid/gametest/`](../../../src/fluidGameTest/java/com/wormzjl/icheme/fluid/gametest/). Unit tests run without a Minecraft server but with its classes on the class path, so codecs and NBT round-trip for real. Save compatibility is held by gates that read checked-in old saves under `src/test/resources/compat/` (never player worlds). Structural gates read the sources as text: the layer imports, the formats table, the math facade.

## Class map

The test packages and which task runs them. Paths under `src/test/java/com/wormzjl/icheme/`.

| Package | Pins | Task |
|---|---|---|
| root | `LayeringTest` (the import rules between the top-level layers, read from the sources), `FormatsTableTest` (every constant `FORMATS.md` names has the value it states) | `test` |
| `science/column/v3/**` | The V3 column: residuals and Jacobian, initializers and neural seeds, side draws, pumparounds, steam, hydraulics, water, the Holland benchmark, the native library's tests ([column-v3.md](column-v3.md)) | `columnGates`; `NativeColumnSolverTest` in `nativeSmoke`; `HollandExample32BenchmarkTest` also in `v3HollandBenchmark` |
| `science/equipment` | Geometry, layouts, designs, saved shapes ([equipment-geometry.md](equipment-geometry.md), [equipment-designs.md](equipment-designs.md), [equipment-structure.md](equipment-structure.md)) | `equipmentGates` |
| `science/fluid/**` | The network step, laws, solver, thermo port, topology, transport, conversion, `DeterministicMath` ([fluid-science.md](fluid-science.md)) | `test`, `fluidScienceTest` |
| `science/thermo/**` | The kernel, the equilibrium engine (`phase/`, `EngineDigestTest`), water, the qualification families `G3F*`, `G4F*`, `G5F*` (`qualification/`), the Helmholtz oracle (`reference/`) ([equilibrium-engine.md](equilibrium-engine.md), [thermo-materials.md](thermo-materials.md)) | `test` |
| `science/material`, `science/math` | The catalog, packages, revisions, assays; the math helpers | `test` |
| `science/machine`, `science/plant/**`, `science/assembly`, `science/flowsheet/**`, `science/tutorial/**` | Machine laws; the running column and the port arithmetic; assemblies; the process simulator and its sizing; the tutorial | `test` (the slow flowsheet classes in `holdGates`) |
| `runtime/**` | The engine, islands, scheduler, certificates, checkpoints, facts, presentation, heat coupling, columns and stills in the world, instruments, control, power ([fluid-runtime.md](fluid-runtime.md)) | `test`, `fluidRuntimeTest`; slow classes in `holdGates` |
| `network` | The wire codecs (column, inspection, equipment, assembly, tutorial) | `test` |
| `world/level/block/**` | Sent designs, names, pipe and rack NBT, floating roofs | `test` |
| `client/**` | The GUI kit and builders, the simulator's screen, the tutorial's screens, effects, LOD ([client-kit.md](client-kit.md)) | `test`; slow flowsheet classes in `holdGates` |
| `fluid/benchmark`, `fluid/support` | `FluidSolverRegressionTest` and its harness; shared test helpers | `fluidSolverRegression`; helpers |

Test overlays outside `data/` keep every other test on the game's catalog: `PilotCryogenicTestCatalog` (the cold fuzz's package too), `TiaJuanaTestCatalog` with `TiaJuanaExtension`, `PilotAqueousTestCatalog`.

## Main flow

To prove a change: list the files changed since the last pass and the tests that load them (the lane's `affected.mjs select` does this from recorded load sets; [verification.md](verification.md)); run those with `--tests` in their task; read `build/test-results/<task>/` first, since a stored pass stands.

## Contracts

**Structural gates** (all in `test`):

| Gate | Rule | How to read a failure |
|---|---|---|
| `LayeringTest` | A layer never imports or names by full name a type of a layer it may not (`science` imports nothing from `runtime`, `world`, `network`, `client`, …); package rules inside layers; a rule never leaves the test | Move the reference to the layer it belongs to; `node tools/code-guide/map.mjs` prints the matrix |
| `FormatsTableTest` | Every constant `documentation/reference/FORMATS.md` names (`Class#FIELD`) has the stated value | Change the code and the row together |
| `EngineDigestTest` (`science/thermo/phase`) | About 430 TP, PH, UV and onset requests on nine engines, one line of raw bits each, equal to `src/test/resources/thermo/engine-digest.txt` | A moved line is a moved result; record only with `ICHEME_ENGINE_DIGEST_RECORD=true` on a reviewed move |
| `DeterministicMathSourceTest` (`science/fluid`) | No non-correctly-rounded `Math`/`StrictMath` call or `Math.random` outside `DeterministicMath` in `science`, `runtime` and `fluid/benchmark` | Route the call through the facade |
| `LanguageFilesTest`, `UnitTextTest` (`equipmentGates`) | Every lang file has en_us's keys, no key twice, the same arguments; every unit text fits its widths in every language | Run only after the translation commit (AGENTS.md, "Translations") |

**Compatibility gates** read every image of their corpus; a missing image of the current revision is written under `build/compat/` and fails the test, and only that new image is copied in. An existing image is never re-recorded to make a gate pass (sheet files are the exception: `compat/flowsheet/` gets no compatibility work, so a sheet format change re-records it).

| Corpus under `src/test/resources/compat/` | Reader | Holds |
|---|---|---|
| `fluid/schema-<n>/`, `steam-v2/`, `compound-basis/`, `island-sets/`, `removed-movers/` | `FluidFactsCompatTest` | One fluid checkpoint per facts schema; worlds under an old steam model, a renamed package, two compound lists, removed movers |
| `equipment/shapes-<n>.snbt`, `repairs-exchanger-types.snbt` | `EquipmentShapeCompatTest` (`equipmentGates`) | Saved shell tags per shape-field revision; the exchanger-type repairs |
| `layouts/layouts.txt`, `layouts/crossflow-pipes.txt` | `EquipmentLayoutGoldenTest`, `CrossflowEquipmentTest` (`equipmentGates`) | What reference shapes place; the one file a change rewrites, after review |
| `constructor/designs-<n>.snbt` | `CrossflowEquipmentTest` | Constructor drafts |
| `accessories/schema-<n>.snbt` | `AccessoryStoreCompatTest` | The accessory store per facts schema |
| `pipes/pipe-<n>.snbt`, `racks/rack-<n>.snbt` | `PipeLineCompatTest` | Pipe joints and paints, rack lane copies |
| `line-pieces/` | `LineContractTest` | Lead copies |
| `control/`, `control-blocks/` | `ControlSystemTest`, `DcsConsoleGameTests` | Controller and DCS facts |
| `simulator/designs-<n>.snbt` | `SentDesignTagsTest` | A simulator block's sent designs |
| `names/names-1.snbt` | `DeviceNamesCompatTest` | Device names |
| `presets/` | `V3ClientPresetsTest`, `V3ColumnTemplateTest` | Old template schemas |
| `flowsheet/format-<n>*.json` | `FlowsheetFileTest`, `WiringTest` | Sheet files of the current format |

`.gitattributes` stores `compat/**/*.dat` and `*.bin` as binary and `*.snbt` as `-text`; `src/test/resources/science/thermo/coolprop/**` is `-text` so its README's SHA-256 stay verifiable.

**GameTests** (source set `fluidGameTest`, its own mod `icheme_fluid_test`, `FluidTestMod`; never in the production jar). Namespaces: `icheme_fluid_test` (`runFluidGameTestServer`, the lane's step), `icheme_column_test` (`ProcessSimulatorGameTests`, `runColumnGameTestServer`), `icheme_review` (review worlds, not gates), `icheme_fluid_benchmark` (`FluidServerBenchmark`). The fluid namespace's classes:

| Class | Holds |
|---|---|
| `FluidHarnessGameTests` | Unloaded-chunk simulation, sparse solver loading, the empty-world check, the deliberate-failure proof |
| `FluidCheckpointGameTests` | A resting tank reloads certified, on the exact route |
| `FluidPresentationGameTests`, `FluidPacketGameTests`, `FluidCompletionBudgetGameTests` | Open replays the snapshot and follows the bucket; handlers refuse invalid, stale, distant, read-only and flooded edits; the routing budget per tick |
| `FluidPhasePortGameTests`, `FluidPumpStartupGameTests`, `SolidPhaseGameTests`, `FluidPropertyReloadGameTests`, `MaterialReloadGameTests` | Phase outlets, a hot pump's start, filter capture and recovery, a property reload, an atomic `/reload` |
| `ChunkLoadGameTests`, `DrawnJointGameTests`, `RegistryFirstGameTests` | Joints drawn from the registry; an in-line machine's joints; the world re-aligned to the registry |
| `EquipmentCompatGameTests`, `EquipmentProtectionGameTests` | A cell's revision or stamp on load, the skylight restate, a retired kind removed whole, a blocked refresh retried; protected removal and deconstruction |
| `PipeLayingGameTests`, `ConduitLayingGameTests`, `CreativePowerGameTests`, `DcsConsoleGameTests` | Laid lines and racks, a column nozzle's largest pipe; the placement adapter for cables; creative power; the DCS console |
| `AssemblyGameTests`, `ColumnOperationGameTests`, `TutorialUnitGameTests` | Assemblies validated and reverted; a column runs in its plant, a DCS runs it; the guide's small tower at its design feed |
| `ControlLoopGameTests`, `LevelLoopGameTests`, `ReliefFlareGameTests`, `ProcessControlGameTests`, `ConverterGameTests` | A flow loop; level loops on the control cadence; relief to a flare; a valve's fitting across a refresh; converters both ways |
| `ShaftTrainGameTests`, `ElectricGameTests`, `PumpHealthGameTests`, `CreateCouplingGameTests`, `CoolingTowerGameTests` | Shaft trains, FE stores, pump wear and repair, a Create coupling (passes at once without Create), a tower bank |

Helpers: `TankCharge`, `FluidContentionProbe`, `EquipmentTestCleanup`, `EquipmentPermissionChecks`, `LayingTestCommands`, `ReviewBatch`.

## Laws and constants

- A GameTest structure is one cell; vanilla rings it with barriers, so a rig that needs free cells starts at x and z 2. Areas are not cleared between runs and grid slots keep advancing: a test removes what it registers, or a later one finds it (the empty-world check fails).
- A GameTest server does not pace its ticks: a tick budget is not time for work on another thread; a test that reads light waits for the light engine's queued work.

## Formats

The corpus images are old saves of the formats in [FORMATS.md](../FORMATS.md); a facts-schema or shape-field change adds one (AGENTS.md). State formats get no image: a mismatch drops the state.

## Tests and gates

Filters that reach a change, by area (run in the task named; narrow to the changed classes' tests):

| Area | Filter |
|---|---|
| A saved fact, shape field, enum or layout | `equipmentGates --tests '*EquipmentShapeCompatTest' --tests '*EquipmentLayoutGoldenTest' --tests '*EquipmentRefreshTest'`; `test --tests '*FluidFactsCompatTest' --tests '*FluidCheckpoint*Test' --tests '*AccessoryStoreCompatTest' --tests '*PipeLineCompatTest'` |
| A pipe fitting, a mover's ports, a gauge | `test --tests '*PipelineFittingTest' --tests '*PhysicalFluidTopologyTest' --tests '*GaugeAccessoriesTest'` |
| The laying tool, racks, joints | `equipmentGates --tests '*PipeRouteTest' --tests '*PipeRackGeometryTest' --tests '*LineGeometryTest'`; `test --tests '*PipeLineCompatTest'` |
| A running column | `test --tests 'com.wormzjl.icheme.science.plant.column.*' --tests '*ColumnRunTest' --tests '*RegulatingValveHoldTest'` |
| Assemblies | `test --tests 'com.wormzjl.icheme.science.assembly.*' --tests 'com.wormzjl.icheme.runtime.assembly.*' --tests '*AssemblyNetworkCodecTest'` |
| The process simulator | `test --tests 'com.wormzjl.icheme.science.flowsheet.*' --tests 'com.wormzjl.icheme.client.gui.flowsheet.*'`; the slow classes with `holdGates --tests` |
| The engine and kernels | `test --tests 'com.wormzjl.icheme.science.thermo.*'` with `EngineDigestTest`; chain-100 exact |

## Traps

- `fluidScienceTest` and `fluidRuntimeTest` miss the engine, the catalog and the column; only `test` and `columnGates` reach them.
- The test classes `G3F1`..`G3F10`, `G4F1`, `G4F2`, `G5F1`..`G5F6` and the "gate G0 to G6" of the thermo qualification are thermo families, not lane steps.
- A test that edits a structure cell goes through `EquipmentPermissionChecks`; plain writes are refused by the protection.
- Benchmark worlds must be fresh: `prepareFluidBenchmark` refuses an existing one.

**Snapshot:** `d6f721b94`, 2026-11-15.
