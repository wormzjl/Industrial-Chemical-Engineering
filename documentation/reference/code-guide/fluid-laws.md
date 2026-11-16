# The fluid laws: devices, heat, vessels, transport and conversion

Up: [code guide](README.md). The step that states them: [fluid-step.md](fluid-step.md). The heat laws in depth: [heat-exchangers.md](heat-exchangers.md); the machine laws: [rotating-machinery.md](rotating-machinery.md); towers: [cooling-towers.md](cooling-towers.md); control valves and vessel levels: [process-control.md](process-control.md).

<!-- j2a-packages: science/fluid/network/FlowControl.java science/fluid/network/ControlConstraints.java science/fluid/network/PipeResistance.java science/fluid/network/Orifice.java science/fluid/network/InletPhase.java science/fluid/network/PhaseDraw.java science/fluid/network/VesselGeometry.java science/fluid/network/OpenVessel.java science/fluid/network/GasBlanket.java science/fluid/network/InlineFilter.java science/fluid/network/InlineMovers.java science/fluid/network/ShaftPlan.java science/fluid/network/PortPlan.java science/fluid/network/HeatLaw.java science/fluid/network/HeatSide.java science/fluid/network/AirCooled.java science/fluid/network/FiredCoil.java science/fluid/network/ConvectionCoil.java science/fluid/network/ExhaustCoil.java science/fluid/network/HeatCurve.java science/fluid/network/ExchangerRelay.java science/fluid/network/HeatControl.java science/fluid/network/HeatRouting.java science/fluid/network/CrossflowHeat.java science/fluid/network/Pool.java science/fluid/network/Burners.java science/fluid/network/Flare.java science/fluid/network/Chimney.java science/fluid/network/CoolingTower.java science/fluid/network/Air.java science/fluid/network/TowerTransfer.java science/fluid/network/TowerExchange.java science/fluid/transport/ science/fluid/conversion/ -->

## Read this when

- A pipe's friction, a pump's or compressor's rise, power or refusal, a valve's mode, a check valve or a filter's cake is wrong.
- A top, bottom, boot or overflow outlet draws the wrong phase or mix; a vessel's level head is wrong.
- An open (floating-roof) tank pressurises, vents, overflows or spills wrongly; a blanketed drum's pad or vent misbehaves.
- A heat side, an air cooler, a fired heater's coils, a kettle's pool, an exhaust coil, a flare, a chimney or a tower moves the wrong heat or mass, or its slice input is planned wrongly.
- Solids settle, deposit, clog or travel wrongly; a viscosity is off.
- A Forge fluid converts to or from the network wrongly.

## What it does

These classes state what a connection or a vessel does, for the step to assemble into rows. `FlowControl` is a connection's device: passive, a mover (`Pump`, `Compressor`, `Machine`, `Ejector`) or a valve (`PressureValve`, `ControlValve`, `Turbine`), with the `Mode` the active set gives it. `PipeResistance` is the friction law; `PhaseDraw` and `VesselGeometry` say what a port draws and at what head; `InletPhase` reads a mover's supply once per slice. A connection may carry a heat law (`HeatLaw` and its laws): its heat for a step is a pure function of its flow and the donor's enthalpy on the donor's curve (`HeatCurve`), with no unknown and no mode, zero at no flow; it enters the receiver's energy row as a mover's work does and is booked as a `HEAT` boundary. Its slice inputs (a coupled partner's read, a controller's effort, firing, fans, a valve's target) are planned before the Newton (`ExchangerRelay`, `HeatControl`, `ShaftPlan`, `PortPlan`). `OpenVessel` and `GasBlanket` state a vessel's releases and its pad as constant terms of its rows. `transport/` holds viscosity and slurry laws; `conversion/` the Forge fluid rules. All are Minecraft-free and pure functions of their inputs.

## Class map

Under `src/main/java/com/wormzjl/icheme/science/fluid/`. Lines at `d6f721b94`.

| Class | Role | Lines |
|---|---|---|
| [`network/FlowControl`](../../../src/main/java/com/wormzjl/icheme/science/fluid/network/FlowControl.java) | A connection's device and `Mode`; movers (`Pump`, `Compressor`, `Machine`, `Ejector`; `lawRise`, `lawRiseSlope`, `lawResidual`, `riseLimit`, `power`, `npsh`, `refuses`), valves (`PressureValve`, `ControlValve` with `ValveSource`, `Turbine`), the controls' bounds | 430 |
| [`network/ControlConstraints`](../../../src/main/java/com/wormzjl/icheme/science/fluid/network/ControlConstraints.java) | Which regulation rows a junction's mass balances leave free: `standing` (flow valves in one series), `moverBounds`, `dependent` (pressure valves on one node), `series` | 146 |
| [`network/PipeResistance`](../../../src/main/java/com/wormzjl/icheme/science/fluid/network/PipeResistance.java) | Darcy-Weisbach with a laminar-turbulent blend and minor loss; `Geometry` (sections, `channels`, `narrowed`: a fouled passage); `DEFAULT_ROUGHNESS_METRES` | 76 |
| [`network/Orifice`](../../../src/main/java/com/wormzjl/icheme/science/fluid/network/Orifice.java) | A restriction orifice plate's loss coefficient `loss(beta)` (C 0.61), its ratios 0.20 to 0.80 by 0.05 (`valid`, `snap`, `next`); batch 2026-11-15-fluid-interfaces W6 | 49 |
| [`network/InletPhase`](../../../src/main/java/com/wormzjl/icheme/science/fluid/network/InletPhase.java) | A mover's suction supply (`walk`, `Supply`), the per-slice refusal (`decide`: a dry suction, a compressor's solids), `density`, `bulk`, `starved` | 343 |
| [`network/PhaseDraw`](../../../src/main/java/com/wormzjl/icheme/science/fluid/network/PhaseDraw.java) | A port's priority draw: segments, capacities, `fill`, `order`, `mixedLiquids`, `mixedShares`, `Segment.pinsGas` | 230 |
| [`network/VesselGeometry`](../../../src/main/java/com/wormzjl/icheme/science/fluid/network/VesselGeometry.java) | A vessel's `Form`, cumulative condensed volume, height from volume, boot datum and stacked-phase `head` | 88 |
| [`network/OpenVessel`](../../../src/main/java/com/wormzjl/icheme/science/fluid/network/OpenVessel.java) | An open vessel's releases over a step: vent, overflow band, spill (`releases`, `releasing`, `overflows`, `releasesAll`, `releaseShare`, `condensedVolume`, `topVolume`) | 187 |
| [`network/GasBlanket`](../../../src/main/java/com/wormzjl/icheme/science/fluid/network/GasBlanket.java) | A closed vessel's blanketing valve: the pad's mass target per step (`plan`, `inflows`) and the vent (`releases`, `ventPressure`), one law `gap` for both | 173 |
| [`network/InlineFilter`](../../../src/main/java/com/wormzjl/icheme/science/fluid/network/InlineFilter.java) | A filter's cake and its energy, owned by a pipe: capacity, clean resistance | 21 |
| [`network/InlineMovers`](../../../src/main/java/com/wormzjl/icheme/science/fluid/network/InlineMovers.java) | An in-line pump's or compressor's `FlowControl.Machine` from its bore (`design`) and the shaft power its driver pays (`shaftPower`, `ratedShaftPower`, `ratedDraw`) | 62 |
| [`network/ShaftPlan`](../../../src/main/java/com/wormzjl/icheme/science/fluid/network/ShaftPlan.java) | A slice input to one connection by device identity (`Kind`), applied at dispatch (`apply`); `TRIM` (a failed control valve trim stands open) | 171 |
| [`network/PortPlan`](../../../src/main/java/com/wormzjl/icheme/science/fluid/network/PortPlan.java) | One slice's plan of a running column's or still's port: `node`, `valve`, `massTarget`, an outlet's `temperature` and `moles`; `COLUMN_SENSOR` | 32 |
| [`network/HeatLaw`](../../../src/main/java/com/wormzjl/icheme/science/fluid/network/HeatLaw.java) | The sealed heat law of a connection's passage | 24 |
| [`network/HeatSide`](../../../src/main/java/com/wormzjl/icheme/science/fluid/network/HeatSide.java) | An exchanger side's law: the wall against its partner's curve, its bypass; the unit's UA as last planned (`conductance`) | 92 |
| [`network/AirCooled`](../../../src/main/java/com/wormzjl/icheme/science/fluid/network/AirCooled.java) | An air cooler's law against ambient air: draught, louvers, `fanLimit`; the bank's UA (`conductance`) | 134 |
| [`network/FiredCoil`](../../../src/main/java/com/wormzjl/icheme/science/fluid/network/FiredCoil.java) | A fired heater's radiant coil: firing and its `limit` | 50 |
| [`network/ConvectionCoil`](../../../src/main/java/com/wormzjl/icheme/science/fluid/network/ConvectionCoil.java) | A fired heater's convection coil on the radiant coil's flue gas; `stackTemperature` | 61 |
| [`network/ExhaustCoil`](../../../src/main/java/com/wormzjl/icheme/science/fluid/network/ExhaustCoil.java) | A gas turbine's counterflow steam coil designed to its pinch (`PINCH`, `APPROACH`), cold with the turbine stopped (`COLD`) | 59 |
| [`network/HeatCurve`](../../../src/main/java/com/wormzjl/icheme/science/fluid/network/HeatCurve.java) | One side's enthalpy-temperature-film curve at one composition and pressure, fixed for a step; `Films`, the liquid film of viscosity, conductivity and heat capacity (`liquidResistance`) | 510 |
| [`network/ExchangerRelay`](../../../src/main/java/com/wormzjl/icheme/science/fluid/network/ExchangerRelay.java) | The relay between an exchanger's two sides in two islands: `Read` (`column`, `heating`, `gives`), `Partner`, `Operation` (`bypass`, `feed`, `column`); `Conductance`, the unit's UA ([heat-exchangers.md](heat-exchangers.md)) | 431 |
| [`network/HeatControl`](../../../src/main/java/com/wormzjl/icheme/science/fluid/network/HeatControl.java) | A heat side's built-in controller: dead-beat effort on the side's own law between slices or steps | 76 |
| [`network/HeatRouting`](../../../src/main/java/com/wormzjl/icheme/science/fluid/network/HeatRouting.java) | Routes through a shared wall; crossflow walls on canonical row and column coordinates | 112 |
| [`network/CrossflowHeat`](../../../src/main/java/com/wormzjl/icheme/science/fluid/network/CrossflowHeat.java) | A two-pass tube bundle against a shell mixed between crossflow rows | 123 |
| [`network/Pool`](../../../src/main/java/com/wormzjl/icheme/science/fluid/network/Pool.java) | A vessel's heated pool on its inventory: a kettle's pool around its tubes, or a still's fired pot (`fired`); `Head` | 128 |
| [`network/Burners`](../../../src/main/java/com/wormzjl/icheme/science/fluid/network/Burners.java) | A fired heater's fuel and combustion air from its radiant duty; `Fuel` | 76 |
| [`network/Flare`](../../../src/main/java/com/wormzjl/icheme/science/fluid/network/Flare.java) | A flare's combustion and rating by component amounts and formulas: `FLAME_LIMIT`, `stableVelocity`, `limit`, soot | 200 |
| [`network/Chimney`](../../../src/main/java/com/wormzjl/icheme/science/fluid/network/Chimney.java) | A chimney's sizing, exit velocity, opacity and `Status`, shared with the simulator and the constructor; `Form` | 167 |
| [`network/CoolingTower`](../../../src/main/java/com/wormzjl/icheme/science/fluid/network/CoolingTower.java) | A water cooling tower's geometry and operating facts; `fanLimit` a dispatch input | 90 |
| [`network/Air`](../../../src/main/java/com/wormzjl/icheme/science/fluid/network/Air.java) | A gas intake's air: dry air (N2, O2, Ar, Ne, He, Kr, Xe) wetted to 50 % relative humidity at 25 °C (`wet`) | 48 |
| [`network/TowerTransfer`](../../../src/main/java/com/wormzjl/icheme/science/fluid/network/TowerTransfer.java) | Merkel rating of a tower's fill, draft, evaporation; `Calibration`; `exhaust` | 540 |
| [`network/TowerExchange`](../../../src/main/java/com/wormzjl/icheme/science/fluid/network/TowerExchange.java) | The atmospheric boundary common to the Newton, the booking and the presentation | 78 |
| [`transport/MixtureViscosity`](../../../src/main/java/com/wormzjl/icheme/science/fluid/transport/MixtureViscosity.java) | Log-mixing liquid and Wilke vapour viscosity with continuation; the supercritical slot rule (`liquidSource`); the dense-fluid residual (`residual`, `jossiStielThodos`, `dense`) and its onset (`reducedDensity`, `denseOnset`, `DENSE_ONSET`, `DENSE_FULL`, `smoothstep`); `revision` | 357 |
| [`transport/SlurryTransport`](../../../src/main/java/com/wormzjl/icheme/science/fluid/transport/SlurryTransport.java) | Krieger-Dougherty viscosity, settling and deposition speed; `Settings` (the solid defaults); `SLUSH_FRACTION` | 104 |
| [`transport/SolidMobility`](../../../src/main/java/com/wormzjl/icheme/science/fluid/transport/SolidMobility.java) | Whether a connection may carry a donor's solids: `Reason`, `Outlet`, `closable`, `monitored` | 116 |
| [`conversion/ConversionRule`](../../../src/main/java/com/wormzjl/icheme/science/fluid/conversion/ConversionRule.java) | One Forge fluid rule: id, revision, priority, fluid or `#tag`, `Phase`; `ToIChemE`, `ToForge`; typed `Reason` and `Refusal`; `toJson` (names resolved) and `RESOLVED`, for the client | 211 |
| [`conversion/ConversionRules`](../../../src/main/java/com/wormzjl/icheme/science/fluid/conversion/ConversionRules.java) | The rules in priority order: `forward`, `reverse` (with `HYSTERESIS`), `parse` | 130 |
| [`conversion/ConversionLaws`](../../../src/main/java/com/wormzjl/icheme/science/fluid/conversion/ConversionLaws.java) | Litres to moles and back: `perLitre`, `fractions`, `LITRES_PER_M3` | 67 |
| [`conversion/ConversionRuntime`](../../../src/main/java/com/wormzjl/icheme/science/fluid/conversion/ConversionRuntime.java) | The rules in force; the client's copy (`client`, `publishClient`, `listenClient`) | 30 |
| [`conversion/ConversionLines`](../../../src/main/java/com/wormzjl/icheme/science/fluid/conversion/ConversionLines.java) | A rule's text on its JEI page as language keys and numbers: the six largest compounds in mol %, T and p; the reverse ranges and windows (`ConversionLinesTest`) | 97 |

## Main flow

A connection's law, as the step reads it:

1. At dispatch, the runtime's slice inputs are applied to the graph: `ShaftPlan.apply` (speeds, displacements, governors, fans, fuel, burners, flare tips, exhausts, failed pumps), the column and still port plans (`PortPlan` through `PassiveStepSolver.ports`), the exchanger reads (`ExchangerRelay.Read`).
2. Once per slice: `InletPhase.decide` on the committed state refuses movers; `PassiveStepSolver.couple` plans each coupled side's wall, bypass, give-back and sit-out, each pool, each convection coil fed from another island, and a complete side's valve target.
3. At each step's start: `PassiveStepSolver.plan` (a radiant coil's firing, a controlled air cooler's draught, an in-island convection coil's flue gas) and `GasBlanket.plan` (each pad's mass target).
4. In each residual: the connection row reads `FlowControl` (rise, law residual), `PipeResistance` (loss), `PhaseDraw` (what the end draws), `HeatLaw` (the heat with the stream), `OpenVessel` (a vessel's releases), `TowerExchange` (a tower's exchange).
5. At the booking: `ConservativeTransport` books the same heat (`EdgeHeat`), the movers' work, the releases, the tower's `ATMOSPHERE` boundary ([fluid-interval.md](fluid-interval.md)).

## Contracts

- **A heat law has no unknown and no mode.** Its heat is a pure function of its flow and the donor's enthalpy for the step, on the donor end's curve (`PassiveStepSolver.sideCurve`, cached per end while the law's span and films, the port and the donor state hold). A coupled side reads its partner's stream on the partner's model (`ExchangerRelay.Partner.model`, through `PortStreams.spanned`), with no second solver.
- **Controllers act between solves.** `HeatControl` and the planned valves fix their effort or target before the Newton, dead-beat on the law, held within `PassiveStepSolver.VALVE_DEADBAND`; no controller bypasses mass, component or energy accounting.
- **Inlet decisions are per slice**, on the committed state with its transport closures read open and endpoint modes only (with hysteresis, `DRY_REFUSE`/`DRY_RESUME`), so a replay decides the same way.
- **Ports act on outflow only.** Inflow through any port is delivered as through a bulk end; a LIQUID port's level head applies in both directions.
- **Validation on construction.** `Pipe`, `Geometry`, the `FlowControl` records (a `Machine`'s speed and set points; its law's rated values in `science/machine`) and `ConversionRule` refuse non-finite or out-of-range values; each conversion refusal refuses that rule alone.
- **A slice input is never a fact.** `ShaftPlan`, `CoolingTower.fanLimit`, `AirCooled.fanLimit`, `FiredCoil.limit` and the planned valve targets are dispatch inputs; the checkpoint's graph carries them for a replay.

## Laws and constants

**Friction** (`PipeResistance`): laminar Hagen-Poiseuille `128 mu L q / (pi rho D^4)`; turbulent Darcy with the Swamee-Jain factor `0.25 / log10(eps/3.7D + 5.74/Re^0.9)^2`; a C1 smoothstep blend over Re 2000 to 4000; minor loss `K q^2 / (2 rho A^2)`. A restriction orifice plate (`Orifice`, D1) is its pipe's minor loss plus `K(beta) = (1 - beta^4)(1 - beta^1.9) / (C^2 beta^4)`, C = 0.61 (ISO 5167-2 differential times the permanent loss fraction): K 1 598, 296, 29.5, 4.19, 1.34 at beta 0.2, 0.3, 0.5, 0.7, 0.8; no solver change, the same K both ways, kept by a resize (K does not depend on the bore). `Geometry.channels`: a run of equal channels in parallel loses what one loses at the flow over the count, its area and volume all channels'. Sections of the same diameter and roughness merge (`Pipe.coalesce`).

**Movers** (`FlowControl`; the curves in [rotating-machinery.md](rotating-machinery.md)). Every running mover is in `MOVER_LAW`: one row `Mover.lawResidual` ties its suction volume flow to its rise. A `Machine` states `head − rise(Q, s)` on its curve (`CentrifugalPump`, `CentrifugalCompressor`, `VacuumPump`) at the speed share its train set for the slice. A set-flow `Pump` or `Compressor` is positive displacement (`PositiveDisplacement`): its set flow is the displacement, its rise limit the relief rise, the row in flow form (`SLIP`, `RELIEF_SLOPE`, `ROUNDING`). An `Ejector` takes its curve as the set-flow law: displacement the curve's suction volume at the trial's suction state, relief rise the maximum discharge less the suction; no work (`power` 0), its motive steam bringing its enthalpy into the discharge junction; without steam it stands `CLOSED`. Its curve is `SteamEjector`'s (`science/machine`, the sheet's correlation): suction volume Q = f_m A v(P_s), `v` its own `speed`, f_m from the motive pressure (`EjectorLaw.motiveFactor`) times the share of the asked steam the nozzle got (`withMotive(P_m, share)`, slice inputs `motivePressure` and `delivered`); its motive steam is a planned mass valve (`ValveSource.EJECTOR`, `ControlValve.ejector()`) whose target `runtime/fluid/Ejectors` sets. The rise limit (a law's rise at no flow): a pump's `maxAddedPressure` × rho_supply / rho_ref (`FluidThermodynamics.pumpReferenceDensity`, the supply's density from `InletPhase.density`); a compressor's `(r_max − 1) P_suction`; a machine's curve at no flow and its speed. The booked work is in [fluid-step.md](fluid-step.md) (`moverPower`). The controls' bounds, which the device menu and the process simulator's sizing limits both read: `Mover.MAXIMUM_VOLUME_FLOW`, `Pump.MAXIMUM_ADDED_PRESSURE`, `Compressor.MAXIMUM_PRESSURE_RATIO` (and its minimum).

**Steam turbine** (`FlowControl.Turbine`): stands in `VALVE_OPEN` for its solve; its own resistance sets its flow, a control valve upstream governs it, and its `shaftPower` (ṁ η_is Δh_is from the inlet's `P/ρ` and k down to the exhaust node's pressure) leaves as a `SHAFT` boundary transfer. A reverse flow passes as through an open line and does no work.

**Valves** (`FlowControl.ControlValve`, `PressureValve`): a bare valve is fully open both ways; active control is forward only: `VALVE_REGULATING` states the upstream pressure or the mass or actual-volume flow, `VALVE_OPEN` zero extra head, `CLOSED` exact zero flow. Throttling adds no work. `Pipe.actuatorArea` keeps the registered valve bore apart from narrower run sections. `ControlConstraints.dependent` puts pressure valves on one node in `VALVE_CONFLICT` at their accepted openings. A planned valve (`ValveSource.planned()`: `SHAFT_SPEED`, `BURNER`, `FLARE`, `STILL`, `EJECTOR`, `ENGINE`, `BLANKET`, `CONVERTER`) takes its mass target from the engine each slice or step, never from the player; `sink()` marks the ones built into a sink (a firebox, a flare tip, a prime mover's fuel void). A running column's port valve carries `PortPlan.COLUMN_SENSOR`. A flare tip's valve (`ControlValve.bare()` true while open) regulates nothing: its flow is what the pressure behind it drives through the tip's bore. An automated shutoff valve (`ValveSource.ON_OFF`, `ControlValve.onOff()`, batch 2026-11-15-fluid-interfaces W7) is a mass valve that is never planned and never regulates: a mass target above 0 is a bare, open line, 0 is `CLOSED` by the same rule as any flow valve. The pressure safety valve is a `PressureValve` on the run out of the guarded node ([runtime-registry.md](runtime-registry.md)).

**Check valves** (`Pipe.forbiddenDirections`): a fitting's permanent one-way mask, oriented with its run; it adds no loss and no cracking pressure. `NetworkReduction.reopened` clears only `blockedDirections`, never this mask.

**Port draws** (`PhaseDraw`): `BULK` (side), `VAPOR` (top), `LIQUID` (bottom: hydrocarbon and water together, then the surviving liquid, then gas), `BOOT_WATER` (water, hydrocarbon, gas), `BOOT_HC` (hydrocarbon, water above the D/3 weir, gas), `OVERFLOW` (the liquid above the fill height, lighter first; a dry end while the level stands at or below it). At a vanishing flow a LIQUID end holding both liquids draws `mixedShares(state, volume)` of its connection's volume: equal volumes until the smaller is spent, the larger then fills the rest; it is continuous in either amount. Every end function takes the connection volume `Pipe.volume()`. A gas a node's seed holds is pinned at a port (`Segment.pinsGas`) only where the liquid the node gains within the step fills its vapour space (`StepEquations.decideSegments`). A boot weir adds a bounded water-rate complementarity row.

**Level head** (`PortStreams.portHead`, `VesselGeometry.head`): in a prism `g m_c H / V` (m_c the condensed mass, H the vessel's `levelHeight`, V its inventory volume); in a round vessel two stacked layers over the body bottom, each with its liquid's mass and its volume share of the solids; a vessel holding solids and no liquid stands them as one bed. A boot's own column is the connection's.

**Open vessels** (`OpenVessel`): over a step of `dt` an open vessel releases the share `min(1, dt / RELEASE_TIME)` of its start excess as constant terms of its rows: the vent that share of its start gas at its start composition and enthalpy, no faster than `VENT_SPEED` through its footprint (`volume / levelHeight`); the spill that share of the liquid above the shell top (`FREEBOARD` over the fill height), lighter first, with its volume share of the solids. Gas that forms within a step leaves in the next. VENT and SPILL are rates a steady island repeats, so they do not keep it from certifying.

**Gas blanketing** (`GasBlanket`): the set pressure is `Inventory.blanket`. The pad's mass target at every step, from the step's start state (`plan`, after the heat plan): the share `OpenVessel.releaseShare(dt)` of the gas the vessel lacks at its set pressure over `GAMMA`, plus the gas for the room its liquid vacates at the previous step's rate (`inflows`), the room at least `ROOM` of the volume, nothing below `RESOLUTION`. The vent: a rigid fitted vessel above `ventPressure` = set × (1 + `BAND`) releases by the same law (`gap`), at most `VENT_SPEED` × footprint, kind `VENT`.

**Heat laws** ([heat-exchangers.md](heat-exchangers.md) for the walls, the relay and the controller). `HeatSide`, `AirCooled`, `FiredCoil`, `ConvectionCoil` and `ExhaustCoil` each give a passage's heat on the donor's `HeatCurve`. `couple` sets, for a ratio side, the ratio times the partner's mass flow; for a column condenser's water side (`Operation.column`), the mass flow that carries the read's `heatRate` from the inlet enthalpy to the outlet at the inlet temperature plus `rise`, capped at the read's `ceiling` (water at or above the ceiling takes nothing); for a heating read (`Read.heating`, a reboiler's utility, `gives()`), `−heatRate / (h(inlet) − h(floor))`, the curve sampled down to the floor, a stream at or under the floor giving nothing. A kettle's pool (`Pool`) enters its vessel's energy row on the step's start state (`poolHeat`), its vapour counted as gas the vessel's ports may draw within the step (`boilUp`). A segment that starts with no flows known and plans a fired coil or a controlled air cooler plans from one rate solve's flows (`plansHeat`).

**Burners** (`Burners`): the radiant duty sets the fuel fired on its lower heating value through one flue-gas capacity C_g = Q_f / (T_flame − T_air), which the convection coil reads too; the fans' shaft per watt fired is `FAN_SHAFT_PER_FIRED`. `FiredCoil.limit` is the share of its duty the burners give (short fuel, no fan power).

**Flare** (`Flare`): the vent gas's combustion by component amounts, lower heating values (`MaterialCatalog.Combustion`) and formulas (C, H, N, O, S). The flame holds while the stoichiometric adiabatic flame temperature with air at `AMBIENT` is at least `FLAME_LIMIT`; below it the gas leaves unburnt. A tip's rating is methane at its stable exit velocity (`LOW_VELOCITY` to `HIGH_VELOCITY` with the heating value); the stack stands for `ALLOWED_FLUX` at its base. `limit` is status only: the flame lifts off above its stable velocity (FLAME OUT) and burns on above its rating (OVER CAPACITY).

**Chimney** (`Chimney`): forms `STEEL` and `BRICK`, each with its exit and height ranges, design velocity and notice temperature; exit area for `SPARE` times the design flow; exit velocity window `LOW_VELOCITY` to `HIGH_VELOCITY`; `gepHeight`; `STACK_LOSS`; smoke opacity `1 − exp(−k C D)`, k `EXTINCTION`. The tip is a passive one-port void at the stack's top.

**Cooling towers** (`CoolingTower`, `TowerTransfer`, `TowerExchange`): one Merkel root on the four-point Chebyshev integral, signed draft, water and energy residuals; the exchange stays outside `HeatLaw` and the booking books the same `ATMOSPHERE` boundary ([cooling-towers.md](cooling-towers.md)).

**Slurry and solids** (`SlurryTransport`, `SolidMobility`): Krieger-Dougherty viscosity up to `MAXIMUM_PACKING_FRACTION`; an outlet stops carrying a slurry at `SLUSH_FRACTION` solids; settling and deposition speed; gravity is `PassiveStepSolver.GRAVITY`. `Settings.defaults()` is the one source of the solid defaults (config `fluid.solid*`). `SolidMobility.monitored` decides which islands the solid event integrator drives; `closable` which closures it may locate.

**Viscosity** (`MixtureViscosity`): log mixing for a liquid, Wilke for a vapour, continued past a table; each viscosity record's revision is part of its `revision`; `liquidData`, `vaporData`, `waterLiquidData` are the ranges the notices read. `residual` is the Jossi-Stiel-Thodos dense-fluid term in the Lohrenz-Bray-Clark statement on mole-fraction pseudo-critical constants, with each dataset's critical volume, `(f^4 - f(0)^4)/xi` so it is exactly zero at zero density; `dense` = `vapor` + `w × residual`, `w = smoothstep((rho_r − DENSE_ONSET)/(DENSE_FULL − DENSE_ONSET))` of the reduced density `rho_r = rho × sum(x_i Vc_i)` (0 at or below 0.25, where the residual is not evaluated and `dense` is `vapor` bit for bit; 1 from 0.5). The network reads every slot's viscosity through one rule, `FluidThermodynamics.slotViscosity` ([fluid-thermo-port.md](fluid-thermo-port.md)): the vapour slot and a liquid slot on a supercritical isotherm read `dense` at the slot's molar density, any other liquid slot `liquid`; `PassiveStepSolver`, `SolidMobility` and the simulator's `StreamProps` read the same rule.

**Conversion** (`conversion/`): `ConversionRules` orders by priority, then id. `forward(fluid, tags, closed)`: the first rule whose `to_icheme` names the fluid or one of its tags (a gas rule only on the closed converter). `reverse(drawn, last, closed)`: rule `last` while the drawn phase holds inside its ranges widened by `HYSTERESIS`, else the first whose ranges hold. `parse` skips a rule whose fluid's mod is absent. `ConversionLaws`: reverse V = m / rho at the drawn phase's density; forward, a litre holds rho / M / `LITRES_PER_M3` mol at the flash of the rule's composition at its T and P (`FluidThermodynamics.liquidDensity`), or a gas rule's own moles per litre; null where the model lacks a compound or the flash holds no liquid; `fractions` null also where the rule gives water and the model is dry. 1 mB is 1 L. The client holds the rules the server last sent (`ConversionRuntime.client`, sent at login and each reload, cleared at logout) for the JEI category; a rule read back from `toJson` with `RESOLVED` equals the server's. The placeholder crude oil `icheme:crude_oil` (`registry/ModFluids`, always registered, in `c:crude_oil`) converts through the bundled tag rule; it hides from the creative tab and JEI while another mod's fluid is in the tag (`ModFluids.othersInTag`).

| Constant | Meaning | File |
|---|---|---|
| `Mover.MAXIMUM_VOLUME_FLOW`, `Pump.MAXIMUM_ADDED_PRESSURE`, `Compressor.MAXIMUM_PRESSURE_RATIO` | the movers' control bounds | `FlowControl` |
| `DEFAULT_ROUGHNESS_METRES` | a pipe's default roughness | `PipeResistance` |
| `DRY_REFUSE`, `DRY_RESUME` | a dry suction's refusal and resumption (capacity share) | `InletPhase` |
| `FREEBOARD`, `RELEASE_TIME`, `VENT_SPEED` | an open vessel's shell over its fill height; release time; vent speed | `OpenVessel` |
| `BAND`, `ROOM`, `GAMMA`, `RESOLUTION` | the blanket's vent band, least room, response, resolution | `GasBlanket` |
| `PINCH`, `APPROACH` | the exhaust coil's design | `ExhaustCoil` |
| `FLAME_LIMIT`, `LOW_VELOCITY`, `HIGH_VELOCITY`, `ALLOWED_FLUX` | a flare's flame and rating | `Flare` |
| `SPARE`, `LOW_VELOCITY`, `HIGH_VELOCITY`, `STACK_LOSS`, `EXTINCTION` | a chimney's sizing and smoke | `Chimney` |
| `FAN_SHAFT_PER_FIRED`, `EXCESS_AIR` | burners' fans and air | `Burners` |
| `MAXIMUM_PACKING_FRACTION`, `SLUSH_FRACTION` | slurry limits | `SlurryTransport` |
| `HYSTERESIS`, `DEFAULT_OTHERS_MAX` | conversion matching | `ConversionRules`, `ConversionRule` |
| `PositiveDisplacement.SLIP`, `Npsh.BREAKDOWN`, `CentrifugalCompressor.DEFAULT_EFFICIENCY` | the machine laws the movers read | `science/machine` |

## Formats

The devices' controls, valve sources and heat laws are written by the runtime's codecs, enums by name in the facts ([runtime-saves.md](runtime-saves.md); numbers in [FORMATS.md](../FORMATS.md)). An appended `ValveSource` or `ShaftPlan.Kind` constant is saved by name.

## Tests and gates

| Rule | Test |
|---|---|
| Friction and section aggregation | `HydraulicReferenceQualificationTest`, `HydraulicMatrixQualificationTest`; a passage's channels: `HeatSidesTest.manyChannelsLoseWhatOneLosesAtItsShareOfTheFlow` |
| Movers, rise limits, the turbine | `FlowControlTest`, `CompressorTest`, `PumpRiseScalingTest`, `MachineMoverTest`, `SteamTurbineConnectionTest`, `SteamEjectorConnectionTest`, `VacuumPumpMoverTest`, `PlungerPumpMoverTest`, `ShaftPlanTest` |
| Dry suction, compressor solids | `InletPhaseTest`, runtime `DrySuctionTest` |
| Port draws and heads | `PhasePortTest`, `PhasePortPriorityTest`, `LevelHeadTest`, `VesselGeometryTest`, `ProcessOutletTest` |
| Open vessels | `OpenTankTest`, runtime `OpenTankRuntimeTest` |
| Gas blanketing and relief | `GasBlanketTest`, runtime `BlanketValveWorldTest`, `BlanketDrainWorldTest`, `ReliefValveWorldTest` |
| Check valves | runtime `PipelineFittingTest` |
| Heat laws, curves, relay, controller | `HeatCurveTest`, `HeatSideNetworkTest`, `ExchangerRelayTest`, `HeatControlTest`, `FiredCoilTest`, `ConvectionCoilTest`, `ExhaustCoilTest`; a heating read: `ExchangerRelayTest.aColumnsHeatingSideGivesItsDueDownToTheFloor` |
| Chimney | `ChimneyTest` |
| Slurry and solids | `SlurryTransportTest`, `SolidChainTransportTest` |
| Valve noise regime | `ValveCavitationTest` |

## Traps

- **`Pump` is not a curve.** It is a positive-displacement law; a centrifugal pump on its curve is a `Machine` with a `CentrifugalPump` law.
- **"100 m/s" is a default, not the cap.** A line's cap is the donor stream's velocity limit ([fluid-thermo-port.md](fluid-thermo-port.md)).
- **An open vessel's `volume` is not its shell.** It is the volume to the fill height; the shell's is `OpenVessel.topVolume`. Code that reads the wall uses `wallCapacity()`.
- **`PhasePort` vs `NodeKind.PORT`.** A tank outlet face and a rate-solve copy of a vessel are unrelated.
- **`ScheduledTransfer` has no runtime producer**; see [fluid-interval.md](fluid-interval.md).
- **`InlineMovers`' fixed duty** (liquid at 2 m/s and 25 m, gas at 10 m/s and ratio 1.5 at 300 K, in `InlineMachines`) is a game design value, not a universal rating.

**Snapshot:** `c004a4c57`, 2026-11-16.
