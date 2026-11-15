# C3 Machines: the laws below the sheet and the geometry: report

Batch C3 of `WAVE_C_BRIEFS.md` (subagent, Opus high), branch `worktree-agent-aec45cb17e127d621` after a fast-forward to `claude/codebase-review-refactor-8a85ef` at `32cfe2d89`. **Nothing compiled or run** (the brief: no Gradle, no JVM); every changed file and every caller (`src/main`, `src/test`, `src/fluidGameTest`, `src/mcpCompat`) was re-read and grepped instead. Every item is a move: the same operations in the same order, the same constant values and names, no enum constant, saved name or format touched.

Done: `science/machine` imports only `science/math` (and the JDK); no code line names `science.flowsheet`, `science.fluid`, `science.equipment`, `science.plant` or `science.column` (grep, comments and strings stripped as `LayeringTest` strips them). `LayeringTest.PACKAGE_RULES` gains `machine-free-of-fluid`, `-flowsheet`, `-equipment` (14 rules). `map.mjs --check`: README counts current.

| Item | Commit | What moved | src/main lines |
|---|---|---|---|
| 1 (M-14, P3-09) | `ed1f3fe4a` | `EjectorLaw` `git mv` `science/flowsheet` → `science/machine` (62 lines, package line only). `SteamEjector.RATED_MOTIVE` 1e6, `RATED_DISCHARGE` 1.2e5 are literals now; `EjectorUnit.MOTIVE_PRESSURE`, `DISCHARGE_PRESSURE` (names kept) read them. `SteamEjector.SPEED` 60, `SPEED_EDGE` .05, `SPEED_EXPONENT` .25; `Limit.VACUUM_SPEED`'s row reads them; `SteamEjector.speed` evaluates the row's rising step itself (`Bound.Step.at`: `60 * pow(.05 / max(key, 1e-9 * .05), .25)` under the edge, 60 to +inf) | +35 −17 |
| 2 (M-14) | `486163418` | New `science/fluid/network/InlineMovers` (62 lines): `design`, `ratedDraw`, `shaftPower`, `ratedShaftPower` as written. `InlineMachines` keeps `inletFlow` (the bore check and `PI b² / 4 · (2 or 10)`), `law(flow, liquid)`, `electricPower`, `SET_FLOW_PUMP_EFFICIENCY`; `design` = `min(MAXIMUM_VOLUME_FLOW, inletFlow)` then `law`, the original order. `ElectricStore.capacity`, `motorCapacity`, `perTick`, `restart`, `motorRestart` take the window's process seconds; `ElectricStores` passes `time.seconds(SLICE_TICKS)`, `seconds(PERIOD_TICKS)`, `tickSeconds()` (the same product order) | +106 −80 |
| 3 (E6) | `2e957c47e` | `VacuumPump.Model`, `RINGS`, `SCREWS` (names, numbers, order kept), `VacuumPump.model(models, number)` (the same clamp and `Math.round`), `VacuumPump.of(model, dryScrew, stages)` (the same type choice, `capacity / 3600`). `VacuumPumpDesign` keeps the shape's pick, `name`, `nozzle` and the drawing; reads the record by accessors | +44 −29 |
| 4 (M-15) | `174b81a30` | `ProcessUnit.SHAFT_DEFAULT_SIZE` 4 beside `shaftPixels`; `ShaftBlock.DEFAULT_SIZE` (name kept) and `ShaftTrains` (two places) read it | +8 −3 |
| 5 | `a28117873` | `LayeringTest`: the three rules | test only |

Imports that no longer cross: `machine.SteamEjector` → `flowsheet.EjectorLaw`, `flowsheet.sizing.Limit`, `flowsheet.unit.EjectorUnit` (qualified); `machine.InlineMachines` → `fluid.network.FlowControl`; `machine.ElectricStore` → `fluid.SimulationTime` (five qualified names); `runtime.fluid.ShaftTrains` → `world.level.block.ShaftBlock` (two qualified; `ShaftTrains` names no `world.level.block` type now). The runtime-free-of-world rule stays out (other references remain).

## Decisions and what differs from the review's drawing

- Item 1: the speed is written twice, as one line in `SteamEjector.speed` and as the `Bound` row (which the sheet's lamps, Auto and the line sizing read as data). The constants are shared; the new `LimitTest.theVacuumSpeedRowIsTheWorldsEjectorSpeed` pins the two to the same bits on 13 pressures (0 Pa to +inf) and pins that both refuse NaN. Only difference: `speed(NaN)` throws `IllegalArgumentException` with the text "Invalid suction pressure NaN" instead of the row's "Outside the row's steps: NaN" (no caller passes NaN: `capacity` returns 0 first).
- Item 1 side effect: `SteamEjector` no longer initialises `Limit` (which loads `ColumnSolve`, equipment and network classes); its load set shrinks.
- Item 2: `InlineMovers` (not the review's "into `FlowControl`"): `FlowControl` is a hot file; the class sits beside it in `science/fluid/network`, whose package already imports `science/machine`, so nothing in `science/machine` points up. `SET_FLOW_PUMP_EFFICIENCY` and `electricPower` stay in `InlineMachines` (no network type). `ElectricStore` takes plain seconds rather than moving `SimulationTime` to `science/math` (the review's other form would touch every caller of `SimulationTime`).
- Item 3: `Model.width` (a drawing number) moves with the catalogue, as the catalogue line holds it; the shape's pick stays in the design because it reads `EquipmentShape`. `UnitDesign.vacuumPump`, `vacuumPumpModel`, `designSpeed` are unchanged.
- Item 4: the new name is `ProcessUnit.SHAFT_DEFAULT_SIZE`; `ShaftBlock.DEFAULT_SIZE` keeps its name and value (a compile-time constant either way).
- `tools/simulator-sizing/archive/machine-rework/DriveFactsCorpus.java` (archived, not compiled) still calls `InlineMachines.design`; left as archived.
- Batch README history line: left to the main thread, as for C1b.

## Tests for the main thread

Gate groups: `equipmentGates` copies `science/machine` whole and `science/equipment/**`, so `VacuumPump`'s catalogue and `ProcessUnit.SHAFT_DEFAULT_SIZE` are inside it. No equipment test or class loads `InlineMovers`, `SteamEjector.speed` or `Limit` (grep), so no gate list needs a line. `columnGates`: nothing moved that it loads. No `build.gradle` change.

| Task | Classes | What it proves |
|---|---|---|
| `test` | `LayeringTest` | the three new rules hold (and the eleven old ones) |
| `test` | `LimitTest`, `SteamEjectorTest`, `EjectorTest`, `SteamEjectorConnectionTest`, `SteamEjectorRuntimeTest`, `SheetSizingTest`, `LineSizingTest`, `ServiceTest`, `FlowsheetPaletteCoverageTest`, `SizingCoverageTest` | item 1: the row and the machine's speed give the same bits; the law and the ejector's figures unchanged |
| `test` | `ElectricStoreTest`, `ShaftCouplingTest`, `TimeFactorElectricTest`, `HeatUnitPowerTest`, `ShaftPowerBookingTest`, `RotatingDriveReworkTest`, `ColumnRunTest`, `PhysicalFluidTopologyTest`, `PipelineFittingTest`, `MachineHealthTest`, `FiredHeaterFuelTest`, `SteamHeaterCoilTest`, `StillRuntimeTest`, `FluidFactsCompatTest` | item 2: store sizes, charges, in-line designs unchanged |
| `test` | `VacuumPumpTest`, `VacuumPumpMoverTest`, `WearTest`, `MoverHealthStatusTest`; `ShaftTrainTest`, `ShaftNetworkTest`, `SimulationTimeTest` | items 3 and 4 |
| `equipmentGates` | `ProcessUnitTest`, `EquipmentLayoutGoldenTest`, `EquipmentShapeCompatTest` | item 3: the drawn casings and the layout table unchanged; item 4 compiles in the group |
| `holdGates` | `ColumnEjectorTest` (and `FlowsheetColumnTest` if the lane's affected script names it) | item 1 on the column's ejector |
| `compileFluidGameTestJava` (with `test`) | `ElectricGameTests`, `ShaftTrainGameTests`, `CreateCouplingGameTests`, `RegistryFirstGameTests`, `AssemblyGameTests` | compile only unless the lane runs the fluid GameTests |

Points where compiling is not certain: `InlineMachines.law` returns a conditional of `CentrifugalPump` and `CentrifugalCompressor` as `MachineLaw` (both implement it: fine as a poly expression); `VacuumPumpDesign` imports the nested `VacuumPump.Model` (no other `Model` in `science/equipment` or `UnitDesign`); `ElectricStoreTest` defines static helpers named `capacity`, `restart`, `motorCapacity`, `motorRestart`, `perTick` taking a `SimulationTime`; `SteamEjector`'s javadoc links `{@link #SPEED_EDGE}` inside its own field group.
