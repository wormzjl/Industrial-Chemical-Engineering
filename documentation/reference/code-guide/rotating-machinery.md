# Rotating machinery: the machine laws

Up: [code guide](README.md). The trains, drivers, electric stores, health, pages and the Create coupling in the world:
[shaft-trains.md](shaft-trains.md). The movers' rows in the step: [fluid-laws.md](fluid-laws.md) and
[fluid-step.md](fluid-step.md); the machine shapes and design points: [equipment-designs.md](equipment-designs.md); the
simulator's tiles: [simulator-units.md](simulator-units.md).

## Read this when

- A pump or compressor delivers the wrong flow or head for its speed, cavitates too early or too late
  (`PUMP_SUCTION_LIMIT`), runs on gas or wet gas wrongly, or its shaft power is off.
- A set-flow pump or compressor, a reciprocating frame, a vacuum pump or an ejector delivers the wrong capacity.
- A steam turbine's work, a motor's rating, efficiency or available power, a prime mover's fuel or exhaust is wrong.
- A machine wears too fast or too slowly for its fault (cavitation, running dry, low flow, wet gas, liquid at a dry
  screw, wet exhaust).
- A driver's next speed, trip or status word is wrong for the power its machines ask (`ShaftTrain.step`).
- You add a machine law, a driver kind or a fault.

## What it does

Physically, a centrifugal pump or compressor makes a head that falls with flow along its curve, scaled by speed through
the affinity laws; a positive-displacement machine delivers its displacement less slip up to its relief valve; a vacuum
pump's capacity follows its suction; a steam turbine gives `ṁ η_is Δh_is`; a motor on its variable-speed drive gives
constant torque below its base speed and constant power above it, and draws its power over its motor and drive
efficiencies; a fuelled prime mover burns fuel on a Willans line; a gearbox joins two shafts at a ratio. Each is a pure
record in `science/machine`, Minecraft-free and on `DeterministicMath`; the package imports only `science/math`
(`LayeringTest`'s `machine-free-of-*` rules: no network, sheet, geometry, plant or column type). The step reads the laws
as rows (`FlowControl.Machine` in mode `MOVER_LAW`), the shaft trains step their drivers on them once per slice, and the
health books the wear their faults cause ([shaft-trains.md](shaft-trains.md)).

## Class map

Paths are relative to `src/main/java/com/wormzjl/icheme/science/machine/`.

| Class | Role | Lines |
|---|---|---|
| [`MachineLaw`](../../../src/main/java/com/wormzjl/icheme/science/machine/MachineLaw.java) | Sealed (`CentrifugalPump`, `CentrifugalCompressor`, `VacuumPump`, `PlungerPump`): `rise(flow, speed, ρ, P, k)`, `riseSlope`, `residual` (the `MOVER_LAW` row; a head form by default, a flow form for a vacuum pump and a plunger pump), `fluidPower`, `shaftPower`, rated flow, head, efficiency | 39 |
| [`CentrifugalPump`](../../../src/main/java/com/wormzjl/icheme/science/machine/CentrifugalPump.java) | The pump's curve and shaft power; `rated(Q, H)` with `bestEfficiency(Q)` (Corripio); `ratedSpeed`; `npsh(speed)`; `withHeadShare` (gas) | 118 |
| [`CentrifugalCompressor`](../../../src/main/java/com/wormzjl/icheme/science/machine/CentrifugalCompressor.java) | The polytropic head curve, `SURGE` (recycle below it), `DEFAULT_EFFICIENCY`; `ratio`, `pressureRise`, `polytropicHead`, `sigma`; `wetEfficiency` | 144 |
| [`PositiveDisplacement`](../../../src/main/java/com/wormzjl/icheme/science/machine/PositiveDisplacement.java) | Form F2e: slip line and relief line joined by a smooth minimum (`SLIP`, `RELIEF_SLOPE`, `ROUNDING`); `flow`, `rise`, `riseSlope`, `residual`; `share`, `shareSlope` for the plunger pump | 95 |
| [`VacuumPump`](../../../src/main/java/com/wormzjl/icheme/science/machine/VacuumPump.java) | Liquid ring and dry screw: capacity by the suction, shaft power, heat to the gas; `maximumShaftPower` (for `Motor.ratingFor`); `liquidIntensity`, `liquidNoise`; the catalogue `Model`, `RINGS`, `SCREWS`, `model(models, number)`, `of(model, dryScrew, stages)` | 215 |
| [`PlungerPump`](../../../src/main/java/com/wormzjl/icheme/science/machine/PlungerPump.java) | A triplex plunger pump on `PositiveDisplacement`'s share (`share`, `shareSlope`): displacement times speed less the slip and the clearance loss `CLEARANCE (r − 1)`, `r = ((p + ΔP)/p)^(1/n)` on the suction's isentropic exponent in the law's `k` (`volumetricEfficiency`, `flow`; `rise` its safeguarded Newton inverse), to `RELIEF_RISE` (25 MPa); the fluid takes `Q isentropicWork`, the shaft the drawn volume times that work over `MECHANICAL_EFFICIENCY` (0.88), none at a standing train; `maximumShaftPower` (the liquid bound, for `Motor.ratingFor`); the catalogue `Model` (PP-3 to PP-100 m³/h at `SPEED`, 300 rpm), `model(number)`, `of(model)`; a liquid law with no NPSH design | 213 |
| [`EjectorLaw`](../../../src/main/java/com/wormzjl/icheme/science/machine/EjectorLaw.java), [`SteamEjector`](../../../src/main/java/com/wormzjl/icheme/science/machine/SteamEjector.java) | The vacuum set's steam law (`K`, `asAir`, `motiveFactor`; at and above water's Tc, where its vapour pressure is +∞, the set lifts the water to the discharge as it does the gas), read by the sheet's `EjectorUnit` and the world's `Ejectors`; the world's set: `speed(P)` (`SPEED`, `SPEED_EDGE`, `SPEED_EXPONENT`; the sheet's `Limit.VACUUM_SPEED` reads them), `RATED_MOTIVE`, `RATED_DISCHARGE`, `capacity`, `maximumDischarge` | 77, 75 |
| [`Npsh`](../../../src/main/java/com/wormzjl/icheme/science/machine/Npsh.java) | NPSH3 by flow and speed; `SUCTION_SPECIFIC_SPEED`; `BREAKDOWN` (`breakdownPressure`); `DUTY_NPSH3`, `dutySpeed`, `forDuty` (an in-line mover's design) | 82 |
| [`GasEntrainment`](../../../src/main/java/com/wormzjl/icheme/science/machine/GasEntrainment.java) | Turpin's onset `α_T` from suction pressure; the head share; `churn` (`CHURN_PEAK`); the clean zone `CLEAN_ONSETS` and `cleanShare(p)` | 68 |
| [`SteamTurbine`](../../../src/main/java/com/wormzjl/icheme/science/machine/SteamTurbine.java) | `isentropicDrop` from the inlet's real `P/ρ`; `work`; `typicalEfficiency` (single stage to multistage, a log blend by power) | 56 |
| [`Motor`](../../../src/main/java/com/wormzjl/icheme/science/machine/Motor.java) | IEC ratings (`standardRating`, `ratingFor` with `SPARE`), `ratedSpeed(poles)`, IE3 `efficiency`, `electricPower` (`DRIVE_EFFICIENCY`), `availablePower(ratedPower, baseRpm, rpm)`, `MAXIMUM_RPM`, `LOWEST_SPEED`; generator ratings | 119 |
| [`InlineMachines`](../../../src/main/java/com/wormzjl/icheme/science/machine/InlineMachines.java) | An in-line pump's or compressor's FE demand (`electricPower`), line-size design values (`inletFlow`, `law`), `SET_FLOW_PUMP_EFFICIENCY` | 47 |
| [`PrimeMover`](../../../src/main/java/com/wormzjl/icheme/science/machine/PrimeMover.java) | Combustion engines and gas turbines: `MODELS`; fuel on a Willans line, exhaust (`EXHAUST_CP` from `AIR`), an engine's radiator fans (`FAN_PER_COOLING`), `LOSS`; `supplyShare` under the required fuel pressure | 214 |
| [`SpeedControl`](../../../src/main/java/com/wormzjl/icheme/science/machine/SpeedControl.java) | Once per slice, never in a Newton: `regulate` (secant or affinity guess, `LARGEST_STEP`), `powerLimited`, `balance` | 79 |
| [`ShaftNetwork`](../../../src/main/java/com/wormzjl/icheme/science/machine/ShaftNetwork.java) | `Kind` (SHAFT, MOTOR, GENERATOR, GEARBOX, STEAM_TURBINE, CASING, CREATIVE_MOTOR, CREATE_COUPLING, COMBUSTION_ENGINE, GAS_TURBINE; `driver()`, `primeMover()`); `Part`, `End`, `Cell`; `compile(parts)` → `Train` (key, driver, `Driven` ratio and efficiency, `Fault` NONE, NO_DRIVER, TWO_DRIVERS, GEAR_LOOP, speed shares, ratios, `Gear`s); `gearLimit(train, rating, power)`; `GEAR_EFFICIENCY`, `MAXIMUM_RATIO`, `CREATE_RATIO` | 360 |
| [`ShaftTrain`](../../../src/main/java/com/wormzjl/icheme/science/machine/ShaftTrain.java) | `Drive` VARIABLE_MOTOR, TURBINE, CREATIVE, CREATE, FUEL (never saved); `Mode` SPEED, REGULATE_FLOW, REGULATE_PRESSURE; `Trip` NONE, OVERLOAD, OVERSPEED, NO_POWER (`latched`); `Driver`, `Measure` (with `gearLimit`), `step` → `Step` (speed, trip, status, governor feed-forward, electric draw); `CREATIVE_SPEED`, `CREATIVE_LIMIT`, `SPEED_DEADBAND` | 248 |
| [`ElectricStore`](../../../src/main/java/com/wormzjl/icheme/science/machine/ElectricStore.java) | A store's arithmetic: `Facts(joules, metered)` (`EMPTY`, `UNMETERED`); windows `SLICE_TICKS`, `PERIOD_TICKS` with `BUFFER_SLICES`, `RESTART_SLICES`, `MOTOR_BUFFER_PERIODS`, `MOTOR_RESTART_PERIODS`, `RATE`; `capacity`, `motorCapacity`, `perTick`, `restart`, `motorRestart` (each given its window's process seconds); `draw`, `accepted`, `generatorLoad`, `onSpeed` (`SPEED_WINDOW`), `motorDraw`, `fanEfficiency` (`FAN_POLES`) | 139 |
| [`Wear`](../../../src/main/java/com/wormzjl/icheme/science/machine/Wear.java) | The fault table and the rate rule: `Fault` CAVITATION, DRY, LOW_FLOW, WET_GAS, LIQUID, WET_EXHAUST, each with its `FaultClass` (EROSION, SEIZURE) and `intensity(q)`; `INCEPTION`, `lowFlow`, `wetGas`, `wetExhaust`, `wetOnset`, `wetExcess`; `rate(erosion, seizure, Settings)`, `rate(σ, dry, Settings)`; `CAVITATION_PROCESS_HOURS`, `DRY_PROCESS_MINUTES`; `Settings` (config `machines.wear.*`) | 206 |
| [`CreateCoupling`](../../../src/main/java/com/wormzjl/icheme/science/machine/CreateCoupling.java) | Create stress to watts: `STRESS_PER_FE`, `wattsPerStress`, `impact`, deadbands (`moved`, `impactMoved`), `SPARE_SHARE`, `DESIGN_SPEED` | 60 |

## Main flow

The laws are called, never call:

1. The step states a mover's row from its law (`FlowControl.Machine` → `MachineLaw.residual`, `rise`, `riseSlope`; a
   set-flow mover `PositiveDisplacement`); once per solve it reads the suction's vapour share into
   `GasEntrainment.headShare` and the liquid share into `CentrifugalCompressor.wetEfficiency`; the suction floor is
   `Npsh.breakdownPressure` ([fluid-laws.md](fluid-laws.md)).
2. At a commit the shaft trains book each machine's `shaftPower`; at a decision point `ShaftTrain.step` turns the booked
   demand into the next speed, trip and status (`SpeedControl.regulate`, `powerLimited`, `balance`; `ShaftNetwork.gearLimit`);
   `ElectricStore.draw` and `generatorLoad` settle the electricity ([shaft-trains.md](shaft-trains.md)).
3. At a commit the health books `Wear.rate` over the machine's faults' intensities.

## Contracts

**Pumps and compressors.**

- Centrifugal pump: `H = H_r (1.21 s² − 0.05 q s − 0.15 q|q| − 0.01 q)`, shaft power `P_r (0.35 s³ + 0.65 q s²)`, with
  `s` the speed share and `q` the flow share; the curve's slope stays below −0.01 H_r/Q_r. A rated speed defaults to the
  2-pole speed.
- Centrifugal compressor: `H_p/H_r = s² (0.645 + 1.244 x − 0.889 x²)`, `x = q/s`; below `SURGE` a recycle holds the
  machine's flow and the net law is a straight line; head to pressure ratio after PTC 10.
- Positive displacement (set-flow pumps and compressors, the reciprocating frame): slip line `u = 1 − SLIP v`, relief
  line `u = RELIEF_SLOPE (1 − v) + δ`, joined by a smooth minimum of width `ROUNDING`. A set-flow pump's set flow is its
  displacement and its "maximum added pressure" the relief rise on its supply's density; a flow valve above its set flow
  cannot regulate (`ControlConstraints.moverBounds`).
- NPSH3 `= NPSH3_r (0.4 s² + 0.6 q²)`; the suction floor is `p_v + BREAKDOWN ρ g NPSH3`; an in-line pump's NPSH3 is
  its duty design's (`Npsh.forDuty`).
- No mover refuses gas or liquid: a centrifugal pump's head is `M = 1/(1 + (α/1.6 α_T)^4)` of its curve, with `α` the
  suction's vapour share and `α_T` Turpin's onset; below `CLEAN_ONSETS` of the onset the vapour does no harm (no wear,
  status word, sound or cavitation count). A centrifugal compressor's efficiency reads `wetEfficiency` of its liquid
  share; a set-flow pump delivers `(1 − α)` of its displacement as liquid, a frame the displacement less the liquid's
  volume. Only a dry suction and, at a compressor, solids above the trace refuse.
- One dense fluid (`State.dense()`) has no second phase. Whichever slot holds it (`InletPhase.measure`), a pump's `α`
  is `1 − w_pump` on its own weight (`FluidThermodynamics.pumpLiquidLikeWeight`, a smoothstep between reduced densities
  `GAS_LIKE_UNTIL` 0.5 and `PUMP_LIQUID_LIKE_FROM` 1.0: a pump keeps its full head from the critical density,
  DENSE_PUMP_RESEARCH_REVIEW.md of batch 2026-11-15-supercritical-fluids), and a compressor's liquid share is `w` on the
  film's (`liquidLikeWeight`, 0.5 to `LIQUID_LIKE_FROM` 1.5); each is the slot's own value to the bit outside its window. Its NPSH density is its own in either slot. What needs a second
  phase does not occur in it: no vapour pressure, no droplet wear (`Supply.dropletMassShare` 0 feeds `Wear.wetGas`
  and the `WET GAS` word), and a pump's suction edge only where its isenthalp really flashes. The compressor's
  head-to-ratio law reads the ideal-gas `k` on the suction's real `P/ρ`, which loses accuracy on a dense suction
  (`S4_MACHINES_REVIEW.md` of batch 2026-11-15-supercritical-fluids).

**Vacuum pumps and ejectors.** `S = s·S_n·φ(P_s)`. Liquid ring: `φ = (1 − P_v/P_s)^n` (`n` 2 for one stage, 1 for two;
`P_v` = `SEAL_VAPOUR_PRESSURE`, the ring's at `SEAL_TEMPERATURE`); shaft `friction·s³ + P_s·Q·ln(P_atm/P_s)/η_iso`
(`ISOTHERMAL_EFFICIENCY`), no heat to the gas. Dry screw: `φ = 1 − SCREW_ULTIMATE/P_s`, shaft `SCREW_POWER` per m³/h ·
`(0.6 s² + 0.4 s (P/P_atm)^0.3)`, `SCREW_HEATING` of the adiabatic rise booked as heat; liquid at a dry screw wears it.
The relief rise is `MAXIMUM_DISCHARGE` less the suction (`reliefRise`). The ejector's steam law and the world's set
(`SteamEjector.speed`, `capacity`, `maximumDischarge`) are shared by the sheet and the world.

**Turbines, motors, prime movers.**

- Steam turbine: `work = ṁ η_is Δh_is`, the isentropic drop from the inlet's real `P/ρ`; `typicalEfficiency` blends by
  power on a log scale. The inlet class (`MachineDuty.Steam`: LP, MP, HP, VHP) and the sections it expands through
  (`SteamTurbineDesign.sections`: HP through MP and LP, MP through LP) name and draw the machine; the work law is one
  expansion from the inlet to the exhaust, whatever sections it passes.
- Motor: IEC ratings with `SPARE` on the duty (`ratingFor`), IE3 efficiency by poles (12 poles read the 8-pole row,
  motors and generators alike), the drive's `DRIVE_EFFICIENCY` in `electricPower`. Every motor runs on its variable-speed
  drive: constant torque below its base speed and constant power above it (`availablePower`), at most `MAXIMUM_RPM`;
  over its power limit it slows (`powerLimited`) and never trips on a load.
- Prime mover: fuel heat `Q_f = Q_r (c₀ + (1 − c₀) x)` on the load share `x`; heat out as shaft work, exhaust from `AIR`
  to the model's exhaust temperature at `EXHAUST_CP`, an engine's radiator (fans `FAN_PER_COOLING` off its own shaft) and
  `LOSS`. Under the required supply pressure the most fuel falls as `√((P_s − P_c)/(P_req − P_c))` (`supplyShare`). An
  engine takes liquid fuel, a gas turbine gas.
- In-line movers: `InlineMachines.inletFlow` (a liquid's and a gas's design velocity) and `law` (a design head, an air
  ratio) size an in-line `FlowControl.Machine` (`InlineMovers.design`, [fluid-laws.md](fluid-laws.md)).

**Drivers** (`ShaftTrain.step`).

- A motor and the creative motor hold their set speed (a share of design, or rpm for the creative motor), or regulate
  the train's first machine's flow (`REGULATE_FLOW`, exponent 1) or discharge pressure (`REGULATE_PRESSURE`, exponent 2)
  through `SpeedControl.regulate`; over the power limit they slow by `powerLimited` (`CURRENT LIMITED`, `POWER LIMITED`).
- A steam turbine's internal admission governor follows the requested speed and the shaft demand, limiting admitted work
  so its speed never passes its set speed; it has no overspeed trip. Delivered steam bounds its work (`STEAM LIMITED`);
  with none the train stands.
- A fuelled prime mover (`Drive.FUEL`) is always governed on the net shaft power its fuel gave, its set speed at most its
  rated speed, no overspeed and no trip; a lost load cuts the fuel in the same step.
- A Create coupling (`Drive.CREATE`): set speed = Create's |rpm|, limit = `wattsPerStress` × spare stress ×
  `SPARE_SHARE`; Create at rest gives `NO ROTATION`; its design speed is `DESIGN_SPEED`, which an auto gearbox behind it
  matches up to `CREATE_RATIO`.
- **Trips**: a step sets only `NO_POWER` (a motor on an empty store, not latched, cleared when the store holds a slice
  again). `OVERLOAD` and `OVERSPEED` are latched names no step sets: a unit saved with one shows `TRIPPED: …`, stands,
  and runs again after the player's reset.
- **Gearbox limit** (protective, no wear): a gearbox passes at most its rating at its input. `gearLimit` is the least
  over the gearboxes rated above 0 of `R_g D / T_g` (`D = Σ P_m/η_m`, `T_g = Σ_behind P_m η_g/η_m`), +∞ for none; where the
  drive's next speed would ask more (by the cube law), `step` takes the damped `powerLimited` step to the limit and says
  `GEAR LIMITED`, and a governor feeds forward only what the gearbox passes. A gearbox rated 0 limits nothing.
- **Faults**: two drivers, no driver, or a loop of gears stand the train (`NO DRIVER`, `TWO DRIVERS`, `GEAR LOOP`).
- Status words: `RUNNING`, `STOPPED`, `CURRENT LIMITED`, `POWER LIMITED`, `STEAM LIMITED`, `GEAR LIMITED`,
  `NO ROTATION`, `TRIPPED: …`.

**Wear** (`Wear.rate`): the largest intensity in each class, the two classes added; lives in process time
(`CAVITATION_PROCESS_HOURS` for erosion, `DRY_PROCESS_MINUTES` for seizure, config `machines.wear.*`). Nothing wears from
clean running.

| Fault | Class | Intensity |
|---|---|---|
| CAVITATION | erosion | Gülich's `intensity(σ)`, `σ = NPSHA/NPSH3`: a peak at σ 2.06, none from `INCEPTION` |
| DRY | seizure | 1 while dry |
| LOW_FLOW | erosion | `lowFlow(share) = ((0.3 − share)/0.3)²` below 30 % of the rated flow at the speed (a centrifugal pump only) |
| WET_GAS | erosion | `wetGas(w) = (w − 0.005)/0.05` of a compressor suction's condensed mass share |
| LIQUID | seizure | `VacuumPump.liquidIntensity`: none up to 1 % liquid by volume, then `liquid / 10 %`, at most 1 (a dry screw) |
| WET_EXHAUST | erosion | `wetExhaust(w, P) = (w − wetOnset(P))/0.05` of a steam turbine's own exhaust moisture; the onset 1 % from 150 kPa up, `WET_ONSET_20_KPA` 4.8 % and `WET_ONSET_10_KPA` 5.6 % (the classes' design moisture), linear in ln P, flat below 10 kPa (batch 2026-11-15-equipment-visual-design, W5) |

## Laws and constants

Each law is in its class above, once; the constants are named there. The notable fixed numbers in formulas (not
constants): the pump's and compressor's curve coefficients, the shaft power split 0.35/0.65, NPSH3's 0.4/0.6, the
entrainment's 1.6 and power 4, the dry screw's 0.6/0.4 split, the low-flow 30 %.

## Formats

The laws carry no saved form. The saved names they own are enums saved by name: `ShaftTrain.Mode`, `ShaftTrain.Trip`
(the units' facts, [shaft-trains.md](shaft-trains.md); numbers in [FORMATS.md](../FORMATS.md#saves)). `Drive` is never
saved.

## Tests and gates

| Rule | Owner | Test |
|---|---|---|
| Head, power, NPSH3, gas share, turbine work, motor rating, speed regulation | `science/machine/*` | `CentrifugalPumpTest`, `CentrifugalCompressorTest`, `PositiveDisplacementTest`, `NpshTest`, `GasEntrainmentTest`, `SteamTurbineTest`, `MotorTest`, `SpeedControlTest` |
| Parts join, a fault, a ratio | `ShaftNetwork.compile` | `ShaftNetworkTest` |
| A driver's speed, trip, status, gear limit | `ShaftTrain.step` | `ShaftTrainTest` |
| The wear rate over the fault table, bit for bit | `Wear` | `WearTest` |
| A vacuum pump's capacity, power and wear | `VacuumPump` | `VacuumPumpTest` |
| A plunger pump's delivery, slip, clearance, relief and power; its inverse and slope | `PlungerPump` | `PlungerPumpTest` |
| A plunger pump's exponent, the reference rows and its row in the network | `NetworkStreams.isentropicExponent`, `PassiveStepSolver.suctionKappa` | `PlungerPumpMoverTest` |
| A store's arithmetic | `ElectricStore` | `ElectricStoreTest` |
| Create stress to watts | `CreateCoupling` | `CreateCouplingTest` |

All under `test`: `--tests 'com.wormzjl.icheme.science.machine.*'`.

## Traps

- **The pump booking is the ideal work.** `FlowControl.Pump.power` and a `Machine` pump give `Q Δp` to the fluid; a
  compressor gives polytropic work over its efficiency. What the driver pays is the law's shaft power
  (`InlineMovers.shaftPower`, [shaft-trains.md](shaft-trains.md)).
- **`ShaftTrain.Step.electricPower` is booked**: `ShaftTrains` charges it to the motor's store (`ElectricStores.draw`).
- `OVERLOAD` and `OVERSPEED` look like live protection; no step sets them.
- `science/machine` may not import the equipment or the network: a law that needs a shape's number takes it as an
  argument (`science/plant/machine/MachineRatings` maps a shape to the law's inputs).

**Snapshot:** `410c37fcb`, 2026-11-16.
