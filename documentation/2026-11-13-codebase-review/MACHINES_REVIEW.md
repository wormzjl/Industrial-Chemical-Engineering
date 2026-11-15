# S7 Rotating machinery review

Batch `2026-11-13-codebase-review`, phase 1, subagent S7 (Opus high), 2026-11-13. Read-only review of `science/machine` (18 files, 2 082 lines), `runtime/fluid/ShaftTrains`, `ShaftUnit`, `ElectricStores`, `PumpHealth`, `PrimeMovers`, `Ejectors`, `MachineView`, `MachinePresentation`, `science/fluid/network/ShaftPlan`, `FlowControl` (Mover, Pump, Compressor, Machine, Ejector, Turbine), `compat/create` (bridge, shaft and coupling entities, `CreateCompat`), `world/item/RepairKitItem`, the `energy`, `create` and `machines.wear` config in `IChemE.java`, and the decision logs of `2026-11-03-rotating-machinery` (D1 to D44, A1 to A14), `2026-11-10-prime-movers` (P11) and `2026-11-10-vacuum-pump` (V2, V6, V9). Outside the scope, read only at the cited lines: `PassiveStepSolver` 1904-1966 and 2040-2141, `ConservativeTransport` 588-604, `FluidWorldAuthority` 1759-1761 and 2486-2499, `WorldTopologyLedger` 485-509, `ShaftParts` 100-138, `MachineDuty` signatures, `flowsheet/EjectorLaw`, `sizing/Limit` line 102. Not read: `CreateCouplingBlock`, `CreateShaftBlock`, `CreateCouplingRenderer`, `CreateCompatClient`, `ColumnPower`, `FiredHeaters`, `Flares`, `Chimneys`, the client pages. No measurement was run (no Gradle allowed), so "bench" here is counted work per call, not time.

## 1. Summary

**What the area does.** `science/machine` holds pure laws (pump, compressor and vacuum-pump curves on one `MachineLaw` row; `PositiveDisplacement` form F2e; NPSH3, gas share, wear; the steam turbine, motor, prime mover, ejector and Create stress rules) and the shaft-train compiler and driver step. `runtime/fluid/ShaftTrains` owns the trains: it plans speed, displacement, governor and fuel targets at dispatch, records shaft power at commit, steps each driver at decision points and wakes islands. `ElectricStores` books FE in joules for motors, generators, fans, in-line movers, burners and stills; `PumpHealth` books pump and dry-screw wear and closes a failed pump.

**Five largest problems.**
1. **The driver pays for a different shaft power than the gas receives.** The solver gives a compressor's gas `ṁ H_p/η` at the suction's own `k`, divided by the wet efficiency (`PassiveStepSolver` 1905, `ConservativeTransport` 594-604). `ShaftTrains.booked` charges the train with `k = 1.4` and no wet factor (lines 665, 676). `ElectricStores.booked` adds a second `/0.65` on top of a set-flow compressor's own `/0.78` (line 499, and `InlineMachines.ratedShaftPower` 55-61). FE drawn and gas work differ by −11 % to +54 % (finding M-01).
2. **Failure modes are lopsided and partly unreachable.** Only pumps and dry screws wear. Both latched protection trips are dead code: every motor compiles as `VARIABLE_MOTOR` (`ShaftUnit` 84), so `OVERLOAD` cannot fire, and every steam turbine is governed (`ShaftTrains` 796), so `OVERSPEED` cannot fire. Only `NO_POWER` is live (M-03).
3. **A worn dry screw cannot be repaired, and its device page does not say FAILED.** `RepairKitItem.pumpAt` takes only `EquipmentShape.Kind.PUMP` (line 52). `FluidWorldAuthority` 2493 reads `health.status` for liquid movers only (M-02).
4. **One plan kind, two owners.** `ShaftPlan.Kind.FAILED` means both "health 0" (`PumpHealth.plans` 188) and "no FE" for in-line movers (`ElectricStores.plans` 472). `PumpHealth`'s "switch back on" rule (189-191) overwrites the electric close (`ShaftTrains.failures` 607). An unpowered set-flow in-line pump then opens and closes on alternate dispatches (M-04).
5. **`ShaftTrains` is a plant hub with O(units) work per step.** It fans out to burners, flares, chimneys, prime movers, stores and health (lines 207-245, 545-646). Each publish copies the whole shaft map twice (`WorldTopologyLedger.shafts` 485 is `Map.copyOf`). Each state write scans every unit (ledger 497). Every turbine and engine step publishes (line 840). Each commit runs two 40-step bisections per machine, whatever the driver's mode (667-668) (M-08 to M-11).

**Ten highest-value changes, ranked by gain over cost.**
1. M-02: the repair kit takes every worn device, and the device page reads health for every worn mover. About 5 lines.
2. M-01: the step result carries each mover's shaft power once. `ShaftTrains`, `ElectricStores` and `InlineMachines` read it instead of recomputing (about 25 lines out). This fixes the k = 1.4, wet-factor and double-efficiency errors at once.
3. M-04: one availability plan per mover (`RUN` 0/1) from one decision: failed, unpowered or switched off. It deletes the FAILED-1 reopen rule.
4. M-03: delete the `FIXED_MOTOR` branch, the ungoverned-turbine branch and `SpeedControl.balance`'s unbounded case. Keep `Trip.OVERLOAD` and `OVERSPEED` as saved names only.
5. M-09: `ShaftTrains.plans` passes one output map to every contributor (each already takes `out`). This removes up to 6 map constructions per dispatch and the putAll chain.
6. M-10: publish only the stepped train's members, read the ledger's live view (no `Map.copyOf`), and index positions in the ledger.
7. M-11: compute `requiredSpeed` only for the first driven member of a train whose driver regulates, on the solve's `k`.
8. M-12: one `MachineHealth` (generalised `PumpHealth`) with a fault table in `Wear`. The two config lives cover every fault class. The facts map is unchanged.
9. M-14: move the ejector correlation (`EjectorLaw`, the vacuum suction speed, the rated constants) into `science/machine`, and the `FlowControl` wrapper out of `InlineMachines`. This leaves `science/machine` with `science/math` as its only import.
10. Section 3.3: the failure-mode matrix. Each new mode is an owner option.

## 2. Module map

| Sub-module | Files (lines) | Wrong-way imports |
|---|---|---|
| `machine/law` (curves, Minecraft-free) | `MachineLaw` 39, `CentrifugalPump` 118, `CentrifugalCompressor` 144, `VacuumPump` 180, `PositiveDisplacement` 88, `Npsh` 82, `GasEntrainment` 68, `SteamTurbine` 58, `SteamEjector` 60, `PrimeMover` 214, `Motor` 119 (1 170) | `SteamEjector` 3-4, 18-19 → `flowsheet.EjectorLaw`, `flowsheet.sizing.Limit`, `flowsheet.unit.EjectorUnit` |
| `machine/shaft` (trains, driver step) | `ShaftNetwork` 297, `ShaftTrain` 218, `SpeedControl` 79, `CreateCoupling` 60 (654) | none |
| `machine/health` | `Wear` 63 | none |
| `machine/power` | `ElectricStore` 132 | 48-68 → `science.fluid.SimulationTime` (five signatures) |
| misplaced in `machine` | `InlineMachines` 63 | 3 → `science.fluid.network.FlowControl`; it builds a `FlowControl.Machine` and reads `Mover`s (a cycle: `FlowControl` imports `machine`) |
| `runtime` shaft engine | `ShaftTrains` 1 160, `ShaftUnit` 87, `MachineView` 129, `MachinePresentation` 101 | `ShaftTrains` 529, 1072 → `world.level.block.ShaftBlock` (runtime into world); 530 → `science.equipment.ProcessUnit`; `ShaftUnit` 3 → `science.equipment.MachineDuty` |
| `runtime` stores and health | `ElectricStores` 602, `PumpHealth` 330 | `ElectricStores` 3 → `science.equipment.HeatSides` |
| `runtime` plant hooks carried by `ShaftTrains` | `PrimeMovers` 232, `Ejectors` 125, plus `FiredHeaters`, `Flares`, `Chimneys` (not read) | `Ejectors` 3 → `flowsheet.EjectorLaw` |
| solver side | `ShaftPlan` 147, `FlowControl` Mover part (lines 50-304) | none |

Inversions to fix: `machine` → `flowsheet` (3 types), `machine` → `fluid` (2 types), `runtime` → `world` (1 constant). REVIEW_PLAN §1 item 7 names only the first.

## 3. Machines, gaps and the failure-mode matrix

### 3.1 One row per machine kind (as the code stands)

| Kind | Law | Solve already computes | Status words | Trips | Wear today |
|---|---|---|---|---|---|
| Centrifugal pump (casing) | `CentrifugalPump` on `MOVER_LAW`, head form | σ = NPSHA/NPSH3, gas ratio r = α/α_T, dry, flow q, speed s (`PassiveStepSolver` 2051, 2111-2141) | FAILED > CAVITATION > GAS (`PumpHealth.status` 318-326); the train's word on the Drive group | none | cavitation `I_c(σ)/8 h`, dry `1/40 min` (`Wear` 48-51) |
| PD / set-flow pump (legacy in-line) | `PositiveDisplacement` F2e | σ (forDuty NPSH, `FlowControl` 150), dry, vapour share | the same, plus "delivers N %" | none | the same |
| In-line pump (new) | `InlineMachines.design`: `CentrifugalPump.rated(2 m/s, 25 m)` | the same as a casing | the same | `NO POWER` (store) | the same |
| Centrifugal compressor | `CentrifugalCompressor`, built-in anti-surge recycle below x = 0.75 (12-13, 57-60) | the suction's condensed mass share → `wetShares` (1960); flow q against the surge flow | train word only | none | none |
| In-line compressor | `InlineMachines`: compressor at 10 m/s, ratio 1.5 | the same | the same | `NO POWER` | none |
| Reciprocating compressor | `FlowControl.Compressor` F2e, displacement from the train | flow, head; no liquid share read (1963 reads liquid movers only) | mode / inlet / shortfall (`FluidWorldAuthority` 2494) | none | none |
| Liquid-ring vacuum pump | `VacuumPump`, `φ = (1 − P_v/P_s)^n`, internal seal at 25 °C (13-17) | suction pressure P_s against P_v = 3 169.9 Pa | train word | none | none |
| Dry-screw vacuum pump | `VacuumPump`, `φ = 1 − 1 Pa/P_s`; discharge rule T_d (V9) | suction vapour share (1959, 2054) | LIQUID (`PumpHealth` 321) on no page: `FluidWorldAuthority` 2493 reads liquid movers only | none | liquid > 1 % by volume: the dry life, a step (`PumpHealth` 164) |
| Steam turbine | `FlowControl.Turbine`, `SteamTurbine.work`, internal governor | steam flow, inlet and exhaust pressure (`ShaftTrains` 686-696); the exhaust node's state, wetness included | RUNNING, STEAM LIMITED, "/ INTERNAL GOVERNOR" | `OVERSPEED` in code, unreachable (M-03) | none |
| Motor (VFD) | `Motor.availablePower` (constant torque below base) | train demand, speed | RUNNING, CURRENT LIMITED, NO DATA LINK / LAST SPEED, MULTIPLE DUTIES, NO DUTY TARGET | `NO_POWER` (self-clearing); `OVERLOAD` unreachable | none |
| Generator | `ElectricStore.generatorLoad` (load at most its rating and its store's room) | speed window ±5 %, load | OFF SPEED, STOPPED | none | none |
| Gearbox | `ShaftNetwork` ratio, η 0.98 | through-power (sum of driven members behind it) | the train's | none | none; its rated power (`MachineDuty.gearbox` 114) is saved but never read |
| Shaft block | `ShaftNetwork` | the drawn size from the driver's rated torque | LOOSE, HELD | none | none |
| Combustion engine, gas turbine | `PrimeMover` Willans line, `Drive.FUEL` | fired heat, supply pressure, phase, exhaust | RUNNING, NO FUEL, WRONG FUEL, FUEL NOT COMBUSTIBLE, LOW FUEL PRESSURE, LOW FUEL | none (P11) | none |
| Creative motor | `ShaftTrain` CREATIVE | demand | RUNNING, POWER LIMITED | none | none |
| Create coupling or bridge | `CreateCoupling`, `CreateBridgeBlockEntity` | Create rpm, spare stress | NO ROTATION, POWER LIMITED | none | none |
| Air cooler and tower fans | `HeatSides.fanPower`, `CoolingTower.fanPower` | fan speed, draw | NO POWER, UNMETERED | none | none |
| Steam ejector | `SteamEjector` on F2e, no work | motive pressure, delivered share | mode | none | none |

### 3.2 Gaps and incoherences

- **The same fault damages one machine and is free on another.**
  - Liquid at the suction: it wears a dry screw from 1 % by volume (40 process min). A reciprocating compressor, the machine real liquid breaks fastest, takes it with no wear and no word. A centrifugal compressor loses efficiency only.
  - Cavitation: it erodes a centrifugal pump. A liquid ring near its blank-off is free.
  - Overload: a gearbox below its driver's power passes it free, because its rating is never read.
- **Trips.** Only a motor has a live trip (`NO_POWER`). The steam turbine's `OVERSPEED` and the fixed motor's `OVERLOAD` remain in the enum, the step, the Reset event, the page and the code guide ("Read this when ... `TRIPPED: OVERLOAD`, `TRIPPED: OVERSPEED`"), but no runtime path reaches them.
- **Rates.** Every rate is coherent on process time:
  - wear: `PumpHealth.book` 132, `time.seconds`;
  - FE at the play-time rate: `ElectricStores.feJoules` 141;
  - Create's watts per su is factor-free, 1.465 W in both directions;
  - the stores' windows are counted in cycles;
  - the page shows play seconds (`MachineView` 105).

  Two exceptions:
  - `PumpHealth`'s javadoc (lines 21-23) still says "online ticks".
  - The dry screw's liquid wear is a step at 1 % while its noise ramps to 10 % (`VacuumPump.liquidNoise` 93-95). A pump's crackle and its wear read the same `I_c(σ)`.
- **Status words for the same state.**
  - A failed pump says FAILED on its device page but RUNNING on its Drive group and machine page: `ShaftTrains.view` 1044-1045 never reads health.
  - A failed or liquid-flooded dry screw shows neither on its device page (`FluidWorldAuthority` 2493).
  - "NO GOVERNOR VALVE" (javadoc 178) is set as "INTERNAL GOVERNOR" (478).
  - Short of power, the word names the cause (CURRENT, POWER, STEAM LIMITED, LOW FUEL ...). That is coherent and should stay.

### 3.3 Proposed failure-mode matrix (D34: wear only from faults the player can see and fix; none from clean running; lives in process time through config; one repair kit; a protective trip or limit prevents the damage; wear is the fault left standing)

**Every new mode is an owner option. D34 itself chose "cavitation + dry only, keep simple".** Any new row asks the owner to widen D34's scope. Life is at the stated typical fault and the default lives. Two lives cover every class, so no config key is added:
- erosion class: `cavitationProcessHours`, 8 h;
- seizure class: `dryProcessMinutes`, 40 min.

| # | Machine | Physics in one line | Driving quantity (already in the solve) | Rate law | Life at a typical fault | Word | Player's fix |
|---|---|---|---|---|---|---|---|
| K1 (keep) | Centrifugal and in-line pump | Vapour bubbles collapse on the vanes | σ (`npshRatio`) | `I_c(σ)/τ_e` | 8 h at σ 2.06, 20.4 h at σ 1 | CAVITATION | Raise the suction level, cool the feed, slow the pump |
| K2 (keep) | Every liquid pump | No liquid film: seals and bearings seize | `dry` (`PassiveStepSolver` 2138-2141) | `1/τ_s` | 40 min | GAS / FAILED | Prime it, refill the supplier |
| N1 | Centrifugal pump (casing, in-line) | Below about 30 % of best-efficiency flow, recirculation, and at shutoff all shaft power heats the trapped liquid (API 610 minimum continuous flow; plan S1's MIN FLOW, never built) | `q_s = Q/(Q_r s)` from the committed flow and speed (`ShaftTrains` 664) | `clamp((0.3 − q_s)/0.3, 0, 1)² / τ_s` | 40 min dead-headed; 2.7 h at 15 % flow | LOW FLOW | Open the discharge, add a minimum-flow recycle |
| N2 | Centrifugal compressor | Droplets erode the impeller; the efficiency loss stays | Condensed mass share `w` (`PassiveStepSolver` 1960: `wetShares`; one field added to `PipeObservation`) | `clamp((w − 0.005)/0.05, 0, 1)/τ_e` | 8 h at w ≥ 5.5 %, 16 h at 3 % | WET GAS | Knock-out drum upstream, heat tracing |
| N3 | Reciprocating compressor | Liquid is incompressible: valves and rods break | Suction liquid volume share. **Not in the solve today**: one `InletPhase.supply` read per running frame per solve, the cost D39 accepted for in-line pumps | `clamp(φ_l/0.1, 0, 1)/τ_s`, as the dry screw (N5) | 40 min at 10 % by volume, 400 min at 1 % | LIQUID | Knock-out drum |
| N4 | Steam turbine | Moisture above about 12 % erodes the last blades | The exhaust node's condensed mass share at commit (`ShaftTrains` 693 already reads that node). It reads the receiver, which may mix other streams; the turbine's own `h_in − W/ṁ` quality would need one PH flash | `clamp((w − 0.12)/0.08, 0, 1)/τ_e` | 8 h at 20 % wet, 16 h at 16 % | WET EXHAUST | More superheat, a higher exhaust pressure |
| N5 (adjust) | Dry screw | Liquid slugs the rotors (owner V6 B, exists) | Suction vapour share (exists) | The step at 1 % becomes `clamp(φ_l/0.1, 0, 1)/τ_s`, the noise's own ramp | 40 min at ≥ 10 %, 400 min at 1 % (now 40 min at 1.1 %) | LIQUID | Knock-out pot, condenser ahead |
| N6 | Liquid ring | The ring cavitates as the suction nears its vapour pressure | `P_s/P_v` (`VacuumPump.share` 98-103 reads it) | `clamp(2 − P_s/P_v, 0, 1)/τ_e` | 11 h at 4 kPa, none above 6.3 kPa | CAVITATION | Air bleed into the suction, a stage ahead |
| N7 | Gearbox | Teeth over their rating pit and break | Through-power at the gearbox (driven members behind it, as `ShaftTrains` 769 sums them per train) against `ratedPower` | **A (recommended): protective, no wear.** The train's limit is the least of the driver's and the gearboxes' ratings: GEAR LIMITED. B: `clamp(P/P_r − 1, 0, 1)/τ_e` | A: none | GEAR LIMITED | Bigger gearbox |
| none | Motor | The VFD current limit is the protection (`Motor.availablePower` 98-101; CURRENT LIMITED) | (demand / rating) | none | none | CURRENT LIMITED | none |
| none | Generator | Its load is at most its rating and its store's room (`ElectricStore` 100-104); off speed it takes none | (load) | none | none | OFF SPEED | none |
| none | Centrifugal compressor surge | The built-in recycle holds the surge line (`CentrifugalCompressor` 57-60), so surge never stands | `q < SURGE s Q_r`, computable at commit | none. Option: the word RECYCLE, since the recycle's power is spent (`shaftPower` 70-72) | none | RECYCLE (option) | Raise the flow, slow the machine |
| none | Engine, gas turbine | P11: no overspeed; the governor caps at rated; the wrong phase keeps the valve shut (P6 B) | (fired heat, supply) | none | none | the fuel words | none |
| none | Creative motor, Create coupling, shaft, fans, ejector | Protected by construction (the impact is cut to the spare) or static | none | none | none | none | none |

P11 needs no revisit for any row. One question remains: the steam turbine's unreachable `OVERSPEED` (M-03). Should the steam turbine follow P11 as well, with its trip deleted? Or should the ungoverned turbine come back? The guide's "Overspeed protection remains a trip" is false today.

Rows the draft in REVIEW_PLAN §2 lists that the code does not support:
- compressor surge: none can stand;
- liquid-ring seal-liquid loss: the seal is internal and fixed (V2 B); no seal flow exists in the solve;
- motor and generator overload heating: protected by construction;
- gearbox over-torque: the rating must first be read, so N7 option A.

### 3.4 Structure and cost

- **Per dispatch** (`ShaftTrains.plans` 545-612): up to six `HashMap`s (fans, fired, fuel, tips, failed, the result), a `putAll` chain, one pipe walk per contributor (electric, burners, prime movers, flares, health: five pipe walks), then a sixth for speeds and governors. Flat form: one `out` map, filled in a fixed order, with one walk of `graph.pipes()` that dispatches by control type.
- **Per commit** (`booked` 623-705): six subsystems each walk the pipes. `PumpHealth.booked` 155-157 and `Ejectors.booked` 52-53 each build a `HashMap` of all observations. For each `Machine`, two `SpeedControl.requiredSpeed` bisections (2 × 42 `law.rise` calls; each compressor `rise` costs one `DeterministicMath.pow`). A shared edge-indexed observation array in `PassiveIntervalSolver.Result` serves all of them.
- **Per decision** (`decide` 737-747): O(trains). But `step` returns true for every turbine, valve or prime-mover train (840), and every true return runs `publish` (922-987). `publish` calls `topology.shafts()` (a `Map.copyOf` of every unit, `WorldTopologyLedger` 485) and walks all units three times (casings, turbines, prime movers). Each `topology.shaft(next)` write scans every unit for a position clash (ledger 497). With N trains stepping in turn, that is O(N × units) per cycle.
- **Per cadence** (100 ticks): `ElectricStores.sync` walks every active registration, plus a copy of the shafts and the stills (252-273). `PumpHealth.cadence` calls `isPump` (a registration lookup) for each pump.
- **Flat form.** `Run` keeps `double[] power`, `double[] output` and `Duty[] duties` aligned with `train.driven()`, in place of three per-run `HashMap`s (138-151). It also keeps an `int` index from unit to its slot, built at compile. `shares`, `governorTargets`, `fuelTargets` and the three `woken` maps stay as maps (keyed by device, rebuilt rarely). `trainOf` and `unitOfDevice` are built at compile only.
- **Plant hub.** `IslandCoordinator.Shafts` has one implementer, which also carries burners, flares, chimneys, prime movers, stores and health (207-245). A `PlantHooks` composite should own the list. `ShaftTrains` keeps the trains (about −150 lines from it, moved).
- **One `Health` record for every machine** (M-12). `MachineHealth` keeps `PumpHealth`'s life cycle (book at commit, rest booking on the cadence, fail at 0, close by plan, repair by a queued kit) for any device whose `Wear.Fault` table has an entry. Per device it keeps health, rate, fault and intensity byte, booked tick, and failed. `Wear` gains `enum Fault { CAVITATION, DRY, LOW_FLOW, WET_GAS, LIQUID, WET_EXHAUST }`, each with `intensity(q)` and its class (erosion or seizure). The sound bytes read the same intensity. The facts stay `Map<Long, Double>` (schema 12 format, only below 1). Shaft-unit identities never collide with device identities (ledger 496 refuses one), so a gearbox's health may key by its unit.

### 3.5 `science/machine` → `science/flowsheet` (and → `fluid`): the inversion

- Move `flowsheet/EjectorLaw` (62 lines, imports only `science.math`) to `science/machine/EjectorLaw`. `EjectorUnit`, `Ejectors` and `SteamEjector` import it from there.
- Add `SteamEjector.suctionSpeed(P)`: 60 m/s from 5 kPa up, `60 (5 kPa/P)^¼` below, the numbers of `Limit.VACUUM_SPEED` (`Limit` 102). `Limit.VACUUM_SPEED` keeps its bound, and a test asserts that the two agree at 1, 5 and 50 kPa. The world's ejector then no longer reads a sizing guideline.
- Move `EjectorUnit.MOTIVE_PRESSURE` and `DISCHARGE_PRESSURE` to `SteamEjector.RATED_MOTIVE` and `RATED_DISCHARGE`; `EjectorUnit` reads them.
- Split `InlineMachines`:
  - `inlineLaw(bore, liquid) → MachineLaw` stays in `machine`;
  - `design(bore, liquid) → FlowControl.Machine` and `ratedShaftPower(Mover)` move to `science/fluid/network`.
- `ElectricStore`'s five `SimulationTime` parameters become `double seconds` arguments, or `SimulationTime` (60 lines, a factor record) moves to `science/math`.

All of these are STRUCTURE; the results are bitwise identical.

## 4. Findings

| id | file:line and symbol | what it is | proposed change | class | lines removed or moved | risk | proof |
|---|---|---|---|---|---|---|---|
| M-01 | `ShaftTrains` 665, 676 `booked`; `ElectricStores` 499 `booked`; `InlineMachines` 55-61 `ratedShaftPower`; against `PassiveStepSolver` 1905, `ConservativeTransport` 594-604 | The driver is charged for a shaft power recomputed with `k = 1.4`, no wet factor and, for a set-flow in-line compressor, a second efficiency (`/0.65` over `Compressor.power`'s `/0.78`, `FlowControl` 180-184). FE drawn against the work the gas gets: a propane frame at r = 3 is charged +13 % (k 1.13); steam or methane +4 % (k 1.3); a wet centrifugal machine is under-charged by `1 − 0.5w` (−2.6 % at w 0.05, −11 % at 0.2); an in-line set-flow compressor +54 % | `PassiveIntervalSolver.Result` carries each mover's shaft power per edge, the solver's own number (fluid work for a compressor; `law.shaftPower` for a pump under S1). `ShaftTrains`, `ElectricStores` and `ColumnPower` read it | DEFECT | −25 lines | Moves FE draws, store lives, generator loads and a machine page's power by the sizes given. No reference in the 33 junction lines or chain-100 reads FE | Add `ShaftPowerBookingTest`: a frame on propane and a wet centrifugal machine; the train demand × dt equals the work `ConservativeTransport` booked. Affected: `ShaftCouplingTest`, `TimeFactorElectricTest`, `HeatUnitPowerTest`, `ElectricGameTests` |
| M-02 | `RepairKitItem` 52 `pumpAt`; `FluidWorldAuthority` 2493 | `PumpHealth` wears and fails a dry screw (V6 B, `PumpHealth` 105-108, 160-168), but the kit refuses every casing except `Kind.PUMP`, and the device status reads `health.status` for liquid movers only. A failed dry screw stays closed for good, and its page never says FAILED | `pumpAt` accepts any casing whose fluid device `health.health(id) != null`. The status line reads `health.status` for every worn mover (`PumpHealth.worn`) | DEFECT | ±5 | none | Add a GameTest case to `PumpHealthGameTests`: a dry screw fed liquid fails and a kit repairs it |
| M-03 | `ShaftUnit` 84 `drive`; `ShaftTrain` 117-119, 181-184; `ShaftTrains` 796 | `MOTOR` always maps to `VARIABLE_MOTOR` (the `variable` argument is unused), so the `FIXED_MOTOR` OVERLOAD trip never runs. Every steam turbine is stepped governed, so the ungoverned OVERSPEED trip never runs | Delete the `FIXED_MOTOR` case, the ungoverned branch, `SpeedControl.balance`'s infinite case for no demand, and the `variable` argument. Keep `Trip.OVERLOAD` and `OVERSPEED` (saved by name) so a saved trip still shows and resets. Correct the code guide's "Read this when" and "Contracts" | STRUCTURE | −20 main, ShaftTrainTest cases | A saved latched trip must still reset (the `Reset` event stays) | `ShaftTrainTest` restated, `compileTestJava`; `ShaftCouplingTest` |
| M-04 | `ElectricStores` 468-472 `plans`; `PumpHealth` 182-193 `plans`; `ShaftTrains` 601-611 `failures`; `ShaftPlan` 109-111 | `FAILED` carries "health 0" and "no FE". For an unpowered set-flow in-line pump, the electric plan closes it (0). On the next dispatch the graph carries `enabled = false`, and `PumpHealth` writes FAILED 1 (switch back on), which overwrites. The pump runs every other slice without power | One plan kind `RUN` (0/1) per mover, decided once at dispatch from failed ∨ unpowered ∨ off. `ShaftPlan.apply` restores `enabled` from the registration. Delete `PumpHealth`'s reopen rule | DEFECT | −12 | Inferred from the code, not run | Add to `PumpHealthTest`: an unpowered legacy in-line pump stays closed over three dispatches |
| M-05 | `CentrifugalCompressor` 12-13, 57-60 | The anti-surge recycle is built into the law, so surge never stands. The recycle's power is charged but nothing shows it | Optional word RECYCLE while `q < SURGE s Q_r` at commit; no wear | STRUCTURE (display) | +5 | none | `MachineColumnTest` |
| M-06 | `PumpHealth` 164; `VacuumPump` 47, 93-95 | Dry-screw wear is a step at 1 % liquid by volume; its noise is a ramp to 10 % | `Wear` reads the same ramp (N5) | PHYSICS | ±3 | Lives move: 1 % liquid 40 min → 400 min; ≥ 10 % unchanged | `PumpHealthTest`, `WearTest` |
| M-07 | `ShaftTrains` 667-668 `booked`, 785, 797 `step` | VFD Auto inverts the curve once per slice with `k = 1.4` (`requiredSpeed`), open loop, no integral term. A compressor in pressure mode on a k = 1.2 gas at r = 2 settles at 2.073: +7.3 kPa over a 2 bar target (3.7 %) | Pass the suction's `k` (the solve has it: `kappa`, `PassiveStepSolver` 1979) through the result | DEFECT | ±4 | Moves Auto compressor speeds by about 2 % | `ShaftCouplingTest`, plus one Auto-pressure case on propane |
| M-08 | `ShaftTrains` 545-612 `plans` | Up to six `HashMap`s per dispatch, six pipe walks | One `out` map and one walk in a fixed order (the order keeps the overwrite precedence) | PERF | −25 | None if the order is kept | `ShaftPlanTest`, `ShaftCouplingTest` |
| M-09 | `ShaftTrains` 840 `step`, 922-987 `publish`; `WorldTopologyLedger` 485, 497 | `publish` runs after every turbine, valve and engine step, copies every unit (`Map.copyOf`) and walks them three times. Each unit write scans every unit | Publish the stepped train's members only. The ledger exposes an unmodifiable view and a position index | PERF | −30 | `Map.copyOf` iteration order is salted per JVM; today no order-dependent use was found, and keep it that way | `ShaftCouplingTest`, `WorldTopologyLedgerTest` (no `ShaftTrainsTest` exists, although `ShaftTrains` 493 names one) |
| M-10 | `ShaftTrains` 138-151 `Run` | Three per-run `HashMap`s keyed by unit | Arrays aligned with `train.driven()` | PERF | ±20 | none | `ShaftCouplingTest` |
| M-11 | `ShaftTrains` 666-668, 681-683 | Two 40-step bisections per machine per commit, also when no driver regulates | Compute them only for the first driven member of a train whose driver is in REGULATE_* mode | PERF | ±6 | none | `ShaftCouplingTest` |
| M-12 | `PumpHealth` (330), `Wear` (63) | Health exists for pumps and dry screws only, with a special branch for the screw (160-168) | `MachineHealth` + `Wear.Fault` table (section 3.4); the matrix rows the owner picks | STRUCTURE (then PHYSICS per new mode) | `PumpHealth` → `MachineHealth`, about ±60 | Facts unchanged; each new mode moves nothing until the owner picks it | `WearTest`, `PumpHealthTest`, `FluidFactsCompatTest` |
| M-13 | `MachineDuty` 114 `gearbox`; `ShaftTrains` 525, 806, 861 | A gearbox's rated power is saved and never read | N7 option A: the train's limit includes the gearboxes in its path | PHYSICS | +15 | An under-rated gearbox now limits its train (GEAR LIMITED) | `ShaftNetworkTest`, `ShaftTrainTest` |
| M-14 | `SteamEjector` 3-4, 18-19; `InlineMachines` 3; `ElectricStore` 48-68 | `machine` imports `flowsheet` and `fluid` | Section 3.5 | STRUCTURE | 62 moved (`EjectorLaw`), 2 constants, 30 moved (`InlineMachines`) | none | `SteamEjectorTest`, `EjectorTest`, `SteamEjectorConnectionTest`, `ElectricStoreTest`, `compileJava` |
| M-15 | `ShaftTrains` 529, 1072 | `runtime` reads `world.level.block.ShaftBlock.DEFAULT_SIZE` | Move the constant to `ProcessUnit` (which already gives `shaftPixels`) | STRUCTURE | 2 | none | compile |
| M-16 | `ShaftTrains` 1044-1045 `view` | A casing whose pump failed shows its train's RUNNING on its Drive group and machine page | `view` takes the health word for a casing (FAILED, or the fault word) | STRUCTURE (display) | +3 | none | `MachineColumnTest` |
| M-17 | `PumpHealth` 21-23 javadoc; `ShaftTrains` 178 vs 478 | Stale words: "online ticks" (the code books process time, 132); "NO GOVERNOR VALVE" against the "INTERNAL GOVERNOR" it sets | Correct the comments | STRUCTURE | 2 | none | none (a comment change runs no test) |
| M-18 | `Ejectors` 32, 57 | `booked` is never pruned for removed ejectors | Retain only the identities seen at `dispatch` | PERF | +2 | none | `SteamEjectorRuntimeTest` |
| M-19 | `Motor` 78-89, 110-117; `ShaftTrains` 806 | `Motor.electricPower` throws for poles other than 2, 4, 6 or 8. `ShaftParts` 132 maps 0 to 2, but `MachineDuty.POLES` allows 12, and `ElectricStore.motorDraw` 113 guards only 0 | Map 12 to 8 in `Motor.efficiency` as `generatorEfficiency` does (58-59), or refuse 12 for a motor in `MachineDuty` | DEFECT (latent) | 1 | none | `MotorTest` |
| M-20 | `ShaftTrain` 109-113, `ShaftTrains` 799-800, 813 | The step's statuses are strings, compared by value (`"STEAM LIMITED"` at 155, `"LOW FUEL"` at 833) | An enum `Word` with a lang key per word, so the status words are one table across drivers | STRUCTURE | ±20 | Wire: the `machine-4` text field stays a string | `MachineColumnTest`, `ShaftTrainTest` |

## 5. Open questions for the main thread

1. M-01 changes FE draws and generator loads by −11 % to +54 % in the cases named. Take it as a defect fix, or offer it to the owner as an option?
2. The section 3.3 matrix asks the owner to widen D34 ("keep this simple"). Present N1 (low flow), N2 (wet gas) and N4 (wet exhaust) as the core set, with N3, N5, N6 and N7 as extras?
3. N3 (reciprocating liquid) needs one supply read per running frame per solve, a solver cost. Ask the owner, or drop the row?
4. The steam turbine's `OVERSPEED`: delete it under P11's reasoning, or restore an ungoverned turbine?
5. M-12 reuses the two lives' config keys for every fault class. Is a widened meaning of a config key acceptable? The facts rule binds facts, not config.
6. M-04 is inferred from the code. Should S6 or a sub-thread confirm the dispatch-graph carry (does an island's next dispatch read the last slice's `enabled`, as `PumpHealth` 179-180 states) before it is fixed?
7. `ColumnPower`'s use of `InlineMachines.ratedShaftPower` (a held reflux pump books about 1.4 times its real shaft power) is S8's. M-01's per-edge shaft power could serve it too.
