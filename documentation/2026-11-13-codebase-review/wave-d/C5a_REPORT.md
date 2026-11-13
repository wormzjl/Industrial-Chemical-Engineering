# C5a The network's thermodynamics port in files, and thermo below the solver (F8): report

Batch C5a of `WAVE_D_BRIEFS.md` (subagent, Opus high). Branch `worktree-agent-ac983673af3383add`, fast-forwarded to `claude/codebase-review-refactor-8a85ef` at `f87936d22`. Three code commits, one per step of the brief: `16d2d65f3` (step 2, `StepPhaseRules`), `4ea72e379` (step 3, `NetworkStreams` and `NetworkWater`), `33044dd9f` (step 4, `LegacySplit`), then this report with the code guide. Step 1 is the table below. **Nothing was compiled or run** (no Gradle, no JVM). Structure only: the same floating-point operations and model calls in the same order, so chain-100, the junction lines, every counter and the digest must not move.

**Lines.** `FluidThermodynamics` goes from 1,594 to 1,255 lines (step 2: 1,448; step 3: 1,262; step 4: 1,255). New files: `solver/StepPhaseRules` 174, `thermo/NetworkStreams` 208, `thermo/NetworkWater` 64, `state/LegacySplit` 10.

## Step 1: the members of `FluidThermodynamics` at `f87936d22`

Callers are files outside the class that name a member of the group (`.name(` or `FluidThermodynamics.NAME`), by source set: main / test / fluidGameTest / lane harnesses (`tools/unified-thermo-port-forms`, `tools/phase-ports-probes`); `mcpCompat` names none. By name, so an upper bound for generic names (`state`, `prepare`, `on`, `time`, `domain`).

| Role | Members | Callers | Fields read | Now |
|---|---|---|---|---|
| Package binding, views | `forNetwork`, constructors, `on`, `island`, `whole`, `withTime`, `compounds`, `wet`, `catalog`, `revision`, `thermodynamicRevision`, `time`, `traceTruncation`, `maximumVelocityMetresPerSecond`, `domain`, `componentCount`, `components`, `molecularWeight(s)`, `pumpReferenceDensity`, `initialNitrogenCharge` | 156 / 269 / 12 / 44 | `catalog`, `whole`, `views`, `domain`, `hydrocarbon`, `maximumVelocity`, `tracePolicy`, `solidSettings`, `time`, the revisions | stays |
| Crystal answers | `crystalSolids`, `solid`, `crystalsAtVolume`, `crystalsAtPressure`, `engineEnergy`, `crystalAnswer`, `crystalsCompete`, `standIn`, `condensedShareOnly`, `CrystalAnswer` | 15 / 16 / 0 / 1 | `crystalIds`, `crystalSpecies`, `crystalTriple`, `crystalCeiling`, `crystalSolids`, `phaseEngine`, `domain`, `hydrocarbon`, `solids` | stays (below) |
| Data-range notices | `dataRanges` | 1 / 1 / 0 / 0 | `domain`, `hydrocarbon`, `viscosity`, `water` | stays |
| Stream functions | `velocityLimit`, `isothermalAcousticBound`, `heatCapacityRatio`, `holdsVapor` … `phaseStream` (30 public, private `acoustic`), `PhaseStream`, `GAS`/`OIL`/`AQUEOUS` | 45 / 56 / 3 / 14 | `maximumVelocity`, `hydrocarbon`, `viscosity`; calls `match`, `molecularWeight`, `total` | `NetworkStreams` (step 3); record and labels stay |
| Water functions | `waterLiquid`, `vaporWaterEnthalpy`, `saturationPressure`, `saturationTemperature`, `waterSaturation` (field and builder), `WaterLiquid` | 7 / 52 / 0 / 8 | `water`, `waterConstants`, `waterEnthalpyOffset`, `whole`, `hydrocarbon` | `NetworkWater` (step 3); record stays |
| Facts' legacy split | `LegacySplit`, `legacySplit`, `fromLegacySplit` | 2 / 1 / 0 / 0 | none (calls `componentCount`, `total`, `placedState`) | record to `fluid/state` (step 4) |
| State builders | `state` (5 overloads), `adoptingState` (2), `tangents`, `slotPhase`, `support`, `solidState`, `prepare` (2), `own`, `match`, `Prepared` | 96 / 180 / 7 / 37 | `hydrocarbon`, `viscosity` (through `Prepared.owner`) | stays |
| Trace fold | `PHASE_TRACE_FRACTION`, `VANISHING_SHARE`, `VANISHING_COLLAPSE`, `foldTracePhases`, `folded` (7), `rigidGas`, `phaseBits`, `vanishing`, `nothing`, `belowShare`, `collapsing`, `phaseAmounts`, `phaseVolumes` | 8 / 3 / 0 / 0 | none (statics; `folded` calls `placedState`) | stays |
| Phase rules of a step | `phaseCheck` (2), `junctionSplit`, `ownPhaseBits`, `lacking`, `tpSeed`, `crossing`, `SLACK_PRESSURE` | 3 / 2 / 0 / 0 | none (calls the fold, the engine entries, `standIn`, `adoptingState`; `junctionSplit` runs `PhaseLayout` and `SparseNewton`) | `solver/StepPhaseRules` (step 2) |
| Flashes, adoption | `flashTP` (2), `equilibriumTP`, `equilibriumPH` (3), `fluidPH`, `equilibriumAtVolume`, `equilibriumUV`, `stepCompetition`, `crystals`, `adopt`, `placed`, `conserved`, `placedState`, `engineIndex` | 25 / 133 / 2 / 22 | `hydrocarbon`, `domain`, `crystalIds`, `crystalSpecies`, `crystalCeiling`, `phaseEngine` | stays |
| Holds | `CriticalBandHold`, `UnsupportedPhases`, `sum` | many (nested types) | none | stays |

## Step 2: the phase rules of a step to `science/fluid/solver/StepPhaseRules` (`16d2d65f3`)

**Form.** A public final class with a private constructor and static members only; each takes the model it judges as its first parameter (`FluidThermodynamics model`). No holder: the solver package cannot be built by the thermo port, and a holder built by the callers would allocate. Moved in source order: `phaseCheck` (both overloads, with the long doc), `ownPhaseBits`, `lacking`, `tpSeed`, `junctionSplit`, `crossing`, `SLACK_PRESSURE` (old lines 953-1024 and 1075-1153). `tpSeed` and `crossing` had no other caller. No forwarder stays.

**Edits that are not a plain move:**
- `static` and a `FluidThermodynamics model` parameter on 7 signatures; 13 `model.` qualifiers (one is the call below); 11 inserted `model` arguments (to the moved helpers and the inner `phaseCheck` call); `this` to `model` twice in `junctionSplit`'s `new PhaseLayout(…)` and `PhaseLayout.support(…)`.
- `crossing` reaches the private `equilibriumUV` through its existing public forwarder `equilibriumAtVolume` (same arguments, same answer, one frame more): the brief's "a public method gives the value already". Its doc link follows.
- `{@link #x}` in the moved docs to members that stayed now read `FluidThermodynamics#x`; the thermo port's own links to moved members read `{@code StepPhaseRules.x}`.

**Public members added to `FluidThermodynamics` (4; the stop rule's limit is about ten):**
- `folded(State,boolean,int)`, was private: the fold with the vanishing phases, which `phaseCheck` calls with its shrinking bits. No public overload takes them. Resolution is unambiguous: `(State,boolean,boolean)` and `(State,boolean,State)` do not take an `int`.
- `nothing(State,boolean)`, was package-private: the refusal test of a proposed phase.
- `sum(double[])`, was private: the validating amount sum of the completeness test (it throws on a negative or non-finite amount; the unvalidating `total` would not).
- `AMBIENT_PRESSURE` = 101325, moved down from `PhaseLayout` (doc with it), because `NetworkCrystals` reads it. `PhaseLayout.AMBIENT_PRESSURE` stays as `=FluidThermodynamics.AMBIENT_PRESSURE`: about 45 callers above the solver read it (`runtime`, `client/gui/flowsheet/AssemblyBlock`, `flowsheet`, `equipment`, tests), some outside my lane. Both are compile-time constants (JLS 15.29), so every use site still inlines 101325.0 and no class is loaded for it (the equipment gate's `ColumnComponents` reads it).

`StepPhaseRules` itself is public with three public statics (`phaseCheck` ×2, `junctionSplit`): they replace the public methods that left the model.

**The import rule.** `grep -rn "science.fluid.solver" src/main/java/com/wormzjl/icheme/science/fluid/thermo` is empty (the two imports of `FluidThermodynamics` and the one of `NetworkCrystals` are gone; no comment names the package). `LayeringTest.PACKAGE_RULES` gains `fluid-thermo-below-solver` (`science/fluid/thermo` → `science.fluid.solver`). `UnaryOperator` left the model's imports with `phaseCheck`.

**Callers edited** (all of them; grep of `src/**` and the lane harness folders):
- main: `PhaseActiveSet`:79, `InventoryEquilibrium`:204, `StartSeeds`:205 and :415 call `StepPhaseRules.x(model,…)` (imports added in the first and last; `InventoryEquilibrium` imports `solver.*`); doc links in those three, `PhaseLayout`:621, `SolverDiagnostics`:122;
- test: `NetworkPhaseEngineTest` (6 calls, 1 link, an import), `PrecipitatedSolidsTest` (3 calls, an import), `LiquidFullBubblePointTest` (doc only);
- fluidGameTest, mcpCompat, lane harnesses: none named a moved member.

## Step 3: `NetworkStreams` and `NetworkWater` (`4ea72e379`)

**Form.** Two package-private final classes in `fluid/thermo`, each with one private final field `model` and a one-line constructor. The model holds them in package-private finals `streams` and `pureWater`, built right after `this.whole=…` (`FluidThermodynamics.java`:127), `streams=new NetworkStreams(this);pureWater=new NetworkWater(this);` (the private constructor; the public one delegates). Each copy that `island`, `on` and `withTime` build runs that constructor, so it gets its own pair, on itself. Two objects per model; nothing per call.
- `NetworkStreams`: the 30 public stream functions with their docs and the private `acoustic`. Qualifiers: 12 `model.` (`maximumVelocity` ×3, `hydrocarbon` ×3, `viscosity` ×3, `match` ×2, `molecularWeight` ×1). `R`, `GAS`, `OIL`, `AQUEOUS` and `total` come in by static imports, so those lines keep their text. Public modifiers kept as written (inert in a package-private class).
- `NetworkWater`: the four water functions, the curve field and its lazy builder. Qualifiers: 16 `model.` (`water` ×7, `waterConstants` ×3, `hydrocarbon` ×3, `waterEnthalpyOffset` ×1, and `whole.waterSaturation()` ×2 now `model.whole.pureWater.waterSaturation()`, so a view still builds and reads its whole model's curve). `synchronized(this)` became `synchronized(model)`: the same monitor as before (the one `phaseEngine()` and `crystalSolids()` take). The qualifier pass skips string literals (the first dry run caught "Non-finite water temperature").
- The model keeps one-line public forwarders, in the old order, under one doc each for the streams and the water. `PhaseStream`, `WaterLiquid` and the labels stay nested. `liquidPhaseDensityOfSlot` stays with the adoption (`placedState` is its only caller); it reaches `slotMass` through the forwarder.

**Widened (private to package-private, nothing public):** `water`, `waterConstants`, `waterEnthalpyOffset`, `maximumVelocity`, `whole`, `match`, `total`.

**Crystal answers stay.** `crystalAnswer` calls `placed` and `engineEnergy`, which the adoption and `equilibriumPH`/`UV` share, and reads `domain` three times, `crystalSolids()` and `phaseEngine()`. They do not separate without field reads across more than a call (the brief's condition).

## Step 4: `LegacySplit` to `science/fluid/state` (`33044dd9f`)

The record alone, its doc de-indented; its link to the reader is `{@code FluidThermodynamics.fromLegacySplit}`. The model imports it; `legacySplit` and `fromLegacySplit` stay. No file names the type (`FluidFacts`:591, :647 and `FluidFactsCompatTest`:248 hold it in a `var`), so no caller changed.

## What `FluidThermodynamics` keeps (1,255 lines)

Constants (`R`, velocity, `AMBIENT_PRESSURE`, trace cutoffs, `TANGENT_COMPOSITION_STEP`, the trace and vanishing shares); the package binding, views and revisions; the phase engine and crystal solids; the crystal answers; `dataRanges`; the component basis; the 34 forwarders; the legacy split's two methods; the state builders and `Prepared`; the trace fold and the vanishing rules; the flashes and the adoption; the two holds; `State`, `PhaseStream`, `WaterLiquid`, `CrystalAnswer`.

## Checks run here (scripts in `tmp/c5a/`, not tracked)

- `step2.mjs`, `step3.mjs`, `step4.mjs`: the cuts. Each edit names its original line and literal and must match the expected count; the qualifier passes count each rewrite against an expected table; CRLF byte for byte; each ran first as a dry run.
- `verify.mjs f87936d22` (15 Java files, 436 lines removed, 545 added): after normalising qualifiers, modifiers, the model parameter and links, every removed line reappears but 23, all intended: 10 doc links, 3 imports, `this`→`model` (1), `equilibriumUV`→`equilibriumAtVolume` (1), `whole.waterSaturation()` (2), `synchronized` (1), `PhaseLayout.`→`FluidThermodynamics.AMBIENT_PRESSURE` in `NetworkCrystals` (2), `pilot.phaseCheck` in `PrecipitatedSolidsTest` (3). 0 CRLF faults; braces, parentheses and brackets balance in every file.
- `names.mjs`: every unqualified call in the three new classes is the class's own member or a static import. `fields.mjs`: no unqualified read of a model field in them (the four `solids` hits in `StepPhaseRules` are `crossing`'s local).
- `node tools/code-guide/map.mjs --readme` (the `science/fluid/**` count 81 → 85), then `--check`: counts current.

## Points where compiling is not certain (checked by reading)

1. `StepPhaseRules.java`:3-6: static imports of `nothing`, `phaseBits`, `sum` and both `vanishing` overloads (public now). `tpSeed`'s local `double sum` shares the name of the imported method; separate namespaces, as before in the model.
2. `StepPhaseRules.java`:57, :65, :77, :168: `model.folded(…)` picks `(State,boolean,int)` at :57 and `(State,boolean)`/`(State,boolean,boolean)` elsewhere, as before.
3. `FluidThermodynamics.java`:78-81 and :127: blank finals `streams`, `pureWater`, assigned once in the private constructor, before any use; `this` is only stored.
4. `NetworkWater.java`:39, :45: `model.whole.pureWater.waterSaturation()` calls a private method of the same class on another instance; legal.
5. `NetworkStreams.java`:3-7: static import of the package-private `total`, same package; `heatCapacityRatio`'s local `total` is a variable.
6. `PhaseLayout.java`:330: `AMBIENT_PRESSURE=FluidThermodynamics.AMBIENT_PRESSURE` is a constant expression; `PhaseLayout` already imports the model.
7. Javadoc links to private or package-private members (`FluidThermodynamics#adopt`, `#rigidGas`, `NetworkStreams#…` from public docs): the build runs no doclint.

**Left stale (not compiled by the lane):** solver copies under `tools/wti-pumping-rate-hold/**`, `tools/fluid-world-inspector/**` (and `PropertyAudit.java`), `tools/unified-thermo-port-m2/diffs/`, `tools/junction-seeding/detached/` call `model.phaseCheck`/`junctionSplit` on their own copies. `tools/unified-thermo-port-forms/probe/FormsProbe.java` already names APIs main no longer has (`PhaseLayout.sum`, `vaporView`).

## Tests for the main thread

| Task | Classes | What it proves |
|---|---|---|
| `test` | `FluidThermodynamicsTest`, `AqueousNetworkGatesTest`, `WetGasGatesTest`, and the rest of `science.fluid.thermo`: `AmbientCrudePresetTest`, `CarriedCrystalsTest`, `DataRangeContinuationTest`, `DirectLiquidContinuityTest`, `DirectLiquidDeterminismTest`, `FluidNitrogenCryogenicTest`, `FluidPropertyCoverageTest`, `HydrocarbonModelTest`, `HydrocarbonTangentTest`, `NetworkPhaseEngineTest` (edited), `NitrogenInitializationTest`, `PreparedRefillTest`, `RangeContinuationGateTest`, `RestrictedPackagePhysicsTest`, `SpineNetworkPathTest`, `TranslatedPengRobinsonTest`, `WaterRegion1Test` | the streams, water functions, fold and phase check bit for bit through the forwarders and `StepPhaseRules`; the water curve per whole model |
| `test` | `LayeringTest` | the new rule holds; no package rule crossed |
| `test` | `FluidFactsCompatTest` (runtime) | `LegacySplit` read and written as before |
| `test` | `PrecipitatedSolidsTest` (edited), `LiquidFullBubblePointTest`, `CrystalDepositionIslandTest`, `ReleasePressureTest` | the vessel's crossing, the phase check with crystals, the constant |
| `test` + `holdGates` | the D-S5 list (62 classes; [D-S5_REPORT.md](D-S5_REPORT.md), tests table) | the step solver bit for bit through `StepPhaseRules` (`PhaseActiveSet`, `StartSeeds`, `InventoryEquilibrium`) and the stream forwarders |
| `test` (dependency) | `compileFluidGameTestJava` | the GameTests compile |
| `fluidSolverRegression` | `FluidSolverRegressionTest` | chain-100 exact, with its counters |
| lane | `affected.mjs` classes for the 15 changed files; the 33 junction lines, the printed lines, the digest | every reference unchanged |

If a reference moves, bisect by the three commits. The only edit that is not a qualifier is the `equilibriumAtVolume` call in `crossing` (step 2), which runs the same method with the same arguments.

## Code guide

`fluid-science.md`: new class-map rows `StepPhaseRules`, `NetworkStreams`, `NetworkWater`, `LegacySplit`; the `FluidThermodynamics` row (forwarders, `AMBIENT_PRESSURE`, the phase rules gone); the phase-check, arriving-seed and symptom-table mentions now name `StepPhaseRules`; the layering line states the new rule. The batch README's history line is left to the main thread.
