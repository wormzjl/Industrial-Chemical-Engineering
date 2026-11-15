# G1 part 2: wet gas, wet exhaust, the gearbox limit and the unreachable trips

Branch `worktree-agent-a8f49328df4068523`. Brief: `WAVE_G_BRIEFS.md`, G1 part 2 (N2, N4, N7 option A with M-13, M-03 option A).

Status: four items committed, then this document. **No test, Gradle, cargo or JVM run**: each commit was made to compile by reading every changed symbol's callers in `src/main`, `src/test` and `src/fluidGameTest`.

| Item | Commit | Files | Tests (written, not run) |
|---|---|---|---|
| N2 | `53637c19b` | `Wear` (`WET_GAS_ONSET`, `WET_GAS_SPAN`, `wetGas`), `PassiveStepSolver` (`PipeObservation.condensedShare`, `withCondensed`), `MachineHealth`, docs in `RepairKitItem`, `FluidWorldAuthority` | `WearTest.wetGasErodesACentrifugalCompressorAboveHalfAPercent`; `MachineHealthTest`: 3 % wet, dry gas, failed and repaired; `MoverHealthStatusTest.aCentrifugalCompressorReadsWetGas` |
| N4 | `9566906ac` | `Wear` (`WET_EXHAUST_*`, `wetExhaust`), `MachineHealth` (`faults`/`worn` on any control), `ShaftPlan` (FAILED on a `Turbine`), `FluidWorldAuthority` (`withHealth` on a control; the turbine's line), `RepairKitItem` (a steam turbine's cells), `en_us.json` (values) | `WearTest.aWetExhaustErodesASteamTurbineAboveTwelvePercent`; `MachineHealthTest`: 16 % wet, dry, failed and repaired, `theCadenceKeepsACompressorsAndATurbinesHealth`; `MoverHealthStatusTest.aSteamTurbineReadsWetExhaust` |
| N7 | `d655c6672` | `ShaftNetwork` (`Gear`, `Train.gears`, `gearLimit`), `ShaftTrain` (`Measure.gearLimit`, `geared`), `ShaftTrains` (the limit into the measure, the Create capacity), `en_us.json` (values), `CreateCouplingGameTests` (30 kW gearbox) | `ShaftTrainTest`: `anUnderRatedGearboxHoldsItsTrainGearLimited`, `anUnratedGearboxLimitsNothing`, `aGearLimitedTurbineFeedsForwardOnlyWhatItsGearboxPasses`; `ShaftNetworkTest.eachGearboxCapsItsTrainAtItsRatingThroughTheGearsBetween` |
| M-03 | `77326d3fa` | `ShaftTrain`, `SpeedControl`, `SteamTurbine`, `ShaftUnit` (`trainDrive()`), `ShaftTrains`; tests restated | see "M-03" below |

## N2: wet gas wears a centrifugal compressor

1. **Expected.** Droplets in a compressor's suction hit the impeller's leading edges at its tip speed (200 to 300 m/s) and erode them; API 617 machines take a knock-out drum because a few tenths of a percent of liquid by mass is tolerable and percents are not. The wear grows with the liquid's mass flow share. Game rule (decided): `(w − 0.005)/0.05`, erosion class: none at 0.5 %, 16 h of process time at 3 %, 8 h from 5.5 % (4 h and 2 h of play at factor 4).
2. **What the code did.** The solve read the suction's condensed mass share (`InletPhase.Supply.condensedMassShare`) for the compressor's wet efficiency (`1 − 0.5 w`) and dropped it; health had no fault for a compressor.
3. **Done.** One new `PipeObservation` component, `condensedShare`, filled in `observe` for a running `Machine` on a `CentrifugalCompressor` law from the number `readSuctions` already computed (NaN on every other row; a 20-argument constructor keeps old callers). `MachineHealth.faults` gives `WET_GAS` to that machine (a constructed casing and an in-line compressor; a reciprocating frame has no fault). Word `WET GAS`, after `GAS`.

## N4: wet exhaust wears a steam turbine

1. **Expected.** The last stages of a condensing turbine run in wet steam; above about 12 % moisture the droplets erode the last blades' leading edges. Example: saturated steam at 10 bar expanded at 0.76 to 0.1 bar exhausts at about 14 % moisture (intensity 0.24, life 33 h); 40 bar and 400 °C to 0.1 bar at 0.76 exhausts at about 8 % (no wear); at 0.45 it stays superheated. Rule (decided): `(w − 0.12)/0.08`, erosion: 16 h at 16 %, 8 h from 20 %.
2. **What the code did.** A turbine (`FlowControl.Turbine`, a valve, not a mover) had no health; `WET_EXHAUST` wore nothing.
3. **Done.**
   - `w` is the condensed mass share of the receiver node at commit (`InletPhase.bulk`, no flash), read while steam passes forward. **Accepted simplification:** the receiver may mix other streams. See decision request 1.
   - Health is keyed by the turbine's fluid device identity (the node its connection leaves). `MachineHealth.faults(FlowControl)` and `worn(FlowControl)` take any control. For today's machines they return the same lists, and `pump(registration)` (the cadence's keep test) now covers compressors and turbines.
   - **Choice: how a failed turbine closes.** The health's `FAILED 0` plan sets the turbine's mass target to 0, the same way a stop or a trip shuts it. The health plans merge last (`ShaftTrains.failures`), so they override the governor. After a repair the train's `GOVERNOR` plan opens it again. The train then reads `STEAM LIMITED` at no speed.
   - **Word order:** `FAILED`, `LIQUID`, `LOW FLOW`, `CAVITATION`, `GAS`, `WET GAS`, `WET EXHAUST`. Today's words keep their order. The two new words belong to machines that read none of the earlier words.
   - The repair kit takes a steam turbine's cells; its fluid cell is the steam inlet.

## N7 option A with M-13: a gearbox limits its train

1. **Expected.** A gearbox's rating is the most power its gear teeth pass continuously (AGMA service factor taken). Beyond it a real gearbox overheats and pits. Protective rule (decided): the train never passes more.
2. **What the code did.** It stored and showed the rating and limited nothing: a 0.75 kW gearbox passed 1 MW.
3. **Done.**
   - Each gearbox on the driver's path is a `ShaftNetwork.Gear`: its efficiency at its input, and the driven members behind it. `gearLimit = min_g R_g D / T_g` with `D = Σ P_m/η_m` and `T_g = Σ_behind P_m η_g/η_m`.
   - At one speed every member's power scales alike, so this is the driver's power at which gearbox g carries its rating at its input.
   - `ShaftTrain.step` takes the drive's own step first. Where that step's speed would ask more than the limit (`demand·(s'/s)³ ≥ L`), the train takes `SpeedControl.powerLimited(s, L, demand, s')`, the damped step whose error halves each slice under the cube law, and says `GEAR LIMITED` while below its set speed.
   - A governor feeds forward `demand·(s_next/s)³`, never more than its own feed-forward. A train below its limit keeps the drive's step bit for bit.
   - The Create coupling's published capacity is `min(capacity, L)`.
   - A rating of 0, or no load behind a gearbox, gives +∞. Shapes always give at least 750 W; 0 occurs only in hand-built units and tests (`ShaftCouplingTest`).

## M-03 option A: the unreachable trips

1. **Expected.** A variable-speed drive limits its current, so it never trips on a load. A governed turbine never passes its set speed.
2. **What the code did.** Every motor compiled as `VARIABLE_MOTOR` (`ShaftUnit.drive` ignored its argument), and every steam turbine was stepped governed (`governed = turbine || valve != null`). So the `FIXED_MOTOR` branch (`OVERLOAD`) and the ungoverned branch (`OVERSPEED`) were dead code.
3. **Done.**
   - Deleted: `Drive.FIXED_MOTOR` and its branches (in `step`, `setSpeed`, `regulated` and the NO_POWER test), `Driver.governed` and the ungoverned tail, `SpeedControl.balance`'s `+∞` (no load now holds the speed; its only caller has `demand > 0`), `SteamTurbine.TRIP_SPEED`, and the unused argument of `ShaftUnit.drive`.
   - The method becomes `trainDrive()`, because the record accessor `drive()` already exists. `ShaftTrains.driver(d[, powered])` follows.
   - `electric()` passes `true` for the drive's efficiency. It was already true for every reachable motor.
   - `Trip.OVERLOAD` and `OVERSPEED` stay, saved by name. A latched unit shows `TRIPPED: …`, stands, and resets (`ShaftTrains.Reset`, unchanged).
   - Tests:
     - Restated: `case1APumpOnAVariableSpeedMotorRunsAndDrawsItsElectricPower` (27.52 kW, 550 FE/t), `aGearboxTrainDemands…` (874.07 kW), `aGeneratorIsALoadOfNothing`, `noSteamWorkCannotTurnAnUnloadedTurbineAndASurplusCannotOverspeedIt`, `case2AGovernedTurbineHoldsItsSetSpeedWhenItsPumpsDischargeCloses`, `ElectricStoreTest.aMotorWithoutPowerStands…` (one drive).
     - Deleted: `case1AFixedMotorTripsOnOverloadAndStaysTripped`, `case2AnUngovernedTurbineOverspeedsAndTrips`.
     - Added: `aSavedLatchedTripStillShowsAndStandsUntilItsReset`.
     - Driver call sites edited where the old ten-argument form `(…, trip, governed)` would now bind as `(…, trip, powered)`: `RotatingDriveReworkTest`, `CreateCouplingTest`, `ShaftTrainFuelTest`, `ElectricStoreTest`.

## Values that move (stated shifts)

- **N2:** a running centrifugal compressor on a suction above 0.5 % condensed by mass now loses health, as given above. Nothing else moves: the solve's efficiency read is unchanged.
- **N4:** a steam turbine whose receiver holds more than 12 % condensed by mass now loses health; failed, it is shut.
- **N7:** a train whose gearbox is rated below its demand now runs slower, at `s·(L/D)^(1/3)` (the 796 kW compressor behind a 750 kW gearbox: 2950 to 2872.7 rpm), with less draw. Every other train keeps its bits.
- **M-03:** none: every live driver took the kept branches.

## What moved by reading

- Health facts format unchanged (`Map<Long, Double>`, values below 1): `FluidFactsCompatTest` passes unchanged. Its fixture helper filters by `MachineHealth::pump`, so a corpus world written fresh would also give its centrifugal compressor (id 37) an entry; the stored images do not.
- Campaign and chain-100: no health and no gearbox. Unchanged.
- GameTests: no review world builds a compressor casing, a steam turbine or a gearbox. In-line compressors appear only in `ConduitLayingGameTests` and `RegistryFirstGameTests` (at most 500 ticks); the longest other timeout, 30,000 ticks, is 1.67 h of process time, so no machine fails. `CreateCouplingGameTests` built a 0.75 kW gearbox in front of a pump of about 8 kW: it would now read GEAR LIMITED and miss its "share 1 ± 2 %" wait. It now builds 30 kW (decision request 2).

## Gradle commands (for the main thread)

- `./gradlew test --tests com.wormzjl.icheme.science.machine.WearTest --tests com.wormzjl.icheme.science.machine.ShaftTrainTest --tests com.wormzjl.icheme.science.machine.ShaftNetworkTest --tests com.wormzjl.icheme.science.machine.ShaftTrainFuelTest --tests com.wormzjl.icheme.science.machine.ElectricStoreTest --tests com.wormzjl.icheme.science.machine.CreateCouplingTest --tests com.wormzjl.icheme.science.machine.RotatingDriveReworkTest --tests com.wormzjl.icheme.science.machine.SpeedControlTest --tests com.wormzjl.icheme.science.machine.SteamTurbineTest --tests com.wormzjl.icheme.runtime.fluid.MachineHealthTest --tests com.wormzjl.icheme.runtime.fluid.MoverHealthStatusTest --tests com.wormzjl.icheme.runtime.fluid.ShaftCouplingTest --tests com.wormzjl.icheme.runtime.fluid.FluidFactsCompatTest --tests com.wormzjl.icheme.science.fluid.network.ShaftPlanTest --tests com.wormzjl.icheme.science.fluid.network.PassiveStepSolverTest` (quick group). It proves the rules, the restated M-03 tests, the facts format and the observation row. It also compiles `src/fluidGameTest` (`compileFluidGameTestJava`).
- `equipmentGates` loads `science/machine` and the lang files, so the final lane's affected set includes it. No equipment test reads the changed behaviour.
- Fluid GameTests in the lane: `ShaftTrainGameTests`, `PumpHealthGameTests`, `ElectricGameTests` and `CreateCouplingGameTests` (passes at once without Create).
- Do not run `LanguageFilesTest` or `UnitTextTest` before the translation commit.

## Lang keys

None added. Values changed:
- N4: `tooltip.icheme.repair_kit`, `chat.icheme.repair_kit.repaired`, `.full`, `.not_a_pump`, `gui.icheme.fluid.device_group.health.hint`, `tutorial.icheme.item.repair_kit.serves`.
- N7: `gui.icheme.equipment_constructor.gearbox.power.hint`, `gui.icheme.equipment_constructor.gearbox.rating.hint`.

## Decision requests

1. **N4 receiver.** A turbine that exhausts straight into a vessel holding liquid (a condenser hotwell, a drum) reads that inventory's share. A dry exhaust then wears at full rate, 8 h. Options:
   - (A) Keep it and say so in the hint.
   - (B) Read the exhaust's own quality, `h_in − W/ṁ` at the exhaust pressure: one PH flash per turbine per commit.
   - (C) Read the receiver's drawn supply at the turbine's port, as N2 does: no flash.
   Recommendation: C.
2. **CreateCouplingGameTests gearbox.** The test now uses 30 kW in place of 0.75 kW. The larger housing may need room the plant's build region lacks; a build failure would say "no room".
3. **GUI.** A compressor's and a turbine's page now shows the Health meter, with "—" in its NPSH rows. Hiding those rows is screen work under the gui-creation skill. `MachineColumn.warns` still lists `UNGOVERNED`, which no step sets.

## CHANGELOG lines (written under `[Unreleased]`)

- **Added:** wet gas wears a centrifugal compressor (N2); wet exhaust wears a steam turbine (N4); a gearbox limits its train, `GEAR LIMITED` (N7).
- **Changed:** an under-rated gearbox now limits its train; the motors' and turbines' unreachable trips are removed (M-03). The code guide line for G1 part 2.

## Run and decisions by the main thread (2026-11-13)

Merged into the review branch with these changes. Tests run on the merge: the 15 classes of the list above (128 tests) and `SteamTurbineConnectionTest` (7): all pass; `compileFluidGameTestJava` passes. The four GameTest classes wait for the unit's final lane.

1. **N4 reads the turbine's own exhaust (decision request 1: neither A nor C, the request's B).**
   - Expected: the moisture that erodes the last blades is that of the steam leaving them: the inlet stream at the exhaust pressure and at `h_in − W/ṁ`. What the condenser or hotwell holds is not the exhaust.
   - What the written code did: it read the receiver node's condensed share. A turbine on a vessel that holds condensate (the usual condensing arrangement) wore at the full rate, 8 h, whatever its steam.
   - Done: `PassiveStepSolver.exhaustMoisture`, one PH equilibrium of the fluid phases in `observe` (on the worker, as the flow meters' TP flash is), carried by the row's `condensedShare`; `MachineHealth` reads the row. Where the equilibrium gives no state the row is NaN: no read, no wear (seen near the dew line and at 5 kPa).
2. **The numbers are set on the solve's work law.** The turbine's work is an ideal-gas isentropic drop on the inlet's heat capacity ratio, which takes less enthalpy from condensing steam than the steam tables do. Measured on the rig (1 MW turbine, generator to void):

   | Inlet | Exhaust | Moisture read | Steam tables at 0.76 |
   |---|---|---|---|
   | 40 bar, 525 K (saturated) | 0.1 bar | 6.2 % | about 19 % |
   | 40 bar, 525 K | 4 bar | 5.8 % | about 10 % |
   | 40 bar, 550 K | 0.1 bar | 4.7 % | |
   | 40 bar, 673 K | 0.1 bar | 0 | about 8 % |
   | 10 bar, 454 K (saturated) | 0.1 bar | 5.5 % | about 14 % |
   | 10 bar, 573 K | 0.1 bar | 0 | |

   With the review's 12 % onset the mode could never act. Rule taken: `(w − 0.01)/0.05`: none to 1 %, 16 h at 3.5 %, 8 h from 6 %. Saturated inlet steam wears at the full rate; about 150 K of superheat at 40 bar wears none. The read hardly moves with the exhaust pressure on this law, so the hint names superheat only. An assumption the physics does not force: the owner may prefer a turbine law on the real enthalpy drop (a PS flash per turbine), which would restore the 12 % and 20 % of the steam tables.
3. **`MachineHealthTest`**: the cadence test's turbine had no ports (a constructed turbine needs a suction and a discharge port); the wet case is saturated steam at 40 bar into dry nitrogen at 0.1 bar (6.2 %, the full intensity, 1/(8 h)); the dry case is 40 bar and 400 °C into a receiver holding 16 % liquid, which must read 0.
4. Decision request 2 (the 30 kW gearbox of `CreateCouplingGameTests`): accepted; the lane's GameTest step judges whether it fits. Request 3 (the Health meter on a compressor's and a turbine's page; `MachineColumn.warns` on `UNGOVERNED`): screen work, on the small-items list. Request 4: noted.
