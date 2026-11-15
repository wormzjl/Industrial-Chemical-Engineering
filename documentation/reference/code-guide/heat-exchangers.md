# Heat exchangers: the heat laws, the curve, the relay and built-in control

Up: [code guide](README.md) · heat units in the world (registration, the coupling across islands, control lines, saves,
the screen): [heat-units.md](heat-units.md)

## Read this when

- An exchanger, kettle, air cooler, fired heater or a gas turbine's steam coil moves the wrong heat, its outlet stands at
  the wrong temperature, or it moves none.
- A side's status says `LIMITED`, `LOW LIMIT` or `DUTY INSUFFICIENT`, and you need the controller's reason.
- You change a heat law, the films, the curve, the wall solve, the give-back, a unit's surface or passage, the
  controller, the kettle's pool, the fired heater's coils, or the crossflow routing.

The step's equations, junctions and booking: [fluid-step.md](fluid-step.md), [fluid-interval.md](fluid-interval.md). The units' shapes and nozzles:
[equipment-geometry.md](equipment-geometry.md). A running column's utilities: [column-run.md](column-run.md).

## What it does

An exchanger passes a hot and a cold stream past a steel wall in counterflow; an air cooler blows ambient air over
finned tubes; a fired heater burns fuel, gives most of its heat to a radiant coil and the rest, in the flue gas, to a
convection coil; a kettle boils a pool around a tube bundle; a gas turbine's exhaust raises steam in one coil. Each
passage of a unit compiles as one pipe that carries a **heat law** (`HeatLaw`: `HeatSide`, `AirCooled`, `FiredCoil`,
`ConvectionCoil`, `ExhaustCoil`). Inside a step's Newton the heat is a pure function of the connection's flow and the
donor's specific enthalpy, on the donor stream's enthalpy-temperature-film curve (`HeatCurve`): no unknown, no mode,
exactly zero at no flow. Everything else is a slice input fixed before the Newton: an air cooler's louvers and fans, a
radiant coil's firing and a convection coil's flue are planned at each step's start; an exchanger's wall, its bypass and
its give-back at each slice's start from the other side as read (`ExchangerRelay`). The wall holds no heat. What the two
sides of an exchanger booked differently waits as the exchanger's heat in transit, returned by the give-back. Each
controllable side has a built-in controller (`HeatControl`), dead-beat on its own law.

## Class map

Paths under `src/main/java/com/wormzjl/icheme/`. Lines at the snapshot.

| Class | Role | Lines |
|---|---|---|
| **`science/fluid/network/`** (Minecraft-free) | | |
| [`HeatLaw`](../../../src/main/java/com/wormzjl/icheme/science/fluid/network/HeatLaw.java) | Sealed: `heat(curve, inlet, massFlow)` (W into the stream), `coldest`, `hottest` (the span the donor's curve covers), `films`, `shape()` (the facts that shape the equations, part of the connection's identity) | 24 |
| [`HeatSide`](../../../src/main/java/com/wormzjl/icheme/science/fluid/network/HeatSide.java) | An exchanger side or a kettle's tubes: facts `area`, `films`, `partner`, `control`; slice inputs `wall` (K per cell, in a forward flow's order), `bypass`, `giveBack` (J/kg on the whole flow), `active` (the sit-out), `limit` | 82 |
| [`AirCooled`](../../../src/main/java/com/wormzjl/icheme/science/fluid/network/AirCooled.java) | An air cooler's process side against air at a constant temperature; `Air` (per m² of face: bare tube, fin film, air capacity rate, `natural` draught share); `draught` (0 louvers shut, `natural` fans still, 1 full fans), `fans()`, `louvers()`, `fanLimit` (the most of full speed its fans may turn this slice), `planned` | 99 |
| [`FiredCoil`](../../../src/main/java/com/wormzjl/icheme/science/fluid/network/FiredCoil.java) | A fired heater's radiant coil: `setTemperature`, `duty`, `firing` (β), `limit` (the share its burners can fire), `partner`; `planned` | 50 |
| [`ConvectionCoil`](../../../src/main/java/com/wormzjl/icheme/science/fluid/network/ConvectionCoil.java) | The convection coil: counterflow against the flue gas leaving the radiant box; `conductance`, `radiant`, `flue`; `FLAME`, `BRIDGEWALL`, `flueOf(radiantHeat)`, `stackTemperature` | 61 |
| [`ExhaustCoil`](../../../src/main/java/com/wormzjl/icheme/science/fluid/network/ExhaustCoil.java) | A gas turbine's steam coil, designed to its pinch: `APPROACH`, `PINCH`, `COLD` | 59 |
| [`HeatCurve`](../../../src/main/java/com/wormzjl/icheme/science/fluid/network/HeatCurve.java) | One stream's h-T-film curve, fixed for a step: `sample`, `of`, `march` (exact per segment), `temperatureAt`, `enthalpyAt` (a plateau's vapour end), `lowestEnthalpyAt` (its liquid end), `temperatureSlope`, `withFilms`, `liquidLikeWeight`; `Films` (`DEFAULT`, `at(x, w)`); `LIQUID_UNTIL`, `GAS_FROM`, `FILM_GAS_UNTIL`, `FILM_LIQUID_FROM`, `BOUNDARY_KELVIN` | 272 |
| [`ExchangerRelay`](../../../src/main/java/com/wormzjl/icheme/science/fluid/network/ExchangerRelay.java) | The relay as pure functions: `Partner` (the other side as read, or a kettle's pool as a `Bath`; `model`, `withModel`), `Read` (`NONE`, `column`, `heating`, `withOperation`, `gives`), `Operation` (`bypass`, `feed`, `column`), `plan`, `wall`, `planPool`, `bathWall`, `capacityRate`, `TOLERANCE` | 372 |
| [`CrossflowHeat`](../../../src/main/java/com/wormzjl/icheme/science/fluid/network/CrossflowHeat.java), [`HeatRouting`](../../../src/main/java/com/wormzjl/icheme/science/fluid/network/HeatRouting.java) | A two-pass tube bundle against a shell mixed between crossflow rows, on the relay's wall solver; the routings `SERIES`, `TWO_PASS_TUBES`, `MIXED_CROSSFLOW` (`grid`) | 123, 112 |
| [`HeatControl`](../../../src/main/java/com/wormzjl/icheme/science/fluid/network/HeatControl.java) | The built-in controller: `Target` MANUAL, OUTLET_TEMPERATURE, DUTY; `value`, `lowLimit`, `manual`; `Limit` NONE, LOWEST, HIGHEST, LOW_LIMIT; `setting` (an Illinois root on the side's own law) | 76 |
| [`Pool`](../../../src/main/java/com/wormzjl/icheme/science/fluid/network/Pool.java) | A kettle's pool on its vessel's `Inventory`: facts `id`, `partner`, `area`, the bundle's `bottom` and diameter; slice inputs `wall`, `giveBack` (W), `active`; `wetted`, `film`, `heat` | 124 |
| **`science/equipment/`** | | |
| [`HeatSides`](../../../src/main/java/com/wormzjl/icheme/science/equipment/HeatSides.java) | `Unit` EXCHANGER, KETTLE, AIR_COOLER, FIRED_HEATER, PRIME_MOVER; `Side` TUBE, SHELL, PROCESS, CONVECTION, RADIANT, EXHAUST; `unit(kind)`, `of(shape)` (each side's nozzle roles, surface and passage, in registration order), `pool(kind)`, `kettleBundle`, `kettleShell`, `heatSide(...)`, `coilDuty`, `airFilms`, `fanPower`, `airCoolerSurface`; `AIR`, `AIR_TEMPERATURE`, `EXCHANGER_CELLS`, `CONVECTION_U`, `NATURAL_DRAUGHT`; the cooling utilities the column and the simulator share: `AIR_SPLIT_TEMPERATURE`, `airSplit`, `TOWER_WATER_TEMPERATURE`, `WATER_RANGE`, `WATER_APPROACH`, `coolingWaterOutlet` | 251 |
| [`ExchangerType`](../../../src/main/java/com/wormzjl/icheme/science/equipment/ExchangerType.java) | The exchanger configuration catalog (`CATALOG`: `STANDARD`, `CROSSFLOW`), saved by name: `key`, `cells`, `routing(side)`, `Layout`, `tubePassage`, `shellPassage` | 71 |

## Main flow

One coupled exchanger side in one slice (the engine side is in [heat-units.md](heat-units.md)):

1. **Read** (at dispatch, on the server; a certified partner behind the slice start materialised to the start first,
   `ExchangerCoupling.reads`): the side's `ExchangerRelay.Read`: the partner as of the slice start, whether it
   is stopped, the transit due, the partner's slice length, the sit-out share, an `Operation` where the side's lines are
   complete, and for a convection coil whose radiant coil is in another island the radiant coil's heat rate.
2. **Slice plan** (worker, once per interval, `PassiveStepSolver.couple`): `ExchangerRelay.plan` gives the side its
   wall, bypass, give-back and active share; `planPool` the pool's wall; each convection coil its flue from
   `Read.heatRate`; each complete side its valve's target. Both curves (`HeatCurve.sample`) span both inlets, 10 K
   beyond, each at its own stream's pressure. A side without a read gets a flat wall at its own temperature and moves no
   heat.
3. **Step plan** (worker, every step, `PassiveStepSolver.plan`): `FiredCoil.planned` (firing), `AirCooled.planned`
   (draught), `ConvectionCoil.flueOf` of the radiant coil's planned heat in the same island; from the last accepted
   step's flows.
4. **Newton**: the connection delivers its donor's stream with the heat it took up into the receiver's energy row
   (`StepEquations.sideHeat`, on the donor end's curve `sideCurve`). A curve is never sampled inside a residual or a
   Jacobian: an evaluation that reaches an end without a kept curve (`heldCurves`, `curveKept`) reads zero there, is
   discarded, the end is sampled between evaluations, and the evaluation is done again from the same point
   (`evaluating`, `evaluated`). A pool's heat enters its vessel's energy row on the step's start state
   (`poolHeat`), and its boil-up (`boilUp`) counts as gas the vessel's ports may draw within the step.
5. **Booking**: `ConservativeTransport` books each side's heat (`EdgeHeat`) into the receiver and as a `HEAT` boundary at
   the connection id, a pool's at its key (`bookedHeat` samples a missing end first).

## Contracts

- **Units and their sides** (`HeatSides.of`):

  | Unit | Sides in registration order | Surface | Passage |
  |---|---|---|---|
  | EXCHANGER | TUBE, SHELL, partners | `UnitRatings.tubeSurface(D, L)` = 75 D² L, each side | tubes: 630 D² tubes per pass of 14.8 mm bore, two passes over 2 L, 4 velocity heads per pass; shell: 18 mm hydraulic diameter, 0.1 D² crossflow area, 2.5 L, a pipe's friction (Kern's drop with baffles about 0.93 D apart) |
  | KETTLE | TUBE, then the shell: a closed reservoir with FEED, VAPOUR_OUTLET and LIQUID_OUTLET (`pool`), partners | 75 D² L of the bundle | tubes as an exchanger's; shell a lying drum 1.6 bundles across over 0.9 L + 0.3 m (`kettleShell`), the bundle shell/1.6 across (`kettleBundle`) |
  | AIR_COOLER | PROCESS, no partner | 5 m² of bare tube per m² of face, per bay (`airCoolerSurface`) | each bay's tubes in one pass of 21.2 mm bore, trains in parallel, bays of a train in series, 1.5 velocity heads per bay |
  | FIRED_HEATER | CONVECTION, RADIANT, partners (the player pipes the crossover) | 30 % of `UnitRatings.heaterDuty` at 15 kW/m², 70 % at `UnitRatings.RADIANT_FLUX` | 6" tubes of 146 mm bore in parallel passes of 8 MW each, half a velocity head per return bend every 10 m |
  | PRIME_MOVER | EXHAUST (a gas turbine's coil, at its FEEDWATER nozzle) | | |

  `PipeResistance.Geometry.channels` is the passage's channel count: the run loses what one channel loses at the flow
  over the count; its area and volume are all channels'.
- **Laws:**

  | Law | Heat (W into the stream) | Slice inputs, planned by |
  |---|---|---|
  | `HeatSide` | (1 − bypass) \|ṁ\| marched through the cells against the wall on the donor's curve, times `active`, plus \|ṁ\| · giveBack | wall, bypass, give-back, active, limit: per slice (`ExchangerRelay.plan`) |
  | `AirCooled` | one march against air at `AIR_TEMPERATURE` over the bare surface with `Air.films(draught)`; 0 with the louvers shut | draught and limit: per step (`AirCooled.planned`); `fanLimit` 0 when its store is empty, so an unpowered bank falls to natural draught |
  | `FiredCoil` | β · \|ṁ\| · (h_set − h_in), h_set = `curve.enthalpyAt(T_set)`, at most its duty at its limit | β per step: 1 within the duty, duty/demand beyond it, 0 at or above the set point or with the burners out |
  | `ConvectionCoil` | counterflow ε C_min (T_bw − T_in) over UA = `CONVECTION_U` × bare tube; the stream's mean capacity rate from its inlet to the bridgewall | flue C_g = Q_r/(T_flame − T_bw): per step from the radiant coil in the same island, else per slice from `Read.heatRate` |
  | `ExhaustCoil` | the outlet enthalpy the least of the enthalpy `APPROACH` under the gas inlet and, at every knot of the stream's curve, the enthalpy that leaves the gas `PINCH` over the stream (a boiling stream's bubble point decides) | gas inlet (K) and capacity rate (W/K) from the turbine's last fired slice; `COLD` while stopped |

- **The curve** is the stream the donor end's port draws (a drum's liquid draw has its own bubble point), at the donor's
  pressure. Beyond its end knots it extends its end lines, value and slope continuous, and raises no notice: the
  property models' own data-range notices report every node state. Solids are not on the curve. A law's `shape()` is in
  the connection's identity; its slice inputs are not.
- **The wall** (`ExchangerRelay.wall`): both streams marched through the cells (`ExchangerType.cells`: `EXCHANGER_CELLS`
  in series for Standard, an 8 × 8 grid for crossflow) in counterflow on their curves with their films. Each sweep
  passes the cells in one stream's order, then the other's; each cell's temperature is an Illinois root of its two
  heats, bracketed by the two entering temperatures. It stops when no cell moves more than `TOLERANCE` (a hundredth of it on a crossflow grid), or after 200 sweeps
  (a wall stopped short is still a valid wall: energy stays exact). It starts from the side's last planned wall; a start
  the first sweep moves by less than the tolerance is kept unchanged, so a steady side plans the same wall and its
  island can certify.
- **A still or stopped partner** gives an active share of 0: the side's cells move nothing and it keeps its wall. A side
  whose own flow is unknown or still gets a wall at the partner's inlet.
- **The give-back**: the side's share of the transit due, W_own Δt_own/(W_own Δt_own + W_other Δt_other), W the capacity
  rate through the cells (`capacityRate`: ṁ Δh/ΔT, or the curve's slope at the inlet; unbounded on a plateau, which then
  takes it all), an enthalpy shift on the whole flow, scaled so the outlet at the slice start stays between the two inlet
  temperatures; the rest stays in transit. A side without flow takes none. A kettle's tubes take none: the pool takes
  all of it.
- **A running column's reads.** `Read.column(due, seconds, ceiling, heatRate)` (a read with no partner and `ceiling` above
  0): no cell moves; the give-back is all the heat owed over the slice, scaled so the outlet stays at or below the
  ceiling. `Read.heating(due, seconds, floor, heatRate)`: the heat the side owes the column (negative), the outlet's
  floor in the ceiling's place; `gives()` says which ([column-run.md](column-run.md)).
- **Crossflow** (`ExchangerType.CROSSFLOW`): `TWO_PASS_TUBES` against `MIXED_CROSSFLOW` on the 8 × 8 wall grid (shell flow
  mixes along the bundle between transverse rows; tubes traverse two passes, mixing in the return header). It reuses the
  curves, films, cell march, wall roots, bypass and transit. Its shell passage: free area 0.1 D L, equivalent path
  2.5 D, hydraulic diameter 0.018 m, a rounded parallel channel count; tubes keep the two-pass law. The kettle keeps its
  pool law. Rating and sizing use the same routing.
- **The kettle's pool** (`Pool`): its heat is Σ film · A_cell · (T_wall − T) · active + give-back, explicit on a step's
  start state, booked as a `HEAT` boundary at the pool's key; the wall planned at the slice start from the tubes as read
  (`bathWall`). The film follows the level (`Pool.wetted`: the share of the bundle under the liquid level; wet tubes the
  boiling film over vapour or the liquid film without it, dry tubes the gas film); a kettle that boils dry stops
  heating. A vapour port's capacity adds the pool's heat over the latent heat, so it draws the boil-up. The LIQUID_OUTLET
  is a plain vessel port: no weir.
- **Control** (`HeatControl.setting`): the effort whose heat meets the target on the side's own law (an Illinois root),
  the give-back not counted. A target past full effort takes full effort (`HIGHEST`); one past the inlet, or below the
  lowest effort's heat, the lowest (`LOWEST`); on a cooled side the low limit then holds the effort back (`LOW_LIMIT`),
  in every mode, manual included. A set point on a plateau names its liquid end on a cooled side and its vapour end on a
  heated side. An exchanger side's bypass is planned per slice with the wall, in turn until it moves less than 1e-6 (at
  most 8 rounds); an air cooler's draught per step, as a fired coil's firing.

## Laws and constants

- **Films** (`HeatCurve.Films.DEFAULT`): liquid 2000, boiling or condensing 5000, gas 100 W/m²K. Each knot carries the
  film of its vapour fraction: liquid below `LIQUID_UNTIL`, the two-phase film to `GAS_FROM`, gas at 1, linear between;
  those knots give a pure component's plateau the two-phase film. A point of one fluid carries its liquid-like weight
  `w = smoothstep((delta − FILM_GAS_UNTIL)/(FILM_LIQUID_FROM − FILM_GAS_UNTIL))` of its reduced density (gas film at or
  below 0.5, liquid from 1.5, `Films.at(x, w)`) and the vapour coordinate `1 − w`, so a dense fluid's film follows its
  density across its slot line. An inserted knot takes the weight only between points of one continuous fluid (the same
  slot, or both dense); on a plateau it keeps the two-phase film.
- **Sampling** (`HeatCurve.sample`): 16 divisions, a phase change bisected to `BOUNDARY_KELVIN`, segments refined while
  the midpoint lies more than 0.2 K off the chord, at most 512 knots.
- **The march** (`HeatCurve.march`): dh/dA = −h_film(h)(T(h) − T_wall)/ṁ integrated exactly on each segment (a
  partial-fraction integral, its limits through `DeterministicMath.log1p` and `expm1`); the stream approaches the wall and
  never crosses it.
- **Air** (`HeatSides.AIR`): per m² of face, 5 m² bare tube, 50 m² of fins under a 100 W/m²K film, 3.5 kW/K of air at
  full fans; natural draught `NATURAL_DRAUGHT` of that. Fan power 1 kW per m² of face at full speed and the cube of the
  speed below (`fanPower`): it heats the air, not the stream.
- **Coils**: `ConvectionCoil.FLAME` and `BRIDGEWALL`; `stackTemperature` (the bridgewall less the convection heat over
  the flue's capacity rate) is a read for the effects' plume.
- **Cooling utilities**: `AIR_SPLIT_TEMPERATURE` (the air plus 15 K) and `airSplit(top, receiver)`;
  `TOWER_WATER_TEMPERATURE`; cooling water leaves at min(T_in + `WATER_RANGE`, process cold end − `WATER_APPROACH`).

## Formats

The laws' facts are a heat unit's registration facts; their slice inputs and the checkpoint's heat-law tags are state
([heat-units.md](heat-units.md)). `ExchangerType` is saved by name. Numbers: [FORMATS.md](../FORMATS.md).

## Tests and gates

| Change | Owner | Tests (`--tests` filter) |
|---|---|---|
| A curve, films, the march, a plateau's ends | `HeatCurve` | `HeatCurveTest` (the kernel against the analytic exponential and latent knots; a film step; a pure condensing stream against water; the boundary's resolution bounds the condensing march) |
| An exchanger side's heat, bypass, reversal, zero flow, a boiling junction | `HeatSide`, `StepEquations.sideHeat`, `bookedHeat`, `ConservativeTransport` | `HeatSideNetworkTest` |
| The wall, the give-back, the plan, the column's reads | `ExchangerRelay` | `ExchangerRelayTest` (`aColumnsHeatingSideGivesItsDueDownToTheFloor`) |
| The controller | `HeatControl` | `HeatControlTest` |
| A fired heater's coils, the steam coil | `FiredCoil`, `ConvectionCoil`, `ExhaustCoil` | `FiredCoilTest`, `ConvectionCoilTest`, `ExhaustCoilTest` |
| Crossflow | `CrossflowHeat`, `HeatRouting`, `ExchangerType` | `CrossflowHeatTest`, `CrossflowEquipmentTest`, `CrossflowSizingTest` |
| A unit's sides, surfaces, passages, air films | `HeatSides` | `HeatSidesTest` (`equipmentGates`); the cooling utilities through `ColumnSolveTest` and `CoolerSizingTest` |
| The pool, the coupling in the world | `Pool`, `planPool` | `ExchangerCouplingTest` ([heat-units.md](heat-units.md)) |

## Traps

- **A slice input is not a fact.** Wall, bypass, give-back, draught, firing and flue are planned; changing them never
  edits a registration.
- **Plan the heat where its inputs are known.** A law that needs its partner plans per slice (`couple`); one that needs
  only its own stream plans per step (`plan`). Nothing switches inside a Newton.
- **A kettle's tubes take no give-back**; the pool takes all of it.
- The air is a constant 25 °C (no weather, biome or altitude).
- The curve's extension past its end knots raises no notice: the property models' own data-range notices do.

**Snapshot:** `9d6d0d82e`, 2026-11-15.
