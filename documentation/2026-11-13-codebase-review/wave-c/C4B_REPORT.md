# C4b The still's column solver out of the network (C17 option (a)): report

Batch C4b of `WAVE_D_BRIEFS.md` (subagent, Opus high), branch `worktree-agent-ae77e466e33874532` after a fast-forward to `claude/codebase-review-refactor-8a85ef` at `f2543d8fa`. **Nothing compiled or run** (the brief: no Gradle, no JVM); every changed file was re-read and every moved symbol grepped in `src/main`, `src/test`, `src/fluidGameTest`, `src/mcpCompat`, `tools/` and the code guide. Structure only: the same thermo calls with the same arguments in the same order, the same doubles read in the same expressions; no saved name, enum constant or format touched.

Commits: `1bd824f9d` step 1, the move (git mv, package lines, imports; `StillColumn.fractions` public, as `BatchStill` reads it from another package now). `67bf08836` step 2, `StageThermo` and `NetworkStageThermo`, every caller on the adapter, code guide. Between the two, `column-free-of-fluid` fails (step 1 still imports the model); both compile by my reading.

Done: `science/column/still/` holds `StillColumn` (1,260 lines) and `StageThermo` (68); it imports only `science/math` and the JDK. `science/plant/still/` holds `NetworkStageThermo` (139), `BatchStill` (597) and `StillHead` (160). Grep of the twenty `LayeringTest` rules (comments included, stricter than the test): no `science.fluid|equipment|machine|plant|flowsheet` name under `science/column`; no `science.column|equipment|plant|flowsheet` under `science/fluid`; machine and material clean; nothing Minecraft in the two new packages. No new rule added (`plant` has none; a `column-still` rule would repeat `column-free-of-*`). `map.mjs --check`: README counts current (`science/fluid` 75 to 72; new row `science/column/still/` (2), `science/plant/still/` (3)).

Why `BatchStill` and `StillHead` moved (the brief's choice): both name `StillColumn` (`BatchStill.step(StillColumn, ...)`, `Pot.last` a `StillColumn.Split`; `StillHead.Job.warm` a `Profile`, `run` builds the column), so in `science/fluid` they would break `fluid-free-of-column`, and neither can take an interface instead (they call `solve` and read `Split`). Moving them is the smaller change; `science/plant/still` because `StillHead` reads `Pool.Head` and `FluidThermodynamics` (column may not), and the layer table puts the still's laws there. Nothing in `science/fluid` names either. `Pool.Head` stays in the network. The tests stay in their packages (`science/fluid/network/BatchStillTest`, `StillHeadTest`) with imports added: fewer changes, the same class names for `--tests`.

`src/main` lines: 23 files, +295 −115 (step 1: package and import lines, 21 files; step 2: `StillColumn` +37 −69, the two new files, two construction lines). `Stills` changes only its two imports (`BatchStill`, `StillHead`) and the qualified `StillColumn.TOTAL_CONDENSER` (line 451): three lines, for C4c's merge. Other qualified-name rewrites: `FxSources`, `FxSounds`, `UnitDoors`, `FluidDeviceScreen`, `IChemE`, `ProcessSolveCoordinator`, `ProcessSolveServices`, `BuiltInInputs`, `ElectricStores`, `FluidFacts`, `FluidWorldAuthority` (plus an import beside its `fluid.network.*`), `StillUnit`, `Limit`, `StillSizing`, `BatchStillUnit`, `StillBatch`, `EquipmentStructure`; tests `FluidFactsCompatTest`, `StillRuntimeTest`, `BatchStillUnitTest`, `StillSizingTest`, `EthanolMainDataTest`, `StillRunRecorder`, `StillRunRecordingTest`; the archived `tools/custom-equipment-rendering-poc/frame-time-probe/FrameTimeProbe.java`.

API change on `StillColumn`: the constructor takes `(StageThermo, double)`; `model()` is gone; `thermo()` returns the `StageThermo`. Test reads of the model: `BatchStillTest.index` casts `column.thermo()` to `NetworkStageThermo` for `model().components()`; its crude test reads `REFINERY` where it read `column.thermo()` (the same object); `EthanolMainDataTest` keeps its adapter.

What could not move: nothing in this brief's scope. Per-solve state (`flashes`, `outlet`, `asked`, `closedAsked`, `shortOfReflux`) stays in `StillColumn`'s fields, as the brief says.

`build.gradle`: no line changes. `test` excludes only `science/column/v3/**` and `science/equipment/**` test folders, so the still's tests stay in `test`; `columnGates` lists `science/column/v3` classes and nothing it loads moved; no `equipmentGates` test loads `BatchStill` (its list never held it; the equipment tests name the `BATCH_STILL` kind only). No GameTest or `mcpCompat` source names a moved class.

Points where I am not sure it compiles:
- `NetworkStageThermo` names `TranslatedPengRobinson.Workspace` (the inherited `TranslatedCubic.Workspace`, as `HydrocarbonModel`'s signatures do) in a private record that implements the inherited member interface `Prepared`; and uses the inherited `Root`, `Phase`, `Phases` unqualified beside `var phase = model.phase(...)` (a `HydrocarbonModel.Phase`, never named).
- `NetworkStageThermo.root(Root root)` beside the parameter `root` in `phase` and `logFugacity` (methods and variables are separate namespaces).
- `BatchStillTest` single-type imports (`BatchStill.Design`, ...) over its own package `science.fluid.network`, as before the move.
- Doc links: `StillColumn` links only `StageThermo` members and its own; `BatchStill` and `NetworkStageThermo` appear as `{@code}`.

## The interface, from what `StillColumn` read before the move

| Old call in `StillColumn` | Fields read | `StageThermo` method | Adapter (`NetworkStageThermo`) |
|---|---|---|---|
| constructor: `model.componentCount()`, `model.wet()` | | `componentCount()`, `water()` | `n`, `water` computed as the column did |
| `components(model)` (spine, else property package), `model.evaluator().derivation()` for water | `criticalTemperatureKelvin`, `criticalPressurePascal`, `acentricFactor`; derivation's `[water]` | `criticalTemperature(i)`, `criticalPressure(i)`, `acentricFactor(i)` | the column's loop, moved whole, in the adapter's constructor |
| `model.molecularWeight(i)` | | `molecularWeight(i)` | copied array |
| `model.phase(t, P, conserved(x), Root)` (liquidRoot twice, liquid/vapour enthalpy and volume) | `molarVolume`, `molarEnthalpy` | `phase(t, p, x, Root)` → `Phase(molarEnthalpy, molarVolume)` | the same 4-argument overload (its own `prepare(t)` inside); the record's fields are eager accessors, no second evaluation |
| `model.prepare(t)` (eval, outletAt) | the workspace | `prepare(t)` → opaque `Prepared` | wraps the one workspace; reused by every later call at `t` as before |
| `model.phase(t, P, conserved(x), Root, terms, false)` | `logFugacityView()` | `logFugacity(t, p, x, Root, prepared)` | the same 6-argument call; returns the same array |
| `thermo.flashTP(t, P, conserved(x), NO_CHECKPOINT)` (layers, outletAt) | `holdsPhase` OIL, AQUEOUS, GAS; `labelAmountsView` OIL, AQUEOUS (layers), GAS, OIL, AQUEOUS (outletAt) | `flash(t, p, x)` → `Phases(gas, oil, aqueous, gasAmounts, oilAmounts, aqueousAmounts)` | one `flashTP` (the 4-argument overload, a no-op checkpoint); the six reads are pure (`State.slot`, the state's arrays or a fresh zero array), taken from that state at once |

`conserved(x)` (the identity on a wet model, water appended at zero on a dry one) moved into the adapter and is applied where the column applied it, so each model call gets the same array (on a wet model the very array). Flashes are still counted by the column before each `flash` (`Split.flashes`). The one change of order: `holdsPhase(GAS)` in `layers` is read when the split returns instead of after the lever rule; it is a pure read of the same state, so no result moves. A `RuntimeException` from any call propagates as before, into the same `try` blocks.

## Tests for the main thread

All in `test` (`./gradlew test --tests <class>`), plus `compileFluidGameTestJava` (which `test` depends on):

| Class | Why |
|---|---|
| `com.wormzjl.icheme.science.fluid.network.BatchStillTest` | the column and the still's law, bit for bit |
| `com.wormzjl.icheme.science.fluid.network.StillHeadTest` | the head on the adapter |
| `com.wormzjl.icheme.runtime.fluid.StillRuntimeTest` | the running still (`Stills`, `StillHead`) |
| `com.wormzjl.icheme.runtime.fluid.FluidFactsCompatTest` | `BatchStill.Design` in the facts images |
| `com.wormzjl.icheme.science.flowsheet.BatchStillUnitTest`, `com.wormzjl.icheme.science.flowsheet.sizing.StillSizingTest` | the sheet's still on the adapter; its sizing |
| `com.wormzjl.icheme.science.material.EthanolMainDataTest` | the column on the ethanol package |
| `com.wormzjl.icheme.science.tutorial.StillRunRecordingTest`, `StillTraysRecordingTest` | the recorded still runs |
| `com.wormzjl.icheme.client.fx.emitter.StillModelTest`, `com.wormzjl.icheme.client.fx.sound.FxSoundsTest`, `com.wormzjl.icheme.client.gui.common.StillDoorTest` | `BatchStill.Status` and `Heater` readers (the brief names `StillModelTest`) |
| `com.wormzjl.icheme.LayeringTest` | the twenty package rules |

`tools/merge-lane/affected.mjs`: the recorded load sets name the old paths `science/fluid/network/{StillColumn,BatchStill,StillHead}`, which this branch deletes, so the lane should select the tests that loaded them; the new paths are in no load set yet.
